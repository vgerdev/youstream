package com.youstream.sftp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Duration;

/** SFTP upload server settings. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SftpConfig {
    @Builder.Default
    private String host = "0.0.0.0";
    /** 0 = any free port (tests). */
    @Builder.Default
    private int port = 2222;
    /**
     * Persisted so clients do not see a changed host key after a restart. Generated on first start when missing;
     * may be a read-only file (a Kubernetes secret) shared by every deployment of the site.
     */
    private Path hostKey;
    /** Expect a PROXY protocol header (v1 or v2) from a load balancer in front of the port. */
    private boolean proxyProtocol;
    @Builder.Default
    private long maxFileBytes = 10L << 30;
    /** Quiet period after a file is closed or renamed before it is ingested. */
    @Builder.Default
    private Duration settleTime = Duration.ofSeconds(2);
    @Builder.Default
    private Duration idleTimeout = Duration.ofMinutes(10);
    /** Base URL written into status files ({@code <publicUrl>/watch/<id>}). */
    @Builder.Default
    private String publicUrl = "http://localhost:8080";
}
