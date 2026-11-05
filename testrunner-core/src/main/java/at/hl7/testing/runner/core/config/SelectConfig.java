package at.hl7.testing.runner.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the configured TestScripts and tests run. Both are lists of patterns; empty selects everything.
 *
 * <p>A pattern is a glob: {@code *} matches any characters, {@code ?} one, everything else itself, without regard
 * to case. Setup and teardown always run.
 *
 * @param testScripts patterns of the TestScripts to run, each matched against the file name (with and without
 *                    extension), {@code TestScript.name} and {@code TestScript.url}; default all
 * @param tests       patterns of the tests to run ({@code TestScript.test.name}); default all. Tests that are not
 *                    selected are neither run nor reported; a TestScript without a selected test is left out
 */
public record SelectConfig(List<String> testScripts, List<String> tests) {

    public SelectConfig {
        testScripts = testScripts == null ? List.of() : testScripts.stream().map(String::trim).toList();
        tests = tests == null ? List.of() : tests.stream().map(String::trim).toList();
    }

    /** Everything is selected. */
    public static SelectConfig all() {
        return new SelectConfig(null, null);
    }

    /** Whether nothing is left out. */
    public boolean selectsAll() {
        return testScripts.isEmpty() && tests.isEmpty();
    }

    /** The patterns for a log line, e.g. {@code TestScripts [*patient*], tests [read]}; empty if all are selected. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!testScripts.isEmpty()) {
            parts.add("TestScripts " + testScripts);
        }
        if (!tests.isEmpty()) {
            parts.add("tests " + tests);
        }
        return String.join(", ", parts);
    }

    /** The problems of the patterns, each naming its property below {@code prefix}. */
    List<String> problems(String prefix) {
        List<String> problems = new ArrayList<>();
        blank(prefix + ".test-scripts", testScripts, problems);
        blank(prefix + ".tests", tests, problems);
        return problems;
    }

    private static void blank(String property, List<String> patterns, List<String> problems) {
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).isEmpty()) {
                problems.add(property + "[" + i + "] is empty; leave it out to select everything");
            }
        }
    }
}
