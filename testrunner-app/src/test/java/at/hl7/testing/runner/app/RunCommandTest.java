package at.hl7.testing.runner.app;

import at.hl7.testing.runner.app.RunCommand.ExitOnSkip;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
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
