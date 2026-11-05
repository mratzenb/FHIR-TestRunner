package at.hl7.testing.runner.app;

import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2.4 of docs/wiki/next-steps.md: a run that is killed (SIGTERM, as a CI job on its timeout, or Ctrl-C) still
 * tears down and writes its reports. The application runs in a JVM of its own, against a slow in-memory server.
 */
class ShutdownIT {

    @TempDir
    Path dir;
    InMemoryFhirServer server;
    Process process;

    @AfterEach
    void stop() {
        if (process != null) {
            process.destroyForcibly();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void killedRunTearsDownAndWritesItsReports() throws Exception {
        server = InMemoryFhirServer.start("4.0.1").delay(Duration.ofMillis(500));
        process = start("--testrunner.shutdown-grace=20s");

        awaitRequest("POST /Patient");
        process.destroy(); // SIGTERM

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("ended within the grace time").isTrue();
        Path junit = reportOfTheRun("junit.xml");
        assertThat(junit).content().contains("Stopped before the next action: the runner was stopped (Ctrl-C or kill)");
        assertThat(server.requestLines()).as("the teardown ran: the created Patient is deleted")
                .last().asString().startsWith("DELETE /Patient/");
        assertThat(log()).contains("Stopping the run (Ctrl-C or kill)");
    }

    @Test
    void runThatDoesNotEndWithinTheGraceTimeIsReportedWithTheResultsSoFar() throws Exception {
        server = InMemoryFhirServer.start("4.0.1").delay(Duration.ofSeconds(5));
        process = start("--testrunner.shutdown-grace=1s");

        awaitRequest("POST /Patient");
        process.destroy();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(reportOfTheRun("junit.xml")).content()
                .contains("Did not end: the run was aborted before it ended");
        assertThat(log()).contains("The run did not end within 1s; writing the reports with the results so far");
    }

    private Process start(String... extra) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                TestRunnerApplication.class.getName(),
                "--config=" + SampleData.wikiSamples().resolve("first-suite.yml"),
                "--novalidator",
                "--testrunner.fhir-server.base-url=" + server.baseUrl(),
                "--testrunner.suites[0].base-dir=" + SampleData.wikiSamples().resolve("first-suite"),
                "--testrunner.reporting.output-dir=" + dir.resolve("results")));
        command.addAll(List.of(extra));
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(dir.resolve("console.log").toFile()).start();
    }

    private void awaitRequest(String line) throws InterruptedException {
        Instant until = Instant.now().plusSeconds(60);
        while (!server.requestLines().contains(line)) {
            assertThat(Instant.now()).as("waiting for %s; console:%n%s", line, log()).isBefore(until);
            assertThat(process.isAlive()).as("the runner ended early; console:%n%s", log()).isTrue();
            Thread.sleep(50);
        }
    }

    private Path reportOfTheRun(String file) throws IOException {
        try (Stream<Path> runs = Files.list(dir.resolve("results"))) {
            Path run = runs.filter(Files::isDirectory).findFirst().orElseThrow();
            Path report = run.resolve(file);
            assertThat(report).as("console:%n%s", log()).exists();
            return report;
        }
    }

    private String log() {
        try {
            return Files.readString(dir.resolve("console.log"));
        } catch (IOException e) {
            return "(no console output: " + e + ")";
        }
    }
}
