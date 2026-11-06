package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.HttpMethod;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.util.Objects;

/**
 * A request to the FHIR server.
 *
 * @param method  the method
 * @param uri     the complete URL
 * @param headers the headers
 * @param body    the body as sent, its bytes and charset; {@code null} for none
 */
public record FhirRequest(@Nonnull HttpMethod method, @Nonnull URI uri, @Nonnull HttpHeaders headers,
                          @Nullable Body body) {

    public FhirRequest(@Nonnull HttpMethod method, @Nonnull URI uri, @Nullable HttpHeaders headers,
                       @Nullable Body body) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        headers = headers == null ? HttpHeaders.empty() : headers;
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.body = body;
    }

    /** A copy with these headers. */
    @Nonnull
    public FhirRequest withHeaders(@Nonnull HttpHeaders newHeaders) {
        return new FhirRequest(method, uri, newHeaders, body);
    }
}
