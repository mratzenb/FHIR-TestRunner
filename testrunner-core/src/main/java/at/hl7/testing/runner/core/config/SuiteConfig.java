package at.hl7.testing.runner.core.config;

import at.hl7.testing.runner.core.model.FhirVersion;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A set of TestScripts together with the Example Instances and profiles they use, typically the content of one
 * Implementation Guide. The folders are resolved against {@code baseDir}.
 *
 * @param name                name for logs and reports; defaults to the folder name of {@code baseDir}
 * @param fhirVersion         {@code AUTO} (default) to detect the version of each TestScript, or a version such
 *                            as {@code R4}, {@code R5}, {@code 4.0}
 * @param baseDir             folder holding the sub folders below
 * @param testScriptsDir      folder of the TestScripts, default {@code Test_Scripts}
 * @param exampleInstancesDir folder of the resources backing the fixtures, default {@code Example_Instances}
 * @param profilesDir         folder of the StructureDefinitions used for profile validation, default {@code Profiles}
 * @param igs                 further {@code -ig} arguments for the profile validation of this suite: package ids
 *                            ({@code hl7.at.fhir.core.r4#2.0.0}), folders or files; package ids are bound to a FHIR
 *                            version, so pin {@code fhirVersion} when setting them
 * @param include             glob patterns (relative to {@code testScriptsDir}) of the TestScripts to run, default all
 *                            {@code .json} and {@code .xml} files
 * @param fhirServer          FHIR server of this suite; {@code null} to use the server of the run
 */
public record SuiteConfig(String name, String fhirVersion, Path baseDir, Path testScriptsDir,
                          Path exampleInstancesDir, Path profilesDir, List<String> igs, List<String> include,
                          FhirServerConfig fhirServer) {

    public static final String AUTO = "AUTO";

    public SuiteConfig {
        fhirVersion = fhirVersion == null || fhirVersion.isBlank() ? AUTO : fhirVersion.trim();
        testScriptsDir = testScriptsDir == null ? Path.of("Test_Scripts") : testScriptsDir;
        exampleInstancesDir = exampleInstancesDir == null ? Path.of("Example_Instances") : exampleInstancesDir;
        profilesDir = profilesDir == null ? Path.of("Profiles") : profilesDir;
        igs = igs == null ? List.of() : List.copyOf(igs);
        include =include == null || include.isEmpty() ? List.of("*.json", "*.xml") : List.copyOf(include);
        if ((name == null || name.isBlank()) && baseDir != null && baseDir.getFileName() != null) {
            name = baseDir.getFileName().toString();
        }
    }

    /** A suite with the default folder layout below {@code baseDir}. */
    public static SuiteConfig of(Path baseDir, String fhirVersion) {
        return new SuiteConfig(null, fhirVersion, baseDir, null, null, null, null, null, null);
    }

    /** The configured version, or empty for {@code AUTO}. */
    public Optional<FhirVersion> pinnedFhirVersion() {
        if (AUTO.equals(fhirVersion.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(FhirVersion.parse(fhirVersion));
    }

    public Path resolvedTestScriptsDir() {
        return baseDir.resolve(testScriptsDir);
    }

    public Path resolvedExampleInstancesDir() {
        return baseDir.resolve(exampleInstancesDir);
    }

    public Path resolvedProfilesDir() {
        return baseDir.resolve(profilesDir);
    }
}
