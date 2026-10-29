package com.youstream.sftp;

import com.youstream.common.model.Video;
import com.youstream.common.model.VideoSource;
import com.youstream.common.model.Visibility;
import com.youstream.common.storage.StorageService;
import com.youstream.media.IngestRequest;
import com.youstream.media.IngestService;
import com.youstream.media.InvalidMediaException;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns finished SFTP uploads into videos. A file is picked up when a write handle on it closes, or when it is
 * renamed into place (clients that upload as {@code .part}/{@code .filepart} first), after a short quiet period.
 *
 * <p>Optional metadata: upload {@code clip.json} (or {@code clip.mp4.json}) <em>before</em> {@code clip.mp4}:
 * {@code {"title": "...", "description": "...", "visibility": "PUBLIC|UNLISTED|PRIVATE"}}. Otherwise the title
 * comes from the file name and the video is private. The outcome is written to {@code clip.mp4.status}.
 */
class UploadWatcher implements SftpEventListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UploadWatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] IGNORED_SUFFIXES = {".status", ".json", ".part", ".partial", ".filepart", ".tmp",
        ".crdownload", ".swp"};

    private final StorageService storage;
    private final IngestService ingest;
    private final SftpConfig config;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("sftp-settle").daemon(true).factory());
    private final ExecutorService ingester = Executors.newFixedThreadPool(2,
        Thread.ofPlatform().name("sftp-ingest-", 0).daemon(true).factory());
    private final Map<Path, AtomicInteger> openWrites = new ConcurrentHashMap<>();
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    /** Files whose upload was cut off at the size limit; deleted when the handle closes. */
    private final Set<Path> oversized = ConcurrentHashMap.newKeySet();

    UploadWatcher(StorageService storage, IngestService ingest, SftpConfig config) {
        this.storage = storage;
        this.ingest = ingest;
        this.config = config;
    }

    // ------------------------------------------------------------------ SFTP events

    @Override
    public void open(ServerSession session, String remoteHandle, Handle handle) throws IOException {
        if (isWrite(handle)) {
            openWrites.computeIfAbsent(real(session, handle.getFile()), p -> new AtomicInteger()).incrementAndGet();
        }
    }

    @Override
    public void writing(ServerSession session, String remoteHandle, FileHandle handle, long offset, byte[] data,
                        int dataOffset, int dataLen) throws IOException {
        if (offset + dataLen > config.getMaxFileBytes()) {
            oversized.add(real(session, handle.getFile()));
            throw new IOException("file exceeds the " + limitMb() + " MB limit");
        }
    }

    @Override
    public void closed(ServerSession session, String remoteHandle, Handle handle, Throwable thrown) throws IOException {
        if (!isWrite(handle)) {
            return;
        }
        Path file = real(session, handle.getFile());
        AtomicInteger count = openWrites.get(file);
        if (count != null && count.decrementAndGet() <= 0) {
            openWrites.remove(file);
        }
        if (oversized.remove(file)) {
            Files.deleteIfExists(file);
            writeStatus(file.resolveSibling(file.getFileName() + ".status"),
                "REJECTED file is larger than the " + limitMb() + " MB limit\n");
            return;
        }
        if (thrown == null) {
            schedule(SftpAuthenticators.userId(session), file);
        }
    }

    @Override
    public void moved(ServerSession session, Path source, Path target, Collection<CopyOption> opts, Throwable thrown)
        throws IOException {
        if (thrown == null) {
            schedule(SftpAuthenticators.userId(session), real(session, target));
        }
    }

    // ------------------------------------------------------------------ settle and ingest

    private void schedule(long userId, Path file) {
        if (ignored(file)) {
            return;
        }
        ScheduledFuture<?> previous = pending.put(file, timer.schedule(() -> settle(userId, file),
            config.getSettleTime().toMillis(), TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** Waits until nothing is writing the file and it has not changed for the settle time. */
    private void settle(long userId, Path file) {
        pending.remove(file);
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            long quietMs = Instant.now().toEpochMilli() - Files.getLastModifiedTime(file).toMillis();
            if (openWrites.containsKey(file) || quietMs < config.getSettleTime().toMillis()) {
                schedule(userId, file);
                return;
            }
            ingester.submit(() -> ingest(userId, file));
        } catch (IOException e) {
            log.warn("Cannot inspect SFTP upload {}", file, e);
        }
    }

    private void ingest(long userId, Path file) {
        String name = file.getFileName().toString();
        Path statusFile = file.resolveSibling(name + ".status");
        Metadata meta = readSidecar(file);
        String incomingKey = "incoming/sftp-" + UUID.randomUUID();
        try {
            if (Files.size(file) == 0) {
                throw new InvalidMediaException("file is empty");
            }
            Path incoming = storage.localPath(incomingKey);
            Files.createDirectories(incoming.getParent());
            Files.move(file, incoming);
            Video video = ingest.ingest(IngestRequest.builder()
                .ownerId(userId)
                .sourceKey(incomingKey)
                .originalFilename(name)
                .title(meta.title())
                .description(meta.description())
                .visibility(meta.visibility())
                .source(VideoSource.SFTP)
                .build());
            if (meta.file() != null) {
                Files.deleteIfExists(meta.file());
            }
            writeStatus(statusFile, "ACCEPTED " + video.getPublicId() + "\n"
                + "title: " + video.getTitle() + "\n"
                + "visibility: " + video.getVisibility() + "\n"
                + "watch: " + config.getPublicUrl() + "/watch/" + video.getPublicId() + "\n"
                + "The video is being processed; check its progress in YouStream Studio.\n");
        } catch (InvalidMediaException e) {
            writeStatus(statusFile, "REJECTED " + e.getMessage() + "\n");
        } catch (Exception e) {
            log.error("SFTP ingest of {} for user {} failed", file, userId, e);
            writeStatus(statusFile, "ERROR could not process the upload; please try again\n");
        }
    }

    private record Metadata(String title, String description, Visibility visibility, Path file) {
    }

    /** {@code clip.json} or {@code clip.mp4.json} next to the upload. */
    private Metadata readSidecar(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        for (String candidate : new String[]{name + ".json", (dot > 0 ? name.substring(0, dot) : name) + ".json"}) {
            Path sidecar = file.resolveSibling(candidate);
            if (!Files.isRegularFile(sidecar)) {
                continue;
            }
            try {
                JsonNode json = JSON.readTree(Files.readString(sidecar, StandardCharsets.UTF_8));
                String visibility = json.path("visibility").asString("PRIVATE").toUpperCase(Locale.ROOT);
                return new Metadata(
                    json.path("title").asString(null),
                    json.path("description").asString(""),
                    Visibility.valueOf(visibility),
                    sidecar);
            } catch (IOException | JacksonException | IllegalArgumentException e) {
                log.info("Ignoring invalid SFTP metadata file {}: {}", sidecar, e.getMessage());
            }
        }
        return new Metadata(null, "", Visibility.PRIVATE, null);
    }

    private static void writeStatus(Path statusFile, String text) {
        try {
            Files.writeString(statusFile, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("Cannot write {}", statusFile, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private long limitMb() {
        return config.getMaxFileBytes() >> 20;
    }

    private static boolean isWrite(Handle handle) {
        return handle instanceof FileHandle fh
            && (fh.getOpenOptions().contains(StandardOpenOption.WRITE) || fh.getOpenOptions().contains(StandardOpenOption.APPEND));
    }

    static boolean ignored(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || name.startsWith("~")) {
            return true;
        }
        for (String suffix : IGNORED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Maps a path of the user's jailed SFTP file system to the real file in storage. */
    private Path real(ServerSession session, Path virtual) {
        Path root = UserFolderFileSystemFactory.userRoot(storage, SftpAuthenticators.userId(session)).toAbsolutePath().normalize();
        String relative = virtual.toAbsolutePath().normalize().toString().replace('\\', '/').replaceFirst("^/+", "");
        Path real = root.resolve(relative).normalize();
        if (!real.startsWith(root)) {
            throw new IllegalStateException("path outside the user's SFTP folder: " + virtual);
        }
        return real;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        ingester.shutdown();
        try {
            ingester.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
