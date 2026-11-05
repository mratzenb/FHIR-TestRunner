package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.OAuth2Config;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.http.AccessTokens;
import at.hl7.testing.runner.core.http.HttpTransport;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The state shared by all TestScripts of one run. Created by {@link TestRunner#run} for every run and passed down
 * explicitly; nothing about a run lives in static fields. Internal to the engine: listeners get a {@link RunInfo},
 * which holds values only.
 *
 * @param runId          id of the run, e.g. {@code run_2026-10-21_14-03-07-123}
 * @param started        when the run started
 * @param configuration  the configuration of the run
 * @param runDirectory   folder of the run's results and logs
 * @param requestCounter counter of the requests sent, shared by all TestScripts so request ids are unique per run
 * @param accessTokens   the OAuth2 access tokens of the run, one source per token endpoint and client, shared by all
 *                       TestScripts so a token is fetched once
 */
record RunContext(String runId, Instant started, RunConfiguration configuration, Path runDirectory,
                         AtomicInteger requestCounter, ConcurrentMap<OAuth2Config, AccessTokens> accessTokens) {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
            .withZone(ZoneId.systemDefault());

    static RunContext start(RunConfiguration configuration, Instant now) {
        String runId = "run_" + STAMP.format(now);
        Path runDirectory = configuration.reporting().outputDir().resolve(runId);
        return new RunContext(runId, now, configuration, runDirectory, new AtomicInteger(), new ConcurrentHashMap<>());
    }

    /** The access tokens of an OAuth2 client, created on first use. */
    AccessTokens accessTokens(OAuth2Config oauth2, HttpTransport transport, Clock clock) {
        return accessTokens.computeIfAbsent(oauth2, config -> new AccessTokens(config, transport, clock));
    }

    /** Folder of the communication logs of this run. */
    Path communicationDirectory() {
        return info().communicationDirectory();
    }

    /** What listeners learn about the run. */
    RunInfo info() {
        return new RunInfo(runId, started, configuration, runDirectory);
    }
}
