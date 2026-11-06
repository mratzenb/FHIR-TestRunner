package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.model.Operation;

import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The servers of one TestScript execution, each with its client, by {@code TestScript.destination} index. Built by
 * {@link ExecutionFactory} from {@link Destinations#map}.
 *
 * <p>With one server every operation goes there, whatever its {@code destination}; with several, each to the server of
 * its {@code destination} – {@link TestScriptChecks} has made sure that every operation names one.
 */
final class Servers {

    /**
     * One server.
     *
     * @param index the destination index
     * @param base  its base URL, without a trailing slash
     * @param http  the client for it: its headers and access token, the TestScript's communication log
     */
    record Endpoint(int index, String base, FhirHttpClient http) {
    }

    private final SortedMap<Integer, Endpoint> endpoints = new TreeMap<>();
    private final boolean several;

    /**
     * @param endpoints at least one
     * @param several   whether the TestScript addresses several servers
     */
    Servers(Collection<Endpoint> endpoints, boolean several) {
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("A TestScript execution needs a server");
        }
        endpoints.forEach(e -> this.endpoints.put(e.index(), e));
        this.several = several;
    }

    /** Every server, in index order. */
    List<Endpoint> all() {
        return List.copyOf(endpoints.values());
    }

    /** The server of the lowest index; the one of a TestScript for one server. */
    Endpoint first() {
        return endpoints.get(endpoints.firstKey());
    }

    boolean several() {
        return several;
    }

    /**
     * The server an operation is sent to.
     *
     * @throws TestScriptException if the TestScript addresses several servers and the operation names none of them
     */
    Endpoint of(Operation operation) {
        if (!several) {
            return first();
        }
        Endpoint endpoint = operation.destination() == null ? null : endpoints.get(operation.destination());
        if (endpoint == null) {
            throw new TestScriptException("the operation names " + (operation.destination() == null ? "no destination"
                    : "destination " + operation.destination()) + ", but the servers are those of destinations "
                    + endpoints.keySet());
        }
        return endpoint;
    }

    /** {@code the server}, or {@code the server of destination 2} when there are several, for messages. */
    String name(Endpoint endpoint) {
        return several ? "the server of destination " + endpoint.index() : "the server";
    }

    /** The base URL of each server, in index order. */
    List<String> bases() {
        return endpoints.values().stream().map(Endpoint::base).toList();
    }
}
