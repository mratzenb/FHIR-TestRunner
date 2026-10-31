package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.ResourceContent;
import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.ProfileDeclaration;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.model.VariableDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Checks that are independent of the FHIR version and need the whole TestScript, run before anything is sent to
 * the server. Each problem found here would make the TestScript fail in every run, so it is reported at once,
 * before a fixture is created on the server.
 */
final class TestScriptChecks {

    private TestScriptChecks() {
    }

    /**
     * @param fixtureResources the resource of each fixture that has one, by fixture id
     * @param builtIn          the names of the values the runner provides, without {@link VariableResolver#BUILT_IN}
     * @return a description of every problem, empty if the TestScript can be run
     */
    static List<String> problems(TestScript testScript, Map<String, Body> fixtureResources, Set<String> builtIn) {
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
        problems.addAll(new References(testScript, fixtureResources, builtIn).problems());
        return problems;
    }

    /**
     * Checks every id and name the actions refer to, walking them in the order they run (setup, tests, teardown):
     * the sources ({@code sourceId}, {@code targetId}, {@code compareToSourceId}, {@code minimumId},
     * {@code variable.sourceId}), the {@code ${…}} placeholders – also those in fixture resources – and
     * {@code validateProfileId}.
     *
     * <p>Only what fails in every run is reported. A response or request kept by a later operation is never there in
     * time; one kept by an earlier operation may be missing too (its test stopped), but that shows only while running.
     */
    private static final class References {

        private final TestScript testScript;
        private final Map<String, Body> fixtureResources;
        private final Set<String> builtIn;
        private final Map<String, VariableDefinition> variables = new HashMap<>();
        private final Set<String> fixtureIds = new HashSet<>();
        private final Set<String> autocreated = new HashSet<>();
        private final Map<String, List<String>> fixturePlaceholders = new HashMap<>();
        /** Where each responseId and requestId is kept, e.g. {@code Test 'read', action 2}. */
        private final Map<String, String> keptIds = new HashMap<>();
        private final Set<String> requestIds = new HashSet<>();
        private final Set<String> profiles = new LinkedHashSet<>();
        private final Set<String> reported = new HashSet<>();
        private final List<String> problems = new ArrayList<>();
        /** The responseIds and requestIds kept before the action being checked. */
        private final Set<String> kept = new HashSet<>();
        private boolean operationBefore;

        References(TestScript testScript, Map<String, Body> fixtureResources, Set<String> builtIn) {
            this.testScript = testScript;
            this.fixtureResources = fixtureResources;
            this.builtIn = builtIn;
            testScript.variables().forEach(v -> variables.putIfAbsent(v.name(), v));
            for (FixtureDefinition fixture : testScript.fixtures()) {
                fixtureIds.add(fixture.id());
                if (fixture.autocreate()) {
                    autocreated.add(fixture.id());
                }
            }
            fixtureResources.forEach((id, body) -> fixturePlaceholders.put(id, placeholders(body)));
            testScript.profiles().stream().map(ProfileDeclaration::id).forEach(profiles::add);
        }

        List<String> problems() {
            List<Step> steps = steps();
            for (Step step : steps) {
                if (step.action() instanceof Operation operation) {
                    if (operation.responseId() != null) {
                        keptIds.putIfAbsent(operation.responseId(), step.where());
                    }
                    if (operation.requestId() != null) {
                        keptIds.putIfAbsent(operation.requestId(), step.where());
                        requestIds.add(operation.requestId());
                    }
                }
            }
            // autocreated fixtures are resolved before the first action
            for (FixtureDefinition fixture : testScript.fixtures()) {
                if (fixture.autocreate()) {
                    placeholdersUsed("Fixture '" + fixture.id() + "' (autocreate)",
                            fixturePlaceholders.getOrDefault(fixture.id(), List.of()));
                }
            }
            steps.forEach(this::check);
            return problems;
        }

        private void check(Step step) {
            String where = step.where();
            List<String> used = new ArrayList<>();
            switch (step.action()) {
                case Operation operation -> {
                    operation.withValues(collectInto(used));
                    source(where, "sourceId", operation.sourceId(), used);
                    source(where, "targetId", operation.targetId(), used);
                }
                case Assertion assertion -> {
                    assertion.withValues(collectInto(used));
                    if (assertion.sourceId() == null && !operationBefore) {
                        problems.add(where + ": the assertion has no sourceId and no operation runs before it, so"
                                + " there is no " + (AssertionEvaluator.onRequest(assertion) ? "request" : "response")
                                + " to check");
                    }
                    source(where, "sourceId", assertion.sourceId(), used);
                    source(where, "compareToSourceId", assertion.compareToSourceId(), used);
                    if (assertion.check() instanceof Check.MinimumId minimum) {
                        source(where, "minimumId", minimum.fixtureId(), used);
                    }
                    if (assertion.check() instanceof Check.ValidateProfile profile && !dynamic(profile.profileId())
                            && !profiles.contains(profile.profileId())) {
                        problems.add(where + ": validateProfileId '" + profile.profileId() + "' is not declared in"
                                + " TestScript.profile" + (profiles.isEmpty() ? " (the TestScript declares no profiles)"
                                : " (declared: " + profiles + ")"));
                    }
                }
            }
            placeholdersUsed(where, used);
            if (step.action() instanceof Operation operation) {
                operationBefore = true;
                if (operation.responseId() != null) {
                    kept.add(operation.responseId());
                }
                if (operation.requestId() != null) {
                    kept.add(operation.requestId());
                }
            }
        }

