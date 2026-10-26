package at.hl7.testing.runner.core.config;

import java.net.URI;
import java.nio.file.Path;

/**
 * Where suites read from FHIR packages ({@link SuiteConfig#packageId()}) come from.
 *
 * @param cacheDir the FHIR package cache, default {@code ~/.fhir/packages} – the cache of the HL7 validator, the IG
 *                 Publisher and SUSHI, so a package is downloaded once for all of them and locally built
 *                 ({@code #dev}) packages are found
 * @param registry the package registry a package missing in the cache is downloaded from, default
 *                 {@code https://packages.fhir.org}
 */
public record PackagesConfig(Path cacheDir, URI registry) {

    public PackagesConfig {
        cacheDir = cacheDir == null ? Path.of(System.getProperty("user.home"), ".fhir", "packages") : cacheDir;
        registry = registry == null ? URI.create("https://packages.fhir.org") : registry;
    }

    public static PackagesConfig defaults() {
        return new PackagesConfig(null, null);
    }
}
