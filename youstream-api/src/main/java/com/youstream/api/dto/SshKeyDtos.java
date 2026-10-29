package com.youstream.api.dto;

import com.youstream.common.model.SshKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** SSH key and SFTP connection bodies for the Account page. */
public final class SshKeyDtos {

    private SshKeyDtos() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key {
        private long id;
        private String name;
        private String keyType;
        private String fingerprint;
        private Instant createdAt;
        private Instant lastUsedAt;

        public static Key of(SshKey k) {
            return new Key(k.getId(), k.getName(), k.getKeyType(), k.getFingerprint(), k.getCreatedAt(), k.getLastUsedAt());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Add {
        private String name;
        private String publicKey;
    }

    /** How the signed-in user connects over SFTP. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SftpInfo {
        private boolean enabled;
        private String host;
        private int port;
        private String username;
        /** True when the user can log in with their directory password (has an LDAP identity). */
        private boolean passwordLogin;
        private String hostKeyFingerprint;
        private long maxFileBytes;
    }
}
