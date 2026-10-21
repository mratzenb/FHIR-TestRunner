package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.model.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationLogTest {

    static final FhirRequest REQUEST = new FhirRequest(HttpMethod.PUT, URI.create("http://s/fhir/Patient/123?x=1"),
            HttpHeaders.of(Map.of("Content-Type", List.of("application/fhir+json"))), "{\"resourceType\":\"Patient\"}");

    @Test
    void writesRequestAndResponseUnfiltered(@TempDir Path dir) {
        CommunicationLog log = CommunicationLog.toFolder(dir, "01_My TestScript", "http://s/fhir");
        FhirResponse response = new FhirResponse(201, "Created", "HTTP/1.1",
                HttpHeaders.of(Map.of("Set-Cookie", List.of("a=1", "b=2"))), "{}");

        String where = log.record("REQ-0007", REQUEST, response);

        assertThat(where).isEqualTo("01_My_TestScript/REQ-0007_PUT_Patient_123");
        assertThat(dir.resolve(where).resolve("request.http")).content()
                .isEqualTo("PUT http://s/fhir/Patient/123?x=1 HTTP/1.1\nContent-Type: application/fhir+json\n\n{\"resourceType\":\"Patient\"}");
        assertThat(dir.resolve(where).resolve("response.http")).content()
                .isEqualTo("HTTP/1.1 201 Created\nSet-Cookie: a=1\nSet-Cookie: b=2\n\n{}");
    }

    @Test
    void failureIsWrittenAsErrorText(@TempDir Path dir) {
        String where = CommunicationLog.toFolder(dir, "01_ts", "http://s/fhir")
                .recordFailure("REQ-0001", REQUEST, new java.net.ConnectException("refused"));

        assertThat(dir.resolve(where).resolve("error.txt")).content().contains("No response received", "refused");
        assertThat(dir.resolve(where).resolve("response.http")).doesNotExist();
    }

    @Test
    void withoutFolderNothingIsWritten() {
        assertThat(CommunicationLog.none().record("REQ-0001", REQUEST, new FhirResponse(200, "", "HTTP/1.1", null, ""))).isNull();
    }
}
