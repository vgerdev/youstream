package com.youstream.media;

import com.youstream.common.model.UploadSession;
import com.youstream.common.storage.StorageService;
import com.youstream.db.Db;
import com.youstream.db.repo.RefreshTokenRepository;
import com.youstream.db.repo.SocialRepository;
import com.youstream.db.repo.UploadSessionRepository;
import lombok.Builder;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Periodic housekeeping: expired upload sessions and their partial files, leftover files in {@code incoming/},
 * stale transcode temp folders, old SFTP status files, expired refresh tokens and day-old view dedup rows.
 */
public class MaintenanceJobs implements AutoCloseable {

    /** What one run removed. */
    @Value
    @Builder
    public static class Report {
        int uploadSessions;
        int incomingFiles;
        int tempFolders;
        int statusFiles;
        int refreshTokens;
        int viewRows;
    }

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);
    /** Longer than an upload session lives (24 h), so only abandoned files are touched. */
    private static final Duration INCOMING_MAX_AGE = Duration.ofHours(26);
    /** Longer than any transcode may run. */
    private static final Duration TEMP_MAX_AGE = Duration.ofHours(24);
    private static final Duration STATUS_MAX_AGE = Duration.ofDays(30);
    private static final Duration TOKEN_GRACE = Duration.ofDays(7);

    private final StorageService storage;
    private final Clock clock;
    private final UploadSessionRepository uploads;
    private final RefreshTokenRepository tokens;
    private final SocialRepository social;
    private ScheduledExecutorService scheduler;

    public MaintenanceJobs(Db db, StorageService storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
        this.uploads = new UploadSessionRepository(db);
        this.tokens = new RefreshTokenRepository(db);
        this.social = new SocialRepository(db);
    }

    public void start(Duration every) {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("maintenance").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                Report r = runOnce();
                log.info("Maintenance: {}", r);
            } catch (RuntimeException e) {
                log.error("Maintenance run failed", e);
            }
        }, 1, every.toMinutes(), TimeUnit.MINUTES);
    }

    public Report runOnce() {
        Instant now = clock.instant();
        return Report.builder()
            .uploadSessions(expiredUploads(now))
            .incomingFiles(deleteOlderThan("incoming", now.minus(INCOMING_MAX_AGE), p -> true, false))
            .tempFolders(deleteOlderThan("hls", now.minus(TEMP_MAX_AGE),
                p -> p.getFileName().toString().endsWith(".tmp"), false))
            .statusFiles(deleteOlderThan("sftp", now.minus(STATUS_MAX_AGE),
                p -> p.getFileName().toString().endsWith(".status"), true))
            .refreshTokens(tokens.deleteExpired(now.minus(TOKEN_GRACE)))
            .viewRows(social.deleteViewsBefore(LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1)))
            .build();
    }

    private int expiredUploads(Instant now) {
        List<UploadSession> expired = uploads.listExpired(now);
        for (UploadSession u : expired) {
            try {
                storage.delete("incoming/" + u.getId());
            } catch (IOException e) {
                log.warn("Cannot delete abandoned upload {}", u.getId(), e);
            }
            uploads.delete(u.getId());
        }
        return expired.size();
    }

    /**
     * Deletes entries directly in storage folder {@code dirKey} (or anywhere below it when {@code recursive}) that
     * match and were last modified before {@code cutoff}. Directories are deleted with their contents.
     */
    private int deleteOlderThan(String dirKey, Instant cutoff, Predicate<Path> matches, boolean recursive) {
        Path dir = storage.localPath(dirKey);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> stale;
        try (Stream<Path> entries = recursive ? Files.walk(dir) : Files.list(dir)) {
            stale = entries.filter(p -> !p.equals(dir)).filter(matches).filter(p -> modifiedBefore(p, cutoff)).toList();
        } catch (IOException e) {
            log.warn("Cannot scan {} for cleanup", dir, e);
            return 0;
        }
        int deleted = 0;
        for (Path p : stale) {
            String key = dirKey + "/" + dir.relativize(p).toString().replace('\\', '/');
            try {
                storage.deleteTree(key);
                deleted++;
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Cannot delete {}", key, e);
            }
        }
        return deleted;
    }

    private static boolean modifiedBefore(Path p, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
