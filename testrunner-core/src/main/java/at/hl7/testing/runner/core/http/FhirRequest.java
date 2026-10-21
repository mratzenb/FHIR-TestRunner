package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.model.HttpMethod;

import java.net.URI;
import java.util.Objects;

/**
 * A request to the FHIR server.
 *
 * @param method  the method
 * @param uri     the complete URL
 * @param headers the headers
 * @param body    the body, {@code null} for none
 */
public record FhirRequest(HttpMethod method, URI uri, HttpHeaders headers, String body) {

    public FhirRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        headers = headers == null ? HttpHeaders.empty() : headers;
    }
}
