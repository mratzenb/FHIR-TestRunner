package at.hl7.testing.runner.core.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the requests of one TestScript execution: assigns each a run-wide request id, adds the configured
 * headers and the OAuth2 access token, logs one line per request and writes the exchange to the
 * {@link CommunicationLog}.
 *
 * <p>The request counter belongs to the run and is shared by all TestScripts, so ids are unique within a run even
 * when TestScripts run in parallel.
 */
public final class FhirHttpClient {

    private static final Logger log = LoggerFactory.getLogger(FhirHttpClient.class);

    private final HttpTransport transport;
    private final AtomicInteger requestCounter;
    private final CommunicationLog communicationLog;
    private final HttpHeaders defaultHeaders;
    private final AccessTokens accessTokens;

    /**
     * @param transport        sends the requests
     * @param requestCounter   the run's request counter
     * @param communicationLog where the exchanges are written
     * @param defaultHeaders   headers added to every request unless the request sets them itself
     */
    public FhirHttpClient(HttpTransport transport, AtomicInteger requestCounter, CommunicationLog communicationLog,
                          HttpHeaders defaultHeaders) {
        this(transport, requestCounter, communicationLog, defaultHeaders, null);
    }

    /**
     * @param accessTokens the OAuth2 access token sent as {@code Authorization: Bearer} unless the request sets an
     *                     {@code Authorization} header itself; {@code null} for none
     */
    public FhirHttpClient(HttpTransport transport, AtomicInteger requestCounter, CommunicationLog communicationLog,
                          HttpHeaders defaultHeaders, AccessTokens accessTokens) {
        this.transport = transport;
        this.requestCounter = requestCounter;
        this.communicationLog = communicationLog;
        this.defaultHeaders = defaultHeaders == null ? HttpHeaders.empty() : defaultHeaders;
        this.accessTokens = accessTokens;
    }

    /**
     * Sends a request.
     *
     * @throws ExchangeFailedException if no response was received; the request is logged before
     */
    public Exchange send(FhirRequest request) {
        String requestId = "REQ-%04d".formatted(requestCounter.incrementAndGet());
        FhirRequest sent;
        try {
            sent = withAccessToken(withDefaultHeaders(request));
        } catch (IOException e) {
            log.error("{} {} {} -> not sent: {}", requestId, request.method(), request.uri(), e.getMessage());
            throw new ExchangeFailedException(requestId, request, "not sent", e);
        }
        long start = System.nanoTime();
        FhirResponse response;
        try {
            response = transport.send(sent);
        } catch (IOException | RuntimeException e) {
            long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            String where = communicationLog.recordFailure(requestId, sent, e);
            log.error("{} {} {} -> no response, {} ({} ms){}", requestId, sent.method(), sent.uri(),
                    e.getClass().getSimpleName(), millis, reference(where));
            throw new ExchangeFailedException(requestId, sent, "no response received", e);
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        String where = communicationLog.record(requestId, sent, response);
        log.info("{} {} {} -> {} {} ({} ms){}", requestId, sent.method(), sent.uri(), response.status(),
                response.reason(), duration.toMillis(), reference(where));
        return new Exchange(requestId, sent, response, duration);
    }

    private FhirRequest withDefaultHeaders(FhirRequest request) {
        HttpHeaders headers = request.headers();
        for (var field : defaultHeaders.asMap().entrySet()) {
            if (!headers.contains(field.getKey()) && !field.getValue().isEmpty()) {
                headers = headers.with(field.getKey(), field.getValue().getFirst());
            }
        }
        return request.withHeaders(headers);
    }

    /** A TestScript that sets {@code Authorization} itself, e.g. to test a wrong token, keeps it. */
    private FhirRequest withAccessToken(FhirRequest request) throws IOException {
        if (accessTokens == null || request.headers().contains("Authorization")) {
            return request;
        }
        return request.withHeaders(request.headers().with("Authorization", "Bearer " + accessTokens.token()));
    }

    private static String reference(String where) {
        return where == null ? "" : " [" + where + "]";
    }

    /** No response was received for a request, or it could not be sent. */
    public static final class ExchangeFailedException extends RuntimeException {

        private final String requestId;

        /** @param what what went wrong, e.g. {@code no response received} */
        ExchangeFailedException(String requestId, FhirRequest request, String what, Exception cause) {
            super(requestId + " " + request.method() + " " + request.uri() + ": " + what + " (" + describe(cause) + ")",
                    cause);
            this.requestId = requestId;
        }

        public String requestId() {
            return requestId;
        }

        /** e.g. {@code ConnectException: Connection refused}, also for exceptions without message. */
        private static String describe(Throwable cause) {
            Throwable root = cause;
            while (root.getMessage() == null && root.getCause() != null) {
                root = root.getCause();
            }
            return root.getMessage() == null ? root.getClass().getSimpleName()
                    : root.getClass().getSimpleName() + ": " + root.getMessage();
        }
    }
}
