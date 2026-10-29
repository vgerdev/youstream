package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.SshKey;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class SshKeyRepository {

    private static final String COLUMNS = "id, user_id, name, key_type, fingerprint, public_key, created_at, last_used_at";

    private final Db db;

    public SshKey insert(SshKey key) {
        return db.run(s -> s
            .sql("INSERT INTO ssh_keys (user_id, name, key_type, fingerprint, public_key) VALUES (?, ?, ?, ?, ?)"
                + " RETURNING " + COLUMNS)
            .set(key.getUserId())
            .set(key.getName())
            .set(key.getKeyType())
            .set(key.getFingerprint())
            .set(key.getPublicKey())
            .select(new ListOutcome<>(SshKeyRepository::map))
            .getFirst());
    }

    public List<SshKey> listByUser(long userId) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM ssh_keys WHERE user_id = ? ORDER BY created_at, id")
            .set(userId)
            .select(new ListOutcome<>(SshKeyRepository::map)));
    }

    public Optional<SshKey> findByFingerprint(String fingerprint) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM ssh_keys WHERE fingerprint = ?")
            .set(fingerprint)
            .select(new ListOutcome<>(SshKeyRepository::map))
            .stream().findFirst());
    }

    /** Deletes a key only if it belongs to the user. */
    public boolean delete(long userId, long keyId) {
        return db.run(s -> s
            .sql("DELETE FROM ssh_keys WHERE id = ? AND user_id = ?")
            .set(keyId)
            .set(userId)
            .update(Outcome.UPDATE_COUNT)) > 0;
    }

    public void markUsed(long keyId, Instant at) {
        db.run(s -> s
            .sql("UPDATE ssh_keys SET last_used_at = ? WHERE id = ?")
            .set(Rows.ts(at))
            .set(keyId)
            .update(Outcome.VOID));
    }

    static SshKey map(ResultSet rs) throws SQLException {
        return SshKey.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .name(rs.getString("name"))
            .keyType(rs.getString("key_type"))
            .fingerprint(rs.getString("fingerprint"))
            .publicKey(rs.getString("public_key"))
            .createdAt(Rows.instant(rs, "created_at"))
            .lastUsedAt(Rows.instant(rs, "last_used_at"))
            .build();
    }
}
