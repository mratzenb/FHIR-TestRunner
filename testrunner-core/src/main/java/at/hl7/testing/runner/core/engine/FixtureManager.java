package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.ReferenceRewriting;
import at.hl7.testing.runner.core.content.ResourceContent;
import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.ContentFormat;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.HttpMethod;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.OperationType;
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
import java.util.function.UnaryOperator;

/**
 * Registers the fixtures of a TestScript, creates those with {@code autocreate} on the server and deletes those
 * with {@code autodelete} afterwards.
 *
 * <p>Autocreated fixtures may reference each other ({@code Patient/<id of another fixture's resource>}). They are
 * created in dependency order and each reference is rewritten to the id the server assigned to the referenced
 * fixture. A reference to a resource that is not an autocreated fixture is left as it is.
 *
 * <p>A fixture that is not autocreated is resolved each time it is used ({@link #resolve}): its {@code ${…}}
 * placeholders are replaced with the values at that moment, and its references to autocreated fixtures are rewritten
 * like those between autocreated fixtures. So a fixture sent in a test can use a variable on an earlier response.
 *
 * <p>Rewriting references is a convenience of this runner, the TestScript specification sends fixtures as written.
 * {@link ReferenceRewriting} switches it off ({@code NONE}: declaration order, nothing rewritten) or extends it to
 * fixtures a create or update operation sent ({@code ALL}, {@link #sentByOperation}).
 *
 * <p>A TestScript for several servers has its autocreated fixtures created "on each server being tested" (FHIR
 * TestScript), one server after the other, and deleted there; references are rewritten to the ids of the server the
 * fixture is sent to ({@link ExecutionState#onServer}).
 */
final class FixtureManager {

    private static final Logger log = LoggerFactory.getLogger(FixtureManager.class);

    private final ExecutionState state;
    private final Servers servers;
    private final UnaryOperator<String> values;
    private final ReferenceRewriting rewriting;
    /** Fixtures being resolved, to detect a variable that reads the fixture it is used in. */
    private final Set<String> resolving = new LinkedHashSet<>();

    /**
     * @param values    replaces {@code ${variable}} placeholders in the text values of the fixture resources
     * @param rewriting which references are rewritten to the ids the server assigned
     */
    FixtureManager(ExecutionState state, Servers servers, UnaryOperator<String> values, ReferenceRewriting rewriting) {
        this.state = state;
        this.servers = servers;
        this.values = values;
        this.rewriting = rewriting;
    }

    /** Whether an operation puts the resource it sends on the server under an id: {@code create} and {@code update}. */
    static boolean createsResource(Operation operation) {
        return operation.type() == OperationType.CREATE || operation.type() == OperationType.UPDATE;
    }

    /** Makes every fixture available to {@code sourceId}, {@code targetId} and variables, as written. */
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
     * The fixture as it is to be used now: placeholders replaced, references to autocreated fixtures rewritten to the
     * server's ids. A fixture created on the server is returned as it was sent.
     *
     * @throws TestScriptException if a placeholder cannot be resolved, naming the fixture
     */
    Source.Fixture resolve(Source.Fixture fixture) {
        if (fixture.serverId() != null) {
            return fixture;
        }
        Body body = withValues(fixture.id(), fixture.body());
        if (state.hasServerReferences()) {
            body = withServerReferences(fixture.id(), body);
        }
        return body == fixture.body() ? fixture : fixture.withBody(body);
    }

