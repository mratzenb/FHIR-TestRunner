package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;

import java.util.Locale;

/**
 * The FHIR releases the runner knows about.
 *
 * <p>Knowing a version does not mean supporting it: support comes from a
 * {@link at.hl7.testing.runner.core.spi.FhirVersionSupport} module on the classpath. {@link #R6} is
 * declared ahead of its publication so that an R6 module can be added without touching the core.
 */
public enum FhirVersion {

    R4("4.0.1"),
    R5("5.0.0"),
    /** Not yet published; the release number is provisional. */
    R6("6.0.0");

    private final String release;

    FhirVersion(String release) {
        this.release = release;
    }

    /** Full release number, e.g. {@code 4.0.1}. */
    @Nonnull
    public String release() {
        return release;
    }

    /** Major and minor release number, e.g. {@code 4.0}, as expected by the HL7 validator's {@code -version}. */
    @Nonnull
    public String majorMinor() {
        return release.substring(0, release.lastIndexOf('.'));
    }

    /**
     * Reads a version as it is written in configurations: {@code R4}, {@code r4}, {@code 4.0} or {@code 4.0.1}.
     *
     * @throws IllegalArgumentException if the text names no known version
     */
    @Nonnull
    public static FhirVersion parse(@Nonnull String text) {
        String value = text.trim().toUpperCase(Locale.ROOT);
        for (FhirVersion version : values()) {
            if (version.name().equals(value) || version.release.equals(value) || version.majorMinor().equals(value)) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unknown FHIR version '" + text + "', expected one of R4, R5, R6");
    }
}
