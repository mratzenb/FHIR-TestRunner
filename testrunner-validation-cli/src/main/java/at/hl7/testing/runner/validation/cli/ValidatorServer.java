package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One HL7 validator running as HTTP server ({@code java -jar validator_cli.jar server <port> ...}) for one FHIR
 * version, one profiles folder and one list of IGs.
 *
 * <p>Started on first use. Once it has failed – it could not start, or it stopped by itself – it stays failed and
 * every call throws {@link ServerFailedException}; it is not restarted, so a validator that crashes on some input does
 * not end up in a restart loop. The validator binds to the loopback interface only and handles one request at a time.
 */
final class ValidatorServer {

    private static final Logger log = LoggerFactory.getLogger(ValidatorServer.class);
    private static final int OUTPUT_LINES_KEPT = 40;
    private static final int START_ATTEMPTS = 2;

    private final ValidationConfig config;
    private final FhirVersion version;
    private final Path profilesDir;
    private final List<String> igs;
    private final HttpClient http;
    private final Deque<String> output = new ArrayDeque<>();
    private Process process;
    private URI base;
    private String failure;

    ValidatorServer(ValidationConfig config, FhirVersion version, Path profilesDir, List<String> igs, HttpClient http) {
        this.config = config;
        this.version = version;
        this.profilesDir = profilesDir;
        this.igs = igs;
        this.http = http;
    }

