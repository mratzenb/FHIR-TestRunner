package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.model.FhirVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportWriterTest {

    /** What a server or a TestScript author could put into a message, a name or a description. */
    static final String HOSTILE = "</pre><script>alert('x')</script><img src=x onerror=\"alert(1)\">&amp;";

    @TempDir
    Path dir;

    static TestScriptResult script(int index, String name, PhaseResult... phases) {
        return new TestScriptResult(index, "suite", name, "javascript:alert(1)", Path.of("Test_Scripts/ts.json"),
                FhirVersion.R4, "http://localhost:8080/fhir", Instant.parse("2026-11-06T10:00:00Z"),
                Duration.ofMillis(1200), List.of(phases));
    }

    static PhaseResult phase(PhaseKind kind, String name, Outcome outcome, List<ActionResult> actions,
                             String... messages) {
        return new PhaseResult(kind, name, outcome, Duration.ofMillis(5), 1, actions, List.of(messages));
    }

    RunResult run(TestScriptResult... scripts) {
        return new RunResult("run_2026-11-06_10-00-00-000", dir, Instant.parse("2026-11-06T10:00:00Z"),
                Duration.ofSeconds(2), List.of(scripts), Map.of());
    }

    private static List<String> all(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results().map(r -> r.group(1)).toList();
    }

    @Test
    void escapesEverythingTakenFromTestScriptsAndResponses() throws Exception {
        RunResult run = run(script(1, HOSTILE,
                phase(PhaseKind.TEST, HOSTILE, Outcome.FAIL, List.of(
                        new ActionResult(ActionResult.ASSERT, HOSTILE, Outcome.FAIL, "Expected '1', got '" + HOSTILE + "'",
                                "REQ-0001", false)), "Expected '1', got '" + HOSTILE + "'", "WARNING: " + HOSTILE)));

        String html = Files.readString(new HtmlReportWriter().write(run, dir));

        assertThat(html).doesNotContain("<script", "<img", "onerror=\"", "</pre><script")
                .contains("&lt;/pre&gt;&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;", "&amp;amp;")
                // the page itself forbids scripts, whatever slips through
                .contains("content=\"default-src 'none'; style-src 'unsafe-inline'\"")
                // a TestScript url is text, never a link
                .contains("<dd>javascript:alert(1)</dd>").doesNotContain("href=\"javascript");
        assertThat(HtmlReportWriter.escape("a\u0000b\u0007c\nd")).isEqualTo("a�b�c\nd");
    }

    @Test
    void linksEveryRequestIdToItsFolderInTheCommunicationLog() throws Exception {
        Path exchange = Files.createDirectories(dir.resolve("communication/01_ts/REQ-0001_POST_Patient"));
        Files.writeString(exchange.resolve("request.http"), "POST …");
        Files.writeString(exchange.resolve("response.http"), "HTTP/1.1 201 Created");
        Path failed = Files.createDirectories(dir.resolve("communication/01_ts/REQ-0002_GET_Patient_a b"));
        Files.writeString(failed.resolve("error.txt"), "No response received.");
        RunResult run = run(script(1, "TS",
                phase(PhaseKind.SETUP, "Setup", Outcome.PASS, List.of(
                        new ActionResult(ActionResult.OPERATION, "create Patient (POST)", Outcome.PASS, null, "REQ-0001", false))),
                phase(PhaseKind.TEST, "read", Outcome.ERROR, List.of(
                        new ActionResult(ActionResult.OPERATION, "read Patient (GET)", Outcome.ERROR, "no response", "REQ-0002", false),
                        new ActionResult(ActionResult.ASSERT, "responseCode '200'", Outcome.ERROR, "not sent", "REQ-0003", false)),
                        "no response")));

        String html = Files.readString(new HtmlReportWriter().write(run, dir));

        List<String> links = all(Pattern.compile("href=\"([^\"#][^\"]*)\""), html);
        assertThat(links).containsExactly(
                "communication/01_ts/REQ-0001_POST_Patient/",
                "communication/01_ts/REQ-0001_POST_Patient/request.http",
                "communication/01_ts/REQ-0001_POST_Patient/response.http",
                "communication/01_ts/REQ-0002_GET_Patient_a%20b/",
                "communication/01_ts/REQ-0002_GET_Patient_a%20b/error.txt");
        // every link points to something that exists in the run folder
        assertThat(links).allSatisfy(link -> assertThat(dir.resolve(link.replace("%20", " "))).exists());
        // a request without a folder (communication log off, not sent) is shown, not linked
        assertThat(html).contains("<td>REQ-0003</td>");
    }

    @Test
    void showsTheOutcomesAndCountsAndOpensWhatDidNotPass() throws Exception {
        RunResult run = run(
                script(1, "Passing", phase(PhaseKind.TEST, "t", Outcome.PASS, List.of())),
                script(2, "Failing", phase(PhaseKind.SETUP, "Setup", Outcome.PASS, List.of(), "WARNING: content ignored"),
                        phase(PhaseKind.TEST, "t", Outcome.FAIL, List.of(), "Expected 200, got 404")),
                script(3, "Skipped", phase(PhaseKind.LOAD, "Load", Outcome.SKIP, List.of(), "Not started: fail-fast")));

        String html = Files.readString(new HtmlReportWriter().write(run, dir));

        assertThat(html).contains("<title>run_2026-11-06_10-00-00-000 – FAIL</title>",
                "3 TestScript(s): 1 passed, 1 failed, 0 errors, 1 skipped",
                "<section class=\"ts only-problems-hidden\" id=\"ts-1\">\n<details>",
                "<section class=\"ts\" id=\"ts-2\">\n<details open>",
                "<li class=\"warning\"><pre>WARNING: content ignored</pre></li>",
                "<li><pre>Expected 200, got 404</pre></li>",
                "<span class=\"badge skip\">SKIP</span>", "<a href=\"#ts-3\">Skipped</a>");
    }
}
