package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.time.Duration;

/**
 * Settings of the HTTP client that talks to the FHIR server.
 *
 * @param connectTimeout  default 10 s
 * @param requestTimeout  default 60 s
 * @param followRedirects default true
 */
public record HttpConfig(@Nonnull Duration connectTimeout, @Nonnull Duration requestTimeout,
                         @Nonnull Boolean followRedirects) {

    public HttpConfig(@Nullable Duration connectTimeout, @Nullable Duration requestTimeout,
                      @Nullable Boolean followRedirects) {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        followRedirects = followRedirects == null || followRedirects;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.followRedirects = followRedirects;
    }

    @Nonnull
    public static HttpConfig defaults() {
        return new HttpConfig(null, null, null);
    }
}
