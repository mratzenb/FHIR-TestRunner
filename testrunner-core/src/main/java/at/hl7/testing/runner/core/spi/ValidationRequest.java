package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;

import java.nio.file.Path;

/**
 * What to validate.
 *
 * @param resource    the resource
 * @param fhirVersion the FHIR version of the resource
 * @param profile     canonical URL of the profile, {@code null} to validate against the base specification only
 * @param profilesDir folder with the StructureDefinitions the profile depends on, may be {@code null}
 */
public record ValidationRequest(Body resource, FhirVersion fhirVersion, String profile, Path profilesDir) {
}
