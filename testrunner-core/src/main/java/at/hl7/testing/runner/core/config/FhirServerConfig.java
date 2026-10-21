package at.hl7.testing.runner.core.config;

import java.net.URI;
import java.util.Map;

/**
 * The FHIR server the TestScripts are run against.
 *
 * @param baseUrl base URL, e.g. {@code http://localhost:8080/fhir}; {@code null} skips every TestScript
 * @param headers headers added to every request, e.g. {@code Authorization}
 */
public record FhirServerConfig(URI baseUrl, Map<String, String> headers) {

    public FhirServerConfig {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static FhirServerConfig of(String baseUrl) {
        return new FhirServerConfig(baseUrl == null ? null : URI.create(baseUrl), Map.of());
    }

    /** Base URL without a trailing slash, or {@code null}. */
    public String base() {
        if (baseUrl == null) {
            return null;
        }
        String value = baseUrl.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
