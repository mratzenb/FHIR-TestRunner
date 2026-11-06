package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Access to a FHIR server with an OAuth 2.0 client credentials grant (RFC 6749, section 4.4): the runner gets an
 * access token from the token endpoint and sends it as {@code Authorization: Bearer <token>} with every request,
 * renewing it shortly before it expires.
 *
 * @param tokenUrl             the token endpoint, e.g. {@code https://auth.example.org/realms/fhir/protocol/openid-connect/token}
 * @param clientId             the client id
 * @param clientSecret         the client secret; never logged
 * @param scope                the requested scopes, space separated, e.g. {@code system/*.rs}; none if absent
 * @param clientAuthentication how client id and secret are sent to the token endpoint, default
 *                             {@link ClientAuthentication#BASIC}
 */
public record OAuth2Config(@Nullable URI tokenUrl, @Nullable String clientId, @Nullable String clientSecret,
                           @Nullable String scope, @Nonnull ClientAuthentication clientAuthentication) {

    /** How the client authenticates at the token endpoint (RFC 6749, section 2.3.1). */
    public enum ClientAuthentication {
        /** HTTP Basic authentication with client id and secret, the method every server must support. */
        BASIC,
        /** {@code client_id} and {@code client_secret} as form parameters of the request body. */
        POST
    }

    public OAuth2Config(@Nullable URI tokenUrl, @Nullable String clientId, @Nullable String clientSecret,
                        @Nullable String scope, @Nullable ClientAuthentication clientAuthentication) {
        scope = scope == null || scope.isBlank() ? null : scope.trim();
        clientAuthentication = clientAuthentication == null ? ClientAuthentication.BASIC : clientAuthentication;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.clientAuthentication = clientAuthentication;
    }

    /** The problems that keep the grant from working, each naming its property below {@code prefix}. */
    List<String> problems(String prefix) {
        List<String> problems = new ArrayList<>();
        if (tokenUrl == null) {
            problems.add(prefix + ".token-url is not set");
        }
        if (clientId == null || clientId.isBlank()) {
            problems.add(prefix + ".client-id is not set");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            problems.add(prefix + ".client-secret is not set");
        }
        return problems;
    }

    /** Without the secret, so the configuration can be logged. */
    @Override
    @Nonnull
    public String toString() {
        return "OAuth2Config[tokenUrl=" + tokenUrl + ", clientId=" + clientId + ", clientSecret=***, scope=" + scope
                + ", clientAuthentication=" + clientAuthentication + "]";
    }
}
