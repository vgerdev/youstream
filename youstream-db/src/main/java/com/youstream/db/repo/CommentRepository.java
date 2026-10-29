package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.Comment;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Comments with one level of replies. {@code videos.comment_count} and {@code comments.reply_count} count
 * comments that are not deleted and are maintained in the same transaction as each change.
 */
@RequiredArgsConstructor
public class CommentRepository {

    private static final String COLUMNS = "c.id, c.video_id, c.user_id, c.parent_id, c.body, c.reply_count, c.created_at,"
        + " c.edited_at, c.deleted_at, u.handle AS author_handle, u.display_name AS author_display_name";
    private static final String FROM = " FROM comments c JOIN users u ON u.id = c.user_id";

    private final Db db;

    public Comment insert(long videoId, long userId, Long parentId, String body) {
        return db.tx(s -> {
            long id = s.sql("INSERT INTO comments (video_id, user_id, parent_id, body) VALUES (?, ?, ?, ?) RETURNING id")
                .set(videoId).set(userId).set(parentId).set(body)
                .select(new ListOutcome<>(rs -> rs.getLong(1))).getFirst();
            s.sql("UPDATE videos SET comment_count = comment_count + 1 WHERE id = ?").set(videoId).update(Outcome.VOID);
            if (parentId != null) {
                s.sql("UPDATE comments SET reply_count = reply_count + 1 WHERE id = ?").set(parentId).update(Outcome.VOID);
            }
            return findById(id).orElseThrow();
        });
    }

    public Optional<Comment> findById(long id) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + FROM + " WHERE c.id = ?")
            .set(id)
            .select(new ListOutcome<>(CommentRepository::map))
            .stream().findFirst());
    }

    /**
     * Top-level comments, newest first. Deleted comments are kept only while they still have replies
     * (shown as "deleted" so the thread stays readable).
     */
    public List<Comment> listTopLevel(long videoId, Long beforeId, int limit) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + FROM + " WHERE c.video_id = ? AND c.parent_id IS NULL"
                + " AND (c.deleted_at IS NULL OR c.reply_count > 0) AND c.id < ? ORDER BY c.id DESC LIMIT ?")
            .set(videoId)
            .set(beforeId != null ? beforeId : Long.MAX_VALUE)
            .set(limit)
            .select(new ListOutcome<>(CommentRepository::map)));
    }

    /** Replies to a comment, oldest first. */
    public List<Comment> listReplies(long parentId, Long afterId, int limit) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + FROM + " WHERE c.parent_id = ? AND c.deleted_at IS NULL AND c.id > ?"
                + " ORDER BY c.id LIMIT ?")
            .set(parentId)
            .set(afterId != null ? afterId : 0L)
            .set(limit)
            .select(new ListOutcome<>(CommentRepository::map)));
    }

    public boolean updateBody(long id, String body, Instant at) {
        return db.run(s -> s
            .sql("UPDATE comments SET body = ?, edited_at = ? WHERE id = ? AND deleted_at IS NULL")
            .set(body)
            .set(Rows.ts(at))
            .set(id)
            .update(Outcome.UPDATE_COUNT)) > 0;
    }

    /** Soft delete; returns false if it was already deleted. */
    public boolean delete(long id, Instant at) {
        return db.tx(s -> {
            List<Comment> found = s.sql("SELECT " + COLUMNS + FROM + " WHERE c.id = ? AND c.deleted_at IS NULL FOR UPDATE OF c")
                .set(id)
                .select(new ListOutcome<>(CommentRepository::map));
            if (found.isEmpty()) {
                return false;
            }
            Comment c = found.getFirst();
            s.sql("UPDATE comments SET deleted_at = ? WHERE id = ?").set(Rows.ts(at)).set(id).update(Outcome.VOID);
            s.sql("UPDATE videos SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = ?")
                .set(c.getVideoId()).update(Outcome.VOID);
            if (c.getParentId() != null) {
                s.sql("UPDATE comments SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = ?")
                    .set(c.getParentId()).update(Outcome.VOID);
            }
            return true;
        });
    }

    static Comment map(ResultSet rs) throws SQLException {
        return Comment.builder()
            .id(rs.getLong("id"))
            .videoId(rs.getLong("video_id"))
            .userId(rs.getLong("user_id"))
            .parentId(Rows.nullableLong(rs, "parent_id"))
            .body(rs.getString("body"))
            .replyCount(rs.getInt("reply_count"))
            .createdAt(Rows.instant(rs, "created_at"))
            .editedAt(Rows.instant(rs, "edited_at"))
            .deletedAt(Rows.instant(rs, "deleted_at"))
            .authorHandle(rs.getString("author_handle"))
            .authorDisplayName(rs.getString("author_display_name"))
            .build();
    }
}
