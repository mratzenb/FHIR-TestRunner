package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.model.Body;

/**
 * Something operations and assertions can refer to by id: a fixture, a kept response or a kept request
 * ({@code sourceId}, {@code targetId}, {@code compareToSourceId}).
 */
public sealed interface Source {

    /** The content. */
    Body body();

    /** The headers of the response or request; empty for a fixture. */
    HttpHeaders headers();

    /** The request that produced it, or {@code null} for a fixture that was never sent. */
    String requestId();

    /**
     * A static fixture. Until it is created on the server, the state keeps its {@code body} as written and hands out a
     * copy with placeholders and references resolved at each use ({@link FixtureManager#resolve}). Once created,
     * {@code serverId} is set and {@code body} is the content sent.
     */
    record Fixture(String id, Body body, String resourceType, String serverId, String requestId) implements Source {

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.empty();
        }

        Fixture created(Body sentBody, String newServerId, String byRequest) {
            return new Fixture(id, sentBody, resourceType, newServerId, byRequest);
        }

        Fixture withBody(Body newBody) {
            return new Fixture(id, newBody, resourceType, serverId, requestId);
        }
    }

    /** A response kept under its {@code responseId}, or the last response. */
    record Response(Exchange exchange) implements Source {

        @Override
        public Body body() {
            return exchange.response().body();
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

    /** A request as sent, kept under its {@code requestId}, or the last request ({@code direction = request}). */
    record Request(Exchange exchange) implements Source {

        @Override
        public Body body() {
            Body body = exchange.request().body();
            return body != null ? body : Body.of(new byte[0], null);
        }

        @Override
        public HttpHeaders headers() {
            return exchange.request().headers();
        }

        @Override
        public String requestId() {
            return exchange.requestId();
        }
    }
}
