package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.RunConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The state shared by all TestScripts of one run. Created by {@link TestRunner#run} for every run and passed down
 * explicitly; nothing about a run lives in static fields.
 *
 * @param runId          id of the run, e.g. {@code run_2026-10-21_14-03-07-123}
 * @param started        when the run started
 * @param configuration  the configuration of the run
 * @param runDirectory   folder of the run's results and logs
 * @param requestCounter counter of the requests sent, shared by all TestScripts so request ids are unique per run
 */
public record RunContext(String runId, Instant started, RunConfiguration configuration, Path runDirectory,
                         AtomicInteger requestCounter) {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
            .withZone(ZoneId.systemDefault());

    static RunContext start(RunConfiguration configuration, Instant now) {
        String runId = "run_" + STAMP.format(now);
        Path runDirectory = configuration.reporting().outputDir().resolve(runId);
        return new RunContext(runId, now, configuration, runDirectory, new AtomicInteger());
    }

    /** Folder of the communication logs of this run. */
    public Path communicationDirectory() {
        return runDirectory.resolve("communication");
    }
}
