package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** An SSH public key a user registered for SFTP uploads. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SshKey {
    private Long id;
    private Long userId;
    private String name;
    private String keyType;
    /** {@code SHA256:...} fingerprint, unique across all users. */
    private String fingerprint;
    /** OpenSSH single-line format. */
    private String publicKey;
    private Instant createdAt;
    private Instant lastUsedAt;
}
