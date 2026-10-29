package com.youstream.db.repo;

import com.youstream.common.model.Comment;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.Visibility;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Comments, reactions, subscriptions and views, with their denormalized counters. */
class SocialRepositoriesTest {

    private Db db;
    private VideoRepository videos;
    private CommentRepository comments;
    private SocialRepository social;
    private User alice;
    private User bob;
    private Video video;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        videos = new VideoRepository(db);
        comments = new CommentRepository(db);
        social = new SocialRepository(db);
        alice = Fixtures.user(db, "alice");
        bob = Fixtures.user(db, "bob");
        video = Fixtures.video(db, alice, "clip");
    }

    @Test
    void commentsAndRepliesMaintainCounts() {
        Comment top = comments.insert(video.getId(), bob.getId(), null, "Great video!");
        Comment reply = comments.insert(video.getId(), alice.getId(), top.getId(), "Thanks!");
        comments.insert(video.getId(), bob.getId(), null, "Second");

        assertThat(top.getAuthorHandle()).isEqualTo("bob");
        assertThat(videos.findById(video.getId()).orElseThrow().getCommentCount()).isEqualTo(3);
        assertThat(comments.findById(top.getId()).orElseThrow().getReplyCount()).isEqualTo(1);
        assertThat(comments.listTopLevel(video.getId(), null, 10)).extracting(Comment::getBody)
            .containsExactly("Second", "Great video!");
        assertThat(comments.listReplies(top.getId(), null, 10)).extracting(Comment::getId).containsExactly(reply.getId());

        assertThat(comments.updateBody(reply.getId(), "Thanks a lot!", Instant.now())).isTrue();
        assertThat(comments.findById(reply.getId()).orElseThrow().getEditedAt()).isNotNull();
    }

    @Test
    void deletedCommentStaysWhileItHasReplies() {
        Comment top = comments.insert(video.getId(), bob.getId(), null, "first");
        Comment reply = comments.insert(video.getId(), alice.getId(), top.getId(), "reply");

        assertThat(comments.delete(top.getId(), Instant.now())).isTrue();
        assertThat(comments.delete(top.getId(), Instant.now())).as("already deleted").isFalse();
        assertThat(comments.listTopLevel(video.getId(), null, 10)).singleElement().satisfies(c -> assertThat(c.isDeleted()).isTrue());

        comments.delete(reply.getId(), Instant.now());
        assertThat(comments.listTopLevel(video.getId(), null, 10)).isEmpty();
        assertThat(videos.findById(video.getId()).orElseThrow().getCommentCount()).isZero();
        assertThat(comments.updateBody(top.getId(), "edit", Instant.now())).as("deleted comments are frozen").isFalse();
    }

    @Test
    void commentPagingIsKeysetBased() {
        for (int i = 0; i < 5; i++) {
            comments.insert(video.getId(), bob.getId(), null, "c" + i);
        }
        List<Comment> page1 = comments.listTopLevel(video.getId(), null, 3);
        List<Comment> page2 = comments.listTopLevel(video.getId(), page1.getLast().getId(), 3);

        assertThat(page1).extracting(Comment::getBody).containsExactly("c4", "c3", "c2");
        assertThat(page2).extracting(Comment::getBody).containsExactly("c1", "c0");
    }

    @Test
    void reactionsSwitchAndClear() {
        assertThat(social.react(video.getId(), bob.getId(), 1)).isEqualTo(new SocialRepository.Counts(1, 0));
        assertThat(social.react(video.getId(), bob.getId(), 1)).as("idempotent").isEqualTo(new SocialRepository.Counts(1, 0));
        assertThat(social.react(video.getId(), bob.getId(), -1)).isEqualTo(new SocialRepository.Counts(0, 1));
        assertThat(social.react(video.getId(), alice.getId(), -1)).isEqualTo(new SocialRepository.Counts(0, 2));
        assertThat(social.reaction(video.getId(), bob.getId())).contains(-1);
        assertThat(social.react(video.getId(), bob.getId(), 0)).isEqualTo(new SocialRepository.Counts(0, 1));
        assertThat(social.reaction(video.getId(), bob.getId())).isEmpty();
        assertThatThrownBy(() -> social.react(video.getId(), bob.getId(), 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentLikesKeepCountsExact() throws Exception {
        List<User> fans = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fans.add(Fixtures.user(db, "fan" + i));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<SocialRepository.Counts>> results = new java.util.ArrayList<>();
            for (User fan : fans) {
                results.add(pool.submit(() -> social.react(video.getId(), fan.getId(), 1)));
            }
            for (Future<SocialRepository.Counts> f : results) {
                f.get();
            }
        }
        assertThat(videos.findById(video.getId()).orElseThrow().getLikeCount()).isEqualTo(8);
    }

    @Test
    void subscriptionsAndFeed() {
        assertThat(social.subscribe(bob.getId(), alice.getId())).isEqualTo(1);
        assertThat(social.subscribe(bob.getId(), alice.getId())).as("idempotent").isEqualTo(1);
        assertThat(social.isSubscribed(bob.getId(), alice.getId())).isTrue();
        assertThat(social.subscriptions(bob.getId())).extracting(User::getHandle).containsExactly("alice");
        assertThatThrownBy(() -> social.subscribe(alice.getId(), alice.getId())).isInstanceOf(IllegalArgumentException.class);

        videos.updateMetadata(video.getId(), "clip", "", Visibility.PUBLIC);
        videos.markReady(video.getId(), 1000, 640, 360, List.of(), Instant.now());
        Video unlisted = Fixtures.video(db, alice, "hidden");
        videos.updateMetadata(unlisted.getId(), "hidden", "", Visibility.UNLISTED);
        videos.markReady(unlisted.getId(), 1000, 640, 360, List.of(), Instant.now());
        Fixtures.video(db, Fixtures.user(db, "carol"), "not subscribed");

        assertThat(videos.listSubscriptionFeed(bob.getId(), null, null, 10)).extracting(Video::getTitle).containsExactly("clip");
        assertThat(videos.listPublicByOwner(alice.getId(), null, null, 10)).extracting(Video::getTitle).containsExactly("clip");
        assertThat(videos.countPublicByOwner(alice.getId())).isEqualTo(1);

        assertThat(social.unsubscribe(bob.getId(), alice.getId())).isZero();
        assertThat(social.unsubscribe(bob.getId(), alice.getId())).isZero();
        assertThat(videos.listSubscriptionFeed(bob.getId(), null, null, 10)).isEmpty();
    }

    @Test
    void viewsCountOncePerViewerPerDay() {
        assertThat(social.recordView(video.getId(), "u:" + bob.getId())).isEqualTo(1);
        assertThat(social.recordView(video.getId(), "u:" + bob.getId())).isEqualTo(1);
        assertThat(social.recordView(video.getId(), "a:somehash")).isEqualTo(2);
        assertThat(videos.findById(video.getId()).orElseThrow().getViewCount()).isEqualTo(2);
    }
}
