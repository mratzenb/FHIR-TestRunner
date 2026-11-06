package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.config.ReportingConfig;
import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes {@code summary.txt}: counts per outcome and the TestScripts that did not pass, with the reason. */
public final class SummaryReportWriter implements ReportWriter {

    @Override
    @Nonnull
    public String format() {
        return ReportingConfig.SUMMARY;
    }

    @Override
    @Nonnull
    public Path write(@Nonnull RunResult result, @Nonnull Path runDirectory) throws IOException {
        Path file = runDirectory.resolve("summary.txt");
        Files.writeString(file, format(result), StandardCharsets.UTF_8);
        return file;
    }

    @Nonnull
    public static String format(@Nonnull RunResult result) {
        Map<Outcome, Long> counts = result.countByOutcome();
        StringBuilder text = new StringBuilder()
                .append("Run:               ").append(result.runId()).append('\n')
                .append("Result:            ").append(result.outcome()).append('\n')
                .append("TestScripts:       ").append(result.testScripts().size()).append('\n')
                .append("  passed:          ").append(counts.get(Outcome.PASS)).append('\n')
                .append("  failed:          ").append(counts.get(Outcome.FAIL)).append('\n')
                .append("  errors:          ").append(counts.get(Outcome.ERROR)).append('\n')
                .append("  skipped:         ").append(counts.get(Outcome.SKIP)).append('\n')
                .append("-".repeat(60)).append('\n');
        for (TestScriptResult testScript : result.testScripts()) {
            text.append("%-6s %s (%s)%n".formatted(testScript.outcome(), testScript.name(), testScript.source().getFileName()));
            for (PhaseResult phase : testScript.phases()) {
                if (phase.outcome() == Outcome.FAIL || phase.outcome() == Outcome.ERROR) {
                    text.append("       ").append(phase.kind().label()).append(" '").append(phase.name()).append("': ")
                            .append(phase.outcome()).append('\n');
                    phase.messages().forEach(m -> text.append("         ").append(m.replace("\n", "\n         ")).append('\n'));
                }
            }
        }
        return text.toString();
    }
}
