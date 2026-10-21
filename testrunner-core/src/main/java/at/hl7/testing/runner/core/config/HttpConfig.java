package at.hl7.testing.runner.core.config;

import java.time.Duration;

/**
 * Settings of the HTTP client that talks to the FHIR server.
 *
 * @param connectTimeout  default 10 s
 * @param requestTimeout  default 60 s
 * @param followRedirects default true
 */
public record HttpConfig(Duration connectTimeout, Duration requestTimeout, Boolean followRedirects) {

    public HttpConfig {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        followRedirects = followRedirects == null || followRedirects;
    }

    public static HttpConfig defaults() {
        return new HttpConfig(null, null, null);
    }
}