        /** Checks a source named by an action; a fixture's placeholders are resolved when it is used. */
        private void source(String where, String kind, String id, List<String> used) {
            if (id == null || dynamic(id)) {
                return;
            }
            if (fixtureResources.containsKey(id)) {
                if (kind.equals("targetId") && !autocreated.contains(id)) {
                    problems.add(where + ": targetId '" + id + "' is a fixture without autocreate, so it has no id on"
                            + " the server");
                } else if (!autocreated.contains(id)) {
                    usedFixture(id, where, used);
                }
            } else if (keptIds.containsKey(id)) {
                if (!kept.contains(id)) {
                    problems.add(where + ": " + kind + " '" + id + "' is not there yet, it is kept by "
                            + keptIds.get(id));
                } else if (kind.equals("targetId") && requestIds.contains(id)) {
                    problems.add(where + ": targetId '" + id + "' is a requestId; use the responseId of that"
                            + " operation to address what it created");
                }
            } else {
                problems.add(where + ": " + kind + " " + unknown(id));
            }
        }

        /**
         * Checks the placeholders used by an action: each must be defined, and a variable must be able to find its
         * source at this point. A variable on a fixture brings in that fixture's placeholders.
         */
        private void placeholdersUsed(String where, List<String> names) {
            Deque<String> todo = new ArrayDeque<>(names);
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String name = todo.poll();
                if (!seen.add(name) || !defined(name, where)) {
                    continue;
                }
                VariableDefinition variable = variables.get(name);
                if (variable == null
                        || (variable.expression() == null && variable.path() == null && variable.headerField() == null)) {
                    continue; // a value of the runner, or only a defaultValue
                }
                String source = variable.sourceId();
                String prefix = where + ": variable '" + name + "' reads ";
                if (source == null) {
                    if (!operationBefore) {
                        problems.add(prefix + "the last response, but no operation runs before");
                    }
                } else if (fixtureResources.containsKey(source)) {
                    if (variable.headerField() != null) {
                        problems.add(prefix + "header " + variable.headerField() + ", but '" + source
                                + "' is a fixture, not a response");
                    } else if (!autocreated.contains(source)) {
                        List<String> fromFixture = new ArrayList<>();
                        usedFixture(source, where, fromFixture);
                        todo.addAll(fromFixture);
                    }
                } else if (!keptIds.containsKey(source)) {
                    problems.add(prefix + "sourceId " + unknown(source));
                } else if (!kept.contains(source)) {
                    problems.add(prefix + "'" + source + "', which is not there yet, it is kept by "
                            + keptIds.get(source));
                }
            }
        }

        /** Adds the placeholders of a fixture used here; an undefined one is reported naming the fixture. */
        private void usedFixture(String fixtureId, String where, List<String> used) {
            for (String name : fixturePlaceholders.get(fixtureId)) {
                if (defined(name, where + ", fixture '" + fixtureId + "'")) {
                    used.add(name);
                }
            }
        }

        /** Whether {@code name} is a variable or a value of the runner; reports it once if not. */
        private boolean defined(String name, String where) {
            if (name.startsWith(VariableResolver.BUILT_IN)) {
                if (builtIn.contains(name.substring(VariableResolver.BUILT_IN.length()))) {
                    return true;
                }
                if (reported.add(name)) {
                    problems.add("'${" + name + "}' (" + where + ") is no value of the runner; known: "
                            + builtIn.stream().sorted().map(n -> "${" + VariableResolver.BUILT_IN + n + "}").toList());
                }
                return false;
            }
            if (variables.containsKey(name)) {
                return true;
            }
            if (reported.add(name)) {
                problems.add("Variable '" + name + "' (" + where + ") is used but not defined by the TestScript");
            }
            return false;
        }

        private String unknown(String id) {
            return fixtureIds.contains(id) ? "'" + id + "' is a fixture without resource"
                    : "'" + id + "' is neither a fixture nor the responseId or requestId of an operation";
        }

        private List<Step> steps() {
            List<Step> steps = new ArrayList<>();
            add(steps, "Setup", testScript.setup());
            for (TestDefinition test : testScript.tests()) {
                add(steps, "Test '" + test.name() + "'", test.actions());
            }
            add(steps, "Teardown", testScript.teardown());
            return steps;
        }

        private static void add(List<Step> steps, String phase, List<Action> actions) {
            for (int i = 0; i < actions.size(); i++) {
                steps.add(new Step(phase + ", action " + (i + 1), actions.get(i)));
            }
        }

        private static UnaryOperator<String> collectInto(List<String> names) {
            return text -> {
                names.addAll(VariableResolver.placeholders(text));
                return text;
            };
        }

        /** The placeholders in the text values of a fixture resource; none if it cannot be read. */
        private static List<String> placeholders(Body body) {
            if (!body.text().contains("${")) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            try {
                ResourceContent.replaceText(body, collectInto(names));
            } catch (IllegalArgumentException e) {
                return List.of(); // reported where the fixture is used
            }
            return names;
        }

        /** An id with a placeholder is only known when it is resolved. */
        private static boolean dynamic(String id) {
            return id.contains("${");
        }

        /** An action and where it is, e.g. {@code Test 'read', action 2}. */
        private record Step(String where, Action action) {
        }
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
