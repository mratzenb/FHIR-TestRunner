package at.hl7.testing.runner.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * A FHIR package registry and a CI build server on a free local port (Jetty), for the tests of package suites.
 *
 * <ul>
 *   <li>Registry, as packages.fhir.org: {@code GET /registry/<name>} lists the versions with their
 *       {@code dist.shasum}; {@code GET /registry/<name>/<version>} redirects to {@code /cdn/<name>-<version>.tgz},
 *       the archive (registries answer with their CDN that way).</li>
 *   <li>CI builds, as build.fhir.org: {@code GET /ci/ig/qas.json} lists every build; a build of
 *       {@code <org>/<repo>} serves {@code /ci/ig/<org>/<repo>[/branches/<branch>]/package.manifest.json} and
 *       {@code package.tgz}.</li>
 * </ul>
 * Anything else is {@code 404}. {@link #requests()} lists the paths asked for.
 */
public final class FakePackageServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Server server = new Server();
    private final ServerConnector connector = new ServerConnector(server);
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> registry = new ConcurrentHashMap<>();
    private final ArrayNode builds = JSON.createArrayNode();
    private final List<String> requests = new ArrayList<>();

    private FakePackageServer() throws Exception {
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
                return FakePackageServer.this.handle(request, response, callback);
            }
        });
        server.start();
    }

    public static FakePackageServer start() {
        try {
            return new FakePackageServer();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the fake package server", e);
        }
    }

    /** {@code http://127.0.0.1:<port>/registry}, for {@code testrunner.packages.registry}. */
    public URI registry() {
        return URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/registry");
    }

    /** {@code http://127.0.0.1:<port>/ci}, for {@code testrunner.packages.ci-build}. */
    public URI ciBuild() {
        return URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/ci");
    }

    /** Publishes a package in the registry, with the SHA-1 of its archive. */
    public FakePackageServer publish(String name, String version, byte[] archive) {
        return publish(name, version, archive, sha1(archive));
    }

    /** Publishes a package whose listed checksum is {@code shasum} ({@code null} for none), whatever the archive is. */
    public synchronized FakePackageServer publish(String name, String version, byte[] archive, String shasum) {
        ObjectNode entry = registry.computeIfAbsent(name, n -> {
            ObjectNode created = JSON.createObjectNode().put("_id", n).put("name", n);
            created.putObject("versions");
            return created;
        });
        ObjectNode dist = ((ObjectNode) entry.get("versions")).putObject(version).put("name", name)
                .put("version", version).putObject("dist");
        if (shasum != null) {
            dist.put("shasum", shasum);
        }
        dist.put("tarball", "http://127.0.0.1:" + connector.getLocalPort() + "/cdn/" + name + "-" + version + ".tgz");
        files.put("/cdn/" + name + "-" + version + ".tgz", archive);
        return this;
    }

    /**
     * Publishes a CI build of {@code repo} ({@code <org>/<repo>}), of the default branch if {@code branch} is
     * {@code null}; listed in {@code qas.json} with {@code dateIso8601} (e.g. {@code 2026-10-20T04:53:02+00:00}).
     *
     * @param manifestDate the {@code date} of its {@code package.manifest.json}, as in its {@code package.json}
     */
    public synchronized FakePackageServer build(String packageId, String repo, String branch, String dateIso8601,
                                                String manifestDate, byte[] archive) {
        String folder = "/ci/ig/" + repo + (branch == null ? "" : "/branches/" + branch);
        builds.addObject().put("url", "http://example.org/ImplementationGuide/" + packageId)
                .put("package-id", packageId).put("dateISO8601", dateIso8601)
                .put("repo", repo + "/branches/" + (branch == null ? "main" : branch) + "/qa.json");
        files.put(folder + "/package.manifest.json", ("{\"name\":\"" + packageId + "\",\"date\":\"" + manifestDate
                + "\"}").getBytes(StandardCharsets.UTF_8));
        files.put(folder + "/package.tgz", archive);
        return this;
    }

    /** The paths asked for, in order. */
    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getDecodedPath();
        synchronized (requests) {
            requests.add(path);
        }
        if (path.equals("/ci/ig/qas.json")) {
            return send(response, callback, 200, "application/json", bytes(builds));
        }
        if (path.startsWith("/registry/")) {
            String[] parts = path.substring("/registry/".length()).split("/");
            ObjectNode entry = registry.get(parts[0]);
            if (entry != null && parts.length == 1) {
                return send(response, callback, 200, "application/json", bytes(entry));
            }
            if (entry != null && parts.length == 2 && entry.path("versions").has(parts[1])) {
                response.setStatus(302);
                response.getHeaders().put("Location", "/cdn/" + parts[0] + "-" + parts[1] + ".tgz");
                response.write(true, null, callback);
                return true;
            }
        }
        byte[] file = files.get(path);
        if (file != null) {
            return send(response, callback, 200, path.endsWith(".json") ? "application/json" : "application/tar+gzip",
                    file);
        }
        return send(response, callback, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean send(Response response, Callback callback, int status, String contentType, byte[] body) {
        response.setStatus(status);
        response.getHeaders().put("Content-Type", contentType);
        response.write(true, ByteBuffer.wrap(body), callback);
        return true;
    }

    private static byte[] bytes(Object json) {
        try {
            return JSON.writeValueAsBytes(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The SHA-1 of the bytes, as hex, like {@code dist.shasum}. */
    public static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A FHIR package archive ({@code .tgz}) with a {@code package/package.json} and further files below
     * {@code package/}.
     *
     * @param dependencies the package's dependencies, name to version
     * @param files        further files, by path below {@code package/}
     */
    public static byte[] archive(String name, String version, String fhirVersion, Map<String, String> dependencies,
                                 Map<String, String> files) {
        ObjectNode manifest = JSON.createObjectNode().put("name", name).put("version", version);
        manifest.putArray("fhirVersions").add(fhirVersion);
        ObjectNode deps = manifest.putObject("dependencies");
        dependencies.forEach(deps::put);
        manifest.putObject("directories").put("lib", "package").put("example", "example");
        Map<String, String> all = new LinkedHashMap<>();
        all.put("package/package.json", manifest.toString());
        files.forEach((path, content) -> all.put("package/" + path, content));
        return tgz(all);
    }

    /** A {@code .tgz} as npm packs it (ustar), with these files. */
    public static byte[] tgz(Map<String, String> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[512];
                put(header, 0, file.getKey());
                put(header, 100, "0000644");
                put(header, 124, "%011o".formatted(content.length));
                put(header, 136, "%011o".formatted(0));
                header[156] = '0';
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
            gzip.write(new byte[1024]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] value = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, header, offset, value.length);
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("Could not stop the fake package server", e);
        }
    }
}
