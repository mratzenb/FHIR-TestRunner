package at.hl7.testing.runner.core.http;

import at.hl7.testing.runner.core.config.OAuth2Config;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.HttpMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Access tokens of an OAuth 2.0 client credentials grant ({@link OAuth2Config}). A token is fetched on first use and
 * kept until shortly before it expires ({@code expires_in}); a token without {@code expires_in} is kept for the run.
 *
 * <p>One instance per token endpoint and client per run, shared by all TestScripts of the run; thread-safe, a token is
 * fetched once even when TestScripts run in parallel. The token request is not part of the communication log: it
 * carries the client secret, and it is no exchange with the FHIR server.
 */
public final class AccessTokens {

    private static final Logger log = LoggerFactory.getLogger(AccessTokens.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** How long before it expires a token is renewed, at most; at least half of its lifetime remains usable. */
    static final Duration RENEW_BEFORE = Duration.ofSeconds(30);

    private final OAuth2Config config;
    private final HttpTransport transport;
    private final Clock clock;
    private String token;
    /** When the token is renewed, {@code null} for a token without expiry. */
    private Instant renewAt;

    public AccessTokens(OAuth2Config config, HttpTransport transport, Clock clock) {
        this.config = config;
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * The current access token, fetched if there is none yet or it is about to expire.
     *
     * @throws IOException if the token endpoint gives no token; the message names the endpoint and its answer
     */
    public synchronized String token() throws IOException {
        if (token == null || (renewAt != null && !clock.instant().isBefore(renewAt))) {
            fetch();
        }
        return token;
    }

    private void fetch() throws IOException {
        Instant requested = clock.instant();
        FhirResponse response = transport.send(request());
        String where = "No OAuth2 access token from " + config.tokenUrl();
        if (!response.isSuccessful()) {
            throw new IOException(where + ": HTTP " + response.status() + " " + response.reason() + error(response));
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(response.bodyText());
        } catch (IOException e) {
            throw new IOException(where + ": the answer is no JSON", e);
        }
        String accessToken = answer.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new IOException(where + ": the answer has no access_token");
        }
        String type = answer.path("token_type").asText("Bearer");
        if (!type.equalsIgnoreCase("Bearer")) {
            throw new IOException(where + ": token_type '" + type + "' is not supported, only Bearer");
        }
        token = accessToken;
        if (answer.path("expires_in").canConvertToLong() && answer.path("expires_in").asLong() > 0) {
            Duration lifetime = Duration.ofSeconds(answer.path("expires_in").asLong());
            Duration margin = lifetime.dividedBy(2).compareTo(RENEW_BEFORE) < 0 ? lifetime.dividedBy(2) : RENEW_BEFORE;
            renewAt = requested.plus(lifetime).minus(margin);
            log.info("OAuth2 access token for client {} received from {}, valid for {} s", config.clientId(),
                    config.tokenUrl(), lifetime.toSeconds());
        } else {
            renewAt = null;
            log.warn("OAuth2 access token for client {} received from {} without expires_in; it is used for the"
                    + " rest of the run", config.clientId(), config.tokenUrl());
        }
    }

    private FhirRequest request() {
        StringJoiner form = new StringJoiner("&");
        form.add("grant_type=client_credentials");
        if (config.scope() != null) {
            form.add("scope=" + encode(config.scope()));
        }
        HttpHeaders headers = HttpHeaders.of(Map.of(
                "Content-Type", List.of("application/x-www-form-urlencoded"),
                "Accept", List.of("application/json")));
        switch (config.clientAuthentication()) {
            case BASIC -> headers = headers.with("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (encode(config.clientId()) + ":" + encode(config.clientSecret())).getBytes(StandardCharsets.UTF_8)));
            case POST -> form.add("client_id=" + encode(config.clientId()))
                    .add("client_secret=" + encode(config.clientSecret()));
        }
        return new FhirRequest(HttpMethod.POST, config.tokenUrl(), headers,
                Body.of(form.toString(), "application/x-www-form-urlencoded"));
    }

    /** {@code error} and {@code error_description} of an OAuth error answer (RFC 6749, section 5.2), if there are. */
    private static String error(FhirResponse response) {
        try {
            JsonNode answer = JSON.readTree(response.bodyText());
            String error = answer.path("error").asText("");
            String description = answer.path("error_description").asText("");
            return error.isEmpty() ? "" : ", " + error + (description.isEmpty() ? "" : " - " + description);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Form encoding, also used for client id and secret before HTTP Basic (RFC 6749, appendix B). */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
