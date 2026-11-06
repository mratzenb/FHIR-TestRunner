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
 *
 * <p>What a server assigned – the fixtures created there and the ids it gave them – is kept per server, by
 * {@code TestScript.destination} index. The state answers for the server set with {@link #onServer}: the one the
 * operation being built goes to, or that of the last operation.
 */
final class ExecutionState {

    /**
     * A fixture created on a server.
     *
     * @param server  the destination index of the server
     * @param fixture the fixture as created, with the server's id
     */
    record Created(int server, Source.Fixture fixture) {
    }

    private final Map<String, Source.Fixture> fixtures = new HashMap<>();
    private final Map<String, Source.Response> responses = new HashMap<>();
    private final Map<String, Source.Request> requests = new HashMap<>();
    private final List<Created> created = new ArrayList<>();
    private final Map<Integer, Map<String, Source.Fixture>> createdOn = new HashMap<>();
    private final Map<Integer, Map<String, String>> serverReferences = new HashMap<>();
    private UnaryOperator<Source.Fixture> fixtureView = UnaryOperator.identity();
    private Source.Response lastResponse;
    private Source.Request lastRequest;
    private int server = 1;

    /** Makes the state answer for the server of destination {@code index}. */
    void onServer(int index) {
        this.server = index;
    }

    /** The destination index the state answers for. */
    int server() {
        return server;
    }

    /** Sets how {@link #find} hands out a fixture; the identity until set. */
    void resolveFixturesWith(UnaryOperator<Source.Fixture> view) {
        this.fixtureView = view;
    }

    void putFixture(Source.Fixture fixture) {
        fixtures.put(fixture.id(), fixture);
    }

    /**
     * Records that a fixture was autocreated on the current server, so that it can be auto-deleted and references to it
     * rewritten.
     *
     * @param reference {@code Type/id} of the fixture's resource as written, {@code null} if references to it are not
     *                  rewritten
     */
    void fixtureCreated(Source.Fixture fixture, String reference) {
        createdOn.computeIfAbsent(server, s -> new HashMap<>()).put(fixture.id(), fixture);
        created.add(new Created(server, fixture));
        if (reference != null) {
            serverAssigned(reference, fixture.resourceType() + "/" + fixture.serverId());
        }
    }

    /**
     * Records that the resource written as {@code reference} ({@code Type/id}) is {@code serverReference} on the current
     * server.
     */
    void serverAssigned(String reference, String serverReference) {
        serverReferences.computeIfAbsent(server, s -> new HashMap<>()).put(reference, serverReference);
    }

    /** Fixtures created on the servers, in creation order. */
    List<Created> createdFixtures() {
        return List.copyOf(created);
    }

    /** {@code Type/id} on the current server of the fixture written as {@code Type/id}, if it was created there. */
    Optional<String> serverReference(String reference) {
        return Optional.ofNullable(serverReferences.getOrDefault(server, Map.of()).get(reference));
    }

    /**
     * Whether a fixture was created on the current server under an id of the server's, i.e. whether references may
     * change.
     */
    boolean hasServerReferences() {
        return !serverReferences.getOrDefault(server, Map.of()).isEmpty();
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
     * A fixture as it is to be used now – as created on the current server, else as written and resolved – or a kept
     * response or request, by id (ids are unique, checked before the run).
     *
     * @throws TestScriptException if a placeholder in the fixture cannot be resolved
     */
    Optional<Source> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Source.Fixture createdHere = createdOn.getOrDefault(server, Map.of()).get(id);
        if (createdHere != null) {
            return Optional.of(createdHere);
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
