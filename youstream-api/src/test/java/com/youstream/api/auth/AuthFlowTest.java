package com.youstream.api.auth;

import com.youstream.api.AppServices;
import com.youstream.api.YouStreamApp;
import com.youstream.auth.SshKeyService;
import com.youstream.auth.UserService;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import com.youstream.auth.entra.EntraConfig;
import com.youstream.auth.entra.EntraOidcService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.ldap.LdapConfig;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.db.TestDatabase;
import com.youstream.devldap.DevLdapServer;
import io.jooby.Server;
import io.jooby.ServerOptions;
import io.jooby.undertow.UndertowServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end over HTTP: dev LDAP login, /api/me, refresh rotation, logout, Microsoft redirect. */
class AuthFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static DevLdapServer ldap;
    private static Server server;
    private static String base;

    private HttpClient http;
    private CookieManager cookies;

    @BeforeAll
    static void start() throws Exception {
        ldap = DevLdapServer.start(0);
        Clock clock = Clock.systemUTC();
        AppServices services = AppServices.builder()
            .databaseHealthy(() -> true)
            .ldap(new LdapAuthenticator(LdapConfig.builder()
                .url(ldap.url()).baseDn(DevLdapServer.BASE_DN)
                .bindDn(DevLdapServer.SERVICE_DN).bindPassword(DevLdapServer.SERVICE_PASSWORD)
                .adminGroup(DevLdapServer.ADMINS_GROUP)
                .build()))
            .entra(new EntraOidcService(EntraConfig.builder()
                .tenantId("tenant-1").clientId("client-1").clientSecret("secret")
                .redirectUri("http://localhost/api/auth/microsoft/callback")
                .build()))
            .users(new UserService(TestDatabase.db(), clock))
            .sshKeys(new SshKeyService(TestDatabase.db(), clock))
            .sftp(new SshKeyRoutes.SftpEndpoint(true, "sftp.test", 2222, "SHA256:host", 10L << 30))
            .tokens(new TokenService("integration-test-secret-0123456789", Duration.ofMinutes(15), clock))
            .refreshTokens(new RefreshTokenService(TestDatabase.db(), Duration.ofDays(30), clock))
            .secureCookies(false) // java.net.CookieManager only returns Secure cookies over https
            .build();

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new UndertowServer().setOptions(new ServerOptions().setPort(port));
        server.start(new YouStreamApp(services));
        base = "http://localhost:" + port;
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        if (ldap != null) {
            ldap.close();
        }
    }

    @BeforeEach
    void setUp() {
        TestDatabase.truncate();
        cookies = new CookieManager();
        http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Test
    void reportsEnabledMethods() throws Exception {
        JsonNode methods = json(get("/api/auth/methods", null));
        assertThat(methods.get("ldap").asBoolean()).isTrue();
        assertThat(methods.get("microsoft").asBoolean()).isTrue();
    }

    @Test
    void ldapLoginMeRefreshAndLogout() throws Exception {
        HttpResponse<String> login = post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"alice-pass\"}", null);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode session = json(login);
        String access = session.get("accessToken").asString();
        assertThat(session.at("/user/username").asString()).isEqualTo("alice");
        assertThat(session.at("/user/admin").asBoolean()).isTrue();
        assertThat(session.at("/user/identities/0").asString()).isEqualTo("LDAP");
        assertThat(session.get("expiresAt").asString()).endsWith("Z");

        HttpCookie refresh = refreshCookie();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/auth");
        assertThat(login.headers().allValues("Set-Cookie")).anyMatch(c -> c.contains("SameSite=Strict"));

        HttpResponse<String> me = get("/api/me", access);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me).get("displayName").asString()).isEqualTo("Alice Admin");

        HttpResponse<String> refreshed = post("/api/auth/refresh", "", null);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(refreshCookie().getValue()).isNotEqualTo(refresh.getValue());
        assertThat(json(refreshed).get("accessToken").asString()).isNotBlank();

        assertThat(post("/api/auth/logout", "", null).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/refresh", "", null).statusCode()).isEqualTo(401);
    }

    @Test
    void manageSshKeysAndShowSftpInfo() throws Exception {
        String access = json(post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"alice-pass\"}", null))
            .get("accessToken").asString();
        String key = PublicKeyEntry.toString(KeyUtils.generateKeyPair("ssh-ed25519", 256).getPublic()) + " alice@laptop";

        HttpResponse<String> added = post("/api/me/ssh-keys", JSON.writeValueAsString(java.util.Map.of("publicKey", key)), access);
        assertThat(added.statusCode()).isEqualTo(201);
        JsonNode created = json(added);
        assertThat(created.get("name").asString()).isEqualTo("alice@laptop");
        assertThat(created.get("fingerprint").asString()).startsWith("SHA256:");

        HttpResponse<String> duplicate = post("/api/me/ssh-keys", JSON.writeValueAsString(java.util.Map.of("publicKey", key)), access);
        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(json(duplicate).get("error").asString()).isEqualTo("invalid_key");

        assertThat(json(get("/api/me/ssh-keys", access))).hasSize(1);
        JsonNode sftp = json(get("/api/me/sftp", access));
        assertThat(sftp.get("host").asString()).isEqualTo("sftp.test");
        assertThat(sftp.get("username").asString()).isEqualTo("alice");
        assertThat(sftp.get("passwordLogin").asBoolean()).isTrue();

        HttpRequest delete = HttpRequest.newBuilder(URI.create(base + "/api/me/ssh-keys/" + created.get("id").asLong()))
            .header("Authorization", "Bearer " + access).DELETE().build();
        assertThat(http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        assertThat(json(get("/api/me/ssh-keys", access))).isEmpty();
        assertThat(get("/api/me/ssh-keys", null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsBadPasswordAndAnonymousMe() throws Exception {
        HttpResponse<String> bad = post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"nope\"}", null);
        assertThat(bad.statusCode()).isEqualTo(401);
        assertThat(json(bad).get("error").asString()).isEqualTo("invalid_credentials");

        assertThat(get("/api/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/me", "not-a-token").statusCode()).isEqualTo(401);
    }

    @Test
    void throttlesRepeatedFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/auth/login", "{\"username\":\"bob\",\"password\":\"x\"}", null).statusCode()).isEqualTo(401);
        }
        assertThat(post("/api/auth/login", "{\"username\":\"bob\",\"password\":\"bob-pass\"}", null).statusCode())
            .isEqualTo(429);
    }

    @Test
    void microsoftLoginRedirectsWithStateCookie() throws Exception {
        HttpResponse<String> rsp = get("/api/auth/microsoft/login?returnTo=/watch/abc", null);

        assertThat(rsp.statusCode()).isEqualTo(302);
        assertThat(rsp.headers().firstValue("Location").orElseThrow())
            .startsWith("https://login.microsoftonline.com/tenant-1/oauth2/v2.0/authorize?");
        assertThat(rsp.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("ys_oidc=") && c.contains("SameSite=Lax"));
    }

    @Test
    void callbackWithoutStateCookieGoesBackToLogin() throws Exception {
        HttpResponse<String> rsp = get("/api/auth/microsoft/callback?code=x&state=y", null);

        assertThat(rsp.statusCode()).isEqualTo(302);
        assertThat(rsp.headers().firstValue("Location")).contains("/login?error=session_expired");
    }

    @Test
    void unknownApiPathIsJson404() throws Exception {
        HttpResponse<String> rsp = get("/api/nope", null);
        assertThat(rsp.statusCode()).isEqualTo(404);
        assertThat(json(rsp).get("error").asString()).isEqualTo("not_found");
    }

    @Test
    void rejectsUnsafeReturnTo() {
        assertThat(AuthRoutes.safeReturnTo("/watch/abc?t=10")).isEqualTo("/watch/abc?t=10");
        assertThat(AuthRoutes.safeReturnTo("//evil.example.com")).isEqualTo("/");
        assertThat(AuthRoutes.safeReturnTo("/\\evil.example.com")).isEqualTo("/");
        assertThat(AuthRoutes.safeReturnTo("https://evil.example.com")).isEqualTo("/");
        assertThat(AuthRoutes.safeReturnTo(null)).isEqualTo("/");
    }

    private HttpCookie refreshCookie() {
        List<HttpCookie> list = cookies.getCookieStore().get(URI.create(base + "/api/auth/refresh"));
        return list.stream().filter(c -> c.getName().equals(AuthRoutes.REFRESH_COOKIE)).findFirst().orElseThrow();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> rsp) {
        return JSON.readTree(rsp.body());
    }
}
