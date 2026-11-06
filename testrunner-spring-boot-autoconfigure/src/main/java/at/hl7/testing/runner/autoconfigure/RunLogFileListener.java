package at.hl7.testing.runner.autoconfigure;

import at.hl7.testing.runner.core.engine.RunInfo;
import at.hl7.testing.runner.core.engine.RunListener;
import at.hl7.testing.runner.core.report.RunResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import jakarta.annotation.Nonnull;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the log of each run to {@code <run folder>/testrunner.log}, next to its reports and communication logs.
 *
 * <p>The file receives the {@code at.hl7.testing.runner} loggers down to {@code testrunner.reporting.log-file-level}
 * (default DEBUG), independent of the console level. Each line names the TestScript it belongs to, so the file stays
 * readable when TestScripts run in parallel.
 */
public class RunLogFileListener implements RunListener {

    /** Pattern of the run log file; {@code %X{testScript}} is the TestScript running, e.g. {@code 01_TestScript-x}. */
    public static final String PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{testScript:--}] %logger{0} - %msg%n%ex";

    private static final String RUNNER_LOGGER = "at.hl7.testing.runner";

    private final Map<String, Attached> appenders = new ConcurrentHashMap<>();

    @Override
    public void runStarted(@Nonnull RunInfo run) {
        if (!run.configuration().reporting().logFile()
                || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        Level level = Level.toLevel(run.configuration().reporting().logFileLevel(), Level.DEBUG);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.start();

        ThresholdFilter threshold = new ThresholdFilter();
        threshold.setLevel(level.levelStr);
        threshold.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setName("testrunner-" + run.runId());
        appender.setFile(run.runDirectory().resolve("testrunner.log").toString());
        appender.setEncoder(encoder);
        appender.addFilter(threshold);
        appender.start();

        Logger logger = context.getLogger(RUNNER_LOGGER);
        Level previous = logger.getLevel();
        if (!logger.isEnabledFor(level)) {
            // let the records reach the file; the console keeps its own threshold through its appender filter
            logger.setLevel(level);
        }
        logger.addAppender(appender);
        appenders.put(run.runId(), new Attached(appender, logger, previous));
    }

    @Override
    public void runFinished(@Nonnull RunInfo run, @Nonnull RunResult result) {
        Attached attached = appenders.remove(run.runId());
        if (attached != null) {
            attached.logger().detachAppender(attached.appender());
            attached.logger().setLevel(attached.previousLevel());
            attached.appender().stop();
        }
    }

    private record Attached(FileAppender<ILoggingEvent> appender, Logger logger, Level previousLevel) {
    }
}
