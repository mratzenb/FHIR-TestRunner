package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.time.Duration;

/**
 * Settings of the validator server ({@code testrunner.validation.mode=server}).
 *
 * @param startupTimeout    how long a server may take until it answers, default 5 minutes (the first start may
 *                          download FHIR packages)
 * @param fallbackToProcess when a server cannot be started or stops unexpectedly, validate with one process per
 *                          validation instead (logged as warning); default true. When false, such validations are
 *                          reported as errors.
 */
public record ServerModeConfig(@Nonnull Duration startupTimeout, @Nonnull Boolean fallbackToProcess) {

    public ServerModeConfig(@Nullable Duration startupTimeout, @Nullable Boolean fallbackToProcess) {
        startupTimeout = startupTimeout == null ? Duration.ofMinutes(5) : startupTimeout;
        fallbackToProcess = fallbackToProcess == null || fallbackToProcess;
        this.startupTimeout = startupTimeout;
        this.fallbackToProcess = fallbackToProcess;
    }

    @Nonnull
    public static ServerModeConfig defaults() {
        return new ServerModeConfig(null, null);
    }
}
