package com.youstream.media;

import com.youstream.common.model.JobStatus;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoRendition;
import com.youstream.common.model.VideoSource;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.common.storage.LocalDiskStorage;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestAndWorkerTest {

    @TempDir
    Path dir;
    private Db db;
    private LocalDiskStorage storage;
    private IngestService ingest;
    private TranscodeWorkerPool workers;
    private long ownerId;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDatabase.db();
        TestDatabase.truncate();
        storage = new LocalDiskStorage(dir.resolve("data"));
        ProcessRunner runner = new ProcessRunner();
        ingest = new IngestService(db, storage, new Ffprobe(MediaTestSupport.FFPROBE, runner));
        workers = new TranscodeWorkerPool(db, storage, MediaTestSupport.config(), runner, Clock.systemUTC());
        ownerId = new UserRepository(db).insert(User.builder().username("owner").displayName("Owner").handle("owner").build()).getId();
    }

    @Test
    void namesAreSanitized() {
        assertThat(IngestService.extension("C:\\Users\\me\\Holiday.MOV")).isEqualTo(".mov");
        assertThat(IngestService.extension("noext")).isEqualTo(".bin");
        assertThat(IngestService.filename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(IngestService.title(IngestRequest.builder().originalFilename("my_trip  2026.mp4").build()))
            .isEqualTo("my trip 2026");
        assertThat(IngestService.title(IngestRequest.builder().originalFilename("x.mp4").title("  Real   title ").build()))
            .isEqualTo("Real title");
    }

    @Test
    void ingestThenTranscodeMakesVideoReady() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        MediaTestSupport.generateClip(storage.localPath("incoming/u1"), 640, 360, 4, true);

        Video video = ingest.ingest(IngestRequest.builder()
            .ownerId(ownerId).sourceKey("incoming/u1").originalFilename("Beach day.mp4")
            .visibility(Visibility.UNLISTED).source(VideoSource.WEB).build());

        assertThat(video.getTitle()).isEqualTo("Beach day");
        assertThat(video.getStatus()).isEqualTo(VideoStatus.UPLOADED);
        assertThat(storage.exists("incoming/u1")).isFalse();
        assertThat(storage.exists(video.getOriginalPath())).isTrue();

        assertThat(workers.runOnce("test-worker")).isTrue();
        assertThat(workers.runOnce("test-worker")).isFalse();

        Video ready = new VideoRepository(db).findById(video.getId()).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(ready.getDurationMs()).isBetween(3_900L, 4_100L);
        assertThat(ready.getHeight()).isEqualTo(360);
        assertThat(ready.getPublishedAt()).isNotNull();
        assertThat(new VideoRepository(db).listRenditions(video.getId()))
            .extracting(VideoRendition::getPlaylistPath).containsExactly("hls/" + video.getPublicId() + "/360p/index.m3u8");
        assertThat(storage.exists("hls/" + video.getPublicId() + "/master.m3u8")).isTrue();
        assertThat(storage.exists("hls/" + video.getPublicId() + ".tmp")).isFalse();
        assertThat(storage.exists("thumbs/" + video.getPublicId() + "/poster.jpg")).isTrue();
        assertThat(new TranscodeJobRepository(db).findLatestForVideo(video.getId()).orElseThrow().getStatus())
            .isEqualTo(JobStatus.DONE);
    }

    @Test
    void rejectsNonVideoUploadAndDeletesIt() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        Files.createDirectories(storage.localPath("incoming"));
        Files.writeString(storage.localPath("incoming/u2"), "not a video");

        assertThatThrownBy(() -> ingest.ingest(IngestRequest.builder()
            .ownerId(ownerId).sourceKey("incoming/u2").originalFilename("evil.mp4").source(VideoSource.WEB).build()))
            .isInstanceOf(InvalidMediaException.class);
        assertThat(storage.exists("incoming/u2")).isFalse();
        assertThat(new VideoRepository(db).listByOwner(ownerId, null, 10)).isEmpty();
    }

    @Test
    void brokenOriginalFailsTheVideo() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        MediaTestSupport.generateClip(storage.localPath("incoming/u3"), 320, 180, 2, false);
        Video video = ingest.ingest(IngestRequest.builder()
            .ownerId(ownerId).sourceKey("incoming/u3").originalFilename("clip.mp4").source(VideoSource.WEB).build());
        // the original gets corrupted after ingest
        Files.writeString(storage.localPath(video.getOriginalPath()), "garbage");

        workers.runOnce("test-worker");

        Video failed = new VideoRepository(db).findById(video.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(failed.getError()).isNotBlank();
        assertThat(workers.runOnce("test-worker")).as("invalid media is not retried").isFalse();
    }
}
