package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The FHIR server the TestScripts are run against.
 *
 * @param baseUrl base URL, e.g. {@code http://localhost:8080/fhir}; {@code null} skips every TestScript
 * @param headers headers added to every request, e.g. {@code Authorization}
 * @param oauth2  get an access token with the OAuth 2.0 client credentials grant and send it with every request;
 *                {@code null} for none
 */
public record FhirServerConfig(@Nullable URI baseUrl, @Nonnull Map<String, String> headers,
                               @Nullable OAuth2Config oauth2) {

    public FhirServerConfig(@Nullable URI baseUrl, @Nullable Map<String, String> headers,
                            @Nullable OAuth2Config oauth2) {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.oauth2 = oauth2;
    }

    @Nonnull
    public static FhirServerConfig of(@Nullable String baseUrl) {
        return new FhirServerConfig(baseUrl == null ? null : URI.create(baseUrl), Map.of(), null);
    }

    /** A copy that gets its access token with {@code newOAuth2}. */
    @Nonnull
    public FhirServerConfig withOAuth2(@Nullable OAuth2Config newOAuth2) {
        return new FhirServerConfig(baseUrl, headers, newOAuth2);
    }

    /** The problems of this server's settings, each naming its property below {@code prefix}. */
    List<String> problems(String prefix) {
        List<String> problems = new ArrayList<>();
        if (oauth2 != null) {
            problems.addAll(oauth2.problems(prefix + ".oauth2"));
            if (headers.keySet().stream().anyMatch("Authorization"::equalsIgnoreCase)) {
                problems.add(prefix + ".headers.Authorization and " + prefix + ".oauth2 are both set; the OAuth2"
                        + " access token is sent as Authorization header, so remove one of them");
            }
        }
        return problems;
    }

    /** Base URL without a trailing slash; empty if none is configured. */
    @Nonnull
    public Optional<String> base() {
        if (baseUrl == null) {
            return Optional.empty();
        }
        String value = baseUrl.toString();
        return Optional.of(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }
}
