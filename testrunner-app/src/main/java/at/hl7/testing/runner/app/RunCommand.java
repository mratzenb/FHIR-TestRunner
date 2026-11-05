package at.hl7.testing.runner.app;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.SelectConfig;
import at.hl7.testing.runner.core.engine.ConfigurationException;
import at.hl7.testing.runner.core.engine.RunControl;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Runs the configured TestScripts once at start-up and turns the outcome into the exit code. Switched off with
 * {@code testrunner.run-on-startup=false}, e.g. when the runner is embedded or in tests.
 *
 * <p>{@code testrunner.mode} says what to do with the TestScripts: {@code run} them (default), only {@code check}
 * them – load them and apply every check done before sending, without a FHIR server ({@link TestRunner#check}) – or
 * {@code list} them: print what a run would run, one TestScript per line with its selected tests, on standard output
 * ({@link TestRunner#list}). In check mode, exit code {@link #EXIT_FAILED} means that a TestScript has problems; a list
 * exits with {@link #EXIT_PASSED}, or {@link #EXIT_FAILED} if a TestScript cannot be read.
 *
 * <p>A skipped TestScript tested nothing. {@code testrunner.exit-on-skip} decides when skips give
 * {@link #EXIT_SKIPPED} instead of {@link #EXIT_PASSED}, so that a CI run that tested nothing does not look green.
 *
 * <p>On Ctrl-C or a kill (SIGTERM), Spring Boot's shutdown hook closes the application context. Before any bean is
 * destroyed – the HTTP transport stays open – this command stops the run ({@link RunControl#cancel}) and waits up to
 * {@code testrunner.shutdown-grace} (default 30 s) for the running TestScripts to tear down and the reports to be
 * written; then it writes the reports with what is known ({@link RunControl#reportNow}). So a CI job killed on its own
 * timeout still leaves {@code junit.xml}. See docs/adr/0013-run-control.md.
 */
@Component
@ConditionalOnProperty(name = "testrunner.run-on-startup", havingValue = "true", matchIfMissing = true)
public class RunCommand implements ApplicationRunner, ExitCodeGenerator, ApplicationListener<ContextClosedEvent> {

    static final int EXIT_PASSED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_INVALID_CONFIGURATION = 2;
    static final int EXIT_SKIPPED = 3;

    /** How long a shutdown waits for the run by default. */
    static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(30);

    /** What the command does with the TestScripts ({@code testrunner.mode}). */
    public enum Mode {
        /** Run them against the FHIR server (default). */
        RUN,
        /** Load and check them; nothing is sent, no FHIR server is needed ({@code --check}). */
        CHECK,
        /** Print what a run would run; nothing is sent or written ({@code --list}). */
        LIST
    }

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
    private final Mode mode;
    private final ExitOnSkip exitOnSkip;
    private final Duration shutdownGrace;
    private final PrintStream out;
    private volatile RunControl control;
    private int exitCode = EXIT_PASSED;

    @Autowired
    public RunCommand(TestRunner runner, RunConfiguration configuration, Environment environment) {
        this(runner, configuration, environment, System.out);
    }

    /** @param out where {@code --list} prints */
    RunCommand(TestRunner runner, RunConfiguration configuration, Environment environment, PrintStream out) {
        this.out = out;
        this.runner = runner;
        this.configuration = configuration;
        Binder binder = Binder.get(environment);
        this.mode = binder.bind("testrunner.mode", Mode.class).orElse(Mode.RUN);
        this.exitOnSkip = binder.bind("testrunner.exit-on-skip", ExitOnSkip.class).orElse(ExitOnSkip.ALL);
        this.shutdownGrace = binder.bind("testrunner.shutdown-grace", Duration.class).orElse(DEFAULT_SHUTDOWN_GRACE);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (mode == Mode.LIST) {
                List<TestRunner.Listed> listed = runner.list(configuration);
                out.print(list(listed, configuration.select()));
                out.flush();
                exitCode = listed.stream().anyMatch(l -> l.problem() != null) ? EXIT_FAILED : EXIT_PASSED;
                return;
            }
            RunControl started = new RunControl();
            control = started;
            RunResult result = switch (mode) {
                case RUN -> runner.run(configuration, started);
                case CHECK -> runner.check(configuration, started);
                case LIST -> throw new IllegalStateException("handled above");
            };
            exitCode = exitCode(result, exitOnSkip);
        } catch (ConfigurationException e) {
            log.error("{}", e.getMessage());
            exitCode = EXIT_INVALID_CONFIGURATION;
        }
    }

    /**
     * The text of {@code --list}: one line per TestScript – position, suite, file, FHIR version, name – then its tests
     * that would run, indented, and a last line with the counts.
     */
    static String list(List<TestRunner.Listed> listed, SelectConfig select) {
        StringBuilder text = new StringBuilder();
        int tests = 0;
        for (TestRunner.Listed testScript : listed) {
            text.append("%2d. %s: %s".formatted(testScript.index(), testScript.suite(), testScript.file()));
            if (testScript.problem() != null) {
                text.append("  (cannot be read: ").append(testScript.problem().lines().findFirst().orElse(""))
                        .append(")\n");
                continue;
            }
            text.append("  (FHIR ").append(testScript.fhirVersion()).append(") ").append(testScript.name());
            if (testScript.tests().size() < testScript.testCount()) {
                text.append(" – ").append(testScript.tests().size()).append(" of ").append(testScript.testCount())
                        .append(" tests");
            }
            text.append('\n');
            testScript.tests().forEach(t -> text.append("      ").append(t).append('\n'));
            tests += testScript.tests().size();
        }
        text.append(listed.size()).append(" TestScript(s), ").append(tests).append(" test(s) would run");
        if (!select.selectsAll()) {
            text.append(" (selected: ").append(select.describe()).append(')');
        }
        return text.append('\n').toString();
    }

    /**
     * The application context is closing: after a normal run there is nothing to do; on Ctrl-C or a kill the run is
     * still going and is stopped. Runs before any bean is destroyed, so the run can still send its teardown.
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        RunControl running = control;
        if (running != null) {
            stop(running, shutdownGrace);
        }
    }

    /** Stops a run: cancels it, waits up to {@code grace} for it to end, else writes the reports with what is known. */
    static void stop(RunControl control, Duration grace) {
        if (control.isFinished()) {
            return;
        }
        log.warn("Stopping the run (Ctrl-C or kill); waiting up to {} for the running TestScripts to tear down",
                RunControl.describe(grace));
        control.cancel("the runner was stopped (Ctrl-C or kill)");
        try {
            if (control.awaitFinished(grace)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.error("The run did not end within {}; writing the reports with the results so far",
                RunControl.describe(grace));
        control.reportNow();
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
