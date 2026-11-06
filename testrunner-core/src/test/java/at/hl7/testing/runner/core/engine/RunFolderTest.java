package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.RequestHeader;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static at.hl7.testing.runner.core.engine.Scripts.check;
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

    /**
     * IHE MHD Retrieve Document: a Binary read in its native content type. The bytes arrive, are kept, are sent again
     * by {@code sourceId} and are written to the communication log unchanged, in a file of their own.
     */
    @Test
    void binaryContentIsKeptSentAndLoggedAsTheBytesExchanged() throws Exception {
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3,
                0, 1, 2, (byte) 0xFF};
        server.on("GET", "/Binary/doc", InMemoryFhirServer.Answer.bytes(200, "application/pdf", pdf));
        server.on("PUT", "/Binary/copy", InMemoryFhirServer.Answer.json(200, "{\"resourceType\":\"Binary\"}"));
        harness.add("ts", script().test(test("t",
                Operation.builder().type("read").resource("Binary").params("/doc").accept("application/pdf")
                        .responseId("document").build(),
                check(new Check.ContentType("application/pdf"), Operator.EQUALS, null),
                check(new Check.HeaderField("Content-Type"), Operator.EQUALS, "application/pdf"),
                Operation.builder().type("update").resource("Binary").params("/copy").contentType("application/pdf")
                        .sourceId("document").build(),
                responseCode("200"))).build());

        var run = harness.run();

        assertThat(run.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requests().get(1).bytes()).as("sent again unchanged").isEqualTo(pdf);
        assertThat(server.requests().get(1).header("Content-Type")).isEqualTo("application/pdf");
        Path read = run.runDirectory().resolve("communication/01_ts/REQ-0001_GET_Binary_doc");
        assertThat(read.resolve("response.body.pdf")).hasBinaryContent(pdf);
        assertThat(read.resolve("response.http")).content().startsWith("HTTP/1.1 200 OK")
                .endsWith("\n\n[17 bytes, not text: response.body.pdf]");
        Path update = run.runDirectory().resolve("communication/01_ts/REQ-0002_PUT_Binary_copy");
        assertThat(update.resolve("request.body.pdf")).hasBinaryContent(pdf);
        assertThat(update.resolve("response.http")).content().endsWith("{\"resourceType\":\"Binary\"}");
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
