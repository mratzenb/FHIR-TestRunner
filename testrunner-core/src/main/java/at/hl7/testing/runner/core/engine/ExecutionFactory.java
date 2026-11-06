package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.http.CommunicationLog;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.http.HttpTransport;
import at.hl7.testing.runner.core.spi.ProfileValidator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds what one TestScript execution needs from its run: an {@link FhirHttpClient} for the TestScript's server
 * with a communication log of its own, the default headers and OAuth2 token of that server, and the values of
 * {@code ${testrunner.…}}.
 */
final class ExecutionFactory {

    private final HttpTransport transport;
    private final ProfileValidator validator;
    private final Clock clock;

    ExecutionFactory(HttpTransport transport, ProfileValidator validator, Clock clock) {
        this.transport = transport;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * The environment of a TestScript that is sent to {@code server}.
     *
     * @param label    {@code <NN>_<file base name>}, the name of its folder in the communication log
     * @param base     the base URL of {@code server}
     * @param builtIn  the values of {@code ${testrunner.…}}, see {@link #builtInValues}
     * @param warnings what the checks before sending found worth a warning
     * @param deadline when the execution must stop
     */
    ExecutionEnvironment create(RunContext run, String label, SuiteConfig suite, FhirServerConfig server, String base,
                                CapabilityCheck capabilities, Map<String, String> builtIn, List<String> warnings,
                                Deadline deadline) {
        CommunicationLog communication = (run.configuration().reporting().communicationLog()
                ? CommunicationLog.toFolder(run.communicationDirectory(), label, base)
                : CommunicationLog.none()).redacting(run.configuration().reporting().redactHeaders());
        HttpHeaders defaultHeaders = HttpHeaders.of(server.headers().entrySet().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), List.of(e.getValue())), Map::putAll));
        FhirHttpClient http = new FhirHttpClient(transport, run.requestCounter(), communication, defaultHeaders,
                server.oauth2() == null ? null : run.accessTokens(server.oauth2(), transport, clock));
        return new ExecutionEnvironment(http, base, validator, suite.resolvedProfilesDir(), suite.igs(),
                capabilities, builtIn, run.configuration().execution().rewriteReferences(), warnings, deadline);
    }

    /**
     * The values TestScripts and their fixtures can use as {@code ${testrunner.<name>}}, e.g. to give the resources
     * of each TestScript execution identifiers of their own, so that TestScripts running in parallel, or again on the
     * same server, do not find each other's data.
     */
    static Map<String, String> builtInValues(RunContext run, int index, Instant started) {
        String uuid = UUID.randomUUID().toString();
        return Map.of(
                "runId", run.runId(),
                "testScriptId", run.runId().replaceAll("[^A-Za-z0-9.-]", "-") + "-" + "%02d".formatted(index),
                "uuid", uuid,
                "timestamp", DateTimeFormatter.ISO_INSTANT.format(started.truncatedTo(ChronoUnit.MILLIS)),
                "date", DateTimeFormatter.ISO_LOCAL_DATE.format(started.atZone(ZoneOffset.UTC)));
    }
}
