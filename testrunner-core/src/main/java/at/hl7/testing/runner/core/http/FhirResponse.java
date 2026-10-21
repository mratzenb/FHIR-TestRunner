package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.model.Body;

/**
 * A response of the FHIR server.
 *
 * @param status      the HTTP status
 * @param reason      the reason phrase, may be empty (HTTP/2 has none)
 * @param httpVersion e.g. {@code HTTP/1.1}
 * @param headers     the headers
 * @param body        the body as text, empty for none
 */
public record FhirResponse(int status, String reason, String httpVersion, HttpHeaders headers, String body) {

    public FhirResponse {
        reason = reason == null ? "" : reason;
        body = body == null ? "" : body;
        headers = headers == null ? HttpHeaders.empty() : headers;
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    public String contentType() {
        return headers.first("Content-Type").orElse("");
    }

    /** The body with its format taken from the {@code Content-Type} header. */
    public Body asBody() {
        return Body.of(body, contentType());
    }
}
