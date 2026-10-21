package at.hl7.testing.runner.core.report;


import java.io.IOException;
import java.nio.file.Path;

/** Writes the result of a run in one format. Selected by {@link #format()} through {@code testrunner.reporting.formats}. */
public interface ReportWriter {

    /** The name of the format, e.g. {@code junit}. */
    String format();

    /**
     * Writes the report.
     *
     * @param runDirectory the folder of the run
     * @return the file written
     */
    Path write(RunResult result, Path runDirectory) throws IOException;
}
