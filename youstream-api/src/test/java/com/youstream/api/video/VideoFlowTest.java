package com.youstream.api.video;

import com.youstream.api.AppServices;
import com.youstream.api.YouStreamApp;
import com.youstream.auth.UserService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.ldap.LdapConfig;
import com.youstream.auth.token.PlaybackTokens;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.common.storage.LocalDiskStorage;
import com.youstream.db.TestDatabase;
import com.youstream.devldap.DevLdapServer;
import com.youstream.media.Ffprobe;
import com.youstream.media.IngestService;
import com.youstream.media.MediaTestSupport;
import com.youstream.media.ProcessRunner;
import com.youstream.media.TranscodeWorkerPool;
import io.jooby.Server;
import io.jooby.ServerOptions;
import io.jooby.undertow.UndertowServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Upload in chunks, transcode, watch, share, delete: over real HTTP with dev LDAP, the test DB and FFmpeg. */
class VideoFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHUNK = 64 * 1024;

    @TempDir
    static Path dir;

    private static DevLdapServer ldap;
    private static Server server;
    private static String base;
    private static TranscodeWorkerPool workers;
    private static LocalDiskStorage storage;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        ldap = DevLdapServer.start(0);
        Clock clock = Clock.systemUTC();
        String secret = "integration-test-secret-0123456789";
        storage = new LocalDiskStorage(dir.resolve("data"));
        ProcessRunner runner = new ProcessRunner();
        workers = new TranscodeWorkerPool(TestDatabase.db(), storage, MediaTestSupport.config(), runner, clock);
        AppServices services = AppServices.builder()
            .databaseHealthy(() -> true)
            .db(TestDatabase.db())
            .storage(storage)
            .ingest(new IngestService(TestDatabase.db(), storage, new Ffprobe(MediaTestSupport.FFPROBE, runner)))
            .ldap(new LdapAuthenticator(LdapConfig.builder()
                .url(ldap.url()).baseDn(DevLdapServer.BASE_DN)
                .bindDn(DevLdapServer.SERVICE_DN).bindPassword(DevLdapServer.SERVICE_PASSWORD)
                .build()))
            .users(new UserService(TestDatabase.db(), clock))
            .tokens(new TokenService(secret, Duration.ofMinutes(15), clock))
            .refreshTokens(new RefreshTokenService(TestDatabase.db(), Duration.ofDays(30), clock))
            .playbackTokens(new PlaybackTokens(secret, Duration.ofHours(6), clock))
            .secureCookies(false)
            .uploadChunkBytes(CHUNK)
            .maxUploadBytes(50L << 20)
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
    void clean() {
        TestDatabase.truncate();
    }

    @Test
    void uploadTranscodeWatchShareAndDelete() throws Exception {
        String alice = login("alice", "alice-pass");
        String bob = login("bob", "bob-pass");
        byte[] clip = Files.readAllBytes(MediaTestSupport.generateClip(dir.resolve("trip.mp4"), 640, 360, 3, true));
        assertThat(clip.length).isGreaterThan(CHUNK);

        // create the upload session
        JsonNode upload = json(send("POST", "/api/uploads", "{\"filename\":\"Trip.mp4\",\"size\":" + clip.length + "}", alice), 201);
        String id = upload.get("id").asString();
        assertThat(upload.get("chunkBytes").asLong()).isEqualTo(CHUNK);

        // out-of-order chunk is refused with the current offset; the first chunk may be re-sent
        assertThat(putChunk(id, clip, CHUNK, alice).statusCode()).isEqualTo(409);
        for (int start = 0; start < clip.length; start += CHUNK) {
            assertThat(putChunk(id, clip, start, alice).statusCode()).isEqualTo(200);
        }
        assertThat(putChunk(id, clip, 0, alice).statusCode()).isEqualTo(200);
        assertThat(json(send("GET", "/api/uploads/" + id, null, alice), 200).get("receivedBytes").asLong()).isEqualTo(clip.length);
        assertThat(send("GET", "/api/uploads/" + id, null, bob).statusCode()).as("not bob's upload").isEqualTo(404);

        // complete -> video exists, queued
        JsonNode created = json(send("POST", "/api/uploads/" + id + "/complete",
            "{\"title\":\"  My trip \",\"visibility\":\"PRIVATE\"}", alice), 201);
        String pid = created.get("publicId").asString();
        assertThat(created.get("title").asString()).isEqualTo("My trip");
        assertThat(created.get("status").asString()).isEqualTo("UPLOADED");
        assertThat(created.get("playbackUrl").isNull()).isTrue();

        assertThat(workers.runOnce("test")).isTrue();

        // owner sees a signed playback URL that serves HLS
        JsonNode video = json(send("GET", "/api/videos/" + pid, null, alice), 200);
        assertThat(video.get("status").asString()).isEqualTo("READY");
        assertThat(video.get("canEdit").asBoolean()).isTrue();
        String playback = video.get("playbackUrl").asString();
        assertThat(playback).matches("/media/" + pid + "/[0-9a-z]+\\.[A-Za-z0-9_-]{22}/master\\.m3u8");
        HttpResponse<String> master = send("GET", playback, null, null);
        assertThat(master.statusCode()).isEqualTo(200);
        assertThat(master.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("mpegurl"));
        assertThat(master.body()).contains("360p/index.m3u8");
        String mediaPlaylist = send("GET", playback.replace("master.m3u8", "360p/index.m3u8"), null, null).body();
        String segment = mediaPlaylist.lines().filter(l -> l.endsWith(".m4s")).findFirst().orElseThrow();
        HttpResponse<String> seg = send("GET", playback.replace("master.m3u8", "360p/" + segment), null, null);
        assertThat(seg.statusCode()).isEqualTo(200);
        assertThat(seg.headers().firstValue("Cache-Control")).contains("private, max-age=31536000, immutable");
        assertThat(send("GET", video.get("posterUrl").asString(), null, null).statusCode()).isEqualTo(200);

        // private: invisible to others, and the public token does not work
        assertThat(send("GET", "/api/videos/" + pid, null, bob).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/videos/" + pid, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/media/" + pid + "/pub/master.m3u8", null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/media/" + pid + "/abc.def/master.m3u8", null, null).statusCode()).isEqualTo(404);
        assertThat(json(send("GET", "/api/videos", null, null), 200).get("items")).isEmpty();
        assertThat(send("PATCH", "/api/videos/" + pid, "{\"title\":\"hacked\"}", bob).statusCode()).isEqualTo(404);

        // publish: appears in the feed with public media URLs
        JsonNode updated = json(send("PATCH", "/api/videos/" + pid, "{\"visibility\":\"PUBLIC\",\"description\":\"Sun!\"}", alice), 200);
        assertThat(updated.get("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(updated.get("title").asString()).isEqualTo("My trip");
        JsonNode feed = json(send("GET", "/api/videos", null, null), 200);
        assertThat(feed.get("items").get(0).get("publicId").asString()).isEqualTo(pid);
        assertThat(feed.get("items").get(0).get("channel").get("handle").asString()).isEqualTo("alice");
        assertThat(feed.get("items").get(0).get("error").isNull()).as("owner-only field hidden").isTrue();
        assertThat(send("GET", "/media/" + pid + "/pub/master.m3u8", null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/thumbs/" + pid + "/pub/poster.jpg", null, null).statusCode()).isEqualTo(200);

        // studio
        JsonNode mine = json(send("GET", "/api/me/videos", null, alice), 200);
        assertThat(mine.get("items").get(0).get("originalFilename").asString()).isEqualTo("Trip.mp4");
        assertThat(json(send("GET", "/api/me/videos", null, bob), 200).get("items")).isEmpty();

        // delete removes the row and the files
        assertThat(send("DELETE", "/api/videos/" + pid, null, bob).statusCode()).as("visible but not bob's").isEqualTo(403);
        assertThat(send("DELETE", "/api/videos/" + pid, null, alice).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/videos/" + pid, null, alice).statusCode()).isEqualTo(404);
        assertThat(storage.exists("hls/" + pid)).isFalse();
        assertThat(storage.exists("originals/" + pid)).isFalse();
    }

    @Test
    void rejectsNonVideoAndOversizedUploads() throws Exception {
        String alice = login("alice", "alice-pass");
        byte[] text = "this is not a video at all".getBytes();

        assertThat(send("POST", "/api/uploads", "{\"filename\":\"huge.mp4\",\"size\":" + (60L << 20) + "}", alice).statusCode())
            .isEqualTo(413);
        assertThat(send("POST", "/api/uploads", "{\"filename\":\"x.mp4\",\"size\":1}", null).statusCode()).isEqualTo(401);

        String id = json(send("POST", "/api/uploads", "{\"filename\":\"notes.mp4\",\"size\":" + text.length + "}", alice), 201)
            .get("id").asString();
        assertThat(send("POST", "/api/uploads/" + id + "/complete", "{}", alice).statusCode()).as("incomplete").isEqualTo(409);
        assertThat(putChunk(id, text, 0, alice).statusCode()).isEqualTo(200);
        HttpResponse<String> complete = send("POST", "/api/uploads/" + id + "/complete", "{}", alice);
        assertThat(complete.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(complete.body()).get("error").asString()).isEqualTo("invalid_media");
    }

    @Test
    void mediaPathsAreWhitelisted() {
        assertThat(MediaRoutes.isHlsFile("master.m3u8")).isTrue();
        assertThat(MediaRoutes.isHlsFile("1080p/seg_00012.m4s")).isTrue();
        assertThat(MediaRoutes.isHlsFile("720p/init_0.mp4")).isTrue();
        assertThat(MediaRoutes.isHlsFile("../../originals/x/source.mp4")).isFalse();
        assertThat(MediaRoutes.isHlsFile("720p/../master.m3u8")).isFalse();
        assertThat(MediaRoutes.isHlsFile("poster.jpg")).isFalse();
    }

    private String login(String user, String password) throws Exception {
        HttpResponse<String> rsp = send("POST", "/api/auth/login",
            "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}", null);
        return json(rsp, 200).get("accessToken").asString();
    }

    private HttpResponse<String> putChunk(String id, byte[] data, int start, String bearer) throws Exception {
        int end = Math.min(data.length, start + CHUNK) - 1;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/uploads/" + id))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/octet-stream")
            .header("Content-Range", "bytes " + start + "-" + end + "/" + data.length)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(Arrays.copyOfRange(data, start, end + 1)))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
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

    private static JsonNode json(HttpResponse<String> rsp, int expectedStatus) {
        assertThat(rsp.statusCode()).as(rsp.body()).isEqualTo(expectedStatus);
        return JSON.readTree(rsp.body());
    }
}
