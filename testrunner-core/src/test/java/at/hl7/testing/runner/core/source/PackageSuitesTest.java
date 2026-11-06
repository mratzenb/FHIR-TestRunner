package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.config.PackagesConfig;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.testsupport.FakePackageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageSuitesTest {

    static final String MANIFEST = """
            {"name":"example.ig","version":"1.0.0","fhirVersions":["4.0.1"],
             "dependencies":{"hl7.fhir.r4.core":"4.0.1","hl7.terminology.r4":"6.2.0"},
             "directories":{"lib":"package","example":"examples"}}""";

    @TempDir
    Path cache;
    FakePackageServer packages;

    @AfterEach
    void stop() {
        if (packages != null) {
            packages.close();
        }
    }

    static SuiteConfig suite(String packageId) {
        return new SuiteConfig(null, null, null, null, null, null, List.of("extra.ig#1.0.0"), null, null, null, packageId, null);
    }

    /** Resolves through the real HTTP client against {@link #packages}. */
    private List<SuiteConfig> resolveOverHttp(boolean dependencies, String... packageIds) {
        PackagesConfig config = new PackagesConfig(cache, packages.registry(), packages.ciBuild());
        try (PackageSuites resolver = new PackageSuites(config, HttpConfig.defaults())) {
            return resolver.resolve(java.util.Arrays.stream(packageIds).map(PackageSuitesTest::suite).toList(),
                    dependencies);
        }
    }

    private static byte[] archive(String name, String version, Map<String, String> dependencies) {
        return FakePackageServer.archive(name, version, "4.0.1", dependencies, Map.of());
    }

    /** Dependencies in the order given, as {@code package.json} lists them: name, version, name, version, … */
    private static Map<String, String> dependencies(String... namesAndVersions) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (int i = 0; i < namesAndVersions.length; i += 2) {
            ordered.put(namesAndVersions[i], namesAndVersions[i + 1]);
        }
        return ordered;
    }

    /**
     * A {@code .tgz} as npm packs it: ustar, every file below {@code package/}. A name longer than 99 characters is
     * written like npm's tar does: a pax header ({@code x}) with the {@code path}, then the entry with a cut name.
     */
    static byte[] tgz(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                String name = file.getKey();
                if (name.length() > 99) {
                    entry(gzip, "PaxHeader/" + name.substring(0, 80), 'x',
                            paxRecord("path", name).getBytes(StandardCharsets.UTF_8));
                    name = name.substring(0, 99);
                }
                entry(gzip, name, '0', file.getValue().getBytes(StandardCharsets.UTF_8));
            }
            gzip.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    /** {@code "<length> <key>=<value>\n"}, the length counting the whole record including its own digits. */
    private static String paxRecord(String key, String value) {
        String rest = " " + key + "=" + value + "\n";
        int length = rest.length() + 1;
        while (String.valueOf(length).length() + rest.length() != length) {
            length = String.valueOf(length).length() + rest.length();
        }
        return length + rest;
    }

    private static void entry(GZIPOutputStream gzip, String name, char type, byte[] content) throws IOException {
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000644");
        put(header, 124, "%011o".formatted(content.length));
        put(header, 136, "%011o".formatted(0));
        header[156] = (byte) type;
        put(header, 257, "ustar");
        put(header, 263, "00");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, "%06o\0 ".formatted(sum));
        gzip.write(header);
        gzip.write(content);
        gzip.write(new byte[(512 - content.length % 512) % 512]);
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] value = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, header, offset, value.length);
    }

    @Test
    void packageInTheCacheBecomesASuiteWithItsFoldersVersionAndDependencies() throws Exception {
        Path packageDir = Files.createDirectories(cache.resolve("example.ig#dev/package"));
        Files.writeString(packageDir.resolve("package.json"), MANIFEST);

        SuiteConfig resolved = new PackageSuites(PackagesConfig.cache(cache), url -> {
            throw new AssertionError("nothing downloaded for a cached package without resolving dependencies");
        }).resolve(List.of(suite("example.ig#dev")), false).getFirst();

        assertThat(resolved.name()).isEqualTo("example.ig#dev");
        assertThat(resolved.resolvedTestScriptsDir()).isEqualTo(packageDir);
        assertThat(resolved.resolvedExampleInstancesDir()).isEqualTo(packageDir.resolve("examples"));
        assertThat(resolved.pinnedFhirVersion()).contains(FhirVersion.R4);
        assertThat(resolved.igs()).as("the package, its dependencies without the core, then the configured ones")
                .containsExactly("example.ig#dev", "hl7.terminology.r4#6.2.0", "extra.ig#1.0.0");
        assertThat(resolved.include()).containsExactly("TestScript-*.json", "TestScript-*.xml");
        assertThat(Files.isDirectory(resolved.resolvedProfilesDir())).as("a package has no Profiles folder").isFalse();
    }

    @Test
    void missingPackageIsDownloadedFromTheRegistryAndVerifiedAgainstItsChecksum() {
        packages = FakePackageServer.start();
        byte[] archive = FakePackageServer.archive("example.ig", "1.0.0", "4.0.1", Map.of(),
                Map.of("TestScript-a.json", "{\"resourceType\":\"TestScript\"}"));
        packages.publish("example.ig", "1.0.0", archive);

        SuiteConfig resolved = resolveOverHttp(false, "example.ig#1.0.0").getFirst();

        assertThat(packages.requests()).as("the versions with their checksum, then the archive through the redirect")
                .containsExactly("/registry/example.ig", "/registry/example.ig/1.0.0", "/cdn/example.ig-1.0.0.tgz");
        assertThat(cache.resolve("example.ig#1.0.0/package/TestScript-a.json")).exists();
        assertThat(resolved.baseDir()).isEqualTo(cache.resolve("example.ig#1.0.0/package"));
        try (var left = Files.list(cache)) {
            assertThat(left).as("no download folder left").hasSize(1);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aDownloadThatDoesNotMatchTheChecksumOfTheRegistryIsNotPutIntoTheCache() {
        packages = FakePackageServer.start();
        packages.publish("example.ig", "1.0.0", archive("example.ig", "1.0.0", Map.of()),
                "0000000000000000000000000000000000000000");

        assertThatThrownBy(() -> resolveOverHttp(false, "example.ig#1.0.0"))
                .isInstanceOf(PackageSuites.PackageException.class)
                .hasMessageContaining("Package example.ig#1.0.0 from " + packages.registry() + "/example.ig/1.0.0"
                        + " does not match the checksum of the registry: SHA-1 ")
                .hasMessageContaining("expected 0000000000000000000000000000000000000000; it was not put into the cache");
        assertThat(cache.resolve("example.ig#1.0.0")).doesNotExist();
        try (var left = Files.list(cache)) {
            assertThat(left).as("no download folder left").isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aPackageWithoutChecksumIsTakenWithAWarningAndOneHoldingAnotherPackageIsRejected() {
        packages = FakePackageServer.start();
        packages.publish("example.ig", "1.0.0", archive("example.ig", "1.0.0", Map.of()), null);
        packages.publish("example.other", "1.0.0", archive("example.evil", "1.0.0", Map.of()));

        assertThat(resolveOverHttp(false, "example.ig#1.0.0").getFirst().baseDir())
                .isEqualTo(cache.resolve("example.ig#1.0.0/package"));
        assertThatThrownBy(() -> resolveOverHttp(false, "example.other#1.0.0"))
                .hasMessageContaining("holds the package 'example.evil', not example.other");
        assertThat(cache.resolve("example.other#1.0.0")).doesNotExist();
    }

    @Test
    void dependenciesAreResolvedTransitivelyWithTheVersionsFoundAndOnlyOnce() throws Exception {
        packages = FakePackageServer.start();
        packages.publish("example.ig", "1.0.0", archive("example.ig", "1.0.0", dependencies("hl7.fhir.r4.core",
                "4.0.1", "example.base", "2.1.x", "example.terminology", "6.2.0")));
        packages.publish("example.base", "2.1.0", archive("example.base", "2.1.0", Map.of()));
        packages.publish("example.base", "2.1.4", archive("example.base", "2.1.4", dependencies(
                "example.terminology", "6.2.0", "example.extensions", "5.3.0")));
        packages.publish("example.base", "2.2.0", archive("example.base", "2.2.0", Map.of()));
        packages.publish("example.terminology", "6.2.0", archive("example.terminology", "6.2.0", Map.of()));
        // in the cache already: never asked for
        Path extensions = Files.createDirectories(cache.resolve("example.extensions#5.3.0/package"));
        Files.writeString(extensions.resolve("package.json"), """
                {"name":"example.extensions","version":"5.3.0","fhirVersions":["4.0.1"]}""");

        SuiteConfig resolved = resolveOverHttp(true, "example.ig#1.0.0").getFirst();

        assertThat(resolved.igs()).as("breadth first, each once, the wildcard as the release found")
                .containsExactly("example.ig#1.0.0", "example.base#2.1.4", "example.terminology#6.2.0",
                        "example.extensions#5.3.0", "extra.ig#1.0.0");
        assertThat(cache.resolve("example.base#2.1.4/package/package.json")).exists();
        assertThat(cache.resolve("example.terminology#6.2.0/package/package.json")).exists();
        assertThat(packages.requests()).filteredOn(p -> p.startsWith("/cdn/"))
                .containsExactly("/cdn/example.ig-1.0.0.tgz", "/cdn/example.base-2.1.4.tgz",
                        "/cdn/example.terminology-6.2.0.tgz");
        assertThat(packages.requests()).as("the core package is the validator's").noneMatch(p -> p.contains("r4.core"));
    }

    @Test
    void aWildcardTakesTheHighestPatchReleaseInTheCacheFirst() throws Exception {
        for (String version : List.of("2.1.2", "2.1.10", "2.2.0")) {
            Path dir = Files.createDirectories(cache.resolve("example.ig#" + version + "/package"));
            Files.writeString(dir.resolve("package.json"), MANIFEST.replace("1.0.0", version));
        }

        SuiteConfig resolved = new PackageSuites(PackagesConfig.cache(cache), url -> {
            throw new IOException("offline");
        }).resolve(List.of(suite("example.ig#2.1.x")), false).getFirst();

        assertThat(resolved.baseDir()).isEqualTo(cache.resolve("example.ig#2.1.10/package"));
        assertThat(resolved.igs()).startsWith("example.ig#2.1.10");
    }

    @Test
    void aDependencyThatCannotBeFoundNamesThePackageThatNeedsIt() {
        packages = FakePackageServer.start();
        packages.publish("example.ig", "1.0.0", archive("example.ig", "1.0.0", Map.of("example.missing", "1.0.0")));
        packages.publish("example.missing", "0.9.0", archive("example.missing", "0.9.0", Map.of()));

        assertThatThrownBy(() -> resolveOverHttp(true, "example.ig#1.0.0"))
                .hasMessageContaining("Package example.missing#1.0.0 is not in the registry " + packages.registry()
                        + ", which has 0.9.0 (a dependency of example.ig#1.0.0)");
        assertThat(resolveOverHttp(false, "example.ig#1.0.0").getFirst().igs())
                .as("without the validator the dependencies are not needed").contains("example.missing#1.0.0");
    }

    @Test
    void currentIsTheLatestCiBuildAndIsDownloadedAgainOnlyWhenANewerOneExists() throws Exception {
        packages = FakePackageServer.start();
        // the package is built by two repositories (a fork); the most recent build wins, as for the HL7 validator
        packages.build("example.ig", "someone/fork", null, "2026-10-01T10:00:00+00:00", "20261001100000",
                archive("example.ig", "0.9.0", Map.of()));
        packages.build("example.ig", "example/ig", null, "2026-10-20T04:53:02+00:00", "20261020045302",
                FakePackageServer.archive("example.ig", "2.1.0", "4.0.1", Map.of(),
                        Map.of("TestScript-new.json", "{}")));

        SuiteConfig resolved = resolveOverHttp(false, "example.ig#current").getFirst();

        Path packageDir = cache.resolve("example.ig#current/package");
        assertThat(resolved.baseDir()).isEqualTo(packageDir);
        assertThat(packageDir.resolve("TestScript-new.json")).exists();
        assertThat(Files.readString(packageDir.resolve("package.json"))).as("kept under the version asked for")
                .contains("\"version\" : \"current\"", "\"original-version\" : \"2.1.0\"");
        assertThat(packages.requests()).contains("/ci/ig/qas.json", "/ci/ig/example/ig/package.manifest.json",
                "/ci/ig/example/ig/package.tgz");

        // the build in the cache has the date of the manifest: not downloaded again
        Files.writeString(packageDir.resolve("package.json"), Files.readString(packageDir.resolve("package.json"))
                .replaceFirst("\\{", "{\"date\":\"20261020045302\","));
        int before = packages.requests().size();
        resolveOverHttp(false, "example.ig#current");
        assertThat(packages.requests().subList(before, packages.requests().size()))
                .doesNotContain("/ci/ig/example/ig/package.tgz");

        // a newer build replaces it
        Files.writeString(packageDir.resolve("package.json"), Files.readString(packageDir.resolve("package.json"))
                .replace("20261020045302", "20261001000000"));
        Files.writeString(packageDir.resolve("left-from-the-old-build.json"), "{}");
        resolveOverHttp(false, "example.ig#current");
        assertThat(packageDir.resolve("left-from-the-old-build.json")).doesNotExist();
        try (var left = Files.list(cache)) {
            assertThat(left.map(p -> p.getFileName().toString())).containsExactly("example.ig#current");
        }
    }

    @Test
    void currentOfABranchAndACachedBuildWhenTheCiServerCannotBeReached() throws Exception {
        packages = FakePackageServer.start();
        packages.build("example.ig", "example/ig", "feature-1", "2026-10-20T04:53:02+00:00", "20261020045302",
                archive("example.ig", "2.1.0", Map.of()));

        assertThat(resolveOverHttp(false, "example.ig#current$feature-1").getFirst().baseDir())
                .isEqualTo(cache.resolve("example.ig#current$feature-1/package"));
        assertThat(packages.requests()).contains("/ci/ig/example/ig/branches/feature-1/package.tgz");

        SuiteConfig offline = new PackageSuites(PackagesConfig.cache(cache), url -> {
            throw new IOException("Connection refused");
        }).resolve(List.of(suite("example.ig#current$feature-1")), false).getFirst();
        assertThat(offline.baseDir()).isEqualTo(cache.resolve("example.ig#current$feature-1/package"));

        assertThatThrownBy(() -> new PackageSuites(PackagesConfig.cache(cache), url -> {
            throw new IOException("Connection refused");
        }).resolve(List.of(suite("example.ig#current")), false))
                .hasMessageContaining("the list of CI builds https://build.fhir.org/ig/qas.json cannot be read:"
                        + " Connection refused");
        assertThatThrownBy(() -> resolveOverHttp(false, "example.unknown#current"))
                .hasMessageContaining("the CI server " + packages.ciBuild() + " has no build of example.unknown");
    }

    @Test
    void aDevDependencyMissingInTheCacheIsTakenFromTheCiBuildLikeTheValidatorDoes() throws Exception {
        packages = FakePackageServer.start();
        Path packageDir = Files.createDirectories(cache.resolve("example.ig#dev/package"));
        Files.writeString(packageDir.resolve("package.json"), """
                {"name":"example.ig","version":"dev","fhirVersions":["4.0.1"],
                 "dependencies":{"example.base":"dev"}}""");
        packages.build("example.base", "example/base", null, "2026-10-20T04:53:02+00:00", "20261020045302",
                archive("example.base", "1.0.0", Map.of()));

        assertThat(resolveOverHttp(true, "example.ig#dev").getFirst().igs())
                .containsExactly("example.ig#dev", "example.base#current", "extra.ig#1.0.0");
    }

    @Test
    void problemsAreReportedWithThePackage() throws Exception {
        PackagesConfig config = new PackagesConfig(cache, URI.create("https://registry.example"), null);

        assertThatThrownBy(() -> new PackageSuites(config, url -> null).resolve(List.of(suite("example.ig#dev")), false))
                .hasMessageContaining("example.ig#dev is not in the package cache").hasMessageContaining("never published");
        assertThatThrownBy(() -> new PackageSuites(config, url -> null).resolve(List.of(suite("../evil#1")), false))
                .hasMessageContaining("is no package id with version");
        assertThatThrownBy(() -> new PackageSuites(config, url -> null).resolve(List.of(suite("evil#../1")), false))
                .hasMessageContaining("is no package id with version");
        assertThatThrownBy(() -> new PackageSuites(config, url -> {
            throw new IOException("HTTP 404");
        }).resolve(List.of(suite("example.ig#9.9.9")), false))
                .hasMessageContaining("the registry https://registry.example/example.ig cannot be read: HTTP 404");
        byte[] escaping = tgz(Map.of("package/../../outside.json", "{}"));
        assertThatThrownBy(() -> new PackageSuites(config, registry("example.ig", "2.0.0", escaping))
                .resolve(List.of(suite("example.ig#2.0.0")), false)).hasMessageContaining("outside the package");
        assertThat(cache.getParent().resolve("outside.json")).doesNotExist();
    }

    /**
     * A downloader that answers like a registry with one package version, whose checksum the archive has, and with the
     * archive for any other URL.
     */
    private static PackageSuites.PackageDownloader registry(String name, String version, byte[] archive) {
        return url -> url.getPath().endsWith("/" + name)
                ? new ByteArrayInputStream(("{\"versions\":{\"" + version + "\":{\"dist\":{\"shasum\":\""
                + FakePackageServer.sha1(archive) + "\"}}}}").getBytes(StandardCharsets.UTF_8))
                : new ByteArrayInputStream(archive);
    }

    @Test
    void longNamesOfPaxArchivesAreKept() throws Exception {
        String longName = "package/example/" + "Patient-" + "x".repeat(100) + ".json";
        byte[] archive = tgz(new LinkedHashMap<>(Map.of("package/package.json", MANIFEST, longName, "{}")));

        new PackageSuites(PackagesConfig.cache(cache), registry("example.ig", "1.0.0", archive))
                .resolve(List.of(suite("example.ig#1.0.0")), false);

        assertThat(cache.resolve("example.ig#1.0.0").resolve(longName)).exists();
        assertThat(PackageSuites.Tar.paxPath("20 mtime=1700000000\n30 path=package/a/long/name\n"))
                .isEqualTo("package/a/long/name");
    }

    @Test
    void aPackagePutIntoTheCacheMeanwhileIsUsedAndOldDownloadFoldersDoNotMatter() throws Exception {
        byte[] archive = tgz(new LinkedHashMap<>(Map.of("package/package.json", MANIFEST)));
        Files.createDirectories(cache.resolve("example.ig#1.0.0.download-leftover/package"));
        PackageSuites.PackageDownloader registry = registry("example.ig", "1.0.0", archive);

        SuiteConfig resolved = new PackageSuites(PackagesConfig.cache(cache), url -> {
            if (url.getPath().endsWith("/1.0.0")) {
                // another run (or the validator) finishes the same package while this one downloads
                Path other = Files.createDirectories(cache.resolve("example.ig#1.0.0/package"));
                Files.writeString(other.resolve("package.json"), MANIFEST);
                Files.writeString(other.resolve("from-the-other-run.txt"), "x");
            }
            return registry.download(url);
        }).resolve(List.of(suite("example.ig#1.0.0")), false).getFirst();

        assertThat(resolved.baseDir()).isEqualTo(cache.resolve("example.ig#1.0.0/package"));
        assertThat(cache.resolve("example.ig#1.0.0/package/from-the-other-run.txt")).exists();
        try (var left = Files.list(cache)) {
            assertThat(left.map(p -> p.getFileName().toString()))
                    .as("this run's download folder is gone, another run's is not touched")
                    .containsExactlyInAnyOrder("example.ig#1.0.0", "example.ig#1.0.0.download-leftover");
        }
    }

    @Test
    void theHttpClientIsClosedWithTheResolver() {
        List<String> events = new ArrayList<>();
        PackageSuites.PackageDownloader downloader = new PackageSuites.PackageDownloader() {
            @Override
            public java.io.InputStream download(URI url) {
                throw new AssertionError("not called");
            }

            @Override
            public void close() {
                events.add("closed");
            }
        };

        try (PackageSuites resolver = new PackageSuites(PackagesConfig.cache(cache), downloader)) {
            assertThat(resolver.resolve(List.of(SuiteConfig.of(cache, "R5")), true)).hasSize(1);
        }

        assertThat(events).containsExactly("closed");
    }

    @Test
    void suitesWithAFolderAreLeftAlone() {
        SuiteConfig folder = SuiteConfig.of(cache, "R5");

        assertThat(new PackageSuites(PackagesConfig.cache(cache), HttpConfig.defaults())
                .resolve(List.of(folder), true)).containsExactly(folder);
    }

    @Test
    void packageIdsAndVersions() {
        assertThat(PackageRef.parse("hl7.at.fhir.core.r4#2.1.x")).hasValueSatisfying(r -> {
            assertThat(r.isWildcard()).isTrue();
            assertThat(r.matches("2.1.12")).isTrue();
            assertThat(r.matches("2.10.1")).isFalse();
            assertThat(r.matches("2.1.0-ballot")).isFalse();
        });
        assertThat(PackageRef.parse("example.ig#current$feature-1")).hasValueSatisfying(r -> {
            assertThat(r.isCurrent()).isTrue();
            assertThat(r.branch()).contains("feature-1");
        });
        assertThat(PackageRef.parse("hl7.fhir.r4.core#4.0.1")).hasValueSatisfying(r -> assertThat(r.isCore()).isTrue());
        assertThat(PackageRef.parse("hl7.fhir.uv.extensions.r4#5.3.0"))
                .hasValueSatisfying(r -> assertThat(r.isCore()).isFalse());
        assertThat(PackageRef.parse("no-version")).isEmpty();
        assertThat(PackageRef.parse("example.ig#")).isEmpty();
        assertThat(PackageRef.parse("example.ig#.hidden")).isEmpty();
        assertThat(PackageRef.parse("example.ig#1/2")).isEmpty();
    }
}