    /** Replaces the placeholders of a fixture's body with their values at this moment. */
    private Body withValues(String fixtureId, Body body) {
        if (!body.text().contains("${")) {
            return body;
        }
        if (!resolving.add(fixtureId)) {
            throw new TestScriptException("a variable reads the fixture it is used in ("
                    + String.join(" -> ", resolving) + " -> " + fixtureId + ")");
        }
        try {
            return ResourceContent.replaceText(body, values);
        } catch (TestScriptException e) {
            throw new TestScriptException("Fixture '" + fixtureId + "': " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new TestScriptException("Fixture '" + fixtureId + "' has placeholders, but its content cannot be"
                    + " read: " + e.getMessage(), e);
        } finally {
            resolving.remove(fixtureId);
        }
    }

    /** Rewrites the references to autocreated fixtures; content that cannot be read is sent as it is. */
    private Body withServerReferences(String fixtureId, Body body) {
        try {
            return ResourceContent.replaceReferences(body, reference -> state.serverReference(localReference(reference))
                    .map(server -> {
                        log.debug("Fixture '{}': reference {} -> {}", fixtureId, reference, server);
                        return server;
                    })
                    .orElse(reference));
        } catch (IllegalArgumentException e) {
            return body;
        }
    }

    /**
     * With {@link ReferenceRewriting#ALL}: after a create or update sent a fixture, records the id the server gave it,
     * so that references to it in fixtures sent later are rewritten. The id comes from the {@code Location} header,
     * else from the resource returned, else (update) from the URL. Nothing happens for other operations, a response
     * that is no success or a fixture whose resource has no id.
     */
    void sentByOperation(Operation operation, Exchange exchange) {
        if (rewriting != ReferenceRewriting.ALL || !createsResource(operation) || operation.sourceId() == null
                || !exchange.response().isSuccessful()) {
            return;
        }
        Source.Fixture fixture = state.fixtureAsWritten(operation.sourceId()).orElse(null);
        if (fixture == null || fixture.resourceType() == null) {
            return; // the body was a kept response, or no resource
        }
        String writtenId = safe(() -> ResourceContent.id(fixture.body()));
        if (writtenId != null && writtenId.contains("${")) {
            writtenId = safe(() -> values.apply(ResourceContent.id(fixture.body())));
        }
        if (writtenId == null) {
            return;
        }
        String onServer = serverReference(fixture.resourceType(), operation, exchange);
        if (onServer == null) {
            log.debug("Fixture '{}' was sent ({}), but the response names no id; references to it are not rewritten",
                    fixture.id(), exchange.requestId());
            return;
        }
        String written = fixture.resourceType() + "/" + writtenId;
        if (!written.equals(onServer)) {
            state.serverAssigned(written, onServer);
            log.info("Fixture '{}' is {} on the server [{}]; references to {} are rewritten (rewrite-references: all)",
                    fixture.id(), onServer, exchange.requestId(), written);
        }
    }

    /** {@code Type/id} of the resource a create or update put on the server, or {@code null} if no id is named. */
    private static String serverReference(String type, Operation operation, Exchange exchange) {
        Map<String, String> location = exchange.response().headers().first("Location")
                .map(ResourceContent::parseLocation).orElse(Map.of());
        if (location.containsKey("id")) {
            return location.getOrDefault("type", type) + "/" + location.get("id");
        }
        Body returned = exchange.response().body();
        if (!returned.isEmpty() && returned.format() != ContentFormat.OTHER
                && type.equals(safe(() -> ResourceContent.resourceType(returned)))) {
            String id = safe(() -> ResourceContent.id(returned));
            if (id != null) {
                return type + "/" + id;
            }
        }
        if (operation.type() == OperationType.UPDATE) {
            Map<String, String> url = ResourceContent.parseLocation(exchange.request().uri().getPath());
            return type.equals(url.get("type")) ? type + "/" + url.get("id") : null;
        }
        return null;
    }

    /**
     * Creates the fixtures with {@code autocreate} in dependency order, on every server. Their placeholders are resolved
     * now, before any operation has run. With {@link ReferenceRewriting#NONE} they are created in declaration order and
     * sent as written.
     *
     * @throws TestScriptException if a placeholder cannot be resolved, the references form a cycle or a server does not
     *                             create a fixture
     */
    void autocreate(List<FixtureDefinition> fixtures) {
        Map<String, Source.Fixture> toCreate = new LinkedHashMap<>();
        for (FixtureDefinition definition : fixtures) {
            if (definition.autocreate()) {
                Source.Fixture fixture = state.fixtureAsWritten(definition.id()).orElseThrow(() ->
                        new TestScriptException("Fixture '" + definition.id() + "' has autocreate, but no resource"));
                if (fixture.resourceType() == null) {
                    throw new TestScriptException("Fixture '" + definition.id() + "' has autocreate, but its content is"
                            + " no FHIR resource");
                }
                toCreate.put(definition.id(), fixture.withBody(withValues(fixture.id(), fixture.body())));
            }
        }
        if (toCreate.isEmpty()) {
            return;
        }
        List<String> order = rewriting == ReferenceRewriting.NONE ? List.copyOf(toCreate.keySet())
                : dependencyOrder(toCreate);
        for (Servers.Endpoint server : servers.all()) {
            state.onServer(server.index());
            String on = servers.several() ? " on the server of destination " + server.index() : "";
            for (String fixtureId : order) {
                Source.Fixture fixture = toCreate.get(fixtureId);
                Body body = ResourceContent.replaceReferences(fixture.body(),
                        reference -> state.serverReference(localReference(reference)).orElse(reference));
                String originalId = ResourceContent.id(fixture.body());
                log.info("Creating fixture '{}' ({}/{}){}", fixtureId, fixture.resourceType(), originalId, on);
                Exchange exchange = server.http().send(new FhirRequest(HttpMethod.POST,
                        URI.create(server.base() + "/" + fixture.resourceType()),
                        HttpHeaders.of(Map.of("Content-Type", List.of(body.format().fhirMimeType()),
                                "Accept", List.of(body.format().fhirMimeType()))),
                        body));
                String serverId = createdId(exchange, "Fixture '" + fixtureId + "'" + on);
                state.fixtureCreated(fixture.created(body, serverId, exchange.requestId()),
                        originalId == null || rewriting == ReferenceRewriting.NONE ? null
                                : fixture.resourceType() + "/" + originalId);
                log.info("Fixture '{}' created as {}/{}{}", fixtureId, fixture.resourceType(), serverId, on);
            }
        }
        state.onServer(servers.first().index());
    }

    /** The order in which the autocreated fixtures are created: those referenced by others first. */
    private static List<String> dependencyOrder(Map<String, Source.Fixture> toCreate) {
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
        return order;
    }

    /**
     * Deletes the fixtures with {@code autodelete} that were created, in reverse creation order, each on the server it
     * was created on.
     *
     * @return a problem for every fixture that could not be deleted
     */
    List<String> autodelete(List<FixtureDefinition> fixtures) {
        Set<String> autodelete = new LinkedHashSet<>();
        fixtures.stream().filter(FixtureDefinition::autodelete).forEach(f -> autodelete.add(f.id()));
        List<String> problems = new ArrayList<>();
        List<ExecutionState.Created> created = new ArrayList<>(state.createdFixtures());
        java.util.Collections.reverse(created);
        Map<Integer, Servers.Endpoint> byIndex = new HashMap<>();
        servers.all().forEach(s -> byIndex.put(s.index(), s));
        for (ExecutionState.Created entry : created) {
            Source.Fixture fixture = entry.fixture();
            if (!autodelete.contains(fixture.id())) {
                continue;
            }
            Servers.Endpoint server = byIndex.get(entry.server());
            String on = servers.several() ? " on the server of destination " + server.index() : "";
            log.info("Deleting fixture '{}' ({}/{}){}", fixture.id(), fixture.resourceType(), fixture.serverId(), on);
            try {
                Exchange exchange = server.http().send(new FhirRequest(HttpMethod.DELETE,
                        URI.create(server.base() + "/" + fixture.resourceType() + "/" + fixture.serverId()),
                        HttpHeaders.empty(), null));
                if (!exchange.response().isSuccessful()) {
                    problems.add("Autodelete of fixture '" + fixture.id() + "'" + on + " returned HTTP "
                            + exchange.response().status() + " [" + exchange.requestId() + "]");
                }
            } catch (FhirHttpClient.ExchangeFailedException e) {
                problems.add("Autodelete of fixture '" + fixture.id() + "'" + on + " failed: " + e.getMessage());
            }
        }
        problems.forEach(log::warn);
        return problems;
    }

    /** @param fixture {@code Fixture 'x'} or {@code Fixture 'x' on the server of destination 2}, for messages */
    private static String createdId(Exchange exchange, String fixture) {
        var response = exchange.response();
        if (!response.isSuccessful()) {
            throw new TestScriptException(fixture + " could not be created (" + exchange.requestId()
                    + "): HTTP " + response.status() + " " + response.reason() + " " + abbreviate(response.bodyText()));
        }
        String id = response.headers().first("Location")
                .map(location -> ResourceContent.parseLocation(location).get("id"))
                .orElse(null);
        if (id == null && !response.body().isEmpty()) {
            id = safe(() -> ResourceContent.id(response.body()));
        }
        if (id == null) {
            throw new TestScriptException(fixture + " was created (" + exchange.requestId()
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
