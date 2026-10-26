package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.config.PackagesConfig;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.model.FhirVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Turns suites read from FHIR packages ({@link SuiteConfig#packageId()}) into suites with a folder, before the run.
 *
 * <p>A package is looked up in the FHIR package cache ({@link PackagesConfig#cacheDir()}, {@code <id>#<version>/
 * package/}), where the HL7 validator, the IG Publisher and SUSHI keep theirs; a locally built IG is there as
 * {@code #dev}. A package that is missing is downloaded from the registry ({@code <registry>/<id>/<version>}, a
 * {@code .tgz}) and unpacked into the cache, like the validator does, so both find it afterwards.
 *
 * <p>From the package's {@code package.json}: the FHIR version ({@code fhirVersions}), the example folder
 * ({@code directories.example}, IG Publisher packages: {@code example} or {@code examples}) and the dependencies, which
 * become further IGs of the suite (the core package of the FHIR version is left out, the validator always has it).
 */
public final class PackageSuites {

    private static final Logger log = LoggerFactory.getLogger(PackageSuites.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** {@code name#version}: name in lower case letters, digits, '.', '-' and '_'; version without '/' or '..'. */
    private static final Pattern PACKAGE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*#[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final Pattern CORE_PACKAGE = Pattern.compile("hl7\\.fhir\\.r[0-9]+[a-z]?\\.core");

    private final PackagesConfig config;
    private final PackageDownloader downloader;

    /** Downloads a package archive ({@code .tgz}) from a URL. */
    @FunctionalInterface
    public interface PackageDownloader {
        InputStream download(URI url) throws IOException;
    }

    public PackageSuites(PackagesConfig config) {
        this(config, PackageSuites::httpDownload);
    }

    public PackageSuites(PackagesConfig config, PackageDownloader downloader) {
        this.config = config;
        this.downloader = downloader;
    }

    /**
     * Resolves the suites read from packages; other suites are returned as they are.
     *
     * @throws PackageException if a package cannot be found, downloaded or read
     */
    public List<SuiteConfig> resolve(List<SuiteConfig> suites) {
        List<SuiteConfig> resolved = new ArrayList<>();
        for (SuiteConfig suite : suites) {
            resolved.add(suite.packageId() == null ? suite : resolve(suite));
        }
        return resolved;
    }

    private SuiteConfig resolve(SuiteConfig suite) {
        String packageId = suite.packageId();
        if (!PACKAGE_ID.matcher(packageId).matches()) {
            throw new PackageException("Suite '" + suite.name() + "': '" + packageId + "' is no package id with version,"
                    + " e.g. hl7.at.fhir.core.r4#2.1.0 or hl7.at.fhir.core.r4#dev");
        }
        Path packageDir = config.cacheDir().resolve(packageId).resolve("package");
        if (!Files.isRegularFile(packageDir.resolve("package.json"))) {
            if (packageId.endsWith("#dev")) {
                throw new PackageException("Package " + packageId + " is not in the package cache " + config.cacheDir()
                        + "; a #dev package is built locally (IG Publisher) and never published");
            }
            download(packageId);
        }
        JsonNode manifest;
        try {
            manifest = JSON.readTree(packageDir.resolve("package.json").toFile());
        } catch (IOException e) {
            throw new PackageException("Cannot read " + packageDir.resolve("package.json") + ": " + e.getMessage(), e);
        }
        FhirVersion version = fhirVersion(packageId, manifest);
        String example = manifest.path("directories").path("example").asText("example");
        List<String> dependencies = new ArrayList<>();
        manifest.path("dependencies").properties().forEach(dependency -> {
            if (!CORE_PACKAGE.matcher(dependency.getKey()).matches()) {
                dependencies.add(dependency.getKey() + "#" + dependency.getValue().asText());
            }
        });
        SuiteConfig resolved = suite.withPackage(packageDir, Path.of(example), version, dependencies);
        log.info("Suite '{}' read from package {} (FHIR {}, {}{})", suite.name(), packageId, version, packageDir,
                dependencies.isEmpty() ? "" : ", depends on " + dependencies);
        return resolved;
    }

    private static FhirVersion fhirVersion(String packageId, JsonNode manifest) {
        JsonNode versions = manifest.path("fhirVersions");
        if (!versions.isArray() || versions.isEmpty()) {
            throw new PackageException("Package " + packageId + " names no FHIR version (fhirVersions in package.json)");
        }
        try {
            return FhirVersion.parse(versions.get(0).asText());
        } catch (IllegalArgumentException e) {
            throw new PackageException("Package " + packageId + ": " + e.getMessage(), e);
        }
    }

    /** Downloads {@code <registry>/<name>/<version>} and unpacks it into {@code <cache>/<name>#<version>/}. */
    private void download(String packageId) {
        String[] parts = packageId.split("#", 2);
        URI url = URI.create(config.registry().toString().replaceAll("/+$", "") + "/" + parts[0] + "/" + parts[1]);
        Path target = config.cacheDir().resolve(packageId);
        Path temporary = config.cacheDir().resolve(packageId + ".download");
        log.info("Package {} is not in the cache {}, downloading {}", packageId, config.cacheDir(), url);
        try {
            deleteRecursively(temporary);
            Files.createDirectories(temporary);
            try (InputStream archive = downloader.download(url)) {
                Tar.extract(new GZIPInputStream(new BufferedInputStream(archive)), temporary);
            }
            if (!Files.isRegularFile(temporary.resolve("package/package.json"))) {
                throw new PackageException("The archive " + url + " is no FHIR package (no package/package.json)");
            }
            Files.createDirectories(target.getParent());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new PackageException("Package " + packageId + " could not be downloaded from " + url + ": "
                    + e.getMessage(), e);
        } finally {
            try {
                deleteRecursively(temporary);
            } catch (IOException e) {
                log.debug("Cannot delete {}: {}", temporary, e.toString());
            }
        }
    }

    private static InputStream httpDownload(URI url) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5))
                .header("Accept", "application/tar+gzip, application/gzip, application/octet-stream").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** A package cannot be used; the run does not start. */
    public static final class PackageException extends RuntimeException {
        PackageException(String message) {
            super(message);
        }

        PackageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Reads the regular files of a tar stream (ustar, as npm packs packages); enough for FHIR packages. */
    static final class Tar {

        private Tar() {
        }

        static void extract(InputStream tar, Path target) throws IOException {
            Path root = target.toAbsolutePath().normalize();
            byte[] header = new byte[512];
            String longName = null;
            while (readFully(tar, header)) {
                if (isZero(header)) {
                    return;
                }
                String name = longName != null ? longName : text(header, 0, 100);
                longName = null;
                String prefix = text(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = Long.parseLong(text(header, 124, 12).trim().isEmpty() ? "0" : text(header, 124, 12).trim(), 8);
                char type = (char) header[156];
                if (type == 'L') { // GNU long name: the name is the content
                    byte[] content = tar.readNBytes((int) size);
                    skip(tar, padding(size));
                    longName = new String(content, StandardCharsets.UTF_8).replace("\0", "");
                    continue;
                }
                Path file = root.resolve(name).normalize();
                if (!file.startsWith(root)) {
                    throw new IOException("The archive contains a path outside the package: " + name);
                }
                if (type == '0' || type == '\0') {
                    Files.createDirectories(file.getParent());
                    try (var out = Files.newOutputStream(file)) {
                        copy(tar, out, size);
                    }
                    skip(tar, padding(size));
                } else {
                    if (type == '5') {
                        Files.createDirectories(file);
                    }
                    skip(tar, size + padding(size)); // links, pax headers etc. are not needed
                }
            }
        }

        private static long padding(long size) {
            return (512 - size % 512) % 512;
        }

        private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
            int read = in.readNBytes(buffer, 0, buffer.length);
            if (read == 0) {
                return false;
            }
            if (read < buffer.length) {
                throw new IOException("Truncated tar archive");
            }
            return true;
        }

        private static boolean isZero(byte[] block) {
            for (byte b : block) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }

        private static String text(byte[] header, int offset, int length) {
            int end = offset;
            while (end < offset + length && header[end] != 0) {
                end++;
            }
            return new String(header, offset, end - offset, StandardCharsets.UTF_8);
        }

        private static void copy(InputStream in, java.io.OutputStream out, long size) throws IOException {
            byte[] buffer = new byte[8192];
            long left = size;
            while (left > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new IOException("Truncated tar archive");
                }
                out.write(buffer, 0, read);
                left -= read;
            }
        }

        private static void skip(InputStream in, long bytes) throws IOException {
            long left = bytes;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("Truncated tar archive");
                    }
                    skipped = 1;
                }
                left -= skipped;
            }
        }
    }
}
