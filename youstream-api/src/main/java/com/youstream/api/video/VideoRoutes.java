package com.youstream.api.video;

import com.youstream.api.AppServices;
import com.youstream.api.auth.CurrentUser;
import com.youstream.api.dto.Page;
import com.youstream.api.dto.SocialDtos;
import com.youstream.api.dto.VideoDto;
import com.youstream.api.dto.VideoUpdate;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoStatus;
import com.youstream.db.repo.SocialRepository;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Feed, watch-page data, Studio listing and owner edits. */
public final class VideoRoutes {

    private static final Logger log = LoggerFactory.getLogger(VideoRoutes.class);
    private static final int PAGE_SIZE = 24;

    private final AppServices s;
    private final VideoRepository videos;
    private final VideoViews views;
    private final PublicVideoCache publicVideos;
    private final SocialRepository social;

    public VideoRoutes(AppServices services, PublicVideoCache publicVideos) {
        this.s = services;
        this.publicVideos = publicVideos;
        this.videos = new VideoRepository(services.getDb());
        this.social = new SocialRepository(services.getDb());
        this.views = new VideoViews(new UserRepository(services.getDb()), new TranscodeJobRepository(services.getDb()),
            services.getPlaybackTokens());
    }

    public void install(Jooby app) {
        app.get("/api/videos", this::feed);
        app.get("/api/videos/{publicId}", ctx -> views.watchDto(viewable(ctx), ctx.getUser(), social));
        app.patch("/api/videos/{publicId}", this::update);
        app.delete("/api/videos/{publicId}", this::delete);
        app.put("/api/videos/{publicId}/reaction", this::react);
        app.post("/api/videos/{publicId}/views", this::view);
        app.get("/api/me/videos", this::mine);
    }

    private SocialDtos.ReactionState react(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Video video = viewable(ctx);
        int value = ctx.body(SocialDtos.Reaction.class).getValue();
        if (value < -1 || value > 1) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "value must be 1 (like), -1 (dislike) or 0 (none)");
        }
        SocialRepository.Counts counts = social.react(video.getId(), caller.getUserId(), value);
        return new SocialDtos.ReactionState(value, counts.likes(), counts.dislikes());
    }

    /** Called by the player after a few seconds of playback; counted once per viewer per day. */
    private SocialDtos.Views view(Context ctx) {
        Video video = viewable(ctx);
        if (video.getStatus() != VideoStatus.READY) {
            return new SocialDtos.Views(video.getViewCount());
        }
        AccessClaims caller = ctx.getUser();
        String viewer = caller != null
            ? "u:" + caller.getUserId()
            // anonymous: a hash, so raw IP addresses are never stored
            : "a:" + sha256(ctx.getRemoteAddress() + "|" + ctx.header("User-Agent").value(""));
        return new SocialDtos.Views(social.recordView(video.getId(), viewer));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Latest public videos; cursor is {@code <publishedAtMillis>_<id>}. */
    private Page<VideoDto> feed(Context ctx) {
        String cursor = ctx.query("cursor").valueOrNull();
        Instant before = null;
        Long beforeId = null;
        if (cursor != null) {
            String[] parts = cursor.split("_", 2);
            try {
                before = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                beforeId = Long.parseLong(parts[1]);
            } catch (RuntimeException e) {
                throw new StatusCodeException(StatusCode.BAD_REQUEST, "invalid cursor");
            }
        }
        List<Video> page = videos.listPublic(before, beforeId, PAGE_SIZE);
        Video last = page.size() == PAGE_SIZE ? page.getLast() : null;
        return new Page<>(views.dtos(page, ctx.getUser()),
            last == null ? null : last.getPublishedAt().toEpochMilli() + "_" + last.getId());
    }

    /** The caller's own videos for Studio, newest first; cursor is the last id. */
    private Page<VideoDto> mine(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Long before = ctx.query("cursor").toOptional().map(c -> {
            try {
                return Long.parseLong(c);
            } catch (NumberFormatException e) {
                throw new StatusCodeException(StatusCode.BAD_REQUEST, "invalid cursor");
            }
        }).orElse(null);
        List<Video> page = videos.listByOwner(caller.getUserId(), before, PAGE_SIZE);
        return new Page<>(views.dtos(page, caller), page.size() == PAGE_SIZE ? page.getLast().getId().toString() : null);
    }

    private VideoDto update(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Video video = VideoViews.requireEditable(videos.findByPublicId(ctx.path("publicId").value()), caller);
        VideoUpdate req = ctx.body(VideoUpdate.class);
        String title = req.getTitle() != null ? validTitle(req.getTitle()) : video.getTitle();
        String description = req.getDescription() != null ? validDescription(req.getDescription()) : video.getDescription();
        videos.updateMetadata(video.getId(), title, description,
            req.getVisibility() != null ? req.getVisibility() : video.getVisibility());
        publicVideos.invalidate(video.getPublicId());
        return views.dto(videos.findById(video.getId()).orElseThrow(), caller);
    }

    private Context delete(Context ctx) throws IOException {
        AccessClaims caller = CurrentUser.require(ctx);
        Video video = VideoViews.requireEditable(videos.findByPublicId(ctx.path("publicId").value()), caller);
        videos.delete(video.getId());
        String pid = video.getPublicId();
        publicVideos.invalidate(pid);
        for (String prefix : List.of("originals/" + pid, "hls/" + pid, "hls/" + pid + ".tmp", "thumbs/" + pid)) {
            try {
                s.getStorage().deleteTree(prefix);
            } catch (IOException e) {
                // the row is gone; leftover files are only wasted space
                log.warn("Could not delete {} for removed video {}", prefix, pid, e);
            }
        }
        log.info("Video {} deleted by user {}", pid, caller.getUserId());
        return ctx.send(StatusCode.NO_CONTENT);
    }

    private Video viewable(Context ctx) {
        return VideoViews.requireViewable(videos.findByPublicId(ctx.path("publicId").value()), ctx.getUser());
    }

    public static String validTitle(String title) {
        String t = title.strip().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > 100) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "title must be 1 to 100 characters");
        }
        return t;
    }

    public static String validDescription(String description) {
        if (description.length() > 5000) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "description must be at most 5000 characters");
        }
        return description.strip();
    }
}
