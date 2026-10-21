package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.model.Body;

/**
 * Something operations and assertions can refer to by id: a fixture or a kept response
 * ({@code sourceId}, {@code targetId}, {@code compareToSourceId}).
 */
public sealed interface Source {

    /** The content. */
    Body body();

    /** The response headers; empty for a fixture. */
    HttpHeaders headers();

    /** The request that produced it, or {@code null} for a fixture that was never sent. */
    String requestId();

    /** A static fixture. {@code serverId} is set once the fixture has been created on the server. */
    record Fixture(String id, Body body, String resourceType, String serverId, String requestId) implements Source {

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.empty();
        }

        Fixture created(Body sentBody, String newServerId, String byRequest) {
            return new Fixture(id, sentBody, resourceType, newServerId, byRequest);
        }
    }

    /** A response kept under its {@code responseId}, or the last response. */
    record Response(Exchange exchange) implements Source {

        @Override
        public Body body() {
            return exchange.response().asBody();
        }

        @Override
        public HttpHeaders headers() {
            return exchange.response().headers();
        }

        @Override
        public String requestId() {
            return exchange.requestId();
        }
    }
}
