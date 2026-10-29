package com.youstream.auth;

import com.youstream.common.model.SshKey;
import com.youstream.db.DataAccessException;
import com.youstream.db.Db;
import com.youstream.db.repo.SshKeyRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.digest.BuiltinDigests;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SSH public keys users register for SFTP uploads. */
public class SshKeyService {

    /** Raised for unparseable, weak or duplicate keys; the message is safe to show to the user. */
    public static class InvalidKeyException extends RuntimeException {
        public InvalidKeyException(String message) {
            super(message);
        }
    }

    private static final Set<String> ALLOWED_TYPES = Set.of(
        KeyUtils.EC_ALGORITHM, "EdDSA", "Ed25519", KeyUtils.RSA_ALGORITHM);
    private static final int MIN_RSA_BITS = 3072;
    private static final int MAX_KEYS = 20;

    private final SshKeyRepository keys;
    private final Clock clock;

    public SshKeyService(Db db, Clock clock) {
        this.keys = new SshKeyRepository(db);
        this.clock = clock;
    }

    /**
     * Parses an OpenSSH public key line ({@code ssh-ed25519 AAAA... comment}) and stores it for the user.
     * The comment is used as the name when none is given.
     */
    public SshKey add(long userId, String name, String openSshLine) {
        if (openSshLine == null || openSshLine.isBlank()) {
            throw new InvalidKeyException("paste a public key, e.g. the contents of ~/.ssh/id_ed25519.pub");
        }
        String line = openSshLine.strip().replaceAll("\\s+", " ");
        if (line.contains("PRIVATE KEY")) {
            throw new InvalidKeyException("that is a private key; paste the .pub file instead (and keep the private key secret)");
        }
        PublicKeyEntry entry;
        PublicKey key;
        try {
            entry = PublicKeyEntry.parsePublicKeyEntry(line);
            key = entry.resolvePublicKey(null, null, PublicKeyEntryResolver.FAILING);
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            throw new InvalidKeyException("not a valid OpenSSH public key");
        }
        if (key == null || !ALLOWED_TYPES.contains(key.getAlgorithm())) {
            throw new InvalidKeyException("unsupported key type; use Ed25519, ECDSA or RSA (3072 bits or more)");
        }
        if (KeyUtils.RSA_ALGORITHM.equals(key.getAlgorithm()) && KeyUtils.getKeySize(key) < MIN_RSA_BITS) {
            throw new InvalidKeyException("RSA keys must be at least " + MIN_RSA_BITS + " bits");
        }
        if (keys.listByUser(userId).size() >= MAX_KEYS) {
            throw new InvalidKeyException("you can register at most " + MAX_KEYS + " keys");
        }
        // "type base64 comment...": the comment (usually user@host) names the key when no name is given
        String[] parts = line.split(" ", 3);
        String comment = parts.length == 3 ? parts[2].strip() : "";
        String keyName = name != null && !name.isBlank() ? name.strip()
            : !comment.isEmpty() ? comment
            : KeyUtils.getKeyType(key);
        try {
            return keys.insert(SshKey.builder()
                .userId(userId)
                .name(keyName.length() > 100 ? keyName.substring(0, 100) : keyName)
                .keyType(KeyUtils.getKeyType(key))
                .fingerprint(fingerprint(key))
                .publicKey(PublicKeyEntry.toString(key))
                .build());
        } catch (DataAccessException e) {
            if (e.isUniqueViolation()) {
                throw new InvalidKeyException("this key is already registered");
            }
            throw e;
        }
    }

    public List<SshKey> list(long userId) {
        return keys.listByUser(userId);
    }

    public boolean delete(long userId, long keyId) {
        return keys.delete(userId, keyId);
    }

    /** The registered key matching a key presented during SSH authentication. */
    public Optional<SshKey> findByKey(PublicKey key) {
        return keys.findByFingerprint(fingerprint(key));
    }

    public void markUsed(long keyId) {
        keys.markUsed(keyId, clock.instant());
    }

    public static String fingerprint(PublicKey key) {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
    }
}
