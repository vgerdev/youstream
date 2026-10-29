package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.RefreshToken;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String COLUMNS = "id, user_id, token_hash, user_agent, created_at, expires_at, revoked_at, replaced_by";

    private final Db db;

    public RefreshToken insert(RefreshToken token) {
        return db.run(s -> s
            .sql("INSERT INTO refresh_tokens (user_id, token_hash, user_agent, expires_at) VALUES (?, ?, ?, ?)"
                + " RETURNING " + COLUMNS)
            .set(token.getUserId())
            .set(token.getTokenHash())
            .set(token.getUserAgent())
            .set(Rows.ts(token.getExpiresAt()))
            .select(new ListOutcome<>(RefreshTokenRepository::map))
            .getFirst());
    }

    public Optional<RefreshToken> findByHash(String tokenHash) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM refresh_tokens WHERE token_hash = ?")
            .set(tokenHash)
            .select(new ListOutcome<>(RefreshTokenRepository::map))
            .stream().findFirst());
    }

    /**
     * Revokes a token if it is still active.
     *
     * @return false if it was already revoked (a reused refresh token)
     */
    public boolean revoke(long tokenId, Long replacedBy, Instant at) {
        return db.run(s -> s
            .sql("UPDATE refresh_tokens SET revoked_at = ?, replaced_by = ? WHERE id = ? AND revoked_at IS NULL")
            .set(Rows.ts(at))
            .set(replacedBy)
            .set(tokenId)
            .update(Outcome.UPDATE_COUNT)) > 0;
    }

    public int revokeAllForUser(long userId, Instant at) {
        return db.run(s -> s
            .sql("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
            .set(Rows.ts(at))
            .set(userId)
            .update(Outcome.UPDATE_COUNT));
    }

    public int deleteExpired(Instant before) {
        return db.run(s -> s
            .sql("DELETE FROM refresh_tokens WHERE expires_at < ?")
            .set(Rows.ts(before))
            .update(Outcome.UPDATE_COUNT));
    }

    static RefreshToken map(ResultSet rs) throws SQLException {
        return RefreshToken.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .tokenHash(rs.getString("token_hash"))
            .userAgent(rs.getString("user_agent"))
            .createdAt(Rows.instant(rs, "created_at"))
            .expiresAt(Rows.instant(rs, "expires_at"))
            .revokedAt(Rows.instant(rs, "revoked_at"))
            .replacedBy(Rows.nullableLong(rs, "replaced_by"))
            .build();
    }
}
