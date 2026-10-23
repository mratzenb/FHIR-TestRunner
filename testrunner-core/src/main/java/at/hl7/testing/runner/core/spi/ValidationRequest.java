package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;

import java.nio.file.Path;
import java.util.List;

/**
 * What to validate.
 *
 * @param resource    the resource
 * @param fhirVersion the FHIR version of the resource
 * @param profile     canonical URL of the profile, {@code null} to validate against the base specification only
 * @param profilesDir folder with the StructureDefinitions the profile depends on, may be {@code null}
 * @param igs         further IGs the profile depends on: package ids, folders or files (the suite's {@code igs});
 *                    {@code null} for none
 */
public record ValidationRequest(Body resource, FhirVersion fhirVersion, String profile, Path profilesDir,
                                List<String> igs) {

    public ValidationRequest {
        igs = igs == null ? List.of() : List.copyOf(igs);
    }
}
