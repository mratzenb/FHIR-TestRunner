package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.ValidationRequest;

import java.nio.file.Path;
import java.util.List;

/**
 * What a validator loads: the packages and definitions of one FHIR version, profiles folder and list of IGs. A
 * {@link ValidatorServer} is bound to one, and {@link PackageCacheGate} warms the package cache once for each.
 */
record EngineKey(FhirVersion version, Path profilesDir, List<String> igs) {

    static EngineKey of(ValidationRequest request) {
        Path profilesDir = request.profilesDir() == null ? null : request.profilesDir().toAbsolutePath().normalize();
        return new EngineKey(request.fhirVersion(), profilesDir, request.igs());
    }

    @Override
    public String toString() {
        return "FHIR " + version + " (profiles " + (profilesDir == null ? "-" : profilesDir) + ", IGs "
                + (igs.isEmpty() ? "-" : igs) + ")";
    }
}