    /**
     * Validates with this server, starting it if necessary.
     *
     * @throws ServerFailedException        if the server cannot be started or stopped by itself
     * @throws ValidatorUnavailableException if the server is running but could not validate this resource
     */
    ValidationResult validate(ValidationRequest request) {
        URI endpoint = endpoint(start(), request.profile());
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(config.timeout())
                .header("Content-Type", request.resource().format().fhirMimeType())
                .header("Accept", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(request.resource().text(), StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ValidatorUnavailableException("The validator server " + this + " did not answer within "
                    + config.timeout());
        } catch (IOException e) {
            throw failedOrUnavailable("The request to the validator server " + this + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidatorUnavailableException("Interrupted while waiting for the validator server " + this, e);
        }
        log.debug("Validator server {} answered HTTP {} in {} ms", this, response.statusCode(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        if (response.statusCode() != 200) {
            throw new ValidatorUnavailableException("The validator server " + this + " could not validate the resource"
                    + " (HTTP " + response.statusCode() + "): " + describe(response.body()));
        }
        return OperationOutcomeReader.read(response.body());
    }

    /** Starts the server unless it is running; waits until it accepts connections. */
    synchronized URI start() {
        if (failure != null) {
            throw new ServerFailedException(failure);
        }
        if (process != null) {
            if (process.isAlive()) {
                return base;
            }
            throw markFailed("The validator server " + this + " stopped unexpectedly (exit code " + process.exitValue()
                    + ")");
        }
        for (int attempt = 1; ; attempt++) {
            int port = freePort();
            List<String> command = command(port);
            log.info("Starting the validator server for FHIR {} (profiles {}, IGs {}) on port {}", version,
                    profilesDir == null ? "-" : profilesDir, igs.isEmpty() ? "-" : igs, port);
            log.debug("Validator server command: {}", String.join(" ", command));
            long started = System.nanoTime();
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw markFailed("The validator server could not be started: " + e.getMessage());
            }
            readOutput(process);
            base = URI.create("http://127.0.0.1:" + port);
            String problem = awaitReady(port);
            if (problem == null) {
                log.info("Validator server for FHIR {} ready on {} after {} s", version, base,
                        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started));
                return base;
            }
            boolean portTaken = outputContains("BindException") || outputContains("Address already in use");
            destroy();
            if (!portTaken || attempt == START_ATTEMPTS) {
                throw markFailed("The validator server for FHIR " + version + " could not be started: " + problem);
            }
            log.warn("Port {} was taken, starting the validator server again on another port", port);
            process = null;
        }
    }

    /** Stops the server: asks it to stop, then ends the process if it does not. */
    synchronized void stop() {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            http.send(HttpRequest.newBuilder(base.resolve("/stop")).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                log.info("Validator server {} stopped", this);
                return;
            }
        } catch (IOException e) {
            log.debug("The validator server {} did not take the stop request: {}", this, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        destroy();
        log.info("Validator server {} ended", this);
    }

    /** Ends the process without asking, e.g. in a shutdown hook. */
    void destroy() {
        Process running = process;
        if (running == null || !running.isAlive()) {
            return;
        }
        running.destroy();
        try {
            if (!running.waitFor(5, TimeUnit.SECONDS)) {
                running.destroyForcibly();
            }
        } catch (InterruptedException e) {
            running.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** The process id of the running server, for tests and diagnostics; -1 if none. */
    synchronized long pid() {
        return process != null && process.isAlive() ? process.pid() : -1;
    }

    List<String> command(int port) {
        List<String> command = ValidatorArguments.launcher(config);
        command.add("server");
        command.add(String.valueOf(port));
        command.addAll(ValidatorArguments.engine(config, version, profilesDir, igs));
        return command;
    }

    /** @return {@code null} when the server accepts connections, else why not */
    private String awaitReady(int port) {
        long deadline = System.nanoTime() + config.server().startupTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return "the process ended with exit code " + process.exitValue() + "\n" + outputTail();
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                return null;
            } catch (IOException notYet) {
                sleep(250);
            }
        }
        return "it did not accept connections within " + config.server().startupTimeout()
                + " (testrunner.validation.server.startup-timeout)\n" + outputTail();
    }

    /**
     * A request failed on the connection level: if the server died (it may take a moment to exit), it is marked
     * failed, so the caller can fall back; otherwise only this validation failed.
     */
    private ValidatorUnavailableException failedOrUnavailable(String message, Exception cause) {
        Process running;
        synchronized (this) {
            running = process;
        }
        try {
            if (running != null && running.waitFor(2, TimeUnit.SECONDS)) {
                return markFailed(message + "; the validator server stopped (exit code " + running.exitValue() + ")\n"
                        + outputTail());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ValidatorUnavailableException(message, cause);
    }

    private synchronized ServerFailedException markFailed(String message) {
        failure = message;
        return new ServerFailedException(message);
    }

    /** Forwards the server's console output to the log (DEBUG) and keeps its last lines for error messages. */
    private void readOutput(Process started) {
        Thread.ofPlatform().daemon().name("validator-server-" + version + "-output").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[validator server {}] {}", version, line);
                    synchronized (output) {
                        output.addLast(line);
                        if (output.size() > OUTPUT_LINES_KEPT) {
                            output.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process ended
            }
        });
    }

    private String outputTail() {
        sleep(100); // let the reader catch up with the last lines of a process that just ended
        synchronized (output) {
            return output.isEmpty() ? "(no output)" : "Validator output:\n" + String.join("\n", output);
        }
    }

    private boolean outputContains(String text) {
        synchronized (output) {
            return output.stream().anyMatch(line -> line.contains(text));
        }
    }

    private static URI endpoint(URI base, String profile) {
        String path = "/validateResource";
        if (profile != null) {
            path += "?profile=" + URLEncoder.encode(profile, StandardCharsets.UTF_8);
        }
        return base.resolve(path);
    }

    private static String describe(String body) {
        try {
            String issues = ValidationResult.describe(OperationOutcomeReader.read(body).issues());
            return issues.isEmpty() ? body : issues;
        } catch (ValidatorUnavailableException e) {
            return body.length() > 500 ? body.substring(0, 500) + "..." : body;
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new ServerFailedException("No free local port for the validator server: " + e.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "FHIR " + version + (base == null ? "" : " at " + base);
    }

    /** The server cannot be used: it could not be started, or it stopped by itself. */
    static final class ServerFailedException extends ValidatorUnavailableException {
        ServerFailedException(String message) {
            super(message);
        }
    }
}
