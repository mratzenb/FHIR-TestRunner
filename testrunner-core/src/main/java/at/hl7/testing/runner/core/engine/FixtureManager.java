package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.ResourceContent;
import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.ContentFormat;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers the fixtures of a TestScript, creates those with {@code autocreate} on the server and deletes those
 * with {@code autodelete} afterwards.
 *
 * <p>Autocreated fixtures may reference each other ({@code Patient/<id of another fixture's resource>}). They are
 * created in dependency order and each reference is rewritten to the id the server assigned to the referenced
 * fixture. A reference to a resource that is not an autocreated fixture is left as it is.
 */
final class FixtureManager {

    private static final Logger log = LoggerFactory.getLogger(FixtureManager.class);

    private final ExecutionState state;
    private final FhirHttpClient http;
    private final String base;

    FixtureManager(ExecutionState state, FhirHttpClient http, String base) {
        this.state = state;
        this.http = http;
        this.base = base;
    }

    /** Makes every fixture available to {@code sourceId}, {@code targetId} and variables. */
    void register(List<FixtureDefinition> fixtures, Map<String, Body> resources) {
        for (FixtureDefinition fixture : fixtures) {
            Body body = resources.get(fixture.id());
            if (body == null) {
                continue; // a fixture without resource only reserves its id
            }
            String type = body.format() == ContentFormat.OTHER ? null : safe(() -> ResourceContent.resourceType(body));
            state.putFixture(new Source.Fixture(fixture.id(), body, type, null, null));
            log.debug("Fixture '{}': {} (autocreate={}, autodelete={})", fixture.id(),
                    type == null ? "no resource" : type + "/" + safe(() -> ResourceContent.id(body)),
                    fixture.autocreate(), fixture.autodelete());
        }
    }

    /**
     * Creates the fixtures with {@code autocreate} in dependency order.
     *
     * @throws TestScriptException if the references form a cycle or the server does not create a fixture
     */
    void autocreate(List<FixtureDefinition> fixtures) {
        Map<String, Source.Fixture> toCreate = new LinkedHashMap<>();
        for (FixtureDefinition definition : fixtures) {
            if (definition.autocreate()) {
                Source.Fixture fixture = (Source.Fixture) state.require(definition.id(), "fixture");
                if (fixture.resourceType() == null) {
                    throw new TestScriptException("Fixture '" + definition.id() + "' has autocreate, but its content is"
                            + " no FHIR resource");
                }
                toCreate.put(definition.id(), fixture);
            }
        }
        if (toCreate.isEmpty()) {
            return;
        }
        Map<String, String> fixtureIdByResourceRef = new HashMap<>();
        toCreate.values().forEach(f -> {
            String id = ResourceContent.id(f.body());
            if (id != null) {
                String previous = fixtureIdByResourceRef.put(f.resourceType() + "/" + id, f.id());
                if (previous != null) {
                    throw new TestScriptException("Fixtures '" + previous + "' and '" + f.id() + "' both contain "
                            + f.resourceType() + "/" + id + "; references between fixtures need unique resource ids");
                }
            }
        });
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        toCreate.forEach((fixtureId, fixture) -> {
            Set<String> dependsOn = new LinkedHashSet<>();
            for (String reference : ResourceContent.references(fixture.body())) {
                String target = fixtureIdByResourceRef.get(localReference(reference));
                if (target != null && !target.equals(fixtureId)) {
                    dependsOn.add(target);
                }
            }
            dependencies.put(fixtureId, dependsOn);
        });
        List<String> order = creationOrder(dependencies);
        log.debug("Fixture creation order: {}", order);

        Map<String, String> serverRefByResourceRef = new HashMap<>();
        for (String fixtureId : order) {
            Source.Fixture fixture = toCreate.get(fixtureId);
            Body body = ResourceContent.replaceReferences(fixture.body(),
                    reference -> serverRefByResourceRef.getOrDefault(localReference(reference), reference));
            log.info("Creating fixture '{}' ({}/{})", fixtureId, fixture.resourceType(), ResourceContent.id(fixture.body()));
            Exchange exchange = http.send(new FhirRequest(HttpMethod.POST, URI.create(base + "/" + fixture.resourceType()),
                    HttpHeaders.of(Map.of("Content-Type", List.of(body.format().fhirMimeType()),
                            "Accept", List.of(body.format().fhirMimeType()))),
                    body.text()));
            String serverId = createdId(exchange, fixtureId);
            Source.Fixture created = fixture.created(body, serverId, exchange.requestId());
            state.fixtureCreated(created);
            String originalId = ResourceContent.id(fixture.body());
            if (originalId != null) {
                serverRefByResourceRef.put(fixture.resourceType() + "/" + originalId, fixture.resourceType() + "/" + serverId);
            }
            log.info("Fixture '{}' created as {}/{}", fixtureId, fixture.resourceType(), serverId);
        }
    }

    /**
     * Deletes the fixtures with {@code autodelete} that were created, in reverse creation order.
     *
     * @return a problem for every fixture that could not be deleted
     */
    List<String> autodelete(List<FixtureDefinition> fixtures) {
        Set<String> autodelete = new LinkedHashSet<>();
        fixtures.stream().filter(FixtureDefinition::autodelete).forEach(f -> autodelete.add(f.id()));
        List<String> problems = new ArrayList<>();
        List<Source.Fixture> created = new ArrayList<>(state.createdFixtures());
        java.util.Collections.reverse(created);
        for (Source.Fixture fixture : created) {
            if (!autodelete.contains(fixture.id())) {
                continue;
            }
            log.info("Deleting fixture '{}' ({}/{})", fixture.id(), fixture.resourceType(), fixture.serverId());
            try {
                Exchange exchange = http.send(new FhirRequest(HttpMethod.DELETE,
                        URI.create(base + "/" + fixture.resourceType() + "/" + fixture.serverId()), HttpHeaders.empty(), null));
                if (!exchange.response().isSuccessful()) {
                    problems.add("Autodelete of fixture '" + fixture.id() + "' returned HTTP "
                            + exchange.response().status() + " [" + exchange.requestId() + "]");
                }
            } catch (FhirHttpClient.ExchangeFailedException e) {
                problems.add("Autodelete of fixture '" + fixture.id() + "' failed: " + e.getMessage());
            }
        }
        problems.forEach(log::warn);
        return problems;
    }

    private static String createdId(Exchange exchange, String fixtureId) {
        var response = exchange.response();
        if (!response.isSuccessful()) {
            throw new TestScriptException("Fixture '" + fixtureId + "' could not be created (" + exchange.requestId()
                    + "): HTTP " + response.status() + " " + response.reason() + " " + abbreviate(response.body()));
        }
        String id = response.headers().first("Location")
                .map(location -> ResourceContent.parseLocation(location).get("id"))
                .orElse(null);
        if (id == null && !response.body().isBlank()) {
            id = safe(() -> ResourceContent.id(response.asBody()));
        }
        if (id == null) {
            throw new TestScriptException("Fixture '" + fixtureId + "' was created (" + exchange.requestId()
                    + "), but the response names no id (no Location header, no resource in the body)");
        }
        return id;
    }

    /** {@code Type/id} of a relative or absolute reference, ignoring a version; unchanged otherwise. */
    static String localReference(String reference) {
        if (reference.startsWith("urn:") || reference.startsWith("#")) {
            return reference;
        }
        Map<String, String> parsed = ResourceContent.parseLocation(reference);
        return parsed.containsKey("type") ? parsed.get("type") + "/" + parsed.get("id") : reference;
    }

    /** Kahn's algorithm; fixtures without dependencies keep their declaration order. */
    static List<String> creationOrder(Map<String, Set<String>> dependencies) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        dependencies.forEach((id, deps) -> missing.put(id, deps.size()));
        Deque<String> ready = new ArrayDeque<>();
        missing.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            dependencies.forEach((dependent, deps) -> {
                if (deps.contains(id) && missing.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            });
        }
        if (order.size() < dependencies.size()) {
            Set<String> cycle = new LinkedHashSet<>(dependencies.keySet());
            order.forEach(cycle::remove);
            throw new TestScriptException("The autocreate fixtures " + cycle + " reference each other in a cycle");
        }
        return order;
    }

    private static String abbreviate(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private static String safe(java.util.function.Supplier<String> value) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
