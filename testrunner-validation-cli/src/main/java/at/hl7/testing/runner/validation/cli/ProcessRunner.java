package at.hl7.testing.runner.validation.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs an external process; replaceable in tests. */
@FunctionalInterface
interface ProcessRunner {

    /**
     * @param output stdout and stderr, merged
     */
    record Result(int exitCode, String output, boolean timedOut) {
    }

    Result run(List<String> command, Path workDir, Duration timeout) throws IOException;

    ProcessRunner DEFAULT = (command, workDir, timeout) -> {
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "<output could not be read: " + e.getMessage() + ">";
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output.getNow(""), true);
            }
            return new Result(process.exitValue(), output.join(), false);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the FHIR validator", e);
        }
    };
}
