package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.SelectConfig;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.source.TestScriptSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which TestScripts and tests of a run are selected ({@code testrunner.select}). Patterns are globs: {@code *} any
 * characters, {@code ?} one character, without regard to case; a pattern without wildcards must match the whole text.
 *
 * <p>A TestScript is selected when a TestScript pattern matches its file name (with or without extension),
 * {@code TestScript.name} or {@code TestScript.url}, and – if test patterns are given – at least one of its tests. Name,
 * url and tests are only known once the file is read; a file that cannot be read is selected only when its file name
 * matches a TestScript pattern, so that its error is reported.
 */
final class Selection {

    private final List<Pattern> testScripts;
    private final List<Pattern> tests;

    Selection(SelectConfig select) {
        this.testScripts = select.testScripts().stream().map(Selection::glob).toList();
        this.tests = select.tests().stream().map(Selection::glob).toList();
    }

    /** Whether everything is selected. */
    boolean all() {
        return testScripts.isEmpty() && tests.isEmpty();
    }

    /** Whether the file name alone selects the TestScript, so that it need not be read to decide. */
    boolean selectsByFileName(TestScriptSource source) {
        return tests.isEmpty() && (testScripts.isEmpty() || matchesAny(testScripts, fileNames(source)));
    }

    /**
     * Whether the TestScript is selected.
     *
     * @param testScript the TestScript read from {@code source}, {@code null} if it cannot be read
     */
    boolean selects(TestScriptSource source, TestScript testScript) {
        if (all()) {
            return true;
        }
        if (testScript == null) {
            return !testScripts.isEmpty() && matchesAny(testScripts, fileNames(source));
        }
        List<String> candidates = new ArrayList<>(fileNames(source));
        if (testScript.name() != null) {
            candidates.add(testScript.name());
        }
        if (testScript.url() != null) {
            candidates.add(testScript.url());
        }
        return (testScripts.isEmpty() || matchesAny(testScripts, candidates))
                && (tests.isEmpty() || testScript.tests().stream().anyMatch(this::selects));
    }

    /** Whether tests are selected by name; if not, every test of a selected TestScript runs. */
    boolean selectsTests() {
        return !tests.isEmpty();
    }

    /** Whether the test is selected. */
    boolean selects(TestDefinition test) {
        return tests.isEmpty() || matchesAny(tests, List.of(test.name()));
    }

    /** The tests of the TestScript that are selected, in their order. */
    List<TestDefinition> tests(TestScript testScript) {
        return testScript.tests().stream().filter(this::selects).toList();
    }

    private static List<String> fileNames(TestScriptSource source) {
        return List.of(source.file().getFileName().toString(), source.baseName());
    }

    private static boolean matchesAny(List<Pattern> patterns, List<String> candidates) {
        return patterns.stream().anyMatch(p -> candidates.stream().anyMatch(c -> p.matcher(c).matches()));
    }

    /** A glob as regular expression: {@code *} and {@code ?} are wildcards, everything else is literal. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

}
