package at.hl7.testing.runner.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builds a stand-in for {@code validator_cli.jar}: a real, executable jar that answers like the HL7 validator, so
 * the validation adapter can be tested end to end (process start, arguments, {@code -output}) without the 200 MB
 * validator and without network access.
 *
 * <p>The fake reports one error per occurrence of the marker {@value #INVALID_MARKER} in the validated file, and
 * "All OK" otherwise. It writes nothing with {@code -output} when the file contains {@value #SILENT_MARKER}, to
 * simulate a validator that did not run.
 *
 * <p>{@code server <port>} runs it as HTTP server like {@code validator_cli.jar server}: {@code POST
 * /validateResource[?profile=..]} answers the same OperationOutcomes, {@code POST /stop} stops it. It writes one line
 * per server start and per validation to the file named by the system property or environment variable
 * {@code FAKE_VALIDATOR_LOG}, if set, so tests can count starts and calls. Further markers:
 * {@value #CRASH_MARKER} ends the server process (simulates a crash), {@value #UNRESOLVABLE_PROFILE} as profile is
 * answered with HTTP 500 like an unknown profile. {@code FAKE_VALIDATOR_SERVER_FAILS} (system property or environment)
 * makes the server exit at start-up, {@code FAKE_VALIDATOR_STARTUP_MILLIS} delays its start.
 */
public final class FakeValidatorJar {

    public static final String INVALID_MARKER = "FAKE-VALIDATOR-INVALID";
    public static final String SILENT_MARKER = "FAKE-VALIDATOR-SILENT";
    public static final String CRASH_MARKER = "FAKE-VALIDATOR-CRASH";
    public static final String UNRESOLVABLE_PROFILE = "http://example.org/StructureDefinition/unresolvable";

    private FakeValidatorJar() {
    }

    /** Writes the fake validator to {@code folder/validator_cli.jar}, compiling it with the running JDK. */
    public static Path create(Path folder) {
        try {
            Path sources = Files.createDirectories(folder.resolve("fake-validator-src"));
            Path source = sources.resolve("FakeValidator.java");
            Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
            Path classes = Files.createDirectories(folder.resolve("fake-validator-classes"));
            int status = javax.tools.ToolProvider.getSystemJavaCompiler()
                    .run(null, null, null, "-d", classes.toString(), source.toString());
            if (status != 0) {
                throw new IllegalStateException("Could not compile the fake validator");
            }
            Path jar = folder.resolve("validator_cli.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeValidator");
            try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
                out.putNextEntry(new JarEntry("FakeValidator.class"));
                out.write(Files.readAllBytes(classes.resolve("FakeValidator.class")));
                out.closeEntry();
            }
            return jar;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the fake validator", e);
        }
    }

    private static final String SOURCE = """
            import com.sun.net.httpserver.HttpServer;
            import java.io.*;
            import java.net.*;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.*;
            import java.util.*;

            public class FakeValidator {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0 && args[0].equals("server")) {
                        server(Integer.parseInt(args[1]), args);
                        return;
                    }
                    String input = null, output = null, profile = null;
                    for (int i = 0; i < args.length; i++) {
                        switch (args[i]) {
                            case "-output" -> output = args[++i];
                            case "-profile" -> profile = args[++i];
                            case "-version", "-tx", "-ig", "-locale" -> i++;
                            default -> { if (!args[i].startsWith("-")) input = args[i]; }
                        }
                    }
                    System.out.println("FHIR Validation tool (fake) " + String.join(" ", args));
                    String content = Files.readString(Path.of(input));
                    log("process " + profile);
                    if (content.contains("%1$s")) {
                        System.err.println("simulated validator failure");
                        System.exit(1);
                    }
                    Files.writeString(Path.of(output), outcome(content, profile));
                    System.out.println("Done");
                }

                static void server(int port, String[] args) throws Exception {
                    String delay = System.getProperty("FAKE_VALIDATOR_STARTUP_MILLIS", System.getenv("FAKE_VALIDATOR_STARTUP_MILLIS"));
                    if (delay != null) Thread.sleep(Long.parseLong(delay));
                    if (System.getProperty("FAKE_VALIDATOR_SERVER_FAILS", System.getenv("FAKE_VALIDATOR_SERVER_FAILS")) != null) {
                        System.out.println("simulated start-up failure");
                        System.exit(3);
                    }
                    log("start " + String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                    server.createContext("/validateResource", exchange -> {
                        String query = exchange.getRequestURI().getQuery();
                        String profile = query == null ? null
                                : URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
                        String content = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        log("validate " + profile + " " + exchange.getRequestHeaders().getFirst("Content-Type"));
                        if (content.contains("%4$s")) {
                            System.out.println("simulated crash");
                            Runtime.getRuntime().halt(9);
                        }
                        int status = "%5$s".equals(profile) ? 500 : 200;
                        String body = status == 500
                                ? "{\\"resourceType\\":\\"OperationOutcome\\",\\"issue\\":[{\\"severity\\":\\"error\\",\\"code\\":\\"exception\\",\\"details\\":{\\"text\\":\\"Validation failed: Unable to resolve profile " + profile + "\\"}}]}"
                                : outcome(content, profile);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/fhir+json");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
                    server.createContext("/stop", exchange -> {
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                        log("stop");
                        new Thread(() -> { server.stop(0); System.exit(0); }).start();
                    });
                    server.start();
                    System.out.println("FHIR Validator HTTP Service (fake) started on port " + port);
                }

                static String outcome(String content, String profile) {
                    int errors = content.split("%2$s", -1).length - 1;
                    StringBuilder issues = new StringBuilder();
                    for (int i = 0; i < errors; i++) {
                        if (i > 0) issues.append(',');
                        issues.append("{\\"severity\\":\\"error\\",\\"code\\":\\"structure\\",\\"details\\":{\\"text\\":\\"fake error ")
                              .append(i + 1).append(" against ").append(profile).append("\\"},\\"expression\\":[\\"Patient.name\\"]}");
                    }
                    if (errors == 0) {
                        issues.append("{\\"severity\\":\\"information\\",\\"code\\":\\"informational\\",\\"details\\":{\\"text\\":\\"All OK\\"}}");
                    }
                    return "{\\"resourceType\\":\\"OperationOutcome\\",\\"issue\\":[" + issues + "]}";
                }

                static synchronized void log(String line) {
                    String file = System.getProperty("FAKE_VALIDATOR_LOG", System.getenv("FAKE_VALIDATOR_LOG"));
                    if (file == null) return;
                    try {
                        Files.writeString(Path.of(file), line + "\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            """.formatted(SILENT_MARKER, INVALID_MARKER, null, CRASH_MARKER, UNRESOLVABLE_PROFILE);
}
