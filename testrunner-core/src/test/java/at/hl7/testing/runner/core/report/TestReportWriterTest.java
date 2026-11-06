package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;
import at.hl7.testing.runner.core.spi.ReadCheck;
import at.hl7.testing.runner.core.spi.TestReportContent;
import at.hl7.testing.runner.core.spi.TestScriptRead;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestReportWriterTest {

    @TempDir
    Path dir;

    /** Records what it is asked to encode; writes the TestReport's id as content. */
    static final class RecordingSupport implements FhirVersionSupport {
        final List<TestReportContent> encoded = new ArrayList<>();

        @Override
        public FhirVersion version() {
            return FhirVersion.R4;
        }

        @Override
        public ReadCheck check(Body content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TestScriptRead readTestScript(Body content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FhirPathEvaluator fhirPath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Body testReport(TestReportContent content) {
            encoded.add(content);
            return Body.json("{\"id\":\"" + content.id() + "\"}");
        }
    }

    static ActionResult operation(String requestId) {
        return new ActionResult(ActionResult.OPERATION, "create Patient (POST)", Outcome.PASS, null, requestId, false);
    }

    static ActionResult assertion(Outcome outcome, String message, boolean warning) {
        return new ActionResult(ActionResult.ASSERT, "responseCode '201'", outcome, message, "REQ-0001", warning);
    }

    static PhaseResult phase(PhaseKind kind, String name, Outcome outcome, List<ActionResult> actions, String... messages) {
        return new PhaseResult(kind, name, outcome, Duration.ZERO, 0, actions, List.of(messages));
    }

    static TestScriptResult script(FhirVersion version, String server, PhaseResult... phases) {
        return new TestScriptResult(3, "suite", "TS", "http://example.org/TestScript/ts", Path.of("Test_Scripts/ts.json"),
                version, server == null ? List.of() : List.of(server), Instant.parse("2026-10-26T10:00:00Z"),
                Duration.ofSeconds(1), List.of(phases));
    }

    RunResult run(TestScriptResult... scripts) {
        return new RunResult("run_2026-10-26_10-00-00-000", dir, Instant.parse("2026-10-26T10:00:00Z"), Duration.ZERO,
                List.of(scripts), Map.of());
    }

    @Test
    void mapsOutcomesWarningsScoreAndCommunicationFolders() throws Exception {
        Files.createDirectories(dir.resolve("communication/03_ts/REQ-0001_POST_Patient"));
        RecordingSupport support = new RecordingSupport();
        TestScriptResult result = script(FhirVersion.R4, "http://server/fhir",
                phase(PhaseKind.SETUP, "Setup", Outcome.PASS, List.of(operation("REQ-0001")),
                        "WARNING: Ignored while reading the TestScript: unknown element 'stopTestOnFail'"),
                phase(PhaseKind.TEST, "warned", Outcome.PASS,
                        List.of(assertion(Outcome.PASS, "Assert failed: x - Expected '201'", true))),
                phase(PhaseKind.TEST, "failed", Outcome.FAIL,
                        List.of(assertion(Outcome.FAIL, "Assert failed: y", false)), "Assert failed: y"),
                phase(PhaseKind.TEST, "skipped", Outcome.SKIP, List.of(), "Skipped: the setup did not pass"),
                phase(PhaseKind.TEARDOWN, "Teardown", Outcome.ERROR, List.of(),
                        "Autodelete of fixture 'p' returned HTTP 500 [REQ-0009]"));

        Path folder = new TestReportWriter(new FhirVersionRegistry(List.of(support))).write(run(result), dir);

        assertThat(folder.resolve("TestReport-03_ts.json")).content().as("a FHIR id has no _").contains("run-2026-10-26-10-00-00-000-03");
        TestReportContent report = support.encoded.getFirst();
        assertThat(report.identifier()).isEqualTo("run_2026-10-26_10-00-00-000/03");
        assertThat(report.testScript()).isEqualTo("http://example.org/TestScript/ts");
        assertThat(report.result()).isEqualTo("fail");
        assertThat(report.score()).as("one of three tests passed").isEqualByComparingTo("33.33");
        assertThat(report.servers()).containsExactly("http://server/fhir");
        assertThat(report.setup()).extracting(TestReportContent.Action::result).containsExactly("warning", "pass");
        assertThat(report.setup().get(1).detail()).isEqualTo("communication/03_ts/REQ-0001_POST_Patient");
        assertThat(report.tests()).extracting(t -> t.actions().getFirst().result())
                .containsExactly("warning", "fail", "skip");
        assertThat(report.tests().get(2).actions().getFirst().message()).isEqualTo("Skipped: the setup did not pass");
        assertThat(report.teardown()).singleElement().satisfies(a -> {
            assertThat(a.result()).isEqualTo("error");
            assertThat(a.message()).contains("Autodelete");
        });
    }

    @Test
    void skippedTestScriptIsPendingAndUnknownVersionsGetNoReport() throws Exception {
        RecordingSupport support = new RecordingSupport();
        TestScriptResult skipped = script(FhirVersion.R4, null,
                phase(PhaseKind.SETUP, "Setup", Outcome.SKIP, List.of(), "The server does not support ..."),
                phase(PhaseKind.TEST, "t", Outcome.SKIP, List.of(), "Skipped: the server lacks a required capability"));
        TestScriptResult unreadable = script(null, null, phase(PhaseKind.LOAD, "Load", Outcome.ERROR, List.of(), "x"));

        Path folder = new TestReportWriter(new FhirVersionRegistry(List.of(support))).write(run(skipped, unreadable), dir);

        assertThat(support.encoded).singleElement().satisfies(r -> {
            assertThat(r.result()).isEqualTo("pending");
            assertThat(r.score()).isEqualByComparingTo("0");
            assertThat(r.setup()).singleElement().extracting(TestReportContent.Action::result).isEqualTo("skip");
        });
        try (var files = Files.list(folder)) {
            assertThat(files).hasSize(1);
        }
    }
}
