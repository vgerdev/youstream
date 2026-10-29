package com.youstream.api.social;

import com.youstream.api.ApiTestServer;
import com.youstream.common.PublicIds;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoSource;
import com.youstream.common.model.Visibility;
import com.youstream.db.TestDatabase;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.youstream.api.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Search, playlists, framing headers and oEmbed over HTTP. */
class DiscoveryFlowTest {

    @TempDir
    static Path dir;
    private static ApiTestServer api;

    private String alice;
    private String bob;

    @BeforeAll
    static void start() throws Exception {
        api = new ApiTestServer(dir);
    }

    @AfterAll
    static void stop() {
        if (api != null) {
            api.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.truncate();
        alice = api.login("alice", "alice-pass");
        bob = api.login("bob", "bob-pass");
    }

    private static String readyVideo(String owner, String title, String description, Visibility visibility) {
        User user = new UserRepository(TestDatabase.db()).findByUsername(owner).orElseThrow();
        VideoRepository videos = new VideoRepository(TestDatabase.db());
        Video v = videos.insert(Video.builder().publicId(PublicIds.next()).ownerId(user.getId()).title(title)
            .description(description).visibility(visibility).source(VideoSource.WEB).originalFilename("x.mp4").build());
        videos.markReady(v.getId(), 60_000, 1280, 720, List.of(), Instant.now());
        return v.getPublicId();
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void searchCoversVideosChannelsAndPlaylistsButOnlyPublicContent() throws Exception {
        String sailing = readyVideo("alice", "Sailing the Caribbean", "catamaran trip", Visibility.PUBLIC);
        readyVideo("alice", "Sailing secrets", "", Visibility.UNLISTED);
        readyVideo("bob", "Private sailing", "", Visibility.PRIVATE);
        json(api.send("POST", "/api/playlists",
            "{\"title\":\"Sailing season\",\"visibility\":\"PUBLIC\",\"videoId\":\"" + sailing + "\"}", alice), 201);

        JsonNode videos = json(api.send("GET", "/api/search?q=" + q("sail"), null, null), 200);
        assertThat(videos.get("items")).extracting(n -> n.get("publicId").asString()).containsExactly(sailing);
        assertThat(json(api.send("GET", "/api/search?q=" + q("CARIB cata"), null, null), 200).get("items")).hasSize(1);
        assertThat(json(api.send("GET", "/api/search?q=" + q("sail") + "&type=playlist", null, null), 200).get("items"))
            .extracting(n -> n.get("title").asString()).containsExactly("Sailing season");
        assertThat(json(api.send("GET", "/api/search?q=" + q("ali") + "&type=channel", null, bob), 200).get("items"))
            .extracting(n -> n.get("handle").asString()).containsExactly("alice");
        // user input never reaches to_tsquery syntax
        assertThat(api.send("GET", "/api/search?q=" + q("') | (x:*"), null, null).statusCode()).isEqualTo(200);
        assertThat(json(api.send("GET", "/api/search?q=" + q("!!!"), null, null), 200).get("items")).isEmpty();
        assertThat(api.send("GET", "/api/search?q=x&type=bogus", null, null).statusCode()).isEqualTo(400);
    }

    @Test
    void playlistLifecycleAndVisibility() throws Exception {
        String a = readyVideo("alice", "a", "", Visibility.PUBLIC);
        String b = readyVideo("alice", "b", "", Visibility.PUBLIC);
        String bobsPrivate = readyVideo("bob", "bob only", "", Visibility.PRIVATE);

        JsonNode created = json(api.send("POST", "/api/playlists", "{\"title\":\" Road trip \"}", alice), 201);
        String pid = created.get("publicId").asString();
        assertThat(created.get("title").asString()).isEqualTo("Road trip");
        assertThat(created.get("visibility").asString()).isEqualTo("PRIVATE");

        json(api.send("POST", "/api/playlists/" + pid + "/items", "{\"videoId\":\"" + a + "\"}", alice), 200);
        json(api.send("POST", "/api/playlists/" + pid + "/items", "{\"videoId\":\"" + b + "\"}", alice), 200);
        assertThat(json(api.send("POST", "/api/playlists/" + pid + "/items", "{\"videoId\":\"" + a + "\"}", alice), 200)
            .get("itemCount").asInt()).as("duplicates ignored").isEqualTo(2);
        assertThat(api.send("POST", "/api/playlists/" + pid + "/items", "{\"videoId\":\"" + bobsPrivate + "\"}", alice).statusCode())
            .as("cannot add a video you cannot watch").isEqualTo(404);

        // private playlist: invisible to others
        assertThat(api.send("GET", "/api/playlists/" + pid, null, bob).statusCode()).isEqualTo(404);
        assertThat(api.send("POST", "/api/playlists/" + pid + "/items", "{\"videoId\":\"" + a + "\"}", bob).statusCode()).isEqualTo(404);

        JsonNode reordered = json(api.send("PUT", "/api/playlists/" + pid + "/items", "{\"videoIds\":[\"" + b + "\",\"" + a + "\"]}", alice), 200);
        assertThat(reordered.get("items")).extracting(n -> n.get("publicId").asString()).containsExactly(b, a);
        assertThat(reordered.get("thumbnailUrl").asString()).contains("/thumbs/" + b);

        json(api.send("PATCH", "/api/playlists/" + pid, "{\"visibility\":\"UNLISTED\"}", alice), 200);
        JsonNode shared = json(api.send("GET", "/api/playlists/" + pid, null, null), 200);
        assertThat(shared.get("canEdit").asBoolean()).isFalse();
        assertThat(shared.get("items")).hasSize(2);
        assertThat(json(api.send("GET", "/api/channels/alice/playlists", null, null), 200)).as("unlisted is not listed").isEmpty();
        assertThat(api.send("PATCH", "/api/playlists/" + pid, "{\"title\":\"mine\"}", bob).statusCode()).isEqualTo(403);

        // a video that becomes private drops out for other viewers
        new VideoRepository(TestDatabase.db()).findByPublicId(a).ifPresent(v ->
            new VideoRepository(TestDatabase.db()).updateMetadata(v.getId(), v.getTitle(), "", Visibility.PRIVATE));
        assertThat(json(api.send("GET", "/api/playlists/" + pid, null, bob), 200).get("items")).hasSize(1);
        assertThat(json(api.send("GET", "/api/playlists/" + pid, null, alice), 200).get("items")).hasSize(2);

        JsonNode mine = json(api.send("GET", "/api/me/playlists?contains=" + b, null, alice), 200);
        assertThat(mine.get(0).get("containsVideo").asBoolean()).isTrue();
        json(api.send("DELETE", "/api/playlists/" + pid + "/items/" + b, null, alice), 200);
        assertThat(json(api.send("GET", "/api/me/playlists?contains=" + b, null, alice), 200).get(0).get("containsVideo").asBoolean()).isFalse();

        assertThat(api.send("DELETE", "/api/playlists/" + pid, null, alice).statusCode()).isEqualTo(204);
        assertThat(api.send("GET", "/api/playlists/" + pid, null, alice).statusCode()).isEqualTo(404);
        assertThat(api.send("POST", "/api/playlists", "{\"title\":\"\"}", alice).statusCode()).isEqualTo(400);
    }

    @Test
    void onlyEmbedPagesMayBeFramed() throws Exception {
        HttpResponse<String> watch = api.send("GET", "/watch/abcdefghijk", null, null);
        assertThat(watch.headers().firstValue("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(watch.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(watch.headers().firstValue("X-Content-Type-Options")).contains("nosniff");

        HttpResponse<String> embed = api.send("GET", "/embed/abcdefghijk", null, null);
        assertThat(embed.headers().firstValue("Content-Security-Policy")).contains("frame-ancestors *");
        assertThat(embed.headers().firstValue("X-Frame-Options")).isEmpty();

        assertThat(api.send("GET", "/api/health", null, null).headers().firstValue("X-Frame-Options")).contains("DENY");
    }

    @Test
    void oembedForPublicAndUnlistedVideos() throws Exception {
        String pub = readyVideo("alice", "Sunset <live>", "", Visibility.PUBLIC);
        String unlisted = readyVideo("alice", "Unlisted one", "", Visibility.UNLISTED);
        String priv = readyVideo("alice", "Private one", "", Visibility.PRIVATE);

        JsonNode o = json(api.send("GET", "/oembed?url=" + q("http://localhost:8080/watch/" + pub + "?t=30"), null, null), 200);
        assertThat(o.get("type").asString()).isEqualTo("video");
        assertThat(o.get("author_name").asString()).isEqualTo("Alice Admin");
        assertThat(o.get("html").asString()).contains("/embed/" + pub, "Sunset &lt;live&gt;").doesNotContain("<live>");
        assertThat(o.get("thumbnail_url").asString()).contains("/thumbs/" + pub + "/pub/");

        JsonNode u = json(api.send("GET", "/oembed?url=" + q("http://localhost:8080/watch/" + unlisted) + "&maxwidth=320", null, null), 200);
        assertThat(u.get("width").asInt()).isEqualTo(320);
        assertThat(u.get("height").asInt()).isEqualTo(180);
        assertThat(u.has("thumbnail_url")).as("no unauthenticated poster for unlisted").isFalse();

        assertThat(api.send("GET", "/oembed?url=" + q("http://localhost:8080/watch/" + priv), null, null).statusCode()).isEqualTo(404);
        assertThat(api.send("GET", "/oembed?url=" + q("https://evil.example/x"), null, null).statusCode()).isEqualTo(404);
        assertThat(api.send("GET", "/oembed?url=" + q("http://localhost:8080/watch/" + pub) + "&format=xml", null, null).statusCode()).isEqualTo(501);
    }
}
