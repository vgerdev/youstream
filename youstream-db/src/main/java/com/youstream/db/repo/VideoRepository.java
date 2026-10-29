package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoRendition;
import com.youstream.common.model.VideoSource;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class VideoRepository {

    private static final String COLUMNS = "id, public_id, owner_id, title, description, visibility, status, source,"
        + " original_filename, original_path, size_bytes, duration_ms, width, height, error, view_count, like_count,"
        + " dislike_count, comment_count, created_at, updated_at, published_at";

    private final Db db;

    public Video insert(Video video) {
        return db.run(s -> s
            .sql("INSERT INTO videos (public_id, owner_id, title, description, visibility, status, source,"
                + " original_filename, original_path, size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)
            .set(video.getPublicId())
            .set(video.getOwnerId())
            .set(video.getTitle())
            .set(video.getDescription())
            .set(Rows.name(video.getVisibility()))
            .set(Rows.name(video.getStatus()))
            .set(Rows.name(video.getSource()))
            .set(video.getOriginalFilename())
            .set(video.getOriginalPath())
            .set(video.getSizeBytes())
            .select(new ListOutcome<>(VideoRepository::map))
            .getFirst());
    }

    public Optional<Video> findById(long id) {
        return findOne("id = ?", id);
    }

    public Optional<Video> findByPublicId(String publicId) {
        return findOne("public_id = ?", publicId);
    }

    /** Owner's videos, newest first; pass the last id of the previous page as {@code beforeId}. */
    public List<Video> listByOwner(long ownerId, Long beforeId, int limit) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM videos WHERE owner_id = ? AND id < ? ORDER BY id DESC LIMIT ?")
            .set(ownerId)
            .set(beforeId != null ? beforeId : Long.MAX_VALUE)
            .set(limit)
            .select(new ListOutcome<>(VideoRepository::map)));
    }

    /** Public, ready videos ordered by publish time (keyset paging on published_at, id). */
    public List<Video> listPublic(Instant beforePublishedAt, Long beforeId, int limit) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM videos WHERE visibility = 'PUBLIC' AND status = 'READY'"
                + " AND (published_at, id) < (?, ?) ORDER BY published_at DESC, id DESC LIMIT ?")
            .set(Rows.ts(beforePublishedAt != null ? beforePublishedAt : Instant.parse("9999-12-31T00:00:00Z")))
            .set(beforeId != null ? beforeId : Long.MAX_VALUE)
            .set(limit)
            .select(new ListOutcome<>(VideoRepository::map)));
    }

    public boolean updateMetadata(long id, String title, String description, Visibility visibility) {
        return db.run(s -> s
            .sql("UPDATE videos SET title = ?, description = ?, visibility = ?, updated_at = now() WHERE id = ?")
            .set(title)
            .set(description)
            .set(visibility.name())
            .set(id)
            .update(Outcome.UPDATE_COUNT)) > 0;
    }

    public void updateStatus(long id, VideoStatus status, String error) {
        db.run(s -> s
            .sql("UPDATE videos SET status = ?, error = ?, updated_at = now() WHERE id = ?")
            .set(status.name())
            .set(error)
            .set(id)
            .update(Outcome.VOID));
    }

    public void updateOriginal(long id, String originalPath, long sizeBytes) {
        db.run(s -> s
            .sql("UPDATE videos SET original_path = ?, size_bytes = ?, updated_at = now() WHERE id = ?")
            .set(originalPath)
            .set(sizeBytes)
            .set(id)
            .update(Outcome.VOID));
    }

    /** Marks transcoding finished: stores probe results and renditions, sets READY and the first publish time. */
    public void markReady(long id, long durationMs, int width, int height, List<VideoRendition> renditions, Instant at) {
        db.txVoid(s -> {
            s.sql("DELETE FROM video_renditions WHERE video_id = ?").set(id).update(Outcome.VOID);
            for (VideoRendition r : renditions) {
                s.sql("INSERT INTO video_renditions (video_id, height, width, bitrate_kbps, playlist_path) VALUES (?, ?, ?, ?, ?)")
                    .set(id).set(r.getHeight()).set(r.getWidth()).set(r.getBitrateKbps()).set(r.getPlaylistPath())
                    .update(Outcome.VOID);
            }
            s.sql("UPDATE videos SET status = 'READY', error = NULL, duration_ms = ?, width = ?, height = ?,"
                    + " published_at = COALESCE(published_at, ?), updated_at = now() WHERE id = ?")
                .set(durationMs).set(width).set(height).set(Rows.ts(at)).set(id)
                .update(Outcome.VOID);
        });
    }

    public List<VideoRendition> listRenditions(long videoId) {
        return db.run(s -> s
            .sql("SELECT video_id, height, width, bitrate_kbps, playlist_path FROM video_renditions"
                + " WHERE video_id = ? ORDER BY height DESC")
            .set(videoId)
            .select(new ListOutcome<>(rs -> VideoRendition.builder()
                .videoId(rs.getLong("video_id"))
                .height(rs.getInt("height"))
                .width(rs.getInt("width"))
                .bitrateKbps(rs.getInt("bitrate_kbps"))
                .playlistPath(rs.getString("playlist_path"))
                .build())));
    }

    public boolean delete(long id) {
        return db.run(s -> s.sql("DELETE FROM videos WHERE id = ?").set(id).update(Outcome.UPDATE_COUNT)) > 0;
    }

    private Optional<Video> findOne(String where, Object value) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM videos WHERE " + where)
            .set(value)
            .select(new ListOutcome<>(VideoRepository::map))
            .stream().findFirst());
    }

    static Video map(ResultSet rs) throws SQLException {
        return Video.builder()
            .id(rs.getLong("id"))
            .publicId(rs.getString("public_id"))
            .ownerId(rs.getLong("owner_id"))
            .title(rs.getString("title"))
            .description(rs.getString("description"))
            .visibility(Rows.enumValue(rs, "visibility", Visibility.class))
            .status(Rows.enumValue(rs, "status", VideoStatus.class))
            .source(Rows.enumValue(rs, "source", VideoSource.class))
            .originalFilename(rs.getString("original_filename"))
            .originalPath(rs.getString("original_path"))
            .sizeBytes(rs.getLong("size_bytes"))
            .durationMs(Rows.nullableLong(rs, "duration_ms"))
            .width(Rows.nullableInt(rs, "width"))
            .height(Rows.nullableInt(rs, "height"))
            .error(rs.getString("error"))
            .viewCount(rs.getLong("view_count"))
            .likeCount(rs.getLong("like_count"))
            .dislikeCount(rs.getLong("dislike_count"))
            .commentCount(rs.getLong("comment_count"))
            .createdAt(Rows.instant(rs, "created_at"))
            .updatedAt(Rows.instant(rs, "updated_at"))
            .publishedAt(Rows.instant(rs, "published_at"))
            .build();
    }
}
