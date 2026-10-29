package com.youstream.auth;

import com.youstream.common.model.SshKey;
import com.youstream.common.model.User;
import com.youstream.db.TestDatabase;
import com.youstream.db.repo.UserRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshKeyServiceTest {

    private SshKeyService service;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        TestDatabase.truncate();
        UserRepository users = new UserRepository(TestDatabase.db());
        alice = users.insert(User.builder().username("alice").displayName("A").handle("alice").build()).getId();
        bob = users.insert(User.builder().username("bob").displayName("B").handle("bob").build()).getId();
        service = new SshKeyService(TestDatabase.db(), Clock.systemUTC());
    }

    static String openSsh(KeyPair kp, String comment) {
        return PublicKeyEntry.toString(kp.getPublic()) + " " + comment;
    }

    @Test
    void acceptsEd25519EcdsaAndStrongRsa() throws Exception {
        KeyPair ed = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        KeyPair ec = KeyUtils.generateKeyPair("ecdsa-sha2-nistp256", 256);
        KeyPair rsa = KeyUtils.generateKeyPair("ssh-rsa", 3072);

        SshKey edKey = service.add(alice, null, "  " + openSsh(ed, "alice@laptop") + "\n");
        service.add(alice, "work", openSsh(ec, "x"));
        service.add(alice, "old box", openSsh(rsa, "y"));

        assertThat(edKey.getName()).isEqualTo("alice@laptop");
        assertThat(edKey.getKeyType()).isEqualTo("ssh-ed25519");
        assertThat(edKey.getFingerprint()).startsWith("SHA256:");
        assertThat(service.list(alice)).extracting(SshKey::getName).containsExactly("alice@laptop", "work", "old box");
        assertThat(service.findByKey(ed.getPublic())).map(SshKey::getUserId).contains(alice);
    }

    @Test
    void rejectsWeakDuplicateAndMalformedKeys() throws Exception {
        KeyPair weak = KeyUtils.generateKeyPair("ssh-rsa", 2048);
        KeyPair ed = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        service.add(alice, null, openSsh(ed, "a"));

        assertInvalid(() -> service.add(alice, null, openSsh(weak, "weak")), "3072");
        assertInvalid(() -> service.add(bob, null, openSsh(ed, "stolen")), "already registered");
        assertInvalid(() -> service.add(alice, null, "ssh-ed25519 not-base64!!"), "not a valid");
        assertInvalid(() -> service.add(alice, null, "-----BEGIN OPENSSH PRIVATE KEY-----"), "private key");
        assertInvalid(() -> service.add(alice, null, " "), "paste");
    }

    @Test
    void deleteIsScopedToOwner() throws Exception {
        SshKey key = service.add(alice, null, openSsh(KeyUtils.generateKeyPair("ssh-ed25519", 256), "a"));

        assertThat(service.delete(bob, key.getId())).isFalse();
        assertThat(service.delete(alice, key.getId())).isTrue();
        assertThat(service.list(alice)).isEmpty();
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(SshKeyService.InvalidKeyException.class).hasMessageContaining(message);
    }
}
