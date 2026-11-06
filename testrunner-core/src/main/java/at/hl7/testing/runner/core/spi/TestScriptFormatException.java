package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.FhirVersion;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Optional;

/** Thrown when a TestScript cannot be read: no TestScript, invalid JSON/XML, or invalid for its FHIR version. */
public class TestScriptFormatException extends RuntimeException {

    private final FhirVersion fhirVersion;

    public TestScriptFormatException(@Nonnull String message) {
        this(message, null, null);
    }

    public TestScriptFormatException(@Nonnull String message, @Nullable Throwable cause) {
        this(message, cause, null);
    }

    /** @param fhirVersion the version of the TestScript if it was determined before the problem occurred */
    public TestScriptFormatException(@Nonnull String message, @Nullable Throwable cause,
                                     @Nullable FhirVersion fhirVersion) {
        super(message, cause);
        this.fhirVersion = fhirVersion;
    }

    /** The FHIR version of the TestScript; empty if it could not be determined. */
    @Nonnull
    public Optional<FhirVersion> fhirVersion() {
        return Optional.ofNullable(fhirVersion);
    }
}
