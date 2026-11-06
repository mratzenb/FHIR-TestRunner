package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.Destination;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The servers a TestScript addresses ({@code TestScript.destination}, {@code operation.destination},
 * {@code metadata.capability.destination}) and the configured servers they are sent to.
 *
 * <p>A TestScript addresses <em>several</em> servers when these name more than one index. Then each index must be
 * mapped in {@code suites[n].fhir-servers}. Otherwise it addresses one – the index it names, or destination 1 if it
 * names none – which is sent to {@code fhir-servers.<index>} if mapped, else to the suite's or the run's
 * {@code fhir-server}, as before several servers were supported (ADR-0019).
 */
final class Destinations {

    private Destinations() {
    }

    /** Every destination index the TestScript names, declared or used. */
    static SortedSet<Integer> indexes(TestScript testScript) {
        SortedSet<Integer> indexes = new TreeSet<>();
        testScript.destinations().stream().map(Destination::index).forEach(indexes::add);
        operations(testScript).map(Operation::destination).filter(Objects::nonNull).forEach(indexes::add);
        testScript.capabilities().stream().map(CapabilityRequirement::destination).filter(Objects::nonNull)
                .forEach(indexes::add);
        return indexes;
    }

    /** Whether the TestScript addresses more than one server. */
    static boolean several(TestScript testScript) {
        return indexes(testScript).size() > 1;
    }

    /** Every operation, in the order they run. */
    static Stream<Operation> operations(TestScript testScript) {
        return Stream.of(testScript.setup().stream(),
                        testScript.tests().stream().map(TestDefinition::actions).flatMap(List::stream),
                        testScript.teardown().stream())
                .flatMap(s -> s)
                .filter(Operation.class::isInstance).map(Operation.class::cast);
    }

    /**
     * Where the TestScript is sent.
     *
     * @param several  whether it addresses several servers
     * @param servers  the server of each destination index, in index order; empty if no server is configured at all
     * @param problems why it cannot be sent: indexes of a TestScript for several servers that are not mapped
     */
    record Mapping(boolean several, SortedMap<Integer, FhirServerConfig> servers, List<String> problems) {

        Mapping {
            servers = Collections.unmodifiableSortedMap(new TreeMap<>(servers));
            problems = List.copyOf(problems);
        }

        /** The base URL of each server, in index order. */
        List<String> bases() {
            return servers.values().stream().map(s -> s.base().orElseThrow()).toList();
        }
    }

    /** The configured servers of the TestScript, see the class description. */
    static Mapping map(RunConfiguration configuration, SuiteConfig suite, TestScript testScript) {
        SortedSet<Integer> indexes = indexes(testScript);
        Map<Integer, FhirServerConfig> mapped = suite.fhirServers();
        FhirServerConfig fallback = configuration.fhirServerOf(suite);
        if (indexes.size() <= 1) {
            int index = indexes.isEmpty() ? 1 : indexes.first();
            FhirServerConfig server = mapped.getOrDefault(index, fallback);
            return new Mapping(false, server.base().isPresent() ? new TreeMap<>(Map.of(index, server)) : new TreeMap<>(),
                    List.of());
        }
        if (mapped.isEmpty() && fallback.base().isEmpty()) {
            return new Mapping(true, new TreeMap<>(), List.of()); // no server at all: skipped like any TestScript
        }
        SortedMap<Integer, FhirServerConfig> servers = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        int suiteIndex = configuration.suites().indexOf(suite);
        for (int index : indexes) {
            FhirServerConfig server = mapped.get(index);
            if (server != null) {
                servers.put(index, server);
            } else {
                problems.add("destination " + describe(testScript, index) + " is not mapped to a server: set"
                        + " testrunner.suites[" + suiteIndex + "].fhir-servers." + index + ".base-url");
            }
        }
        if (!problems.isEmpty()) {
            problems.addFirst("The TestScript addresses " + indexes.size() + " servers (destinations " + indexes
                    + "); the runner sends each to the server configured for its index, not to fhir-server");
        }
        return new Mapping(true, servers, problems);
    }

    /** {@code 2 (FHIR-Server)}, or {@code 2} if it is not declared. */
    static String describe(TestScript testScript, int index) {
        return testScript.destinations().stream().filter(d -> d.index() == index).findFirst()
                .map(Destination::toString).orElse(String.valueOf(index));
    }
}
