package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.config.OAuth2Config;
import at.hl7.testing.runner.core.config.OAuth2Config.ClientAuthentication;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokensTest {

    static final OAuth2Config CONFIG = new OAuth2Config(URI.create("https://auth.example/token"), "runner",
            "s3cr:t", "system/*.rs system/*.cud", null);

    final List<FhirRequest> sent = new ArrayList<>();
    final MutableClock clock = new MutableClock();

    private HttpTransport answering(String... bodies) {
        return request -> {
            sent.add(request);
            String body = bodies[Math.min(sent.size(), bodies.length) - 1];
            return FhirResponse.text(200, "OK", "HTTP/1.1",
                    HttpHeaders.of(Map.of("Content-Type", List.of("application/json"))), body);
        };
    }

    @Test
    void fetchesATokenWithBasicAuthenticationAndKeepsIt() throws IOException {
        AccessTokens tokens = new AccessTokens(CONFIG, answering("""
                {"access_token":"t1","token_type":"Bearer","expires_in":300}"""), clock);

        assertThat(tokens.token()).isEqualTo("t1");
        assertThat(tokens.token()).isEqualTo("t1");

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.uri()).hasToString("https://auth.example/token");
            assertThat(request.bodyText()).isEqualTo("grant_type=client_credentials&scope=system%2F*.rs+system%2F*.cud");
            assertThat(request.headers().first("Content-Type")).contains("application/x-www-form-urlencoded");
            String basic = request.headers().first("Authorization").orElseThrow();
            assertThat(new String(Base64.getDecoder().decode(basic.substring("Basic ".length())), StandardCharsets.UTF_8))
                    .isEqualTo("runner:s3cr%3At");
        });
    }

    @Test
    void renewsTheTokenShortlyBeforeItExpires() throws IOException {
        AccessTokens tokens = new AccessTokens(CONFIG, answering("""
                {"access_token":"t1","expires_in":300}""", """
                {"access_token":"t2","expires_in":300}"""), clock);

        assertThat(tokens.token()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(269));
        assertThat(tokens.token()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(tokens.token()).isEqualTo("t2");
        assertThat(sent).hasSize(2);
    }

    @Test
    void clientSecretInTheBodyWithPost() throws IOException {
        OAuth2Config post = new OAuth2Config(CONFIG.tokenUrl(), "runner", "s3cr:t", null, ClientAuthentication.POST);

        new AccessTokens(post, answering("{\"access_token\":\"t1\"}"), clock).token();

        assertThat(sent.getFirst().bodyText()).isEqualTo("grant_type=client_credentials&client_id=runner&client_secret=s3cr%3At");
        assertThat(sent.getFirst().headers().contains("Authorization")).isFalse();
    }

    @Test
    void rejectionNamesTheEndpointAndTheOAuthError() {
        HttpTransport rejecting = request -> FhirResponse.text(401, "Unauthorized", "HTTP/1.1", null, """
                {"error":"invalid_client","error_description":"Client authentication failed"}""");

        assertThatThrownBy(() -> new AccessTokens(CONFIG, rejecting, clock).token())
                .isInstanceOf(IOException.class)
                .hasMessage("No OAuth2 access token from https://auth.example/token: HTTP 401 Unauthorized,"
                        + " invalid_client - Client authentication failed");
    }

    @Test
    void answerWithoutTokenOrWithAnotherTypeIsAnError() {
        assertThatThrownBy(() -> new AccessTokens(CONFIG, answering("{}"), clock).token())
                .hasMessageEndingWith("the answer has no access_token");
        assertThatThrownBy(() -> new AccessTokens(CONFIG, answering("""
                {"access_token":"t","token_type":"mac"}"""), clock).token())
                .hasMessageEndingWith("token_type 'mac' is not supported, only Bearer");
    }

    @Test
    void secretIsNotInTheTextOfTheConfiguration() {
        assertThat(CONFIG.toString()).contains("clientId=runner", "clientSecret=***").doesNotContain("s3cr");
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-31T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
