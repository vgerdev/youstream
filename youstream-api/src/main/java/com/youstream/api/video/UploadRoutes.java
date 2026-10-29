package com.youstream.api.video;

import com.youstream.api.AppServices;
import com.youstream.api.auth.CurrentUser;
import com.youstream.api.dto.UploadDtos;
import com.youstream.api.dto.VideoDto;
import com.youstream.api.dto.VideoUpdate;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.model.UploadSession;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoSource;
import com.youstream.common.model.Visibility;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UploadSessionRepository;
import com.youstream.db.repo.UserRepository;
import com.youstream.media.IngestRequest;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumable browser uploads: create a session, PUT chunks with {@code Content-Range}, then complete with metadata.
 * Chunks are written in order; re-sending a chunk that was already stored is accepted, so clients can simply retry.
 */
public final class UploadRoutes {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final AppServices s;
    private final UploadSessionRepository uploads;
    private final VideoViews views;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public UploadRoutes(AppServices services) {
        this.s = services;
        this.uploads = new UploadSessionRepository(services.getDb());
        this.views = new VideoViews(new UserRepository(services.getDb()), new TranscodeJobRepository(services.getDb()),
            services.getPlaybackTokens());
    }

    public void install(Jooby app) {
        app.post("/api/uploads", this::create);
        app.get("/api/uploads/{id}", ctx -> status(session(ctx)));
        app.put("/api/uploads/{id}", this::chunk);
        app.post("/api/uploads/{id}/complete", this::complete);
        app.delete("/api/uploads/{id}", this::abort);
    }

    private UploadDtos.Status create(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        UploadDtos.Create req = ctx.body(UploadDtos.Create.class);
        if (req.getSize() <= 0) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "size must be positive");
        }
        if (req.getSize() > s.getMaxUploadBytes()) {
            throw new StatusCodeException(StatusCode.REQUEST_ENTITY_TOO_LARGE,
                "file is larger than the " + (s.getMaxUploadBytes() >> 20) + " MB limit");
        }
        UploadSession upload = uploads.insert(UploadSession.builder()
            .id(UUID.randomUUID())
            .userId(caller.getUserId())
            .filename(req.getFilename() == null || req.getFilename().isBlank() ? "video" : req.getFilename())
            .totalBytes(req.getSize())
            .expiresAt(Instant.now().plus(SESSION_TTL))
            .build());
        ctx.setResponseCode(StatusCode.CREATED);
        return status(upload);
    }

    private UploadDtos.Status chunk(Context ctx) throws IOException {
        UploadSession upload = session(ctx);
        Matcher range = CONTENT_RANGE.matcher(ctx.header("Content-Range").value(""));
        if (!range.matches()) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Content-Range: bytes <start>-<end>/<total> is required");
        }
        long start = Long.parseLong(range.group(1));
        long end = Long.parseLong(range.group(2));
        long total = Long.parseLong(range.group(3));
        long length = end - start + 1;
        if (total != upload.getTotalBytes() || end < start || end >= total || length > s.getUploadChunkBytes()
            || ctx.getRequestLength() != length) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "invalid Content-Range for this upload");
        }
        synchronized (locks.computeIfAbsent(upload.getId(), id -> new Object())) {
            long received = uploads.find(upload.getId(), upload.getUserId()).orElseThrow().getReceivedBytes();
            if (start > received) {
                ctx.setResponseCode(StatusCode.CONFLICT);
                return status(upload.toBuilder().receivedBytes(received).build());
            }
            try (InputStream body = ctx.body().stream()) {
                s.getStorage().write(incomingKey(upload), start, body, length);
            }
            long next = Math.max(received, end + 1);
            uploads.updateReceived(upload.getId(), next);
            return status(upload.toBuilder().receivedBytes(next).build());
        }
    }

    private VideoDto complete(Context ctx) throws IOException, InterruptedException {
        AccessClaims caller = CurrentUser.require(ctx);
        UploadSession upload = session(ctx);
        if (upload.getReceivedBytes() != upload.getTotalBytes()) {
            throw new StatusCodeException(StatusCode.CONFLICT,
                "upload incomplete: " + upload.getReceivedBytes() + " of " + upload.getTotalBytes() + " bytes received");
        }
        VideoUpdate meta = ctx.body(VideoUpdate.class);
        Video video;
        try {
            video = s.getIngest().ingest(IngestRequest.builder()
                .ownerId(caller.getUserId())
                .sourceKey(incomingKey(upload))
                .originalFilename(upload.getFilename())
                .title(meta.getTitle() == null || meta.getTitle().isBlank() ? null : VideoRoutes.validTitle(meta.getTitle()))
                .description(meta.getDescription() == null ? "" : VideoRoutes.validDescription(meta.getDescription()))
                .visibility(meta.getVisibility() == null ? Visibility.PRIVATE : meta.getVisibility())
                .source(VideoSource.WEB)
                .build());
        } finally {
            locks.remove(upload.getId());
        }
        uploads.delete(upload.getId());
        ctx.setResponseCode(StatusCode.CREATED);
        return views.dto(video, caller);
    }

    private Context abort(Context ctx) throws IOException {
        UploadSession upload = session(ctx);
        s.getStorage().delete(incomingKey(upload));
        uploads.delete(upload.getId());
        locks.remove(upload.getId());
        return ctx.send(StatusCode.NO_CONTENT);
    }

    private UploadSession session(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        UUID id;
        try {
            id = UUID.fromString(ctx.path("id").value());
        } catch (IllegalArgumentException e) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "upload not found");
        }
        return uploads.find(id, caller.getUserId())
            .filter(u -> u.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "upload not found or expired"));
    }

    private UploadDtos.Status status(UploadSession u) {
        return new UploadDtos.Status(u.getId().toString(), u.getFilename(), u.getTotalBytes(), u.getReceivedBytes(),
            s.getUploadChunkBytes());
    }

    static String incomingKey(UploadSession u) {
        return "incoming/" + u.getId();
    }
}
