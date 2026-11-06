package at.hl7.testing.runner.app;

import jakarta.annotation.Nonnull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.List;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar testrunner-app.jar --config=my-run.yml [--check | --list] [--testscript=*patient*] [--test=read]
 *     [--novalidator] [--testrunner.execution.parallelism=4] ...
 * </pre>
 *
 * Short options:
 * <ul>
 *   <li>{@code --config=<file>} adds a configuration file to the Spring Boot configuration
 *       ({@code --spring.config.additional-location=file:<file>});</li>
 *   <li>{@code --novalidator}: {@code --testrunner.validation.enabled=false};</li>
 *   <li>{@code --check}: {@code --testrunner.mode=check} – load and check the TestScripts, send nothing;</li>
 *   <li>{@code --list}: {@code --testrunner.mode=list} – print what would run, send and write nothing;</li>
 *   <li>{@code --testscript=<glob>}: one more entry of {@code testrunner.select.test-scripts} (file name,
 *       {@code TestScript.name} or url); may be repeated, or several globs separated by commas;</li>
 *   <li>{@code --test=<glob>}: one more entry of {@code testrunner.select.tests} (test name); likewise.</li>
 * </ul>
 * Each also works as {@code --option value}. Any property can also be given as argument, system property or
 * environment variable ({@code TESTRUNNER_FHIRSERVER_BASEURL}).
 *
 * <p>Exit codes: 0 every TestScript passed, 1 at least one failed or had an error, 2 the configuration is invalid,
 * 3 TestScripts were skipped – by default only when every TestScript was, see {@link RunCommand.ExitOnSkip}.
 */
@SpringBootApplication
public class TestRunnerApplication {

    public static void main(@Nonnull String[] args) {
        SpringApplication application = new SpringApplication(TestRunnerApplication.class);
        System.exit(SpringApplication.exit(application.run(translate(args))));
    }

    /** Expands the short options into Spring Boot properties. */
    static String[] translate(String[] args) {
        List<String> translated = new ArrayList<>();
        List<String> testScripts = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--config") && i + 1 < args.length) {
                translated.add("--spring.config.additional-location=" + location(args[++i]));
            } else if (arg.startsWith("--config=")) {
                translated.add("--spring.config.additional-location=" + location(arg.substring("--config=".length())));
            } else if (arg.equals("--novalidator")) {
                translated.add("--testrunner.validation.enabled=false");
            } else if (arg.equals("--check")) {
                translated.add("--testrunner.mode=check");
            } else if (arg.equals("--list")) {
                translated.add("--testrunner.mode=list");
            } else if (arg.equals("--testscript") && i + 1 < args.length) {
                testScripts.addAll(patterns(args[++i]));
            } else if (arg.startsWith("--testscript=")) {
                testScripts.addAll(patterns(arg.substring("--testscript=".length())));
            } else if (arg.equals("--test") && i + 1 < args.length) {
                tests.addAll(patterns(args[++i]));
            } else if (arg.startsWith("--test=")) {
                tests.addAll(patterns(arg.substring("--test=".length())));
            } else {
                translated.add(arg);
            }
        }
        // indexed, so that each glob stays one entry even with a comma inside a property value
        for (int i = 0; i < testScripts.size(); i++) {
            translated.add("--testrunner.select.test-scripts[" + i + "]=" + testScripts.get(i));
        }
        for (int i = 0; i < tests.size(); i++) {
            translated.add("--testrunner.select.tests[" + i + "]=" + tests.get(i));
        }
        return translated.toArray(String[]::new);
    }

    /** The globs of one option value: separated by commas, blanks around them removed. */
    private static List<String> patterns(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    private static String location(String path) {
        return path.contains(":") && !path.matches("^[A-Za-z]:\\\\.*") ? path : "file:" + path;
    }
}
