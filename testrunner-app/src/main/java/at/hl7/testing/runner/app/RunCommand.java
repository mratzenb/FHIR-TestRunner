package at.hl7.testing.runner.app;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.engine.ConfigurationException;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the configured TestScripts once at start-up and turns the outcome into the exit code. Switched off with
 * {@code testrunner.run-on-startup=false}, e.g. when the runner is embedded or in tests.
 */
@Component
@ConditionalOnProperty(name = "testrunner.run-on-startup", havingValue = "true", matchIfMissing = true)
public class RunCommand implements ApplicationRunner, ExitCodeGenerator {

    static final int EXIT_PASSED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_INVALID_CONFIGURATION = 2;

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    private final TestRunner runner;
    private final RunConfiguration configuration;
    private int exitCode = EXIT_PASSED;

    public RunCommand(TestRunner runner, RunConfiguration configuration) {
        this.runner = runner;
        this.configuration = configuration;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            RunResult result = runner.run(configuration);
            exitCode = result.outcome() == Outcome.PASS || result.outcome() == Outcome.SKIP ? EXIT_PASSED : EXIT_FAILED;
        } catch (ConfigurationException e) {
            log.error("{}", e.getMessage());
            exitCode = EXIT_INVALID_CONFIGURATION;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
