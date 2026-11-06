package at.hl7.testing.runner.core.report;

import jakarta.annotation.Nonnull;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The result of a run.
 *
 * @param runId        id of the run, also the name of its folder, e.g. {@code run_2026-10-21_14-03-07-123}
 * @param runDirectory folder of the run's results and logs
 * @param started      when the run started
 * @param duration     how long it took
 * @param testScripts  the TestScript results, ordered by their position in the run
 * @param reportFiles  the report files written, by format
 */
public record RunResult(@Nonnull String runId, @Nonnull Path runDirectory, @Nonnull Instant started,
                        @Nonnull Duration duration, @Nonnull List<TestScriptResult> testScripts,
                        @Nonnull Map<String, Path> reportFiles) {

    public RunResult {
        testScripts = testScripts.stream().sorted(Comparator.comparingInt(TestScriptResult::index)).toList();
        reportFiles = Map.copyOf(reportFiles);
    }

    @Nonnull
    public RunResult withReportFiles(@Nonnull Map<String, Path> files) {
        return new RunResult(runId, runDirectory, started, duration, testScripts, files);
    }

    /** The combined outcome of all TestScripts; {@link Outcome#ERROR} for a run without TestScripts. */
    @Nonnull
    public Outcome outcome() {
        if (testScripts.isEmpty()) {
            return Outcome.ERROR;
        }
        return testScripts.stream().map(TestScriptResult::outcome).reduce(Outcome.PASS, Outcome::and);
    }

    /** Number of TestScripts per outcome. */
    @Nonnull
    public Map<Outcome, Long> countByOutcome() {
        Map<Outcome, Long> counts = testScripts.stream()
                .collect(Collectors.groupingBy(TestScriptResult::outcome, Collectors.counting()));
        return Arrays.stream(Outcome.values())
                .collect(Collectors.toMap(Function.identity(), o -> counts.getOrDefault(o, 0L)));
    }
}
