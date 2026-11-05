package at.hl7.testing.runner.app;

import at.hl7.testing.runner.app.RunCommand.ExitOnSkip;
import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.config.ReportingConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.SelectConfig;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.engine.RunControl;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.engine.TestRunners;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunCommandTest {

    @Test
    void failureWinsOverSkips() {
        RunResult run = run(Outcome.SKIP, Outcome.FAIL);

        for (ExitOnSkip policy : ExitOnSkip.values()) {
            assertThat(RunCommand.exitCode(run, policy)).isEqualTo(RunCommand.EXIT_FAILED);
        }
    }

    @Test
    void byDefaultOnlyARunThatTestedNothingExitsWithSkipped() {
        assertThat(RunCommand.exitCode(run(Outcome.SKIP, Outcome.SKIP), ExitOnSkip.ALL)).isEqualTo(RunCommand.EXIT_SKIPPED);
        assertThat(RunCommand.exitCode(run(Outcome.SKIP, Outcome.PASS), ExitOnSkip.ALL)).isEqualTo(RunCommand.EXIT_PASSED);
        assertThat(RunCommand.exitCode(run(Outcome.PASS), ExitOnSkip.ALL)).isEqualTo(RunCommand.EXIT_PASSED);
    }

    @Test
    void anyAndNever() {
        assertThat(RunCommand.exitCode(run(Outcome.SKIP, Outcome.PASS), ExitOnSkip.ANY)).isEqualTo(RunCommand.EXIT_SKIPPED);
        assertThat(RunCommand.exitCode(run(Outcome.PASS), ExitOnSkip.ANY)).isEqualTo(RunCommand.EXIT_PASSED);
        assertThat(RunCommand.exitCode(run(Outcome.SKIP, Outcome.SKIP), ExitOnSkip.NEVER)).isEqualTo(RunCommand.EXIT_PASSED);
    }

    @Test
    void stopReturnsAtOnceAfterTheRun() throws Exception {
        RunControl control = new RunControl();
        finishedRun(control);

        long started = System.nanoTime();
        RunCommand.stop(control, Duration.ofSeconds(10));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(control.isCancelled()).as("a finished run is not cancelled").isFalse();
    }

    @Test
    void stopCancelsARunningRunAndWaitsForIt() throws Exception {
        RunControl control = new RunControl();
        Thread run = Thread.ofPlatform().start(() -> {
            while (!control.isCancelled()) {
                Thread.onSpinWait();
            }
            finishedRun(control);
        });

        RunCommand.stop(control, Duration.ofSeconds(10));
        run.join();

        assertThat(control.isCancelled()).isTrue();
        assertThat(control.isFinished()).isTrue();
    }

    @Test
    void listShowsTheTestScriptsWithTheirSelectedTestsAndTheCounts() {
        List<TestRunner.Listed> listed = List.of(
                new TestRunner.Listed(1, "r4", Path.of("r4/Test_Scripts/a.json"), FhirVersion.R4, "A", "http://x/a",
                        List.of("read", "search"), 2, null),
                new TestRunner.Listed(2, "r4", Path.of("r4/Test_Scripts/b.json"), FhirVersion.R4, "B", null,
                        List.of("read"), 3, null),
                new TestRunner.Listed(3, "r4", Path.of("r4/Test_Scripts/broken.json"), null, null, null, List.of(), 0,
                        "The FHIR version of broken.json could not be determined, it is …\n  R4: …"));

        String text = RunCommand.list(listed, new SelectConfig(null, List.of("read", "search")));

        assertThat(text).isEqualTo("""
                 1. r4: %s  (FHIR R4) A
                      read
                      search
                 2. r4: %s  (FHIR R4) B – 1 of 3 tests
                      read
                 3. r4: %s  (cannot be read: The FHIR version of broken.json could not be determined, it is …)
                3 TestScript(s), 3 test(s) would run (selected: tests [read, search])
                """.formatted(Path.of("r4/Test_Scripts/a.json"), Path.of("r4/Test_Scripts/b.json"),
                Path.of("r4/Test_Scripts/broken.json")));
    }

    /** Lets {@code control} go through a run without TestScripts, as {@code TestRunner.run} does. */
    private static void finishedRun(RunControl control) {
        try (InMemoryFhirServer server = InMemoryFhirServer.start("4.0.1")) {
            Path dir = java.nio.file.Files.createTempDirectory("run-command-test");
            java.nio.file.Files.createDirectories(dir.resolve("suite/Test_Scripts"));
            var configuration = new RunConfiguration(FhirServerConfig.of(server.baseUrl()),
                    List.of(SuiteConfig.of(dir.resolve("suite"), "R4")), null, null, ValidationConfig.disabled(),
                    new ReportingConfig(dir.resolve("results"), null, false, false, null, null), null, null);
            TestRunners.create(HttpConfig.defaults(), ProfileValidator.DISABLED).run(configuration, control);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static RunResult run(Outcome... outcomes) {
        List<TestScriptResult> results = new ArrayList<>();
        for (int i = 0; i < outcomes.length; i++) {
            PhaseResult phase = new PhaseResult(PhaseKind.TEST, "t", outcomes[i], Duration.ZERO, 0, List.of(), List.of());
            results.add(new TestScriptResult(i + 1, "suite", "ts" + i, null, Path.of("ts" + i + ".json"), null, null,
                    Instant.EPOCH, Duration.ZERO, List.of(phase)));
        }
        return new RunResult("run", Path.of("run"), Instant.EPOCH, Duration.ZERO, results, Map.of());
    }
}
