package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.config.PackagesConfig;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.model.FhirVersion;
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

    static SuiteConfig suite(String packageId) {
        return new SuiteConfig(null, null, null, null, null, null, List.of("extra.ig#1.0.0"), null, null, null, packageId);
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

        SuiteConfig resolved = new PackageSuites(new PackagesConfig(cache, null), url -> {
            throw new AssertionError("no download for a cached package");
        }).resolve(List.of(suite("example.ig#dev"))).getFirst();

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
    void missingPackageIsDownloadedIntoTheCache() throws Exception {
        byte[] archive = tgz(new LinkedHashMap<>(Map.of(
                "package/package.json", MANIFEST,
                "package/TestScript-a.json", "{\"resourceType\":\"TestScript\"}")));
        List<URI> downloads = new ArrayList<>();

        SuiteConfig resolved = new PackageSuites(new PackagesConfig(cache, URI.create("https://registry.example/")),
                url -> {
                    downloads.add(url);
                    return new ByteArrayInputStream(archive);
                }).resolve(List.of(suite("example.ig#1.0.0"))).getFirst();

        assertThat(downloads).containsExactly(URI.create("https://registry.example/example.ig/1.0.0"));
        assertThat(cache.resolve("example.ig#1.0.0/package/TestScript-a.json")).exists();
        assertThat(resolved.baseDir()).isEqualTo(cache.resolve("example.ig#1.0.0/package"));
        try (var left = Files.list(cache)) {
            assertThat(left).as("no download folder left").hasSize(1);
        }
    }

    @Test
    void problemsAreReportedWithThePackage() throws Exception {
        PackagesConfig config = new PackagesConfig(cache, URI.create("https://registry.example"));

        assertThatThrownBy(() -> new PackageSuites(config, url -> null).resolve(List.of(suite("example.ig#dev"))))
                .hasMessageContaining("example.ig#dev is not in the package cache").hasMessageContaining("never published");
        assertThatThrownBy(() -> new PackageSuites(config, url -> null).resolve(List.of(suite("../evil#1"))))
                .hasMessageContaining("is no package id with version");
        assertThatThrownBy(() -> new PackageSuites(config, url -> {
            throw new IOException("HTTP 404");
        }).resolve(List.of(suite("example.ig#9.9.9"))))
                .hasMessageContaining("could not be downloaded from https://registry.example/example.ig/9.9.9: HTTP 404");
        byte[] escaping = tgz(Map.of("package/../../outside.json", "{}"));
        assertThatThrownBy(() -> new PackageSuites(config, url -> new ByteArrayInputStream(escaping))
                .resolve(List.of(suite("example.ig#2.0.0")))).hasMessageContaining("outside the package");
        assertThat(cache.getParent().resolve("outside.json")).doesNotExist();
    }

    @Test
    void longNamesOfPaxArchivesAreKept() throws Exception {
        String longName = "package/example/" + "Patient-" + "x".repeat(100) + ".json";
        byte[] archive = tgz(new LinkedHashMap<>(Map.of("package/package.json", MANIFEST, longName, "{}")));

        new PackageSuites(new PackagesConfig(cache, URI.create("https://registry.example")),
                url -> new ByteArrayInputStream(archive)).resolve(List.of(suite("example.ig#1.0.0")));

        assertThat(cache.resolve("example.ig#1.0.0").resolve(longName)).exists();
        assertThat(PackageSuites.Tar.paxPath("20 mtime=1700000000\n30 path=package/a/long/name\n"))
                .isEqualTo("package/a/long/name");
    }

    @Test
    void aPackagePutIntoTheCacheMeanwhileIsUsedAndOldDownloadFoldersDoNotMatter() throws Exception {
        byte[] archive = tgz(new LinkedHashMap<>(Map.of("package/package.json", MANIFEST)));
        Files.createDirectories(cache.resolve("example.ig#1.0.0.download-leftover/package"));

        SuiteConfig resolved = new PackageSuites(new PackagesConfig(cache, URI.create("https://registry.example")),
                url -> {
                    // another run (or the validator) finishes the same package while this one downloads
                    Path other = Files.createDirectories(cache.resolve("example.ig#1.0.0/package"));
                    Files.writeString(other.resolve("package.json"), MANIFEST);
                    Files.writeString(other.resolve("from-the-other-run.txt"), "x");
                    return new ByteArrayInputStream(archive);
                }).resolve(List.of(suite("example.ig#1.0.0"))).getFirst();

        assertThat(resolved.baseDir()).isEqualTo(cache.resolve("example.ig#1.0.0/package"));
        assertThat(cache.resolve("example.ig#1.0.0/package/from-the-other-run.txt")).exists();
        try (var left = Files.list(cache)) {
            assertThat(left.map(p -> p.getFileName().toString()))
                    .as("this run's download folder is gone, another run's is not touched")
                    .containsExactlyInAnyOrder("example.ig#1.0.0", "example.ig#1.0.0.download-leftover");
        }
    }

    @Test
    void suitesWithAFolderAreLeftAlone() {
        SuiteConfig folder = SuiteConfig.of(cache, "R5");

        assertThat(new PackageSuites(new PackagesConfig(cache, null)).resolve(List.of(folder))).containsExactly(folder);
    }
}
