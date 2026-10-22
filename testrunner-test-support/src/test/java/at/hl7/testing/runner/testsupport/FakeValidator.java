package at.hl7.testing.runner.testsupport;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static at.hl7.testing.runner.testsupport.FakeValidatorJar.CRASH_MARKER;
import static at.hl7.testing.runner.testsupport.FakeValidatorJar.INVALID_MARKER;
import static at.hl7.testing.runner.testsupport.FakeValidatorJar.SILENT_MARKER;
import static at.hl7.testing.runner.testsupport.FakeValidatorJar.UNRESOLVABLE_PROFILE;

/**
 * The main class of the jar built by {@link FakeValidatorJar}; runs in a process of its own, like
 * {@code validator_cli.jar}. What it answers is described there.
 */
final class FakeValidator {

    private FakeValidator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("server")) {
            server(Integer.parseInt(args[1]), Arrays.copyOfRange(args, 2, args.length));
            return;
        }
        String input = null;
        String output = null;
        String profile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-output" -> output = args[++i];
                case "-profile" -> profile = args[++i];
                case "-version", "-tx", "-ig", "-locale" -> i++;
                default -> {
                    if (!args[i].startsWith("-")) {
                        input = args[i];
                    }
                }
            }
        }
        System.out.println("FHIR Validation tool (fake) " + String.join(" ", args));
        String content = Files.readString(Path.of(input));
        log("process " + profile);
        if (content.contains(SILENT_MARKER)) {
            System.err.println("simulated validator failure");
            System.exit(1);
        }
        Files.writeString(Path.of(output), outcome(content, profile));
        System.out.println("Done");
    }

    private static void server(int port, String[] engineArguments) throws Exception {
        String delay = setting("FAKE_VALIDATOR_STARTUP_MILLIS");
        if (delay != null) {
            Thread.sleep(Long.parseLong(delay));
        }
        if (setting("FAKE_VALIDATOR_SERVER_FAILS") != null) {
            System.out.println("simulated start-up failure");
            System.exit(3);
        }
        log("start " + String.join(" ", engineArguments));
        // Jetty logs through SLF4J; the fake's class path has no provider, so it would print a warning
        System.setProperty("slf4j.provider", "org.slf4j.helpers.NOP_FallbackServiceProvider");
        System.setProperty("slf4j.internal.verbosity", "WARN");
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(InetAddress.getLoopbackAddress().getHostAddress());
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                return switch (request.getHttpURI().getPath()) {
                    case "/validateResource" -> FakeValidator.validate(request, response, callback);
                    case "/stop" -> FakeValidator.stop(response, callback);
                    default -> false;
                };
            }
        });
        server.start();
        System.out.println("FHIR Validator HTTP Service (fake) started on port " + port);
    }

    private static boolean validate(Request request, Response response, Callback callback) throws IOException {
        String query = request.getHttpURI().getQuery();
        String profile = query == null ? null
                : URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
        String content = Content.Source.asString(request, StandardCharsets.UTF_8);
        log("validate " + profile + " " + request.getHeaders().get("Content-Type"));
        if (content.contains(CRASH_MARKER)) {
            System.out.println("simulated crash");
            Runtime.getRuntime().halt(9);
        }
        boolean unresolvable = UNRESOLVABLE_PROFILE.equals(profile);
        String body = unresolvable
                ? "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"exception\","
                        + "\"details\":{\"text\":\"Validation failed: Unable to resolve profile " + profile + "\"}}]}"
                : outcome(content, profile);
        response.setStatus(unresolvable ? 500 : 200);
        response.getHeaders().put("Content-Type", "application/fhir+json");
        Content.Sink.write(response, true, body, callback);
        return true;
    }

    /** Answers, then ends the process once the answer is sent. */
    private static boolean stop(Response response, Callback callback) {
        log("stop");
        response.setStatus(200);
        response.write(true, null, Callback.from(() -> {
            callback.succeeded();
            Thread.ofPlatform().start(() -> System.exit(0));
        }, callback::failed));
        return true;
    }

    private static String outcome(String content, String profile) {
        int errors = content.split(INVALID_MARKER, -1).length - 1;
        StringBuilder issues = new StringBuilder();
        for (int i = 0; i < errors; i++) {
            if (i > 0) {
                issues.append(',');
            }
            issues.append("{\"severity\":\"error\",\"code\":\"structure\",\"details\":{\"text\":\"fake error ")
                    .append(i + 1).append(" against ").append(profile).append("\"},\"expression\":[\"Patient.name\"]}");
        }
        if (errors == 0) {
            issues.append("{\"severity\":\"information\",\"code\":\"informational\",\"details\":{\"text\":\"All OK\"}}");
        }
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[" + issues + "]}";
    }

    /** A system property, else the environment variable of the same name. */
    private static String setting(String name) {
        return System.getProperty(name, System.getenv(name));
    }

    private static synchronized void log(String line) {
        String file = setting("FAKE_VALIDATOR_LOG");
        if (file == null) {
            return;
        }
        try {
            Files.writeString(Path.of(file), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
