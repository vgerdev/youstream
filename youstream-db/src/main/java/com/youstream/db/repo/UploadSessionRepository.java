package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.UploadSession;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public class UploadSessionRepository {

    private static final String COLUMNS = "id, user_id, filename, total_bytes, received_bytes, created_at, expires_at";

    private final Db db;

    public UploadSession insert(UploadSession upload) {
        return db.run(s -> s
            .sql("INSERT INTO upload_sessions (id, user_id, filename, total_bytes, expires_at) VALUES (?, ?, ?, ?, ?)"
                + " RETURNING " + COLUMNS)
            .set(upload.getId())
            .set(upload.getUserId())
            .set(upload.getFilename())
            .set(upload.getTotalBytes())
            .set(Rows.ts(upload.getExpiresAt()))
            .select(new ListOutcome<>(UploadSessionRepository::map))
            .getFirst());
    }

    /** Finds an upload only if it belongs to the user. */
    public Optional<UploadSession> find(UUID id, long userId) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM upload_sessions WHERE id = ? AND user_id = ?")
            .set(id)
            .set(userId)
            .select(new ListOutcome<>(UploadSessionRepository::map))
            .stream().findFirst());
    }

    public void updateReceived(UUID id, long receivedBytes) {
        db.run(s -> s
            .sql("UPDATE upload_sessions SET received_bytes = ? WHERE id = ?")
            .set(receivedBytes)
            .set(id)
            .update(Outcome.VOID));
    }

    public void delete(UUID id) {
        db.run(s -> s.sql("DELETE FROM upload_sessions WHERE id = ?").set(id).update(Outcome.VOID));
    }

    public int deleteExpired(Instant now) {
        return db.run(s -> s
            .sql("DELETE FROM upload_sessions WHERE expires_at < ?")
            .set(Rows.ts(now))
            .update(Outcome.UPDATE_COUNT));
    }

    static UploadSession map(ResultSet rs) throws SQLException {
        return UploadSession.builder()
            .id(Rows.uuid(rs, "id"))
            .userId(rs.getLong("user_id"))
            .filename(rs.getString("filename"))
            .totalBytes(rs.getLong("total_bytes"))
            .receivedBytes(rs.getLong("received_bytes"))
            .createdAt(Rows.instant(rs, "created_at"))
            .expiresAt(Rows.instant(rs, "expires_at"))
            .build();
    }
}
