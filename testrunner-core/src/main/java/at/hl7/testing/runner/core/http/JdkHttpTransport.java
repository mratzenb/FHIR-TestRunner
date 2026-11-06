package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.config.HttpConfig;
import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

/**
 * {@link HttpTransport} using the JDK's {@link HttpClient}. Thread-safe; one instance serves all TestScripts of a
 * run, including those running in parallel.
 */
public final class JdkHttpTransport implements HttpTransport, AutoCloseable {

    /** Headers the JDK client sets itself and refuses to take from the caller. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;
    private final HttpConfig config;

    public JdkHttpTransport(@Nonnull HttpConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(config.followRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    @Nonnull
    public FhirResponse send(@Nonnull FhirRequest request) throws IOException {
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body().bytes());
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(config.requestTimeout())
                .method(request.method().name(), body);
        request.headers().asMap().forEach((name, values) -> {
            if (!RESTRICTED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        try {
            // bytes, not text: a Binary in its native content type (a PDF, an image) must arrive unchanged
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            String version = response.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
            return FhirResponse.of(response.statusCode(), ReasonPhrases.of(response.statusCode()), version,
                    HttpHeaders.of(response.headers().map()), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + request.method() + " " + request.uri(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
