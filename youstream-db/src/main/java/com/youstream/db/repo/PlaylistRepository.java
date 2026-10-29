package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.Playlist;
import com.youstream.common.model.Video;
import com.youstream.common.model.Visibility;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Playlists and their ordered items; {@code item_count} is kept in step with {@code playlist_items}. */
@RequiredArgsConstructor
public class PlaylistRepository {

    private static final String COLUMNS = "p.id, p.public_id, p.owner_id, p.title, p.description, p.visibility,"
        + " p.item_count, p.created_at, p.updated_at";

    private final Db db;

    public Playlist insert(Playlist playlist) {
        return db.run(s -> s
            .sql("INSERT INTO playlists AS p (public_id, owner_id, title, description, visibility) VALUES (?, ?, ?, ?, ?)"
                + " RETURNING " + COLUMNS)
            .set(playlist.getPublicId())
            .set(playlist.getOwnerId())
            .set(playlist.getTitle())
            .set(playlist.getDescription())
            .set(playlist.getVisibility().name())
            .select(new ListOutcome<>(PlaylistRepository::map))
            .getFirst());
    }

    public Optional<Playlist> findByPublicId(String publicId) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM playlists p WHERE p.public_id = ?")
            .set(publicId)
            .select(new ListOutcome<>(PlaylistRepository::map))
            .stream().findFirst());
    }

    /** All of an owner's playlists, most recently changed first. */
    public List<Playlist> listByOwner(long ownerId) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM playlists p WHERE p.owner_id = ? ORDER BY p.updated_at DESC, p.id DESC")
            .set(ownerId)
            .select(new ListOutcome<>(PlaylistRepository::map)));
    }

    public List<Playlist> listPublicByOwner(long ownerId) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM playlists p WHERE p.owner_id = ? AND p.visibility = 'PUBLIC'"
                + " ORDER BY p.updated_at DESC, p.id DESC")
            .set(ownerId)
            .select(new ListOutcome<>(PlaylistRepository::map)));
    }

    /** Which of the owner's playlists already contain the video (for the "Save to playlist" dialog). */
    public Set<Long> playlistsContaining(long ownerId, long videoId) {
        return Set.copyOf(db.run(s -> s
            .sql("SELECT p.id FROM playlists p JOIN playlist_items i ON i.playlist_id = p.id"
                + " WHERE p.owner_id = ? AND i.video_id = ?")
            .set(ownerId)
            .set(videoId)
            .select(new ListOutcome<>(rs -> rs.getLong(1)))));
    }

    public void update(long id, String title, String description, Visibility visibility) {
        db.run(s -> s
            .sql("UPDATE playlists SET title = ?, description = ?, visibility = ?, updated_at = now() WHERE id = ?")
            .set(title).set(description).set(visibility.name()).set(id)
            .update(Outcome.VOID));
    }

    public boolean delete(long id) {
        return db.run(s -> s.sql("DELETE FROM playlists WHERE id = ?").set(id).update(Outcome.UPDATE_COUNT)) > 0;
    }

    /**
     * Appends a video; adding one that is already in the playlist changes nothing.
     *
     * @return false if it was already there
     */
    public boolean addItem(long playlistId, long videoId) {
        return db.tx(s -> {
            s.sql("SELECT id FROM playlists WHERE id = ? FOR UPDATE").set(playlistId).select(Outcome.NOT_EMPTY);
            boolean added = !s.sql("INSERT INTO playlist_items (playlist_id, video_id, position)"
                    + " SELECT ?, ?, COALESCE(MAX(position), 0) + 1 FROM playlist_items WHERE playlist_id = ?"
                    + " ON CONFLICT DO NOTHING RETURNING 1")
                .set(playlistId).set(videoId).set(playlistId)
                .select(new ListOutcome<>(rs -> rs.getInt(1))).isEmpty();
            if (added) {
                s.sql("UPDATE playlists SET item_count = item_count + 1, updated_at = now() WHERE id = ?")
                    .set(playlistId).update(Outcome.VOID);
            }
            return added;
        });
    }

    public boolean removeItem(long playlistId, long videoId) {
        return db.tx(s -> {
            boolean removed = s.sql("DELETE FROM playlist_items WHERE playlist_id = ? AND video_id = ?")
                .set(playlistId).set(videoId).update(Outcome.UPDATE_COUNT) > 0;
            if (removed) {
                s.sql("UPDATE playlists SET item_count = GREATEST(item_count - 1, 0), updated_at = now() WHERE id = ?")
                    .set(playlistId).update(Outcome.VOID);
            }
            return removed;
        });
    }

    /**
     * Puts the given videos first, in this order; videos not listed keep their relative order after them.
     */
    public void reorder(long playlistId, List<Long> videoIds) {
        db.txVoid(s -> {
            List<Long> current = s.sql("SELECT video_id FROM playlist_items WHERE playlist_id = ? ORDER BY position FOR UPDATE")
                .set(playlistId)
                .select(new ListOutcome<>(rs -> rs.getLong(1)));
            List<Long> ordered = new java.util.ArrayList<>(videoIds.stream().filter(current::contains).distinct().toList());
            current.stream().filter(id -> !ordered.contains(id)).forEach(ordered::add);
            for (int i = 0; i < ordered.size(); i++) {
                s.sql("UPDATE playlist_items SET position = ? WHERE playlist_id = ? AND video_id = ?")
                    .set(i + 1).set(playlistId).set(ordered.get(i)).update(Outcome.VOID);
            }
            s.sql("UPDATE playlists SET updated_at = now() WHERE id = ?").set(playlistId).update(Outcome.VOID);
        });
    }

    /** Items in playlist order. */
    public List<Video> items(long playlistId) {
        return db.run(s -> s
            .sql("SELECT v.id, v.public_id, v.owner_id, v.title, v.description, v.visibility, v.status, v.source,"
                + " v.original_filename, v.original_path, v.size_bytes, v.duration_ms, v.width, v.height, v.error,"
                + " v.view_count, v.like_count, v.dislike_count, v.comment_count, v.created_at, v.updated_at, v.published_at"
                + " FROM playlist_items i JOIN videos v ON v.id = i.video_id WHERE i.playlist_id = ? ORDER BY i.position")
            .set(playlistId)
            .select(new ListOutcome<>(VideoRepository::map)));
    }

    /** Public playlists matching a full-text query, best match first. */
    public List<Playlist> search(String tsQuery, int limit, int offset) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM playlists p WHERE p.visibility = 'PUBLIC' AND p.item_count > 0"
                + " AND p.search @@ to_tsquery('english', ?)"
                + " ORDER BY ts_rank(p.search, to_tsquery('english', ?)) DESC, p.updated_at DESC, p.id DESC LIMIT ? OFFSET ?")
            .set(tsQuery).set(tsQuery).set(limit).set(offset)
            .select(new ListOutcome<>(PlaylistRepository::map)));
    }

    static Playlist map(ResultSet rs) throws SQLException {
        return Playlist.builder()
            .id(rs.getLong("id"))
            .publicId(rs.getString("public_id"))
            .ownerId(rs.getLong("owner_id"))
            .title(rs.getString("title"))
            .description(rs.getString("description"))
            .visibility(Rows.enumValue(rs, "visibility", Visibility.class))
            .itemCount(rs.getInt("item_count"))
            .createdAt(Rows.instant(rs, "created_at"))
            .updatedAt(Rows.instant(rs, "updated_at"))
            .build();
    }
}
