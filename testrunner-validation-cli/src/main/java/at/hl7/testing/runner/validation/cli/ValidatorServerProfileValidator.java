package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates with long-running HL7 validator servers ({@code testrunner.validation.mode=server}).
 *
 * <p>The validator loads the FHIR core packages and IGs once per server instead of once per validation, which turns
 * 7–15 s per validation into a few milliseconds after start-up. A server is bound to the settings it was started
 * with, so there is one per FHIR version, profiles folder and IGs (in practice one per suite and FHIR version),
 * started on first use and stopped by {@link #close()}
 * (a JVM shutdown hook makes sure no validator outlives the runner).
 *
 * <p>The first validation of each key starts its server and runs alone ({@link PackageCacheGate}), so that servers
 * and fallback processes do not install the same packages into an empty package cache at the same time.
 *
 * <p>When a server cannot be started or stops by itself, the validations it should have done fall back to
 * {@link ValidatorCliProfileValidator} (one process each) if {@code testrunner.validation.server.fallback-to-process}
 * is true (default), with a warning; otherwise they are reported as errors. A failed server is not restarted.
 * Problems of a single validation on a running server (e.g. an unknown profile) are reported as they are, without
 * fallback.
 */
public final class ValidatorServerProfileValidator implements ProfileValidator, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ValidatorServerProfileValidator.class);

    private final ValidationConfig config;
    private final ProfileValidator fallback;
    private final PackageCacheGate gate;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<EngineKey, ValidatorServer> servers = new ConcurrentHashMap<>();
    private final Map<EngineKey, Boolean> fallbackAnnounced = new ConcurrentHashMap<>();
    private final Thread shutdownHook = Thread.ofPlatform().unstarted(this::destroyAll);
    private volatile boolean closed;

    public ValidatorServerProfileValidator(@Nonnull ValidationConfig config) {
        this(config, new PackageCacheGate());
    }

    private ValidatorServerProfileValidator(ValidationConfig config, PackageCacheGate gate) {
        this(config, new ValidatorCliProfileValidator(config, gate), gate);
    }

    ValidatorServerProfileValidator(ValidationConfig config, ProfileValidator fallback) {
        this(config, fallback, new PackageCacheGate());
    }

    ValidatorServerProfileValidator(ValidationConfig config, ProfileValidator fallback, PackageCacheGate gate) {
        this.config = config;
        this.fallback = fallback;
        this.gate = gate;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    @Override
    @Nonnull
    public String description() {
        return "HL7 validator in server mode, one server per FHIR version, profiles folder and IGs ("
                + config.validatorJar() + "; fallback to one process per validation: "
                + (config.server().fallbackToProcess() ? "on" : "off") + ")";
    }

    /** Problems that keep the validator from running, for the start of the run; empty if it looks usable. */
    @Nonnull
    public List<String> problems() {
        return ValidatorArguments.problems(config);
    }

    @Override
    @Nonnull
    public List<String> configurationProblems() {
        return ValidatorArguments.configurationProblems(config);
    }

    @Override
    @Nonnull
    public ValidationResult validate(@Nonnull ValidationRequest request) {
        if (closed) {
            throw new ValidatorUnavailableException("The validator has been closed");
        }
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new ValidatorUnavailableException(String.join("; ", problems));
        }
        EngineKey key = EngineKey.of(request);
        ValidatorServer server = servers.computeIfAbsent(key,
                k -> new ValidatorServer(config, k.version(), k.profilesDir(), k.igs(), http));
        try {
            return gate.run(key, () -> server.validate(request));
        } catch (ValidatorServer.ServerFailedException e) {
            if (!config.server().fallbackToProcess()) {
                throw new ValidatorUnavailableException(e.getMessage()
                        + "\n(testrunner.validation.server.fallback-to-process is off; set it to true, or set"
                        + " testrunner.validation.mode=process)", e);
            }
            if (fallbackAnnounced.putIfAbsent(key, Boolean.TRUE) == null) {
                log.warn("{}\nValidating FHIR {} with one validator process per validation instead (slower)."
                        + " Set testrunner.validation.mode=process to skip the server.", e.getMessage(), key.version());
            }
            return fallback.validate(request);
        }
    }

    /** Stops every validator server. */
    @Override
    public void close() {
        closed = true;
        servers.values().forEach(ValidatorServer::stop);
        servers.clear();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // the hook is running or has run
        }
    }

    /** The validator servers started so far, for tests and diagnostics. */
    int serverCount() {
        return (int) servers.values().stream().filter(s -> s.pid() > 0).count();
    }

    private void destroyAll() {
        servers.values().forEach(ValidatorServer::destroy);
    }
}
