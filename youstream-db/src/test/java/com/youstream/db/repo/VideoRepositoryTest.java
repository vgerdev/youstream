package com.youstream.db.repo;

import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoRendition;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoRepositoryTest {

    private Db db;
    private VideoRepository videos;
    private User owner;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        videos = new VideoRepository(db);
        owner = Fixtures.user(db, "owner");
    }

    @Test
    void insertAppliesDefaults() {
        Video v = Fixtures.video(db, owner, "first");

        assertThat(v.getId()).isPositive();
        assertThat(v.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(v.getStatus()).isEqualTo(VideoStatus.UPLOADED);
        assertThat(v.getPublishedAt()).isNull();
        assertThat(videos.findByPublicId(v.getPublicId())).contains(v);
    }

    @Test
    void markReadyStoresRenditionsAndPublishesOnce() {
        Video v = Fixtures.video(db, owner, "clip");
        Instant first = Instant.parse("2026-01-01T10:00:00Z");
        List<VideoRendition> ladder = List.of(
            VideoRendition.builder().height(720).width(1280).bitrateKbps(2800).playlistPath("hls/x/720p/index.m3u8").build(),
            VideoRendition.builder().height(360).width(640).bitrateKbps(800).playlistPath("hls/x/360p/index.m3u8").build());

        videos.markReady(v.getId(), 61_000, 1280, 720, ladder, first);
        videos.markReady(v.getId(), 61_000, 1280, 720, ladder.subList(0, 1), first.plusSeconds(60));

        Video ready = videos.findById(v.getId()).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(ready.getDurationMs()).isEqualTo(61_000);
        assertThat(ready.getPublishedAt()).isEqualTo(first);
        assertThat(videos.listRenditions(v.getId())).extracting(VideoRendition::getHeight).containsExactly(720);
    }

    @Test
    void publicFeedShowsOnlyPublicReadyVideosNewestFirst() {
        Video a = publish(Fixtures.video(db, owner, "a"), Visibility.PUBLIC, "2026-01-01T00:00:00Z");
        Video b = publish(Fixtures.video(db, owner, "b"), Visibility.PUBLIC, "2026-01-02T00:00:00Z");
        Video c = publish(Fixtures.video(db, owner, "c"), Visibility.PUBLIC, "2026-01-03T00:00:00Z");
        publish(Fixtures.video(db, owner, "unlisted"), Visibility.UNLISTED, "2026-01-04T00:00:00Z");
        Fixtures.video(db, owner, "processing");

        List<Video> page1 = videos.listPublic(null, null, 2);
        assertThat(page1).extracting(Video::getTitle).containsExactly("c", "b");

        Video last = page1.getLast();
        List<Video> page2 = videos.listPublic(last.getPublishedAt(), last.getId(), 2);
        assertThat(page2).extracting(Video::getId).containsExactly(a.getId());
        assertThat(List.of(a, b, c)).allSatisfy(v -> assertThat(v.getId()).isPositive());
    }

    @Test
    void listsOwnerVideosWithKeysetPaging() {
        for (int i = 0; i < 5; i++) {
            Fixtures.video(db, owner, "v" + i);
        }
        Fixtures.video(db, Fixtures.user(db, "other"), "not-mine");

        List<Video> page1 = videos.listByOwner(owner.getId(), null, 3);
        List<Video> page2 = videos.listByOwner(owner.getId(), page1.getLast().getId(), 3);

        assertThat(page1).extracting(Video::getTitle).containsExactly("v4", "v3", "v2");
        assertThat(page2).extracting(Video::getTitle).containsExactly("v1", "v0");
    }

    @Test
    void updatesMetadataStatusAndDeletes() {
        Video v = Fixtures.video(db, owner, "draft");

        assertThat(videos.updateMetadata(v.getId(), "Final", "desc", Visibility.UNLISTED)).isTrue();
        videos.updateStatus(v.getId(), VideoStatus.FAILED, "ffmpeg exited 1");
        videos.updateOriginal(v.getId(), "originals/x/source.mp4", 99);

        Video updated = videos.findById(v.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Final");
        assertThat(updated.getVisibility()).isEqualTo(Visibility.UNLISTED);
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(updated.getError()).isEqualTo("ffmpeg exited 1");
        assertThat(updated.getOriginalPath()).isEqualTo("originals/x/source.mp4");
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(v.getUpdatedAt());

        assertThat(videos.delete(v.getId())).isTrue();
        assertThat(videos.findById(v.getId())).isEmpty();
    }

    @Test
    void searchVectorIsGenerated() {
        Video v = Fixtures.video(db, owner, "Sailing the Caribbean");

        assertThat(TestDatabase.exists(
            "SELECT 1 FROM videos WHERE id = ? AND search @@ websearch_to_tsquery('english', 'sailing')", v.getId())).isTrue();
    }

    private Video publish(Video v, Visibility visibility, String at) {
        videos.updateMetadata(v.getId(), v.getTitle(), "", visibility);
        videos.markReady(v.getId(), 1000, 640, 360, List.of(), Instant.parse(at));
        return videos.findById(v.getId()).orElseThrow();
    }
}
