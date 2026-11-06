package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.RequestHeader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.withHeaders;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run leaves in its folder: one communication folder per request, credentials masked, the reports, the HTML
 * report linking into the communication log.
 */
class RunFolderTest extends EngineTestBase {

    @Test
    void communicationLogHasOneFolderPerRequest() throws Exception {
        harness.add("ts", script().test(test("t", op("capabilities"), op("search", "Patient", null, null, null, null))).build());

        var run = harness.run();

        Path folder = run.runDirectory().resolve("communication/01_ts");
        assertThat(folder.resolve("REQ-0001_GET_metadata/request.http")).content().startsWith("GET " + server.baseUrl() + "/metadata HTTP/1.1");
        assertThat(folder.resolve("REQ-0001_GET_metadata/response.http")).content().startsWith("HTTP/1.1 200 OK")
                .contains("CapabilityStatement");
        assertThat(folder.resolve("REQ-0002_GET_Patient/response.http")).exists();
        assertThat(run.reportFiles()).containsOnlyKeys("junit", "summary", "json");
        assertThat(run.reportFiles().get("junit")).content().contains("<testsuite name=\"TS\"", "assertions=\"0\"");
    }

    @Test
    void htmlReportLinksTheRequestsToTheirCommunicationFolders() throws Exception {
        harness.formats("html").add("ts", script().test(test("t", op("capabilities"), responseCode("404"))).build());

        var run = harness.run();

        Path html = run.reportFiles().get("html");
        assertThat(html).isEqualTo(run.runDirectory().resolve("report.html"));
        assertThat(html).content().contains("<a href=\"communication/01_ts/REQ-0001_GET_metadata/\">REQ-0001</a>",
                "<a href=\"communication/01_ts/REQ-0001_GET_metadata/response.http\">response</a>",
                "Expected &#39;404&#39;, got &#39;200&#39;", "<span class=\"badge fail\">FAIL</span>");
        assertThat(run.runDirectory().resolve("communication/01_ts/REQ-0001_GET_metadata/response.http")).exists();
        assertThat(Files.readString(html)).doesNotContain("<script");
    }

    @Test
    void communicationLogMasksCredentialsByDefault() throws Exception {
        harness.add("ts", script().test(test("t", withHeaders(op("capabilities"),
                new RequestHeader("Authorization", "Basic dXNlcjpzZWNyZXQ="))))
                .build());

        var run = harness.run();

        assertThat(run.runDirectory().resolve("communication/01_ts/REQ-0001_GET_metadata/request.http")).content()
                .contains("Authorization: Basic ***").doesNotContain("dXNlcjpzZWNyZXQ=");
        assertThat(server.requests().getFirst().header("Authorization")).isEqualTo("Basic dXNlcjpzZWNyZXQ=");
    }
}
