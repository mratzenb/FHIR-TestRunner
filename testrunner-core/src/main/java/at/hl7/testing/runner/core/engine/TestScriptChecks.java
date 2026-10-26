package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.model.VariableDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Checks that are independent of the FHIR version and need the whole TestScript, run before anything is sent to
 * the server.
 */
final class TestScriptChecks {

    private TestScriptChecks() {
    }

    /** @return a description of every problem, empty if the TestScript can be run */
    static List<String> problems(TestScript testScript) {
        List<String> problems = new ArrayList<>();
        Map<String, String> ids = new HashMap<>();
        for (FixtureDefinition fixture : testScript.fixtures()) {
            unique(ids, fixture.id(), "fixture", problems);
        }
        allActions(testScript).forEach(action -> {
            if (action instanceof Operation operation) {
                unique(ids, operation.responseId(), "responseId", problems);
                unique(ids, operation.requestId(), "requestId", problems);
            }
        });
        problems.addAll(destinationProblems(testScript));
        Set<String> variableNames = new HashSet<>();
        for (VariableDefinition variable : testScript.variables()) {
            if (!variableNames.add(variable.name())) {
                problems.add("Variable '" + variable.name() + "' is defined twice");
            }
            long sources = Stream.of(variable.expression(), variable.path(), variable.headerField())
                    .filter(v -> v != null).count();
            if (sources > 1) {
                problems.add("Variable '" + variable.name() + "' has more than one of expression, path and headerField");
            }
            if (sources == 0 && variable.defaultValue() == null) {
                problems.add("Variable '" + variable.name() + "' has neither expression, path, headerField nor defaultValue");
            }
        }
        return problems;
    }

    /**
     * The runner sends every request to the one server configured for the suite. A TestScript written for several
     * servers cannot run as written; running it against one server anyway would test something else. A single
     * destination index that is not declared is accepted: many TestScripts use {@code destination: 1} without
     * declaring it, and they address one server all the same.
     */
    private static List<String> destinationProblems(TestScript testScript) {
        Set<Integer> used = new TreeSet<>();
        allActions(testScript).forEach(action -> {
            if (action instanceof Operation operation && operation.destination() != null) {
                used.add(operation.destination());
            }
        });
        if (testScript.destinations().size() <= 1 && used.size() <= 1) {
            return List.of();
        }
        return List.of("The TestScript sends requests to more than one server (TestScript.destination "
                + testScript.destinations() + ", operation.destination " + used + "); the runner sends every request"
                + " to the one FHIR server configured for the suite");
    }

    private static void unique(Map<String, String> ids, String id, String kind, List<String> problems) {
        if (id == null) {
            return;
        }
        String previous = ids.putIfAbsent(id, kind);
        if (previous != null) {
            problems.add("Duplicate id '" + id + "', used as " + previous + " and as " + kind
                    + "; fixture ids, responseIds and requestIds must be unique");
        }
    }

    private static Stream<Action> allActions(TestScript testScript) {
        return Stream.of(testScript.setup().stream(),
                        testScript.tests().stream().map(TestDefinition::actions).flatMap(List::stream),
                        testScript.teardown().stream())
                .flatMap(s -> s);
    }
}
