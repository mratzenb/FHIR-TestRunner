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
