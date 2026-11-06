package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.model.Body;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

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
public record FhirResponse(int status, @Nonnull String reason, @Nonnull String httpVersion,
                           @Nonnull HttpHeaders headers, @Nonnull Body body) {

    public FhirResponse(int status, @Nullable String reason, @Nonnull String httpVersion,
                        @Nullable HttpHeaders headers, @Nullable Body body) {
        reason = reason == null ? "" : reason;
        headers = headers == null ? HttpHeaders.empty() : headers;
        body = body == null ? Body.of(new byte[0], headers.first("Content-Type").orElse(null)) : body;
        this.status = status;
        this.reason = reason;
        this.httpVersion = httpVersion;
        this.headers = headers;
        this.body = body;
    }

    /** A response with a text body, its format from the {@code Content-Type} header or the text. */
    @Nonnull
    public static FhirResponse text(int status, @Nullable String reason, @Nonnull String httpVersion,
                                    @Nullable HttpHeaders headers, @Nullable String body) {
        return new FhirResponse(status, reason, httpVersion, headers, textBody(headers, body));
    }

    /** A response as received: the bytes, with charset and format from the {@code Content-Type} header. */
    @Nonnull
    public static FhirResponse of(int status, @Nullable String reason, @Nonnull String httpVersion,
                                  @Nullable HttpHeaders headers, @Nullable byte[] body) {
        HttpHeaders received = headers == null ? HttpHeaders.empty() : headers;
        return new FhirResponse(status, reason, httpVersion, received,
                Body.of(body, received.first("Content-Type").orElse(null)));
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    @Nonnull
    public String contentType() {
        return headers.first("Content-Type").orElse("");
    }

    /** The body as text, decoded with the charset of the {@code Content-Type}; empty for none. */
    @Nonnull
    public String bodyText() {
        return body.text();
    }

    private static Body textBody(HttpHeaders headers, String body) {
        String contentType = headers == null ? null : headers.first("Content-Type").orElse(null);
        return Body.of(body == null ? "" : body, contentType);
    }
}
