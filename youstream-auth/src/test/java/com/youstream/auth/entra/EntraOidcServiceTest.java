package com.youstream.auth.entra;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.auth.ExternalIdentity;
import com.youstream.common.model.IdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the full code flow against a local stub of the Entra token and JWKS endpoints. */
class EntraOidcServiceTest {

    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String CLIENT_ID = "client-123";

    private HttpServer stub;
    private RSAKey signingKey;
    /** Public key served by the JWKS endpoint; normally the signing key. */
    private RSAKey publishedKey;
    private String authority;
    private volatile String lastTokenRequest;
    private volatile String lastAuthorization;
    /** Lets a test tamper with the ID token claims the stub issues. */
    private volatile UnaryOperator<JWTClaimsSet.Builder> claimsOverride = b -> b;
    private volatile String nonceForToken;

    @BeforeEach
    void startStub() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        publishedKey = signingKey;
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        authority = "http://localhost:" + stub.getAddress().getPort();
        stub.createContext("/" + TENANT + "/discovery/v2.0/keys",
            ex -> respond(ex, 200, new JWKSet(publishedKey.toPublicJWK()).toString()));
        stub.createContext("/" + TENANT + "/oauth2/v2.0/token", this::token);
        stub.start();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private EntraOidcService service() {
        return new EntraOidcService(EntraConfig.builder()
            .tenantId(TENANT).clientId(CLIENT_ID).clientSecret("s3cret")
            .redirectUri("http://localhost:8080/api/auth/microsoft/callback")
            .authority(authority)
            .build());
    }

    @Test
    void beginBuildsAuthorizeUrlWithPkce() {
        EntraOidcService.Authorization auth = service().begin("/watch/abc", null);
        Map<String, String> query = Arrays.stream(auth.uri().getRawQuery().split("&"))
            .map(p -> p.split("=", 2))
            .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));

        assertThat(auth.uri().getPath()).isEqualTo("/" + TENANT + "/oauth2/v2.0/authorize");
        assertThat(query).containsEntry("client_id", CLIENT_ID)
            .containsEntry("response_type", "code")
            .containsEntry("code_challenge_method", "S256")
            .containsEntry("state", auth.state().getState())
            .containsEntry("nonce", auth.state().getNonce())
            .containsKey("code_challenge");
        assertThat(auth.state().getReturnTo()).isEqualTo("/watch/abc");
    }

    @Test
    void completeRedeemsCodeAndMapsClaims() {
        EntraOidcService service = service();
        EntraOidcService.Authorization auth = service.begin("/", null);
        nonceForToken = auth.state().getNonce();

        ExternalIdentity id = service.complete("the-code", auth.state().getState(), auth.state());

        assertThat(id.getProvider()).isEqualTo(IdentityProvider.ENTRA);
        assertThat(id.getSubject()).isEqualTo("oid-42");
        assertThat(id.getUsername()).isEqualTo("paul@contoso.com");
        assertThat(id.getEmail()).isEqualTo("paul@contoso.com");
        assertThat(id.getDisplayName()).isEqualTo("Paul Example");
        assertThat(id.isAdmin()).isTrue();
        assertThat(lastTokenRequest).contains("grant_type=authorization_code", "code=the-code",
            "code_verifier=" + auth.state().getCodeVerifier());
        assertThat(lastAuthorization).startsWith("Basic ");
    }

    @Test
    void rejectsWrongState() {
        EntraOidcService service = service();
        EntraOidcService.Authorization auth = service.begin("/", null);

        assertInvalid(() -> service.complete("the-code", "forged", auth.state()));
    }

    @Test
    void rejectsNonceMismatch() {
        EntraOidcService service = service();
        EntraOidcService.Authorization auth = service.begin("/", null);
        nonceForToken = "some-other-nonce";

        assertInvalid(() -> service.complete("the-code", auth.state().getState(), auth.state()));
    }

    @Test
    void rejectsTokenForAnotherAudienceOrTenant() {
        EntraOidcService service = service();
        EntraOidcService.Authorization auth = service.begin("/", null);
        nonceForToken = auth.state().getNonce();

        claimsOverride = b -> b.audience("someone-else");
        assertInvalid(() -> service.complete("the-code", auth.state().getState(), auth.state()));

        claimsOverride = b -> b.claim("tid", "99999999-0000-0000-0000-000000000000");
        assertInvalid(() -> service.complete("the-code", auth.state().getState(), auth.state()));
    }

    @Test
    void rejectsTokenSignedByUnknownKey() throws Exception {
        EntraOidcService service = service();
        EntraOidcService.Authorization auth = service.begin("/", null);
        nonceForToken = auth.state().getNonce();
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate(); // JWKS still serves the old public key

        assertInvalid(() -> service.complete("the-code", auth.state().getState(), auth.state()));
    }

    @Test
    void errorResponseFromEntraIsInvalidCredentials() {
        stub.removeContext("/" + TENANT + "/oauth2/v2.0/token");
        stub.createContext("/" + TENANT + "/oauth2/v2.0/token",
            ex -> respond(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: expired\"}"));
        EntraOidcService service = service();
        EntraOidcService.Authorization auth = service.begin("/", null);

        assertInvalid(() -> service.complete("old-code", auth.state().getState(), auth.state()));
    }

    private void token(HttpExchange ex) throws IOException {
        lastTokenRequest = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastAuthorization = ex.getRequestHeaders().getFirst("Authorization");
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(authority + "/" + TENANT + "/v2.0")
                .audience(CLIENT_ID)
                .subject("pairwise-sub")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("nonce", nonceForToken)
                .claim("oid", "oid-42")
                .claim("tid", TENANT)
                .claim("preferred_username", "paul@contoso.com")
                .claim("name", "Paul Example")
                .claim("roles", List.of("YouStream.Admin"));
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
                claimsOverride.apply(claims).build());
            jwt.sign(new RSASSASigner(signingKey));
            respond(ex, 200, "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\""
                + jwt.serialize() + "\"}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"server_error\"}");
        }
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AuthException.class,
            e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_CREDENTIALS));
    }
}
