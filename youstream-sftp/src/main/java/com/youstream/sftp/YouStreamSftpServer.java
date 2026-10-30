package com.youstream.sftp;

import com.youstream.auth.LoginThrottle;
import com.youstream.auth.SshKeyService;
import com.youstream.auth.UserService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.common.storage.StorageService;
import com.youstream.media.IngestService;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;

/**
 * Upload-only SFTP server: password (LDAP) or registered-key login, each user jailed in a private folder,
 * no shell, no remote commands, no port/agent/X11 forwarding. Finished uploads become videos ({@link UploadWatcher}).
 */
public class YouStreamSftpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YouStreamSftpServer.class);
    /** Leaves room for SFTP headers inside OpenSSH's 256 KiB message limit. */
    private static final int MAX_DATA_PER_PACKET = 255 * 1024;

    private final SshServer sshd;
    private final UploadWatcher watcher;
    private final SimpleGeneratorHostKeyProvider hostKeys;
    private final Path hostKeyPath;

    /**
     * @param ldap null to allow SSH-key login only
     */
    public YouStreamSftpServer(SftpConfig config, StorageService storage, IngestService ingest, UserService users,
                               SshKeyService keys, LdapAuthenticator ldap, LoginThrottle throttle) throws IOException {
        hostKeyPath = config.getHostKey();
        hostKeys = hostKeyProvider(hostKeyPath);

        watcher = new UploadWatcher(storage, ingest, config);
        SftpSubsystemFactory sftp = new SftpSubsystemFactory();
        sftp.addSftpEventListener(watcher);
        sftp.setFileSystemAccessor(new UploadFileSystemAccessor());

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost(config.getHost());
        sshd.setPort(config.getPort());
        sshd.setKeyPairProvider(hostKeys);
        if (config.isProxyProtocol()) {
            sshd.setServerProxyAcceptor(new ProxyProtocolAcceptor());
        }
        sshd.setPasswordAuthenticator(ldap == null ? null : SftpAuthenticators.password(ldap, users, throttle));
        sshd.setPublickeyAuthenticator(SftpAuthenticators.publicKey(keys, users));
        sshd.setKeyboardInteractiveAuthenticator(null);
        sshd.setGSSAuthenticator(null);
        sshd.setHostBasedAuthenticator(null);
        // SFTP only: no shell, no exec, no forwarding of any kind
        sshd.setShellFactory(null);
        sshd.setCommandFactory(null);
        sshd.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        sshd.setAgentFactory(null);
        sshd.setSubsystemFactories(List.of(sftp));
        sshd.setFileSystemFactory(new UserFolderFileSystemFactory(storage));

        // SSHD advertises these via limits@openssh.com. OpenSSH's sftp caps a whole message at 256 KiB, so a
        // 256 KiB data limit makes it build oversized write packets ("Outbound message too long 262169").
        SftpModuleProperties.MAX_WRITEDATA_PACKET_LENGTH.set(sshd, MAX_DATA_PER_PACKET);
        SftpModuleProperties.MAX_READDATA_PACKET_LENGTH.set(sshd, MAX_DATA_PER_PACKET);
        CoreModuleProperties.AUTH_TIMEOUT.set(sshd, Duration.ofSeconds(60));
        CoreModuleProperties.MAX_AUTH_REQUESTS.set(sshd, 6);
        CoreModuleProperties.IDLE_TIMEOUT.set(sshd, config.getIdleTimeout());
        CoreModuleProperties.SERVER_IDENTIFICATION.set(sshd, "YouStream");
    }

    public void start() throws IOException {
        // generate a missing key before listening; a read-only location must already hold one
        if (!Files.isRegularFile(hostKeyPath)) {
            Files.createDirectories(hostKeyPath.toAbsolutePath().getParent());
        }
        if (hostKeyFingerprint() == null) {
            throw new IOException("SFTP host key " + hostKeyPath + " is missing and cannot be created");
        }
        sshd.start();
        log.info("SFTP server listening on port {} (host key {})", port(), hostKeyFingerprint());
    }

    /** Actual port (useful when configured with 0). */
    public int port() {
        return sshd.getPort();
    }

    /** {@code SHA256:...} fingerprint of the host key, shown to users so they can verify the first connection. */
    public String hostKeyFingerprint() {
        return fingerprint(hostKeys);
    }

    /**
     * Fingerprint of an existing host key file, for processes that show SFTP instructions without running the
     * server; null when the file does not exist (yet).
     */
    public static String hostKeyFingerprint(Path hostKey) {
        return Files.isRegularFile(hostKey) ? fingerprint(hostKeyProvider(hostKey)) : null;
    }

    /** Creates the host key file if it does not exist yet; returns its fingerprint. */
    public static String generateHostKey(Path hostKey) throws IOException {
        Files.createDirectories(hostKey.toAbsolutePath().getParent());
        String fingerprint = fingerprint(hostKeyProvider(hostKey));
        if (fingerprint == null) {
            throw new IOException("cannot create host key " + hostKey);
        }
        return fingerprint;
    }

    private static SimpleGeneratorHostKeyProvider hostKeyProvider(Path hostKey) {
        SimpleGeneratorHostKeyProvider provider = new SimpleGeneratorHostKeyProvider(hostKey);
        provider.setAlgorithm(KeyUtils.EC_ALGORITHM);
        provider.setKeySize(256);
        return provider;
    }

    private static String fingerprint(SimpleGeneratorHostKeyProvider provider) {
        try {
            for (KeyPair kp : provider.loadKeys(null)) {
                return KeyUtils.getFingerPrint(kp.getPublic());
            }
        } catch (Exception e) {
            log.warn("Cannot read SFTP host key", e);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
        watcher.close();
    }
}
