package at.hl7.testing.runner.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.List;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar testrunner-app.jar --config=my-run.yml [--novalidator] [--testrunner.execution.parallelism=4] ...
 * </pre>
 *
 * {@code --config} adds a configuration file to the Spring Boot configuration (it is short for
 * {@code --spring.config.additional-location=file:...}); {@code --novalidator} is short for
 * {@code --testrunner.validation.enabled=false}. Any property can also be given as argument, system property or
 * environment variable ({@code TESTRUNNER_FHIRSERVER_BASEURL}).
 *
 * <p>Exit codes: 0 every TestScript passed, 1 at least one failed or had an error, 2 the configuration is invalid,
 * 3 TestScripts were skipped – by default only when every TestScript was, see {@link RunCommand.ExitOnSkip}.
 */
@SpringBootApplication
public class TestRunnerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(TestRunnerApplication.class);
        System.exit(SpringApplication.exit(application.run(translate(args))));
    }

    /** Expands the short options into Spring Boot properties. */
    static String[] translate(String[] args) {
        List<String> translated = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--config") && i + 1 < args.length) {
                translated.add("--spring.config.additional-location=" + location(args[++i]));
            } else if (arg.startsWith("--config=")) {
                translated.add("--spring.config.additional-location=" + location(arg.substring("--config=".length())));
            } else if (arg.equals("--novalidator")) {
                translated.add("--testrunner.validation.enabled=false");
            } else {
                translated.add(arg);
            }
        }
        return translated.toArray(String[]::new);
    }

    private static String location(String path) {
        return path.contains(":") && !path.matches("^[A-Za-z]:\\\\.*") ? path : "file:" + path;
    }
}
