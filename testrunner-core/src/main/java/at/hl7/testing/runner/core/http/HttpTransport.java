package at.hl7.testing.runner.core.http;

import jakarta.annotation.Nonnull;

import java.io.IOException;

/** Sends a request and returns the response; the replaceable network layer below {@link FhirHttpClient}. */
@FunctionalInterface
public interface HttpTransport {

    /**
     * @throws IOException if no response was received
     */
    @Nonnull
    FhirResponse send(@Nonnull FhirRequest request) throws IOException;
}
