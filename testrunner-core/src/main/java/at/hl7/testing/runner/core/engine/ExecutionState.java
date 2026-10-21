package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mutable state of one TestScript execution: fixtures, kept responses and the last response.
 *
 * <p>Owned by exactly one {@link TestScriptExecution} and therefore confined to one thread; it replaces the
 * module-level globals of the Python proof of concept, so TestScripts can run in parallel without sharing state.
 */
final class ExecutionState {

    private final Map<String, Source.Fixture> fixtures = new HashMap<>();
    private final Map<String, Source.Response> responses = new HashMap<>();
    private final List<Source.Fixture> created = new ArrayList<>();
    private Source.Response lastResponse;

    void putFixture(Source.Fixture fixture) {
        fixtures.put(fixture.id(), fixture);
    }

    /** Records that a fixture was created on the server, so that it can be auto-deleted. */
    void fixtureCreated(Source.Fixture fixture) {
        fixtures.put(fixture.id(), fixture);
        created.add(fixture);
    }

    /** Fixtures created on the server, in creation order. */
    List<Source.Fixture> createdFixtures() {
        return List.copyOf(created);
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

    /** A fixture or kept response by id; a fixture wins (ids are unique, checked before the run). */
    Optional<Source> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Source.Fixture fixture = fixtures.get(id);
        return fixture != null ? Optional.of(fixture) : Optional.ofNullable(responses.get(id));
    }

    /** Like {@link #find(String)}, but an unknown id is an error of the TestScript. */
    Source require(String id, String usedAs) {
        return find(id).orElseThrow(() -> new TestScriptException(
                usedAs + " '" + id + "' is neither a fixture nor the responseId of an earlier operation"));
    }
}
