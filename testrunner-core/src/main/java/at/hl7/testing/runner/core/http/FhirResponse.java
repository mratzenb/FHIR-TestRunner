package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.model.Body;

/**
 * A response of the FHIR server.
 *
 * @param status      the HTTP status
 * @param reason      the reason phrase, may be empty (HTTP/2 has none)
 * @param httpVersion e.g. {@code HTTP/1.1}
 * @param headers     the headers
 * @param body        the body as received: its bytes, the charset and format the {@code Content-Type} names; empty
 *                    for none
 */
public record FhirResponse(int status, String reason, String httpVersion, HttpHeaders headers, Body body) {

    public FhirResponse {
        reason = reason == null ? "" : reason;
        headers = headers == null ? HttpHeaders.empty() : headers;
        body = body == null ? Body.of(new byte[0], headers.first("Content-Type").orElse(null)) : body;
    }

    /** A response with a text body, its format from the {@code Content-Type} header or the text. */
    public static FhirResponse text(int status, String reason, String httpVersion, HttpHeaders headers, String body) {
        return new FhirResponse(status, reason, httpVersion, headers, textBody(headers, body));
    }

    /** A response as received: the bytes, with charset and format from the {@code Content-Type} header. */
    public static FhirResponse of(int status, String reason, String httpVersion, HttpHeaders headers, byte[] body) {
        HttpHeaders received = headers == null ? HttpHeaders.empty() : headers;
        return new FhirResponse(status, reason, httpVersion, received,
                Body.of(body, received.first("Content-Type").orElse(null)));
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    public String contentType() {
        return headers.first("Content-Type").orElse("");
    }

    /** The body as text, decoded with the charset of the {@code Content-Type}; empty for none. */
    public String bodyText() {
        return body.text();
    }

    private static Body textBody(HttpHeaders headers, String body) {
        String contentType = headers == null ? null : headers.first("Content-Type").orElse(null);
        return Body.of(body == null ? "" : body, contentType);
    }
}
