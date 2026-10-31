package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The mutable state of one TestScript execution: fixtures, kept responses and the last response.
 *
 * <p>Owned by exactly one {@link TestScriptExecution} and therefore confined to one thread; it replaces the
 * module-level globals of the Python proof of concept, so TestScripts can run in parallel without sharing state.
 *
 * <p>A fixture is kept as written. {@link #find} hands it out as it is to be used at that moment, through the view set
 * with {@link #resolveFixturesWith} (placeholders replaced, references to autocreated fixtures rewritten).
 */
final class ExecutionState {

    private final Map<String, Source.Fixture> fixtures = new HashMap<>();
    private final Map<String, Source.Response> responses = new HashMap<>();
    private final List<Source.Fixture> created = new ArrayList<>();
    private final Map<String, String> serverReferences = new HashMap<>();
    private UnaryOperator<Source.Fixture> fixtureView = UnaryOperator.identity();
    private Source.Response lastResponse;

    /** Sets how {@link #find} hands out a fixture; the identity until set. */
    void resolveFixturesWith(UnaryOperator<Source.Fixture> view) {
        this.fixtureView = view;
    }

    void putFixture(Source.Fixture fixture) {
        fixtures.put(fixture.id(), fixture);
    }

    /**
     * Records that a fixture was created on the server, so that it can be auto-deleted and references to it rewritten.
     *
     * @param reference {@code Type/id} of the fixture's resource as written, {@code null} if it has no id
     */
    void fixtureCreated(Source.Fixture fixture, String reference) {
        fixtures.put(fixture.id(), fixture);
        created.add(fixture);
        if (reference != null) {
            serverReferences.put(reference, fixture.resourceType() + "/" + fixture.serverId());
        }
    }

    /** Fixtures created on the server, in creation order. */
    List<Source.Fixture> createdFixtures() {
        return List.copyOf(created);
    }

    /** {@code Type/id} on the server of the autocreated fixture written as {@code Type/id}, if there is one. */
    Optional<String> serverReference(String reference) {
        return Optional.ofNullable(serverReferences.get(reference));
    }

    /** Whether any fixture was created on the server, i.e. whether references may need rewriting. */
    boolean hasCreatedFixtures() {
        return !serverReferences.isEmpty();
    }

    void responseReceived(Exchange exchange, String responseId) {
        Source.Response response = new Source.Response(exchange);
        lastResponse = response;
        if (responseId != null) {
            responses.put(responseId, response);
        }
    }

    Optional<Source.Response> lastResponse() {
        return Optional.ofNullable(lastResponse);
    }

    /** A fixture as written, without placeholders replaced; for creating it. */
    Optional<Source.Fixture> fixtureAsWritten(String id) {
        return Optional.ofNullable(fixtures.get(id));
    }

    /**
     * A fixture as it is to be used now, or a kept response, by id; a fixture wins (ids are unique, checked before the
     * run).
     *
     * @throws TestScriptException if a placeholder in the fixture cannot be resolved
     */
    Optional<Source> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Source.Fixture fixture = fixtures.get(id);
        return fixture != null ? Optional.of(fixtureView.apply(fixture)) : Optional.ofNullable(responses.get(id));
    }

    /** Like {@link #find(String)}, but an unknown id is an error of the TestScript. */
    Source require(String id, String usedAs) {
        return find(id).orElseThrow(() -> new TestScriptException(
                usedAs + " '" + id + "' is neither a fixture nor the responseId of an earlier operation"));
    }
}
