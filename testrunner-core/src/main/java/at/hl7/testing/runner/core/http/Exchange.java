package at.hl7.testing.runner.core.http;

import java.time.Duration;

/**
 * A request together with the response the server sent for it.
 *
 * @param requestId the run-wide id of the request, e.g. {@code REQ-0007}; names it in the log and in the
 *                  communication log
 * @param request   the request as sent
 * @param response  the response as received
 * @param duration  time between sending the request and receiving the complete response
 */
public record Exchange(String requestId, FhirRequest request, FhirResponse response, Duration duration) {
}
