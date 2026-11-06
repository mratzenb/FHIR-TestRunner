package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.nio.file.Path;

/**
 * Where suites read from FHIR packages ({@link SuiteConfig#packageId()}) and the packages they depend on come from.
 * The downloads use the JVM's proxy and TLS settings, like the FHIR server's client, and the timeouts of
 * {@link HttpConfig}.
 *
 * @param cacheDir the FHIR package cache, default {@code ~/.fhir/packages} – the cache of the HL7 validator, the IG
 *                 Publisher and SUSHI, so a package is downloaded once for all of them and locally built
 *                 ({@code #dev}) packages are found
 * @param registry the package registry a released package missing in the cache is downloaded from, and whose
 *                 checksum it must match, default {@code https://packages.fhir.org}
 * @param ciBuild  the server of the CI builds of IGs, where {@code #current} packages come from, default
 *                 {@code https://build.fhir.org} – as for the HL7 validator
 */
public record PackagesConfig(@Nonnull Path cacheDir, @Nonnull URI registry, @Nonnull URI ciBuild) {

    public PackagesConfig(@Nullable Path cacheDir, @Nullable URI registry, @Nullable URI ciBuild) {
        cacheDir = cacheDir == null ? Path.of(System.getProperty("user.home"), ".fhir", "packages") : cacheDir;
        registry = registry == null ? URI.create("https://packages.fhir.org") : registry;
        ciBuild = ciBuild == null ? URI.create("https://build.fhir.org") : ciBuild;
        this.cacheDir = cacheDir;
        this.registry = registry;
        this.ciBuild = ciBuild;
    }

    @Nonnull
    public static PackagesConfig defaults() {
        return new PackagesConfig(null, null, null);
    }

    /** The defaults with another cache. */
    @Nonnull
    public static PackagesConfig cache(@Nonnull Path cacheDir) {
        return new PackagesConfig(cacheDir, null, null);
    }
}
