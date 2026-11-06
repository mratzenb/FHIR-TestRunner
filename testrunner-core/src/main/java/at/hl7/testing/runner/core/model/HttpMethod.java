package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;

import java.util.Locale;

/** The HTTP methods a TestScript operation can use ({@code TestScript...operation.method}). */
public enum HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS;

    /** Reads a method code such as {@code get} or {@code POST}. */
    @Nonnull
    public static HttpMethod parse(@Nonnull String code) {
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown HTTP method '" + code + "'", e);
        }
    }

    /** Whether a request with this method carries the fixture named by {@code sourceId} as body. */
    public boolean sendsBody() {
        return this == POST || this == PUT || this == PATCH;
    }
}
