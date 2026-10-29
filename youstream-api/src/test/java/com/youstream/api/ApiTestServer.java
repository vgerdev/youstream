package com.youstream.api;

import com.youstream.auth.SshKeyService;
import com.youstream.auth.UserService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.ldap.LdapConfig;
import com.youstream.auth.token.PlaybackTokens;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.common.storage.LocalDiskStorage;
import com.youstream.db.TestDatabase;
import com.youstream.devldap.DevLdapServer;
import io.jooby.Server;
import io.jooby.ServerOptions;
import io.jooby.undertow.UndertowServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The real app on a free port with dev LDAP and the test database, plus small HTTP helpers. */
public final class ApiTestServer implements AutoCloseable {

    public static final ObjectMapper JSON = new ObjectMapper();

    private final DevLdapServer ldap;
    private final Server server;
    private final RateLimits rateLimits = new RateLimits(RateLimits.DEFAULT_RULES, java.time.Clock.systemUTC());
    private final String base;
    private final HttpClient http = HttpClient.newHttpClient();

    public ApiTestServer(Path storageDir) throws Exception {
        ldap = DevLdapServer.start(0);
        Clock clock = Clock.systemUTC();
        String secret = "integration-test-secret-0123456789";
        AppServices services = AppServices.builder()
            .databaseHealthy(() -> true)
            .db(TestDatabase.db())
            .storage(new LocalDiskStorage(storageDir))
            .ldap(new LdapAuthenticator(LdapConfig.builder()
                .url(ldap.url()).baseDn(DevLdapServer.BASE_DN)
                .bindDn(DevLdapServer.SERVICE_DN).bindPassword(DevLdapServer.SERVICE_PASSWORD)
                .build()))
            .users(new UserService(TestDatabase.db(), clock))
            .sshKeys(new SshKeyService(TestDatabase.db(), clock))
            .tokens(new TokenService(secret, Duration.ofMinutes(15), clock))
            .refreshTokens(new RefreshTokenService(TestDatabase.db(), Duration.ofDays(30), clock))
            .playbackTokens(new PlaybackTokens(secret, Duration.ofHours(6), clock))
            .secureCookies(false)
            .rateLimits(rateLimits)
            .build();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new UndertowServer().setOptions(new ServerOptions().setPort(port));
        server.start(new YouStreamApp(services));
        base = "http://localhost:" + port;
    }

    /** Call between tests: user ids restart after a database reset, so old counts would carry over. */
    public void resetRateLimits() {
        rateLimits.reset();
    }

    /** Signs in with a dev LDAP account and returns the access token. */
    public String login(String user, String password) throws Exception {
        return json(send("POST", "/api/auth/login",
            "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}", null), 200).get("accessToken").asString();
    }

    public HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            req.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static JsonNode json(HttpResponse<String> rsp, int expectedStatus) {
        assertThat(rsp.statusCode()).as("%s %s", rsp.request().uri(), rsp.body()).isEqualTo(expectedStatus);
        return JSON.readTree(rsp.body());
    }

    @Override
    public void close() {
        server.stop();
        ldap.close();
    }
}
