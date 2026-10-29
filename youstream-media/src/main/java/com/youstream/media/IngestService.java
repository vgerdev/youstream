package com.youstream.media;

import com.youstream.common.PublicIds;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.storage.StorageService;
import com.youstream.db.Db;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single entry point for new videos from the browser and from SFTP: validates the file with ffprobe, moves it to
 * {@code originals/{publicId}/}, then creates the video row and its transcode job in one transaction.
 */
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final Pattern EXTENSION = Pattern.compile("\\.([A-Za-z0-9]{1,8})$");
    private static final int MAX_TITLE = 100;

    private final Db db;
    private final StorageService storage;
    private final Ffprobe ffprobe;
    private final VideoRepository videos;
    private final TranscodeJobRepository jobs;
    private final long quotaBytes;

    public IngestService(Db db, StorageService storage, Ffprobe ffprobe) {
        this(db, storage, ffprobe, 0);
    }

    /** @param quotaBytes per-user limit on the total size of uploaded originals; 0 = unlimited */
    public IngestService(Db db, StorageService storage, Ffprobe ffprobe, long quotaBytes) {
        this.db = db;
        this.storage = storage;
        this.ffprobe = ffprobe;
        this.videos = new VideoRepository(db);
        this.jobs = new TranscodeJobRepository(db);
        this.quotaBytes = quotaBytes;
    }

    public long quotaBytes() {
        return quotaBytes;
    }

    /** Bytes of originals the user already stores. */
    public long usedBytes(long userId) {
        return videos.totalBytesByOwner(userId);
    }

    /**
     * @throws InvalidMediaException if the file is not a video (the upload is deleted)
     * @throws QuotaExceededException if it would exceed the user's storage quota (the upload is deleted)
     */
    public Video ingest(IngestRequest req) throws IOException, InterruptedException {
        long size = storage.size(req.getSourceKey());
        if (quotaBytes > 0) {
            long used = usedBytes(req.getOwnerId());
            if (used + size > quotaBytes) {
                storage.delete(req.getSourceKey());
                throw new QuotaExceededException(used, quotaBytes);
            }
        }
        try {
            ffprobe.probe(storage.localPath(req.getSourceKey()));
        } catch (InvalidMediaException e) {
            log.info("Rejected upload '{}' from user {}: {}", req.getOriginalFilename(), req.getOwnerId(), e.getMessage());
            storage.delete(req.getSourceKey());
            throw e;
        }
        String publicId = PublicIds.next();
        String originalKey = "originals/" + publicId + "/source" + extension(req.getOriginalFilename());
        storage.move(req.getSourceKey(), originalKey);
        try {
            Video video = db.tx(s -> {
                Video created = videos.insert(Video.builder()
                    .publicId(publicId)
                    .ownerId(req.getOwnerId())
                    .title(title(req))
                    .description(req.getDescription() == null ? "" : req.getDescription())
                    .visibility(req.getVisibility())
                    .status(VideoStatus.UPLOADED)
                    .source(req.getSource())
                    .originalFilename(filename(req.getOriginalFilename()))
                    .originalPath(originalKey)
                    .sizeBytes(size)
                    .build());
                jobs.enqueue(created.getId());
                return created;
            });
            log.info("Ingested {} '{}' ({} bytes, {}) for user {}", publicId, video.getTitle(), size, req.getSource(),
                req.getOwnerId());
            return video;
        } catch (RuntimeException e) {
            storage.deleteTree("originals/" + publicId);
            throw e;
        }
    }

    static String title(IngestRequest req) {
        String title = req.getTitle();
        if (title == null || title.isBlank()) {
            title = EXTENSION.matcher(filename(req.getOriginalFilename())).replaceFirst("").replace('_', ' ').strip();
        }
        title = title.strip().replaceAll("\\s+", " ");
        if (title.isEmpty()) {
            title = "Untitled video";
        }
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE).strip() : title;
    }

    /** Last path segment only, so client-supplied names cannot carry directories. */
    static String filename(String name) {
        if (name == null || name.isBlank()) {
            return "video";
        }
        String last = name.replace('\\', '/');
        last = last.substring(last.lastIndexOf('/') + 1).strip();
        return last.isEmpty() ? "video" : (last.length() > 255 ? last.substring(last.length() - 255) : last);
    }

    static String extension(String name) {
        var m = EXTENSION.matcher(filename(name));
        return m.find() ? "." + m.group(1).toLowerCase(Locale.ROOT) : ".bin";
    }
}
