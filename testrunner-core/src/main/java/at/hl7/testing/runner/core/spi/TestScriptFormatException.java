package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.FhirVersion;

/** Thrown when a TestScript cannot be read: no TestScript, invalid JSON/XML, or invalid for its FHIR version. */
public class TestScriptFormatException extends RuntimeException {

    private final FhirVersion fhirVersion;

    public TestScriptFormatException(String message) {
        this(message, null, null);
    }

    public TestScriptFormatException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /** @param fhirVersion the version of the TestScript if it was determined before the problem occurred */
    public TestScriptFormatException(String message, Throwable cause, FhirVersion fhirVersion) {
        super(message, cause);
        this.fhirVersion = fhirVersion;
    }

    /** The FHIR version of the TestScript, {@code null} if it could not be determined. */
    public FhirVersion fhirVersion() {
        return fhirVersion;
    }
}
