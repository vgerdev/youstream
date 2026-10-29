package com.youstream.sftp;

import com.youstream.auth.LoginThrottle;
import com.youstream.auth.SshKeyService;
import com.youstream.auth.UserService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.ldap.LdapConfig;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoSource;
import com.youstream.common.model.Visibility;
import com.youstream.common.storage.LocalDiskStorage;
import com.youstream.db.TestDatabase;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import com.youstream.devldap.DevLdapServer;
import com.youstream.media.Ffprobe;
import com.youstream.media.IngestService;
import com.youstream.media.MediaTestSupport;
import com.youstream.media.ProcessRunner;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real SSHD client against the SFTP server, with dev LDAP, the test database and FFmpeg. */
class SftpServerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long MAX_FILE = 5L << 20;

    @TempDir
    static Path dir;

    private static DevLdapServer ldap;
    private static YouStreamSftpServer server;
    private static SshClient client;
    private static LocalDiskStorage storage;
    private static UserService users;
    private static SshKeyService keys;
    private static byte[] clip;

    @BeforeAll
    static void start() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        ldap = DevLdapServer.start(0);
        storage = new LocalDiskStorage(dir.resolve("data"));
        users = new UserService(TestDatabase.db(), Clock.systemUTC());
        keys = new SshKeyService(TestDatabase.db(), Clock.systemUTC());
        IngestService ingest = new IngestService(TestDatabase.db(), storage,
            new Ffprobe(MediaTestSupport.FFPROBE, new ProcessRunner()));
        LdapAuthenticator auth = new LdapAuthenticator(LdapConfig.builder()
            .url(ldap.url()).baseDn(DevLdapServer.BASE_DN)
            .bindDn(DevLdapServer.SERVICE_DN).bindPassword(DevLdapServer.SERVICE_PASSWORD).build());
        server = new YouStreamSftpServer(SftpConfig.builder()
            .port(0)
            .hostKey(dir.resolve("data/ssh/host_key"))
            .maxFileBytes(MAX_FILE)
            .settleTime(Duration.ofMillis(300))
            .publicUrl("http://yt.test")
            .build(), storage, ingest, users, keys, auth, new LoginThrottle());
        server.start();
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        clip = Files.readAllBytes(MediaTestSupport.generateClip(dir.resolve("clip.mp4"), 320, 180, 2, false));
    }

    @AfterAll
    static void stop() throws IOException {
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.close();
        }
        if (ldap != null) {
            ldap.close();
        }
    }

    @BeforeEach
    void clean() throws IOException {
        TestDatabase.truncate();
        // user ids restart at 1 after truncate, so the SFTP folders must be emptied too
        storage.deleteTree("sftp");
    }

    @Test
    void passwordUploadBecomesPrivateVideoWithStatusFile() throws Exception {
        try (ClientSession session = passwordSession("alice", "alice-pass");
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            write(sftp, "/Holiday_clip.mp4", clip);

            Video video = awaitVideo("alice");
            assertThat(video.getTitle()).isEqualTo("Holiday clip");
            assertThat(video.getVisibility()).isEqualTo(Visibility.PRIVATE);
            assertThat(video.getSource()).isEqualTo(VideoSource.SFTP);
            assertThat(video.getOriginalFilename()).isEqualTo("Holiday_clip.mp4");

            String status = awaitFile(sftp, "/Holiday_clip.mp4.status");
            assertThat(status).startsWith("ACCEPTED " + video.getPublicId())
                .contains("watch: http://yt.test/watch/" + video.getPublicId());
            assertThat(names(sftp, "/")).containsExactly("Holiday_clip.mp4.status");
        }
    }

    @Test
    void sidecarMetadataAndPartialRename() throws Exception {
        try (ClientSession session = passwordSession("alice", "alice-pass");
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            sftp.mkdir("/trips");
            write(sftp, "/trips/rome.json",
                "{\"title\":\"Rome, day 1\",\"description\":\"Colosseum\",\"visibility\":\"unlisted\"}".getBytes(StandardCharsets.UTF_8));
            // clients like WinSCP upload under a temporary name and rename at the end
            write(sftp, "/trips/rome.mp4.filepart", clip);
            Thread.sleep(800);
            assertThat(videosOf("alice")).as("partial file is not ingested").isEmpty();
            sftp.rename("/trips/rome.mp4.filepart", "/trips/rome.mp4");

            Video video = awaitVideo("alice");
            assertThat(video.getTitle()).isEqualTo("Rome, day 1");
            assertThat(video.getDescription()).isEqualTo("Colosseum");
            assertThat(video.getVisibility()).isEqualTo(Visibility.UNLISTED);
            awaitFile(sftp, "/trips/rome.mp4.status");
            assertThat(names(sftp, "/trips")).containsExactly("rome.mp4.status");
        }
    }

    @Test
    void registeredKeyLogsInOnlyAsItsOwner() throws Exception {
        User bob = new UserRepository(TestDatabase.db())
            .insert(User.builder().username("bob").displayName("Bob").handle("bob").build());
        KeyPair key = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        keys.add(bob.getId(), "laptop", PublicKeyEntry.toString(key.getPublic()));

        try (ClientSession session = keySession("bob", key);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            write(sftp, "/demo.mp4", clip);
            assertThat(awaitVideo("bob").getTitle()).isEqualTo("demo");
        }
        assertThat(keys.list(bob.getId()).getFirst().getLastUsedAt()).isNotNull();
        assertThatThrownBy(() -> keySession("alice", key)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> keySession("bob", KeyUtils.generateKeyPair("ssh-ed25519", 256))).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsBadPasswordsNonVideosAndOversizedFiles() throws Exception {
        assertThatThrownBy(() -> passwordSession("alice", "wrong")).isInstanceOf(IOException.class);

        try (ClientSession session = passwordSession("alice", "alice-pass");
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            write(sftp, "/notes.mp4", "this is not a video".getBytes(StandardCharsets.UTF_8));
            assertThat(awaitFile(sftp, "/notes.mp4.status")).startsWith("REJECTED");
            assertThat(videosOf("alice")).isEmpty();

            assertThatThrownBy(() -> write(sftp, "/huge.mp4", new byte[(int) MAX_FILE + 1024]))
                .isInstanceOf(IOException.class);
            assertThat(awaitFile(sftp, "/huge.mp4.status")).startsWith("REJECTED file is larger than the 5 MB limit");
            assertThat(names(sftp, "/")).doesNotContain("huge.mp4");
        }
    }

    @Test
    void usersAreJailedAndCannotRunCommands() throws Exception {
        try (ClientSession alice = passwordSession("alice", "alice-pass");
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(alice)) {
            write(sftp, "/../../escape.json", "{}".getBytes(StandardCharsets.UTF_8));
            write(sftp, "/private.json", "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(names(sftp, "/..")).contains("escape.json", "private.json");
        }
        assertThat(Files.exists(storage.root().resolve("escape.json"))).isFalse();
        assertThat(Files.exists(storage.root().resolve("sftp/escape.json"))).isFalse();

        try (ClientSession bob = passwordSession("bob", "bob-pass");
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(bob)) {
            assertThat(names(sftp, "/")).doesNotContain("private.json", "escape.json");

            // no links out of the jail, and the server's OS account is not revealed
            write(sftp, "/target.json", "{}".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> sftp.symLink("/link.json", "/target.json")).isInstanceOf(IOException.class);
            // protocol v3 (what OpenSSH uses) carries the "ls -l" long name
            try (SftpClient v3 = SftpClientFactory.instance().createSftpClient(bob, 3)) {
                String longName = null;
                for (SftpClient.DirEntry e : v3.readDir("/")) {
                    if (e.getFilename().equals("target.json")) {
                        longName = e.getLongFilename();
                    }
                }
                assertThat(longName).contains(" youstream youstream ")
                    .doesNotContain(System.getProperty("user.name")).doesNotContain("Principal[");
            }

            // the server has no command factory: the request is refused and the channel closes without running anything
            try (ChannelExec exec = bob.createExecChannel("ls /")) {
                exec.open().verify(WAIT);
                exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), WAIT);
                Integer exit = exec.getExitStatus();
                assertThat(exit == null || exit != 0).as("exec must not run (exit status %s)", exit).isTrue();
            } catch (IOException refused) {
                // also acceptable: the channel open itself is rejected
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ClientSession passwordSession(String user, String password) throws IOException {
        ClientSession session = client.connect(user, "localhost", server.port()).verify(WAIT).getSession();
        session.addPasswordIdentity(password);
        try {
            session.auth().verify(WAIT);
        } catch (IOException e) {
            session.close();
            throw e;
        }
        return session;
    }

    private static ClientSession keySession(String user, KeyPair key) throws IOException {
        ClientSession session = client.connect(user, "localhost", server.port()).verify(WAIT).getSession();
        session.addPublicKeyIdentity(key);
        try {
            session.auth().verify(WAIT);
        } catch (IOException e) {
            session.close();
            throw e;
        }
        return session;
    }

    private static void write(SftpClient sftp, String path, byte[] data) throws IOException {
        try (OutputStream out = sftp.write(path)) {
            out.write(data);
        }
    }

    private static List<String> names(SftpClient sftp, String path) throws IOException {
        List<String> names = new ArrayList<>();
        for (SftpClient.DirEntry e : sftp.readDir(path)) {
            if (!e.getFilename().equals(".") && !e.getFilename().equals("..")) {
                names.add(e.getFilename());
            }
        }
        return names;
    }

    private static List<Video> videosOf(String username) {
        return new UserRepository(TestDatabase.db()).findByUsername(username)
            .map(u -> new VideoRepository(TestDatabase.db()).listByOwner(u.getId(), null, 10))
            .orElse(List.of());
    }

    private static Video awaitVideo(String username) throws InterruptedException {
        return await(() -> videosOf(username).stream().findFirst().orElse(null));
    }

    private static String awaitFile(SftpClient sftp, String path) throws InterruptedException {
        return await(() -> {
            try (InputStream in = sftp.read(path)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private static <T> T await(Supplier<T> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out waiting");
    }
}
