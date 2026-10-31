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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Runs the configured TestScripts once at start-up and turns the outcome into the exit code. Switched off with
 * {@code testrunner.run-on-startup=false}, e.g. when the runner is embedded or in tests.
 *
 * <p>A skipped TestScript tested nothing. {@code testrunner.exit-on-skip} decides when skips give
 * {@link #EXIT_SKIPPED} instead of {@link #EXIT_PASSED}, so that a CI run that tested nothing does not look green.
 */
@Component
@ConditionalOnProperty(name = "testrunner.run-on-startup", havingValue = "true", matchIfMissing = true)
public class RunCommand implements ApplicationRunner, ExitCodeGenerator {

    static final int EXIT_PASSED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_INVALID_CONFIGURATION = 2;
    static final int EXIT_SKIPPED = 3;

    /** When skipped TestScripts make the exit code {@link #EXIT_SKIPPED} ({@code testrunner.exit-on-skip}). */
    public enum ExitOnSkip {
        /** Only when every TestScript was skipped, i.e. nothing was tested (default). */
        ALL,
        /** When any TestScript was skipped and none failed. */
        ANY,
        /** Never; skipped TestScripts count as passed. */
        NEVER
    }

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    private final TestRunner runner;
    private final RunConfiguration configuration;
    private final ExitOnSkip exitOnSkip;
    private int exitCode = EXIT_PASSED;

    public RunCommand(TestRunner runner, RunConfiguration configuration, Environment environment) {
        this.runner = runner;
        this.configuration = configuration;
        this.exitOnSkip = Binder.get(environment).bind("testrunner.exit-on-skip", ExitOnSkip.class)
                .orElse(ExitOnSkip.ALL);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            exitCode = exitCode(runner.run(configuration), exitOnSkip);
        } catch (ConfigurationException e) {
            log.error("{}", e.getMessage());
            exitCode = EXIT_INVALID_CONFIGURATION;
        }
    }

    /** The exit code of a run: failures first, then skips as {@code exitOnSkip} says. */
    static int exitCode(RunResult result, ExitOnSkip exitOnSkip) {
        if (result.outcome() == Outcome.FAIL || result.outcome() == Outcome.ERROR) {
            return EXIT_FAILED;
        }
        long skipped = result.countByOutcome().get(Outcome.SKIP);
        int total = result.testScripts().size();
        boolean counts = switch (exitOnSkip) {
            case ALL -> skipped == total;
            case ANY -> skipped > 0;
            case NEVER -> false;
        };
        if (!counts) {
            return EXIT_PASSED;
        }
        log.error("{} of {} TestScript(s) skipped{}: exit code {} (testrunner.exit-on-skip={}; never to exit with 0)",
                skipped, total, skipped == total ? ", nothing was tested" : "", EXIT_SKIPPED,
                exitOnSkip.name().toLowerCase(Locale.ROOT));
        return EXIT_SKIPPED;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
