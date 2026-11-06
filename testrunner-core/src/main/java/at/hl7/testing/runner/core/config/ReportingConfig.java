package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Where and how the results of a run are written. Every run gets a folder {@code run_<timestamp>} below
 * {@code outputDir}.
 *
 * @param outputDir        default {@code testrunner-results}
 * @param formats          report formats, default {@code junit} and {@code summary}; also {@code json},
 *                         {@code testreport} (a FHIR TestReport per TestScript) and {@code html} (one page per run)
 * @param communicationLog whether every request and response is written to {@code communication/}, default true
 * @param logFile          whether the run writes its own log file {@code testrunner.log}, default true
 * @param logFileLevel     lowest level written to the log file, default {@code DEBUG}
 * @param redactHeaders    headers whose values the communication log masks (case-insensitive), default
 *                         {@link #DEFAULT_REDACT_HEADERS}; an empty list masks nothing
 */
public record ReportingConfig(@Nonnull Path outputDir, @Nonnull List<String> formats, @Nonnull Boolean communicationLog,
                              @Nonnull Boolean logFile, @Nonnull String logFileLevel,
                              @Nonnull List<String> redactHeaders) {

    public static final String JUNIT = "junit";
    public static final String SUMMARY = "summary";
    public static final String JSON = "json";
    public static final String TESTREPORT = "testreport";
    public static final String HTML = "html";
    /** Headers that carry credentials or sessions. */
    public static final List<String> DEFAULT_REDACT_HEADERS =
            List.of("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie");

    public ReportingConfig(@Nullable Path outputDir, @Nullable List<String> formats, @Nullable Boolean communicationLog,
                           @Nullable Boolean logFile, @Nullable String logFileLevel,
                           @Nullable List<String> redactHeaders) {
        outputDir = outputDir == null ? Path.of("testrunner-results") : outputDir;
        formats = formats == null || formats.isEmpty()
                ? List.of(JUNIT, SUMMARY)
                : formats.stream().map(f -> f.trim().toLowerCase(Locale.ROOT)).toList();
        communicationLog = communicationLog == null || communicationLog;
        logFile = logFile == null || logFile;
        logFileLevel = logFileLevel == null || logFileLevel.isBlank() ? "DEBUG" : logFileLevel.trim().toUpperCase(Locale.ROOT);
        redactHeaders = redactHeaders == null ? DEFAULT_REDACT_HEADERS
                : redactHeaders.stream().map(String::trim).filter(h -> !h.isEmpty()).toList();
        this.outputDir = outputDir;
        this.formats = formats;
        this.communicationLog = communicationLog;
        this.logFile = logFile;
        this.logFileLevel = logFileLevel;
        this.redactHeaders = redactHeaders;
    }

    @Nonnull
    public static ReportingConfig defaults() {
        return new ReportingConfig(null, null, null, null, null, null);
    }
}
