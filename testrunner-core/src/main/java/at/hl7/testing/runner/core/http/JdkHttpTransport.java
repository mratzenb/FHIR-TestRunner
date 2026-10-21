package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.config.HttpConfig;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    public JdkHttpTransport(HttpConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(config.followRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public FhirResponse send(FhirRequest request) throws IOException {
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(config.requestTimeout())
                .method(request.method().name(), body);
        request.headers().asMap().forEach((name, values) -> {
            if (!RESTRICTED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String version = response.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
            return new FhirResponse(response.statusCode(), ReasonPhrases.of(response.statusCode()), version,
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
