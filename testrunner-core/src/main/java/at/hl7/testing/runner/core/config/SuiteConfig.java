package at.hl7.testing.runner.core.config;

import at.hl7.testing.runner.core.model.FhirVersion;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A set of TestScripts together with the Example Instances and profiles they use, typically the content of one
 * Implementation Guide. The folders are resolved against {@code baseDir}.
 *
 * <p>A suite comes from a folder ({@code baseDir}) or from a FHIR package ({@code packageId}). A package is resolved
 * before the run ({@code source.PackageSuites}) into a suite with a folder: the package's {@code package/} folder for
 * the TestScripts, its example folder for the Example Instances, its FHIR version and the package itself as IG.
 *
 * @param name                name for logs and reports; defaults to the folder name of {@code baseDir}, or the
 *                            package id
 * @param fhirVersion         {@code AUTO} (default) to detect the version of each TestScript, or a version such
 *                            as {@code R4}, {@code R5}, {@code 4.0}
 * @param baseDir             folder holding the sub folders below; not with {@code packageId}
 * @param testScriptsDir      folder of the TestScripts, default {@code Test_Scripts}
 * @param exampleInstancesDir folder of the resources backing the fixtures, default {@code Example_Instances}
 * @param profilesDir         folder of the StructureDefinitions used for profile validation, default {@code Profiles}
 * @param igs                 further {@code -ig} arguments for the profile validation of this suite: package ids
 *                            ({@code hl7.at.fhir.core.r4#2.0.0}), folders or files; package ids are bound to a FHIR
 *                            version, so pin {@code fhirVersion} when setting them
 * @param include             glob patterns (relative to {@code testScriptsDir}) of the TestScripts to run, default all
 *                            {@code .json} and {@code .xml} files
 * @param fhirServer          FHIR server of this suite; {@code null} to use the server of the run
 * @param parallel            whether the TestScripts of this suite may run in parallel with others (default true,
 *                            effective with {@code execution.parallelism > 1}); false runs them one after the other,
 *                            while nothing else runs, for TestScripts that share data on the server
 * @param packageId           a FHIR package the suite is read from instead of {@code baseDir}, with its version, e.g.
 *                            {@code hl7.at.fhir.core.r4#2.1.0} or {@code hl7.at.fhir.core.r4#dev}; see
 *                            {@link PackagesConfig}
 */
public record SuiteConfig(@Nullable String name, @Nonnull String fhirVersion, @Nullable Path baseDir,
                          @Nonnull Path testScriptsDir, @Nonnull Path exampleInstancesDir, @Nonnull Path profilesDir,
                          @Nonnull List<String> igs, @Nonnull List<String> include,
                          @Nullable FhirServerConfig fhirServer, @Nonnull Boolean parallel,
                          @Nullable String packageId) {

    public static final String AUTO = "AUTO";
    private static final List<String> DEFAULT_INCLUDE = List.of("*.json", "*.xml");

    public SuiteConfig(@Nullable String name, @Nullable String fhirVersion, @Nullable Path baseDir,
                       @Nullable Path testScriptsDir, @Nullable Path exampleInstancesDir, @Nullable Path profilesDir,
                       @Nullable List<String> igs, @Nullable List<String> include,
                       @Nullable FhirServerConfig fhirServer, @Nullable Boolean parallel, @Nullable String packageId) {
        fhirVersion = fhirVersion == null || fhirVersion.isBlank() ? AUTO : fhirVersion.trim();
        testScriptsDir = testScriptsDir == null ? Path.of("Test_Scripts") : testScriptsDir;
        exampleInstancesDir = exampleInstancesDir == null ? Path.of("Example_Instances") : exampleInstancesDir;
        profilesDir = profilesDir == null ? Path.of("Profiles") : profilesDir;
        igs = igs == null ? List.of() : List.copyOf(igs);
        include = include == null || include.isEmpty() ? DEFAULT_INCLUDE : List.copyOf(include);
        parallel = parallel == null || parallel;
        packageId = packageId == null || packageId.isBlank() ? null : packageId.trim();
        if ((name == null || name.isBlank()) && packageId != null) {
            name = packageId;
        } else if ((name == null || name.isBlank()) && baseDir != null && baseDir.getFileName() != null) {
            name = baseDir.getFileName().toString();
        }
        this.name = name;
        this.fhirVersion = fhirVersion;
        this.baseDir = baseDir;
        this.testScriptsDir = testScriptsDir;
        this.exampleInstancesDir = exampleInstancesDir;
        this.profilesDir = profilesDir;
        this.igs = igs;
        this.include = include;
        this.fhirServer = fhirServer;
        this.parallel = parallel;
        this.packageId = packageId;
    }

    /** A suite with the default folder layout below {@code baseDir}. */
    @Nonnull
    public static SuiteConfig of(@Nonnull Path baseDir, @Nullable String fhirVersion) {
        return new SuiteConfig(null, fhirVersion, baseDir, null, null, null, null, null, null, null, null);
    }

    /**
     * The suite of an unpacked FHIR package: TestScripts in {@code package/}, Example Instances in the package's example
     * folder, no Profiles folder (the package is an IG of its own), the package itself before the configured IGs.
     * Name, include, server and parallel are kept; the FHIR version is the package's unless one is configured.
     *
     * @param packageDir   the {@code package/} folder of the unpacked package
     * @param examplesDir  the example folder, relative to {@code packageDir}
     * @param version      the FHIR version of the package
     * @param dependencies further IGs the package depends on, as package ids
     */
    @Nonnull
    public SuiteConfig withPackage(@Nonnull Path packageDir, @Nonnull Path examplesDir, @Nonnull FhirVersion version,
                                   @Nonnull List<String> dependencies) {
        List<String> packageIgs = new ArrayList<>();
        packageIgs.add(packageId);
        packageIgs.addAll(dependencies);
        igs.stream().filter(ig -> !packageIgs.contains(ig)).forEach(packageIgs::add);
        // the package's own definitions come in with the package id; a Profiles folder does not exist in a package
        return new SuiteConfig(name, AUTO.equals(fhirVersion) ? version.name() : fhirVersion, packageDir, Path.of("."),
                examplesDir, packageDir.resolve("other").resolve("no-profiles-folder"), packageIgs,
                include.equals(DEFAULT_INCLUDE) ? List.of("TestScript-*.json", "TestScript-*.xml") : include,
                fhirServer, parallel, packageId);
    }

    /** The configured version, or empty for {@code AUTO}. */
    @Nonnull
    public Optional<FhirVersion> pinnedFhirVersion() {
        if (AUTO.equals(fhirVersion.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(FhirVersion.parse(fhirVersion));
    }

    @Nonnull
    public Path resolvedTestScriptsDir() {
        return baseDir.resolve(testScriptsDir).normalize();
    }

    @Nonnull
    public Path resolvedExampleInstancesDir() {
        return baseDir.resolve(exampleInstancesDir);
    }

    @Nonnull
    public Path resolvedProfilesDir() {
        return baseDir.resolve(profilesDir);
    }
}
