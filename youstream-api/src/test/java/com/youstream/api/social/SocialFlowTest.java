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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.youstream.api.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Comments, likes, channels, subscriptions, views and profile edits over HTTP. */
class SocialFlowTest {

    @TempDir
    static Path dir;
    private static ApiTestServer api;

    private String alice;
    private String bob;
    private String pid;

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
        pid = readyVideo("alice", "Alice's trip", Visibility.PUBLIC);
    }

    /** Inserts a transcoded video directly, so these tests need no FFmpeg. */
    private static String readyVideo(String owner, String title, Visibility visibility) {
        User user = new UserRepository(TestDatabase.db()).findByUsername(owner).orElseThrow();
        VideoRepository videos = new VideoRepository(TestDatabase.db());
        Video v = videos.insert(Video.builder().publicId(PublicIds.next()).ownerId(user.getId()).title(title)
            .visibility(visibility).source(VideoSource.WEB).originalFilename("x.mp4").build());
        videos.markReady(v.getId(), 60_000, 1280, 720, List.of(), Instant.now());
        return v.getPublicId();
    }

    @Test
    void commentThreadWithRepliesEditAndDelete() throws Exception {
        JsonNode top = json(api.send("POST", "/api/videos/" + pid + "/comments", "{\"body\":\"  Lovely!  \"}", bob), 201);
        assertThat(top.get("body").asString()).isEqualTo("Lovely!");
        assertThat(top.at("/author/handle").asString()).isEqualTo("bob");
        assertThat(top.get("canEdit").asBoolean()).isTrue();

        long topId = top.get("id").asLong();
        JsonNode reply = json(api.send("POST", "/api/videos/" + pid + "/comments",
            "{\"body\":\"Thanks!\",\"parentId\":" + topId + "}", alice), 201);
        // a reply to a reply joins the same thread
        JsonNode nested = json(api.send("POST", "/api/videos/" + pid + "/comments",
            "{\"body\":\"np\",\"parentId\":" + reply.get("id").asLong() + "}", bob), 201);
        assertThat(nested.get("parentId").asLong()).isEqualTo(topId);

        JsonNode threads = json(api.send("GET", "/api/videos/" + pid + "/comments", null, null), 200);
        assertThat(threads.get("items")).hasSize(1);
        assertThat(threads.at("/items/0/replyCount").asInt()).isEqualTo(2);
        assertThat(threads.at("/items/0/canEdit").asBoolean()).as("anonymous").isFalse();
        JsonNode replies = json(api.send("GET", "/api/videos/" + pid + "/comments?parent=" + topId, null, bob), 200);
        assertThat(replies.get("items")).extracting(n -> n.get("body").asString()).containsExactly("Thanks!", "np");
        assertThat(json(api.send("GET", "/api/videos/" + pid, null, null), 200).get("commentCount").asLong()).isEqualTo(3);

        // only the author edits; the video owner may delete anyone's comment
        assertThat(api.send("PATCH", "/api/comments/" + topId, "{\"body\":\"hacked\"}", alice).statusCode()).isEqualTo(403);
        assertThat(json(api.send("PATCH", "/api/comments/" + topId, "{\"body\":\"Lovely!!\"}", bob), 200)
            .get("editedAt").isNull()).isFalse();
        assertThat(api.send("DELETE", "/api/comments/" + nested.get("id").asLong(), null, alice).statusCode()).isEqualTo(204);
        assertThat(api.send("DELETE", "/api/comments/" + topId, null, bob).statusCode()).isEqualTo(204);

        JsonNode after = json(api.send("GET", "/api/videos/" + pid + "/comments", null, null), 200);
        assertThat(after.at("/items/0/deleted").asBoolean()).as("kept while it has replies").isTrue();
        assertThat(after.at("/items/0/body").isNull()).isTrue();
        assertThat(api.send("POST", "/api/videos/" + pid + "/comments", "{\"body\":\" \"}", bob).statusCode()).isEqualTo(400);
        assertThat(api.send("POST", "/api/videos/" + pid + "/comments", "{\"body\":\"x\"}", null).statusCode()).isEqualTo(401);
    }

    @Test
    void privateVideoCommentsAreHidden() throws Exception {
        String secret = readyVideo("alice", "secret", Visibility.PRIVATE);
        assertThat(api.send("GET", "/api/videos/" + secret + "/comments", null, bob).statusCode()).isEqualTo(404);
        assertThat(api.send("POST", "/api/videos/" + secret + "/comments", "{\"body\":\"hi\"}", bob).statusCode()).isEqualTo(404);
        assertThat(api.send("PUT", "/api/videos/" + secret + "/reaction", "{\"value\":1}", bob).statusCode()).isEqualTo(404);
    }

    @Test
    void likesDislikesAndViews() throws Exception {
        JsonNode liked = json(api.send("PUT", "/api/videos/" + pid + "/reaction", "{\"value\":1}", bob), 200);
        assertThat(liked.get("likeCount").asLong()).isEqualTo(1);
        JsonNode disliked = json(api.send("PUT", "/api/videos/" + pid + "/reaction", "{\"value\":-1}", bob), 200);
        assertThat(disliked.get("likeCount").asLong()).isZero();
        assertThat(disliked.get("dislikeCount").asLong()).isEqualTo(1);
        assertThat(json(api.send("GET", "/api/videos/" + pid, null, bob), 200).get("myReaction").asInt()).isEqualTo(-1);
        assertThat(json(api.send("GET", "/api/videos/" + pid, null, null), 200).get("myReaction").isNull()).isTrue();
        assertThat(api.send("PUT", "/api/videos/" + pid + "/reaction", "{\"value\":7}", bob).statusCode()).isEqualTo(400);

        assertThat(json(api.send("POST", "/api/videos/" + pid + "/views", null, bob), 200).get("viewCount").asLong()).isEqualTo(1);
        assertThat(json(api.send("POST", "/api/videos/" + pid + "/views", null, bob), 200).get("viewCount").asLong())
            .as("same viewer, same day").isEqualTo(1);
        assertThat(json(api.send("POST", "/api/videos/" + pid + "/views", null, null), 200).get("viewCount").asLong()).isEqualTo(2);
    }

    @Test
    void channelsSubscriptionsAndFeed() throws Exception {
        readyVideo("alice", "hidden", Visibility.UNLISTED);

        JsonNode channel = json(api.send("GET", "/api/channels/alice", null, bob), 200);
        assertThat(channel.get("displayName").asString()).isEqualTo("Alice Admin");
        assertThat(channel.get("videoCount").asLong()).isEqualTo(1);
        assertThat(channel.get("subscribed").asBoolean()).isFalse();
        assertThat(json(api.send("GET", "/api/channels/alice", null, alice), 200).get("mine").asBoolean()).isTrue();
        assertThat(json(api.send("GET", "/api/channels/alice/videos", null, null), 200).get("items"))
            .extracting(n -> n.get("title").asString()).containsExactly("Alice's trip");
        assertThat(api.send("GET", "/api/channels/nobody", null, null).statusCode()).isEqualTo(404);

        assertThat(json(api.send("PUT", "/api/channels/alice/subscription", null, bob), 200).get("subscriberCount").asLong()).isEqualTo(1);
        assertThat(api.send("PUT", "/api/channels/alice/subscription", null, alice).statusCode()).as("own channel").isEqualTo(400);
        assertThat(json(api.send("GET", "/api/videos/" + pid, null, bob), 200).at("/channel/subscribed").asBoolean()).isTrue();
        assertThat(json(api.send("GET", "/api/feed/subscriptions", null, bob), 200).get("items"))
            .extracting(n -> n.get("publicId").asString()).containsExactly(pid);
        assertThat(json(api.send("GET", "/api/me/subscriptions", null, bob), 200))
            .extracting(n -> n.get("handle").asString()).containsExactly("alice");

        assertThat(json(api.send("DELETE", "/api/channels/alice/subscription", null, bob), 200).get("subscriberCount").asLong()).isZero();
        assertThat(json(api.send("GET", "/api/feed/subscriptions", null, bob), 200).get("items")).isEmpty();
        assertThat(api.send("GET", "/api/feed/subscriptions", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void profileEditsKeepHandlesUnique() throws Exception {
        JsonNode updated = json(api.send("PATCH", "/api/me",
            "{\"handle\":\"Alice.Travels\",\"displayName\":\" Alice  Travels \",\"bio\":\"Trips and tips\"}", alice), 200);
        assertThat(updated.get("handle").asString()).isEqualTo("alice.travels");
        assertThat(updated.get("displayName").asString()).isEqualTo("Alice Travels");
        assertThat(json(api.send("GET", "/api/channels/alice.travels", null, null), 200).get("bio").asString()).isEqualTo("Trips and tips");

        assertThat(api.send("PATCH", "/api/me", "{\"handle\":\"alice.travels\"}", bob).statusCode()).isEqualTo(409);
        assertThat(api.send("PATCH", "/api/me", "{\"handle\":\"../x\"}", bob).statusCode()).isEqualTo(400);
        assertThat(api.send("PATCH", "/api/me", "{\"displayName\":\"\"}", bob).statusCode()).isEqualTo(400);

        // a new sign-in keeps the edited display name
        String again = api.login("alice", "alice-pass");
        assertThat(json(api.send("GET", "/api/me", null, again), 200).get("displayName").asString()).isEqualTo("Alice Travels");
    }
}
