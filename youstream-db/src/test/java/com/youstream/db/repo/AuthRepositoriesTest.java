package com.youstream.db.repo;

import com.youstream.common.model.RefreshToken;
import com.youstream.common.model.SshKey;
import com.youstream.common.model.UploadSession;
import com.youstream.common.model.User;
import com.youstream.db.DataAccessException;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Refresh tokens, SSH keys and upload sessions. */
class AuthRepositoriesTest {

    private Db db;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        alice = Fixtures.user(db, "alice");
        bob = Fixtures.user(db, "bob");
    }

    @Test
    void refreshTokenRotation() {
        RefreshTokenRepository tokens = new RefreshTokenRepository(db);
        Instant now = Instant.now();
        RefreshToken t1 = tokens.insert(RefreshToken.builder()
            .userId(alice.getId()).tokenHash("h1").userAgent("test").expiresAt(now.plusSeconds(3600)).build());
        RefreshToken t2 = tokens.insert(RefreshToken.builder()
            .userId(alice.getId()).tokenHash("h2").expiresAt(now.plusSeconds(3600)).build());

        assertThat(tokens.findByHash("h1")).map(t -> t.isActive(now)).contains(true);
        assertThat(tokens.revoke(t1.getId(), t2.getId(), now)).isTrue();
        assertThat(tokens.revoke(t1.getId(), null, now)).as("reuse detected").isFalse();

        RefreshToken revoked = tokens.findByHash("h1").orElseThrow();
        assertThat(revoked.isActive(now)).isFalse();
        assertThat(revoked.getReplacedBy()).isEqualTo(t2.getId());

        assertThat(tokens.revokeAllForUser(alice.getId(), now)).isEqualTo(1);
        assertThat(tokens.deleteExpired(now.plusSeconds(7200))).isEqualTo(2);
    }

    @Test
    void sshKeysAreUniqueAndScopedToOwner() {
        SshKeyRepository keys = new SshKeyRepository(db);
        SshKey key = keys.insert(SshKey.builder()
            .userId(alice.getId()).name("laptop").keyType("ssh-ed25519")
            .fingerprint("SHA256:abc").publicKey("ssh-ed25519 AAAA alice@laptop").build());

        assertThat(keys.findByFingerprint("SHA256:abc")).map(SshKey::getUserId).contains(alice.getId());
        assertThat(keys.listByUser(alice.getId())).containsExactly(key);
        assertThatThrownBy(() -> keys.insert(key.toBuilder().userId(bob.getId()).build()))
            .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(e.isUniqueViolation()).isTrue());

        Instant used = Instant.parse("2026-05-01T12:00:00Z");
        keys.markUsed(key.getId(), used);
        assertThat(keys.findByFingerprint("SHA256:abc").orElseThrow().getLastUsedAt()).isEqualTo(used);

        assertThat(keys.delete(bob.getId(), key.getId())).as("not bob's key").isFalse();
        assertThat(keys.delete(alice.getId(), key.getId())).isTrue();
        assertThat(keys.listByUser(alice.getId())).isEmpty();
    }

    @Test
    void uploadSessionsAreScopedToOwner() {
        UploadSessionRepository uploads = new UploadSessionRepository(db);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        uploads.insert(UploadSession.builder()
            .id(id).userId(alice.getId()).filename("clip.mp4").totalBytes(100).expiresAt(now.plusSeconds(60)).build());

        uploads.updateReceived(id, 40);
        assertThat(uploads.find(id, alice.getId())).map(UploadSession::getReceivedBytes).contains(40L);
        assertThat(uploads.find(id, bob.getId())).isEmpty();

        assertThat(uploads.deleteExpired(now)).isZero();
        assertThat(uploads.deleteExpired(now.plusSeconds(120))).isEqualTo(1);
    }
}
