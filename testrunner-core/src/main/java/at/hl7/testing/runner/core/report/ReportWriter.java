package at.hl7.testing.runner.core.report;

import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.nio.file.Path;

/** Writes the result of a run in one format. Selected by {@link #format()} through {@code testrunner.reporting.formats}. */
public interface ReportWriter {

    /** The name of the format, e.g. {@code junit}. */
    @Nonnull
    String format();

    /**
     * Writes the report.
     *
     * @param runDirectory the folder of the run
     * @return the file written
     */
    @Nonnull
    Path write(@Nonnull RunResult result, @Nonnull Path runDirectory) throws IOException;
}
