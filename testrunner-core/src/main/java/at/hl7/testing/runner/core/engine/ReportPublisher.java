package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Writes the reports of a run in the configured formats and logs its summary. A writer that fails is logged; the
 * other reports are still written.
 */
final class ReportPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReportPublisher.class);

    private final List<ReportWriter> writers;

    ReportPublisher(List<ReportWriter> writers) {
        this.writers = List.copyOf(writers);
    }

    /** The formats there is a writer for. */
    Set<String> formats() {
        return writers.stream().map(ReportWriter::format).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Writes the reports into the run folder and logs the summary.
     *
     * @return the result with the report files written
     */
    RunResult publish(RunContext run, RunResult result) {
        RunResult published = result.withReportFiles(write(run, result));
        logSummary(published);
        return published;
    }

    private Map<String, Path> write(RunContext run, RunResult result) {
        Map<String, Path> files = new LinkedHashMap<>();
        for (String format : run.configuration().reporting().formats()) {
            ReportWriter writer = writers.stream().filter(w -> w.format().equals(format)).findFirst().orElseThrow();
            try {
                files.put(format, writer.write(result, run.runDirectory()));
            } catch (IOException | RuntimeException e) {
                log.error("Could not write the {} report: {}", format, e.toString());
            }
        }
        return files;
    }

    private static void logSummary(RunResult result) {
        Map<Outcome, Long> counts = result.countByOutcome();
        log.info("===== Summary {}: {} TestScript(s), {} passed, {} failed, {} errors, {} skipped -> {}", result.runId(),
                result.testScripts().size(), counts.get(Outcome.PASS), counts.get(Outcome.FAIL),
                counts.get(Outcome.ERROR), counts.get(Outcome.SKIP), result.outcome());
        result.reportFiles().forEach((format, file) -> log.info("  {} report: {}", format, file.toAbsolutePath()));
    }
}
