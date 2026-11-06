package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.RunConfiguration;
import jakarta.annotation.Nonnull;

import java.nio.file.Path;
import java.time.Instant;

/**
 * What a {@link RunListener} learns about a run: values only. The state of the run – the request counter, the OAuth2
 * tokens – stays in the engine (docs/adr/0004-no-global-state.md).
 *
 * @param runId         id of the run, e.g. {@code run_2026-10-21_14-03-07-123}
 * @param started       when the run started
 * @param configuration the configuration of the run (immutable)
 * @param runDirectory  folder of the run's results and logs
 */
public record RunInfo(@Nonnull String runId, @Nonnull Instant started, @Nonnull RunConfiguration configuration,
                      @Nonnull Path runDirectory) {

    /** Folder of the communication logs of this run. */
    @Nonnull
    public Path communicationDirectory() {
        return runDirectory.resolve("communication");
    }
}
