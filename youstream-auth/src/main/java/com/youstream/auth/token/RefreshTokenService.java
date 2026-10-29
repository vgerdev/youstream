package com.youstream.auth.token;

import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.common.model.RefreshToken;
import com.youstream.db.Db;
import com.youstream.db.repo.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque, rotating refresh tokens. Only a SHA-256 hash is stored; presenting an already rotated token
 * is treated as theft and revokes every session of that user.
 */
public class RefreshTokenService {

    public record Issued(long userId, String token, Instant expiresAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final RefreshTokenRepository tokens;
    private final Duration ttl;
    private final Clock clock;

    public RefreshTokenService(Db db, Duration ttl, Clock clock) {
        this.db = db;
        this.tokens = new RefreshTokenRepository(db);
        this.ttl = ttl;
        this.clock = clock;
    }

    public Duration ttl() {
        return ttl;
    }

    public Issued issue(long userId, String userAgent) {
        String raw = newToken();
        RefreshToken stored = store(userId, raw, userAgent);
        return new Issued(userId, raw, stored.getExpiresAt());
    }

    /** Exchanges a valid refresh token for a new one. */
    public Issued rotate(String raw, String userAgent) {
        if (raw == null || raw.isBlank()) {
            throw new AuthException(Reason.INVALID_TOKEN, "missing refresh token");
        }
        Instant now = clock.instant();
        RefreshToken current = tokens.findByHash(hash(raw))
            .orElseThrow(() -> new AuthException(Reason.INVALID_TOKEN, "unknown refresh token"));
        if (current.getRevokedAt() != null) {
            throw reuseDetected(current.getUserId(), now);
        }
        if (!current.isActive(now)) {
            throw new AuthException(Reason.INVALID_TOKEN, "expired refresh token");
        }
        String nextRaw = newToken();
        RefreshToken next = db.tx(s -> {
            RefreshToken stored = store(current.getUserId(), nextRaw, userAgent);
            // conditional on revoked_at IS NULL: fails if a concurrent request rotated this token first
            return tokens.revoke(current.getId(), stored.getId(), now) ? stored : null;
        });
        if (next == null) {
            throw reuseDetected(current.getUserId(), now);
        }
        return new Issued(current.getUserId(), nextRaw, next.getExpiresAt());
    }

    public void revoke(String raw) {
        if (raw != null && !raw.isBlank()) {
            tokens.findByHash(hash(raw)).ifPresent(t -> tokens.revoke(t.getId(), null, clock.instant()));
        }
    }

    public void revokeAll(long userId) {
        tokens.revokeAllForUser(userId, clock.instant());
    }

    private RefreshToken store(long userId, String raw, String userAgent) {
        return tokens.insert(RefreshToken.builder()
            .userId(userId)
            .tokenHash(hash(raw))
            .userAgent(truncate(userAgent))
            .expiresAt(clock.instant().plus(ttl))
            .build());
    }

    private AuthException reuseDetected(long userId, Instant now) {
        log.warn("Refresh token reuse for user {}; revoking all sessions", userId);
        tokens.revokeAllForUser(userId, now);
        return new AuthException(Reason.INVALID_TOKEN, "refresh token reuse detected");
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String truncate(String userAgent) {
        return userAgent == null || userAgent.length() <= 255 ? userAgent : userAgent.substring(0, 255);
    }
}
