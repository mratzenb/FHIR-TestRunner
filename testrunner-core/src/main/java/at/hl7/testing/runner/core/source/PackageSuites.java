package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.config.PackagesConfig;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.model.FhirVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Turns suites read from FHIR packages ({@link SuiteConfig#packageId()}) into suites with a folder, before the run.
 *
 * <p>A package is looked up in the FHIR package cache ({@link PackagesConfig#cacheDir()}, {@code <id>#<version>/
 * package/}), where the HL7 validator, the IG Publisher and SUSHI keep theirs; a locally built IG is there as
 * {@code #dev}. The version decides where a missing package comes from:
 * <ul>
 *   <li>{@code 2.1.0}: the registry ({@code <registry>/<name>/<version>}, a {@code .tgz}), verified against the SHA-1
 *       the registry lists for it ({@code <registry>/<name>}, {@code versions.<version>.dist.shasum});</li>
 *   <li>{@code 2.1.x}: the highest patch release of 2.1 in the cache, else the highest one the registry lists;</li>
 *   <li>{@code current} and {@code current$<branch>}: the latest CI build on {@link PackagesConfig#ciBuild()}, found
 *       as the HL7 validator finds it (the most recent build of the package in {@code ig/qas.json}). A cached build is
 *       used while its {@code date} matches the build's {@code package.manifest.json}; if the CI server cannot be
 *       reached, the cached one is used. CI builds have no checksum;</li>
 *   <li>{@code dev}: only the cache – it is built locally and never published.</li>
 * </ul>
 * A download is unpacked into a folder of its own and then moved into place, so other runs and the validator, which
 * share the cache, never see a half-unpacked package; if one of them puts the package there first, theirs is used.
 *
 * <p>From the package's {@code package.json}: the FHIR version ({@code fhirVersions}), the example folder
 * ({@code directories.example}, IG Publisher packages: {@code example} or {@code examples}) and the dependencies,
 * which become further IGs of the suite (the core package of the FHIR version is left out, the validator always has
 * it). With {@code dependencies} the dependencies are resolved like the package itself, transitively, so the
 * validator finds every package verified in the cache; a {@code dev} dependency missing in the cache is taken from
 * the CI build, as the validator does.
 *
 * <p>One instance resolves the suites of one run, on one thread. Downloads share one HTTP client, created on the first
 * download and closed by {@link #close()}; it follows redirects (registries redirect to their CDN) and uses the
 * connect and request timeout of {@link HttpConfig}, and like the FHIR server's client the JVM's proxy and TLS settings.
 */
public final class PackageSuites implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PackageSuites.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** {@code Fri, 09 Oct, 2026 06:27:26 +0000}, the {@code date} of {@code qas.json}. */
    private static final DateTimeFormatter QA_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM, yyyy HH:mm:ss Z",
            Locale.US);

    private final PackagesConfig config;
    private final PackageDownloader downloader;
    /** The packages resolved so far, by {@code name#version} as written; each is resolved once. */
    private final Map<String, Resolved> resolved = new HashMap<>();
    /** The registry's entry of each package name ({@code <registry>/<name>}), read once. */
    private final Map<String, JsonNode> registryEntries = new HashMap<>();
    /** The repositories that build each package, newest build first, from {@code qas.json}; read when needed first. */
    private Map<String, List<String>> ciBuilds;

    /** Downloads something from a URL: a package archive ({@code .tgz}) or a JSON document. */
    @FunctionalInterface
    public interface PackageDownloader extends AutoCloseable {

        /**
         * @return the content; the caller closes it
         * @throws IOException if the URL does not answer with the content, e.g. {@code HTTP 404}
         */
        InputStream download(URI url) throws IOException;

        /** Releases what the downloads held, e.g. an HTTP client. */
        @Override
        default void close() {
        }
    }

    /** Downloads with a JDK {@link HttpClient} built from {@code http}. */
    public PackageSuites(PackagesConfig config, HttpConfig http) {
        this(config, new JdkDownloader(http));
    }

    /** @param downloader closed by {@link #close()} */
    public PackageSuites(PackagesConfig config, PackageDownloader downloader) {
        this.config = config;
        this.downloader = downloader;
    }

    /**
     * Resolves the suites read from packages; other suites are returned as they are.
     *
     * @param dependencies whether the packages the suites' packages depend on are needed (they are only for the
     *                     validator): then they are resolved transitively and become the suites' IGs with the version
     *                     resolved; otherwise the direct dependencies become IGs as written
     * @throws PackageException if a package cannot be found, downloaded, verified or read
     */
    public List<SuiteConfig> resolve(List<SuiteConfig> suites, boolean dependencies) {
        List<SuiteConfig> result = new ArrayList<>();
        for (SuiteConfig suite : suites) {
            result.add(suite.packageId() == null ? suite : resolve(suite, dependencies));
        }
        return result;
    }

    /** Closes the downloader, e.g. its HTTP client. */
    @Override
    public void close() {
        downloader.close();
    }

    /**
     * A package in the cache.
     *
     * @param ref      the package with the version it has in the cache, e.g. {@code 2.1.3} for {@code 2.1.x}
     * @param dir      its {@code package/} folder
     * @param manifest its {@code package.json}
     */
    private record Resolved(PackageRef ref, Path dir, JsonNode manifest) {

        /** The dependencies as {@code package.json} lists them, without the core package of the FHIR version. */
        Map<String, String> dependencies() {
            Map<String, String> dependencies = new LinkedHashMap<>();
            manifest.path("dependencies").properties().forEach(d -> dependencies.put(d.getKey(), d.getValue().asText()));
            dependencies.keySet().removeIf(name -> PackageRef.of(name, "0").map(PackageRef::isCore).orElse(false));
            return dependencies;
        }
    }

    private SuiteConfig resolve(SuiteConfig suite, boolean dependencies) {
        String packageId = suite.packageId();
        PackageRef ref = PackageRef.parse(packageId).orElseThrow(() -> new PackageException("Suite '" + suite.name()
                + "': '" + packageId + "' is no package id with version, e.g. hl7.at.fhir.core.r4#2.1.0, #2.1.x,"
                + " #current or #dev"));
        Resolved pkg = resolve(ref);
        FhirVersion version = fhirVersion(pkg);
        String example = pkg.manifest().path("directories").path("example").asText("example");
        List<String> igs = new ArrayList<>();
        igs.add(pkg.ref().id());
        igs.addAll(dependencies ? dependencyClosure(pkg)
                : pkg.dependencies().entrySet().stream().map(d -> d.getKey() + "#" + d.getValue()).toList());
        SuiteConfig result = suite.withPackage(pkg.dir(), Path.of(example), version, igs);
        log.info("Suite '{}' read from package {} (FHIR {}, {}{})", suite.name(), pkg.ref(), version, pkg.dir(),
                igs.size() == 1 ? "" : ", depends on " + igs.subList(1, igs.size()));
        return result;
    }

    /**
     * Every package {@code pkg} depends on, directly or not, resolved, in breadth-first order. A dependency the runner
     * cannot read (an npm alias, a version range) is left to the validator, as written.
     */
    private List<String> dependencyClosure(Resolved pkg) {
        Set<String> closure = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>(List.of(pkg.ref().id()));
        Deque<Resolved> open = new ArrayDeque<>(List.of(pkg));
        while (!open.isEmpty()) {
            Resolved next = open.poll();
            next.dependencies().forEach((name, version) -> {
                Optional<PackageRef> parsed = PackageRef.of(name, version);
                if (parsed.isEmpty()) {
                    log.warn("Package {} depends on {}#{}, which the runner cannot resolve; it is passed to the"
                            + " validator as written", next.ref(), name, version);
                    closure.add(name + "#" + version);
                    return;
                }
                PackageRef dependency = parsed.get();
                if (dependency.isDev() && cached(dependency).isEmpty()) {
                    log.info("Package {} depends on {}, which is not in the cache; using the CI build, as the HL7"
                            + " validator does", next.ref(), dependency);
                    dependency = dependency.withVersion("current");
                }
                Resolved found;
                try {
                    found = resolve(dependency);
                } catch (PackageException e) {
                    throw new PackageException(e.getMessage() + " (a dependency of " + next.ref() + ")", e);
                }
                if (visited.add(found.ref().id())) {
                    closure.add(found.ref().id());
                    open.add(found);
                }
            });
        }
        return List.copyOf(closure);
    }

    /** The package in the cache, downloaded first if needed. */
    private Resolved resolve(PackageRef ref) {
        Resolved known = resolved.get(ref.id());
        if (known != null) {
            return known;
        }
        Resolved result;
        if (ref.isDev()) {
            result = cached(ref).orElseThrow(() -> new PackageException("Package " + ref + " is not in the package cache "
                    + config.cacheDir() + "; a #dev package is built locally (IG Publisher) and never published"));
        } else if (ref.isCurrent()) {
            result = currentBuild(ref);
        } else if (ref.isWildcard()) {
            result = highestPatch(ref);
        } else {
            result = cached(ref).orElseGet(() -> fromRegistry(ref));
        }
        resolved.put(ref.id(), result);
        return result;
    }

    /** The package if it is in the cache, with a readable {@code package.json}. */
    private Optional<Resolved> cached(PackageRef ref) {
        Path dir = config.cacheDir().resolve(ref.id()).resolve("package");
        Path manifest = dir.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Resolved(ref, dir, JSON.readTree(manifest.toFile())));
        } catch (IOException e) {
            throw new PackageException("Cannot read " + manifest + ": " + e.getMessage(), e);
        }
    }

    /** {@code 2.1.x}: the highest patch release in the cache, else the highest one the registry lists. */
    private Resolved highestPatch(PackageRef ref) {
        Optional<String> inCache;
        try (Stream<Path> folders = Files.isDirectory(config.cacheDir()) ? Files.list(config.cacheDir()) : Stream.empty()) {
            inCache = folders.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(ref.name() + "#"))
                    .map(name -> name.substring(ref.name().length() + 1))
                    .filter(ref::matches)
                    .filter(v -> Files.isRegularFile(config.cacheDir().resolve(ref.name() + "#" + v)
                            .resolve("package/package.json")))
                    .max(Comparator.comparingLong(PackageRef::patch));
        } catch (IOException e) {
            throw new PackageException("Cannot list the package cache " + config.cacheDir() + ": " + e.getMessage(), e);
        }
        if (inCache.isPresent()) {
            log.info("Package {} is {} in the cache", ref, inCache.get());
            return resolve(ref.withVersion(inCache.get()));
        }
        List<String> versions = new ArrayList<>();
        registryEntry(ref).path("versions").fieldNames().forEachRemaining(versions::add);
        String highest = versions.stream().filter(ref::matches).max(Comparator.comparingLong(PackageRef::patch))
                .orElseThrow(() -> new PackageException("Package " + ref + ": no version in the registry "
                        + config.registry() + " matches, it has " + describe(versions)));
        log.info("Package {} is {} in the registry", ref, highest);
        return resolve(ref.withVersion(highest));
    }

    /** An exact version from the registry, verified against the checksum the registry lists for it. */
    private Resolved fromRegistry(PackageRef ref) {
        JsonNode versions = registryEntry(ref).path("versions");
        JsonNode version = versions.path(ref.version());
        if (version.isMissingNode()) {
            List<String> known = new ArrayList<>();
            versions.fieldNames().forEachRemaining(known::add);
            throw new PackageException("Package " + ref + " is not in the registry " + config.registry() + ", which has "
                    + describe(known));
        }
        String shasum = version.path("dist").path("shasum").asText("");
        if (shasum.isBlank()) {
            log.warn("The registry lists no checksum (dist.shasum) for package {}; its download is not verified", ref);
        }
        URI url = URI.create(base(config.registry()) + "/" + ref.name() + "/" + ref.version());
        install(ref, url, shasum.isBlank() ? null : shasum, false);
        return cached(ref).orElseThrow();
    }

    /** {@code <registry>/<name>}: the versions of a package and their checksums, npm style. */
    private JsonNode registryEntry(PackageRef ref) {
        JsonNode entry = registryEntries.get(ref.name());
        if (entry == null) {
            URI url = URI.create(base(config.registry()) + "/" + ref.name());
            log.info("Package {} is not in the cache {}, asking the registry {}", ref, config.cacheDir(), url);
            try {
                entry = json(url);
            } catch (IOException e) {
                throw new PackageException("Package " + ref + ": the registry " + url + " cannot be read: "
                        + e.getMessage(), e);
            }
            registryEntries.put(ref.name(), entry);
        }
        return entry;
    }

    /**
     * {@code current} or {@code current$<branch>}: the CI build in the cache while it is the latest, else downloaded
     * anew. Without the CI server a cached build is used.
     */
    private Resolved currentBuild(PackageRef ref) {
        Optional<Resolved> cached = cached(ref);
        String folder;
        String date;
        try {
            String repository = ciBuild(ref);
            List<String> folders = ref.branch().map(b -> List.of(repository + "/branches/" + b))
                    .orElse(List.of(repository, repository + "/branches/main"));
            IOException failed = null;
            folder = null;
            date = null;
            for (String candidate : folders) {
                try {
                    date = json(URI.create(candidate + "/package.manifest.json")).path("date").asText("");
                    folder = candidate;
                    break;
                } catch (IOException e) {
                    failed = e;
                }
            }
            if (folder == null) {
                throw new PackageException("Package " + ref + ": no CI build at " + String.join(" or ", folders)
                        + " (" + failed.getMessage() + ")", failed);
            }
        } catch (PackageException e) {
            if (cached.isPresent()) {
                log.warn("Cannot check whether package {} in the cache is the latest CI build, using it: {}", ref,
                        e.getMessage());
                return cached.get();
            }
            throw e;
        }
        if (cached.isPresent() && !date.isEmpty() && date.equals(cached.get().manifest().path("date").asText())) {
            log.info("Package {} in the cache is the latest CI build ({}, {})", ref, date, folder);
            return cached.get();
        }
        log.info("Package {}: {} the CI build of {} from {}; CI builds have no checksum, the download is not verified",
                ref, cached.isPresent() ? "replacing the cached build with" : "downloading", date, folder);
        install(ref, URI.create(folder + "/package.tgz"), null, true);
        return cached(ref).orElseThrow();
    }

    /**
     * The folder of the package's builds on the CI server, {@code <ci-build>/ig/<org>/<repository>}: the repository of
     * its most recent build in {@code ig/qas.json}, as the HL7 validator chooses it.
     */
    private String ciBuild(PackageRef ref) {
        if (ciBuilds == null) {
            URI url = URI.create(base(config.ciBuild()) + "/ig/qas.json");
            log.info("Reading the list of CI builds {}", url);
            try {
                ciBuilds = repositories(json(url));
            } catch (IOException e) {
                throw new PackageException("Package " + ref + ": the list of CI builds " + url + " cannot be read: "
                        + e.getMessage(), e);
            }
        }
        List<String> repositories = ciBuilds.getOrDefault(ref.name(), List.of());
        if (repositories.isEmpty()) {
            throw new PackageException("Package " + ref + ": the CI server " + config.ciBuild() + " has no build of "
                    + ref.name() + " (ig/qas.json)");
        }
        if (repositories.size() > 1) {
            log.info("Package {} is built by the repositories {}; using the one with the most recent build", ref.name(),
                    repositories);
        }
        return base(config.ciBuild()) + "/ig/" + repositories.getFirst();
    }

    /**
     * By package name, the repositories ({@code <org>/<repository>}) that built it, the one with the most recent build
     * first.
     */
    static Map<String, List<String>> repositories(JsonNode builds) {
        record Build(String packageId, String repository, OffsetDateTime date) {
        }
        List<Build> found = new ArrayList<>();
        for (JsonNode build : builds) {
            String packageId = build.path("package-id").asText("");
            String[] repo = build.path("repo").asText("").split("/");
            if (!packageId.contains(".") || repo.length < 2 || !build.has("url")) {
                continue;
            }
            found.add(new Build(packageId, repo[0] + "/" + repo[1], date(build)));
        }
        found.sort(Comparator.comparing(Build::date).reversed());
        Map<String, List<String>> repositories = new HashMap<>();
        for (Build build : found) {
            List<String> known = repositories.computeIfAbsent(build.packageId(), id -> new ArrayList<>());
            if (!known.contains(build.repository())) {
                known.add(build.repository());
            }
        }
        return repositories;
    }

    private static OffsetDateTime date(JsonNode build) {
        try {
            if (build.hasNonNull("dateISO8601")) {
                return OffsetDateTime.parse(build.get("dateISO8601").asText());
            }
            return OffsetDateTime.parse(build.path("date").asText(""), QA_DATE);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.MIN;
        }
    }

    /**
     * Downloads the archive at {@code url} and unpacks it into {@code <cache>/<name>#<version>/}.
     *
     * @param sha1    the SHA-1 the archive must have (hex), {@code null} to take it unverified
     * @param replace whether a package already in place is replaced (a newer CI build), else it is kept
     */
    private void install(PackageRef ref, URI url, String sha1, boolean replace) {
        Path target = config.cacheDir().resolve(ref.id());
        Path temporary = null;
        log.info("Downloading package {} from {}", ref, url);
        try {
            Files.createDirectories(config.cacheDir());
            temporary = Files.createTempDirectory(config.cacheDir(), ref.id() + ".download-");
            String actual = unpack(url, temporary);
            if (sha1 != null && !sha1.equalsIgnoreCase(actual)) {
                throw new PackageException("Package " + ref + " from " + url + " does not match the checksum of the"
                        + " registry: SHA-1 " + actual + ", expected " + sha1.toLowerCase(Locale.ROOT)
                        + "; it was not put into the cache");
            }
            Path manifest = temporary.resolve("package/package.json");
            if (!Files.isRegularFile(manifest)) {
                throw new PackageException("The archive " + url + " is no FHIR package (no package/package.json)");
            }
            checkName(ref, url, manifest);
            if (sha1 != null) {
                log.info("Package {} matches the checksum of the registry (SHA-1 {})", ref, actual);
            }
            moveIntoPlace(ref, temporary, target, replace);
        } catch (IOException e) {
            throw new PackageException("Package " + ref + " could not be downloaded from " + url + ": "
                    + e.getMessage(), e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /** Unpacks the archive into {@code folder}; returns the SHA-1 of every byte received, as hex. */
    private String unpack(URI url, Path folder) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-1", e);
        }
        try (DigestInputStream received = new DigestInputStream(downloader.download(url), sha1)) {
            Tar.extract(new GZIPInputStream(new BufferedInputStream(received)), folder);
            received.transferTo(OutputStream.nullOutputStream()); // what follows the end of the tar, for the checksum
        }
        return HexFormat.of().formatHex(sha1.digest());
    }

    /**
     * The archive must hold the package asked for: {@code name}, or {@code name.r4} for a package {@code name} of
     * FHIR R4, as the HL7 validator accepts it. The cache keeps a package under the version it was asked for; as the
     * validator does, {@code package.json} then says so and keeps the version of the build as
     * {@code original-version} – for a CI build {@code current} instead of {@code 2.1.0}.
     */
    private static void checkName(PackageRef ref, URI url, Path manifest) throws IOException {
        ObjectNode content = (ObjectNode) JSON.readTree(manifest.toFile());
        String name = content.path("name").asText("").toLowerCase(Locale.ROOT);
        String wanted = ref.name().toLowerCase(Locale.ROOT);
        if (!wanted.equals(name) && !(wanted.startsWith(name + ".") && wanted.substring(name.length() + 1)
                .matches("r[0-9]+b?"))) {
            throw new PackageException("The archive " + url + " holds the package '" + content.path("name").asText("")
                    + "', not " + ref.name() + "; it was not put into the cache");
        }
        String version = content.path("version").asText("");
        if (!version.equals(ref.version())) {
            content.put("original-version", version);
            content.remove("version");
            content.put("version", ref.version());
            JSON.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), content);
        }
    }

    private static void moveIntoPlace(PackageRef ref, Path unpacked, Path target, boolean replace) throws IOException {
        Path previous = null;
        if (replace && Files.exists(target)) {
            previous = target.resolveSibling(target.getFileName() + ".replaced-" + UUID.randomUUID());
            Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(unpacked, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!Files.isRegularFile(target.resolve("package/package.json"))) {
                if (previous != null) {
                    Path restore = previous;
                    previous = null; // kept as it is if it cannot be moved back
                    Files.move(restore, target, StandardCopyOption.ATOMIC_MOVE);
                }
                throw e;
            }
            log.info("Package {} was put into the cache by someone else meanwhile, using that one", ref);
        } finally {
            deleteQuietly(previous);
        }
    }

    private JsonNode json(URI url) throws IOException {
        try (InputStream in = downloader.download(url)) {
            return JSON.readTree(in);
        }
    }

    private static FhirVersion fhirVersion(Resolved pkg) {
        JsonNode versions = pkg.manifest().path("fhirVersions");
        if (!versions.isArray() || versions.isEmpty()) {
            throw new PackageException("Package " + pkg.ref() + " names no FHIR version (fhirVersions in package.json)");
        }
        try {
            return FhirVersion.parse(versions.get(0).asText());
        } catch (IllegalArgumentException e) {
            throw new PackageException("Package " + pkg.ref() + ": " + e.getMessage(), e);
        }
    }

    private static String base(URI url) {
        return url.toString().replaceAll("/+$", "");
    }

    /** The versions for a message: all of them, or the last ten. */
    private static String describe(List<String> versions) {
        if (versions.isEmpty()) {
            return "no version";
        }
        return versions.size() <= 10 ? String.join(", ", versions)
                : "… " + String.join(", ", versions.subList(versions.size() - 10, versions.size()));
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            log.debug("Cannot delete {}: {}", path, e.toString());
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

    /**
     * Downloads with a JDK {@link HttpClient}, created on the first download and shared by all downloads of one
     * resolution. Like the client of the FHIR server ({@code JdkHttpTransport}) it uses the JVM's proxy selector and
     * SSL context and the connect timeout of {@link HttpConfig}; the request timeout limits the wait for the response
     * headers. It always follows redirects: registries answer with one to their CDN.
     */
    private static final class JdkDownloader implements PackageDownloader {

        private final HttpConfig http;
        private HttpClient client;

        JdkDownloader(HttpConfig http) {
            this.http = http;
        }

        @Override
        public InputStream download(URI url) throws IOException {
            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(http.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
            }
            HttpRequest request = HttpRequest.newBuilder(url).timeout(http.requestTimeout()).GET().build();
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

        @Override
        public void close() {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Reads the regular files of a tar stream: ustar as npm packs packages, with the long names of GNU tar
     * ({@code L} entries) and of pax ({@code path} in an {@code x} entry); enough for FHIR packages.
     */
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
                String name = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                if (longName != null) { // the full path from a preceding long name entry
                    name = longName;
                    longName = null;
                }
                long size = Long.parseLong(text(header, 124, 12).trim().isEmpty() ? "0" : text(header, 124, 12).trim(), 8);
                char type = (char) header[156];
                if (type == 'L') { // GNU long name: the name is the content
                    byte[] content = tar.readNBytes((int) size);
                    skip(tar, padding(size));
                    longName = new String(content, StandardCharsets.UTF_8).replace("\0", "");
                    continue;
                }
                if (type == 'x') { // pax header of the next entry: "<length> <key>=<value>\n" records
                    byte[] content = tar.readNBytes((int) size);
                    skip(tar, padding(size));
                    String path = paxPath(new String(content, StandardCharsets.UTF_8));
                    if (path != null) {
                        longName = path;
                    }
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

        /** The {@code path} record of a pax header, or {@code null}. */
        static String paxPath(String records) {
            int at = 0;
            while (at < records.length()) {
                int space = records.indexOf(' ', at);
                if (space < 0) {
                    return null;
                }
                int length;
                try {
                    length = Integer.parseInt(records.substring(at, space));
                } catch (NumberFormatException e) {
                    return null;
                }
                // the length counts bytes; the records of FHIR packages are ASCII, so bytes and chars agree
                String record = records.substring(space + 1, Math.min(records.length(), at + length)).strip();
                if (record.startsWith("path=")) {
                    return record.substring("path=".length());
                }
                at += Math.max(length, 1);
            }
            return null;
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
