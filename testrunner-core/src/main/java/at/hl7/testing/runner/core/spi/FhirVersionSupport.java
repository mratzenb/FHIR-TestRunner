package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import jakarta.annotation.Nonnull;

/**
 * Support for one FHIR version: reading TestScripts of that version into the version-neutral model and
 * evaluating FHIRPath on resources of that version.
 *
 * <p>Implementations are found with {@link java.util.ServiceLoader}; a module registers its implementation in
 * {@code META-INF/services/at.hl7.testing.runner.core.spi.FhirVersionSupport}. Supporting a new FHIR version (R6)
 * means adding such a module, the core stays untouched. Implementations must be thread-safe.
 */
public interface FhirVersionSupport {

    /** The FHIR version supported. */
    @Nonnull
    FhirVersion version();

    /** Checks whether {@code content} is a TestScript of this version. Used to detect the version of a TestScript. */
    @Nonnull
    ReadCheck check(@Nonnull Body content);

    /**
     * Reads a TestScript of this version.
     *
     * @throws TestScriptFormatException if the content is no TestScript or violates the rules of this version
     */
    @Nonnull
    TestScriptRead readTestScript(@Nonnull Body content);

    /** FHIRPath evaluation for resources of this version. */
    @Nonnull
    FhirPathEvaluator fhirPath();

    /**
     * Writes a {@code TestReport} resource of this version.
     *
     * @return the {@code TestReport} as JSON
     * @throws UnsupportedOperationException if the module cannot write TestReports
     */
    @Nonnull
    default Body testReport(@Nonnull TestReportContent content) {
        throw new UnsupportedOperationException("The FHIR " + version() + " module cannot write TestReports");
    }
}
