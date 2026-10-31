package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The mutable state of one TestScript execution: fixtures, kept responses and requests, the last response and
 * request.
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
    private final Map<String, Source.Request> requests = new HashMap<>();
    private final List<Source.Fixture> created = new ArrayList<>();
    private final Map<String, String> serverReferences = new HashMap<>();
    private UnaryOperator<Source.Fixture> fixtureView = UnaryOperator.identity();
    private Source.Response lastResponse;
    private Source.Request lastRequest;

    /** Sets how {@link #find} hands out a fixture; the identity until set. */
    void resolveFixturesWith(UnaryOperator<Source.Fixture> view) {
        this.fixtureView = view;
    }

    void putFixture(Source.Fixture fixture) {
        fixtures.put(fixture.id(), fixture);
    }

    /**
     * Records that a fixture was autocreated on the server, so that it can be auto-deleted and references to it
     * rewritten.
     *
     * @param reference {@code Type/id} of the fixture's resource as written, {@code null} if references to it are not
     *                  rewritten
     */
    void fixtureCreated(Source.Fixture fixture, String reference) {
        fixtures.put(fixture.id(), fixture);
        created.add(fixture);
        if (reference != null) {
            serverAssigned(reference, fixture.resourceType() + "/" + fixture.serverId());
        }
    }

    /** Records that the resource written as {@code reference} ({@code Type/id}) is {@code serverReference} on the server. */
    void serverAssigned(String reference, String serverReference) {
        serverReferences.put(reference, serverReference);
    }

    /** Fixtures created on the server, in creation order. */
    List<Source.Fixture> createdFixtures() {
        return List.copyOf(created);
    }

    /** {@code Type/id} on the server of the fixture written as {@code Type/id}, if it was created there. */
    Optional<String> serverReference(String reference) {
        return Optional.ofNullable(serverReferences.get(reference));
    }

    /** Whether a fixture was created on the server under an id of the server's, i.e. whether references may change. */
    boolean hasServerReferences() {
        return !serverReferences.isEmpty();
    }

    /**
     * Records an operation's exchange: its response and its request become the last ones, and are kept under the
     * operation's ids if it has them.
     */
    void exchanged(Exchange exchange, String requestId, String responseId) {
        lastResponse = new Source.Response(exchange);
        lastRequest = new Source.Request(exchange);
        if (responseId != null) {
            responses.put(responseId, lastResponse);
        }
        if (requestId != null) {
            requests.put(requestId, lastRequest);
        }
    }

    Optional<Source.Response> lastResponse() {
        return Optional.ofNullable(lastResponse);
    }

    Optional<Source.Request> lastRequest() {
        return Optional.ofNullable(lastRequest);
    }

    /** A fixture as written, without placeholders replaced; for creating it. */
    Optional<Source.Fixture> fixtureAsWritten(String id) {
        return Optional.ofNullable(fixtures.get(id));
    }

    /**
     * A fixture as it is to be used now, or a kept response or request, by id (ids are unique, checked before the run).
     *
     * @throws TestScriptException if a placeholder in the fixture cannot be resolved
     */
    Optional<Source> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Source.Fixture fixture = fixtures.get(id);
        if (fixture != null) {
            return Optional.of(fixtureView.apply(fixture));
        }
        return Optional.<Source>ofNullable(responses.get(id)).or(() -> Optional.ofNullable(requests.get(id)));
    }

    /** Like {@link #find(String)}, but an unknown id is an error of the TestScript. */
    Source require(String id, String usedAs) {
        return find(id).orElseThrow(() -> new TestScriptException(
                usedAs + " '" + id + "' is neither a fixture nor the responseId or requestId of an earlier operation"));
    }
}
