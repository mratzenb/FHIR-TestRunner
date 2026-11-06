package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.model.FhirVersion;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The result of one TestScript.
 *
 * @param index       position of the TestScript in the run, starting at 1
 * @param suite       name of the suite
 * @param name        name of the TestScript
 * @param url         {@code TestScript.url}, or the file if the TestScript could not be read
 * @param source      the file the TestScript was read from
 * @param fhirVersion the FHIR version, {@code null} if it could not be determined
 * @param fhirServer  base URL of the server the TestScript ran against, {@code null} if it was not run
 * @param started     when the execution started
 * @param duration    how long it took
 * @param phases      the phase results, in order
 */
public record TestScriptResult(int index, @Nonnull String suite, @Nonnull String name, @Nonnull String url,
                               @Nonnull Path source, @Nullable FhirVersion fhirVersion, @Nullable String fhirServer,
                               @Nonnull Instant started, @Nonnull Duration duration,
                               @Nonnull List<PhaseResult> phases) {

    public TestScriptResult {
        phases = List.copyOf(phases);
    }

    /** The combined outcome of all phases. */
    @Nonnull
    public Outcome outcome() {
        return phases.stream().map(PhaseResult::outcome).reduce(Outcome.PASS, Outcome::and);
    }
}
