package com.youstream.media;

import com.youstream.common.model.RefreshToken;
import com.youstream.common.model.UploadSession;
import com.youstream.common.model.User;
import com.youstream.common.storage.LocalDiskStorage;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import com.youstream.db.repo.RefreshTokenRepository;
import com.youstream.db.repo.UploadSessionRepository;
import com.youstream.db.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceJobsTest {

    @TempDir
    Path dir;
    private Db db;
    private LocalDiskStorage storage;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDatabase.db();
        TestDatabase.truncate();
        storage = new LocalDiskStorage(dir.resolve("data"));
        userId = new UserRepository(db).insert(User.builder().username("cleaner").displayName("Cleaner").handle("cleaner").build()).getId();
    }

    private Path file(String key, Duration age) throws Exception {
        Path p = storage.localPath(key);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(age)));
        return p;
    }

    @Test
    void removesOnlyStaleData() throws Exception {
        UploadSessionRepository uploads = new UploadSessionRepository(db);
        UUID expired = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        uploads.insert(UploadSession.builder().id(expired).userId(userId).filename("a").totalBytes(10)
            .expiresAt(Instant.now().minusSeconds(60)).build());
        uploads.insert(UploadSession.builder().id(active).userId(userId).filename("b").totalBytes(10)
            .expiresAt(Instant.now().plusSeconds(3600)).build());
        Path expiredFile = file("incoming/" + expired, Duration.ofMinutes(5));
        Path activeFile = file("incoming/" + active, Duration.ofMinutes(5));
        Path orphan = file("incoming/sftp-" + UUID.randomUUID(), Duration.ofDays(2));
        Path staleTmp = file("hls/AAAAAAAAAAA.tmp/360p/index.m3u8", Duration.ofDays(2));
        Files.setLastModifiedTime(staleTmp.getParent().getParent(), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path runningTmp = file("hls/BBBBBBBBBBB.tmp/360p/index.m3u8", Duration.ofMinutes(1));
        Path finished = file("hls/CCCCCCCCCCC/master.m3u8", Duration.ofDays(400));
        Path oldStatus = file("sftp/" + userId + "/trips/a.mp4.status", Duration.ofDays(40));
        Path newStatus = file("sftp/" + userId + "/b.mp4.status", Duration.ofDays(1));
        Path upload = file("sftp/" + userId + "/c.json", Duration.ofDays(40));
        new RefreshTokenRepository(db).insert(RefreshToken.builder().userId(userId).tokenHash("old")
            .expiresAt(Instant.now().minus(Duration.ofDays(30))).build());
        new RefreshTokenRepository(db).insert(RefreshToken.builder().userId(userId).tokenHash("live")
            .expiresAt(Instant.now().plus(Duration.ofDays(30))).build());

        MaintenanceJobs.Report report = new MaintenanceJobs(db, storage, Clock.systemUTC()).runOnce();

        assertThat(report.getUploadSessions()).isEqualTo(1);
        assertThat(report.getRefreshTokens()).isEqualTo(1);
        assertThat(expiredFile).doesNotExist();
        assertThat(activeFile).exists();
        assertThat(orphan).doesNotExist();
        assertThat(staleTmp.getParent().getParent()).doesNotExist();
        assertThat(runningTmp).exists();
        assertThat(finished).exists();
        assertThat(oldStatus).doesNotExist();
        assertThat(newStatus).exists();
        assertThat(upload).as("only .status files are cleaned in SFTP folders").exists();
        assertThat(uploads.find(active, userId)).isPresent();
        assertThat(new RefreshTokenRepository(db).findByHash("live")).isPresent();
    }
}
