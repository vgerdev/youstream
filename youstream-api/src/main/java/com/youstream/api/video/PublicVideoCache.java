package com.youstream.api.video;

import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.db.repo.VideoRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived cache of "is this video public and ready?" for {@code pub}-token media requests, so HLS segment
 * requests do not each hit the database. Edits and deletes must call {@link #invalidate(String)}.
 */
public class PublicVideoCache {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(boolean playable, Instant expires) {
    }

    private final VideoRepository videos;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public PublicVideoCache(VideoRepository videos) {
        this.videos = videos;
    }

    public boolean isPublicAndReady(String publicId) {
        Instant now = Instant.now();
        Entry entry = entries.get(publicId);
        if (entry == null || entry.expires().isBefore(now)) {
            boolean playable = videos.findByPublicId(publicId)
                .map(v -> v.getVisibility() == Visibility.PUBLIC && v.getStatus() == VideoStatus.READY)
                .orElse(false);
            entry = new Entry(playable, now.plus(TTL));
            if (entries.size() > MAX_ENTRIES) {
                entries.clear();
            }
            entries.put(publicId, entry);
        }
        return entry.playable();
    }

    public void invalidate(String publicId) {
        entries.remove(publicId);
    }
}
