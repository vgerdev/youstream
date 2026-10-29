package com.youstream.api.social;

import com.youstream.api.AppServices;
import com.youstream.api.auth.CurrentUser;
import com.youstream.api.dto.PlaylistDtos;
import com.youstream.api.video.VideoViews;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.PublicIds;
import com.youstream.common.model.Playlist;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.Visibility;
import com.youstream.db.repo.PlaylistRepository;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Playlists: create, view, edit, add/remove/reorder items, "Save to playlist", channel playlists. */
public final class PlaylistRoutes {

    private static final int MAX_PLAYLISTS = 200;
    private static final int MAX_ITEMS = 5_000;

    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final UserRepository users;
    private final PlaylistViews views;

    public PlaylistRoutes(AppServices services) {
        this.playlists = new PlaylistRepository(services.getDb());
        this.videos = new VideoRepository(services.getDb());
        this.users = new UserRepository(services.getDb());
        VideoViews videoViews = new VideoViews(users, new TranscodeJobRepository(services.getDb()), services.getPlaybackTokens());
        this.views = new PlaylistViews(playlists, users, videoViews);
    }

    public PlaylistViews views() {
        return views;
    }

    public void install(Jooby app) {
        app.post("/api/playlists", this::create);
        app.get("/api/playlists/{publicId}", ctx -> views.detail(viewable(ctx), ctx.getUser()));
        app.patch("/api/playlists/{publicId}", this::update);
        app.delete("/api/playlists/{publicId}", this::delete);
        app.post("/api/playlists/{publicId}/items", this::addItem);
        app.delete("/api/playlists/{publicId}/items/{videoId}", this::removeItem);
        app.put("/api/playlists/{publicId}/items", this::reorder);
        app.get("/api/me/playlists", this::mine);
        app.get("/api/channels/{handle}/playlists", this::channelPlaylists);
    }

    private PlaylistDtos.Playlist create(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        PlaylistDtos.Edit req = ctx.body(PlaylistDtos.Edit.class);
        if (playlists.listByOwner(caller.getUserId()).size() >= MAX_PLAYLISTS) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "you can have at most " + MAX_PLAYLISTS + " playlists");
        }
        Playlist created = playlists.insert(Playlist.builder()
            .publicId(PublicIds.next())
            .ownerId(caller.getUserId())
            .title(validTitle(req.getTitle()))
            .description(validDescription(req.getDescription()))
            .visibility(req.getVisibility() == null ? Visibility.PRIVATE : req.getVisibility())
            .build());
        if (req.getVideoId() != null) {
            playlists.addItem(created.getId(), watchable(req.getVideoId(), caller).getId());
            created = playlists.findByPublicId(created.getPublicId()).orElseThrow();
        }
        ctx.setResponseCode(StatusCode.CREATED);
        return views.summary(created, caller, null);
    }

    private PlaylistDtos.Playlist update(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Playlist p = editable(ctx, caller);
        PlaylistDtos.Edit req = ctx.body(PlaylistDtos.Edit.class);
        playlists.update(p.getId(),
            req.getTitle() == null ? p.getTitle() : validTitle(req.getTitle()),
            req.getDescription() == null ? p.getDescription() : validDescription(req.getDescription()),
            req.getVisibility() == null ? p.getVisibility() : req.getVisibility());
        return views.detail(playlists.findByPublicId(p.getPublicId()).orElseThrow(), caller);
    }

    private Context delete(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        playlists.delete(editable(ctx, caller).getId());
        return ctx.send(StatusCode.NO_CONTENT);
    }

    private PlaylistDtos.Playlist addItem(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Playlist p = editable(ctx, caller);
        if (p.getItemCount() >= MAX_ITEMS) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "a playlist can hold at most " + MAX_ITEMS + " videos");
        }
        playlists.addItem(p.getId(), watchable(ctx.body(PlaylistDtos.Item.class).getVideoId(), caller).getId());
        return views.summary(playlists.findByPublicId(p.getPublicId()).orElseThrow(), caller, null);
    }

    private PlaylistDtos.Playlist removeItem(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Playlist p = editable(ctx, caller);
        videos.findByPublicId(ctx.path("videoId").value()).ifPresent(v -> playlists.removeItem(p.getId(), v.getId()));
        return views.summary(playlists.findByPublicId(p.getPublicId()).orElseThrow(), caller, null);
    }

    private PlaylistDtos.Playlist reorder(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Playlist p = editable(ctx, caller);
        List<String> order = Optional.ofNullable(ctx.body(PlaylistDtos.Order.class).getVideoIds()).orElse(List.of());
        List<Long> ids = order.stream().map(videos::findByPublicId).flatMap(Optional::stream).map(Video::getId).toList();
        playlists.reorder(p.getId(), ids);
        return views.detail(playlists.findByPublicId(p.getPublicId()).orElseThrow(), caller);
    }

    /** The caller's playlists; {@code ?contains=<videoId>} marks those that already hold that video. */
    private List<PlaylistDtos.Playlist> mine(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        String contains = ctx.query("contains").valueOrNull();
        Set<Long> containing = contains == null ? null : videos.findByPublicId(contains)
            .map(v -> playlists.playlistsContaining(caller.getUserId(), v.getId()))
            .orElse(Set.of());
        return playlists.listByOwner(caller.getUserId()).stream().map(p -> views.summary(p, caller, containing)).toList();
    }

    private List<PlaylistDtos.Playlist> channelPlaylists(Context ctx) {
        User owner = users.findByHandle(ctx.path("handle").value().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "channel not found"));
        AccessClaims caller = ctx.getUser();
        return playlists.listPublicByOwner(owner.getId()).stream()
            .filter(p -> p.getItemCount() > 0)
            .map(p -> views.summary(p, caller, null))
            .toList();
    }

    private Playlist viewable(Context ctx) {
        return PlaylistViews.requireViewable(playlists.findByPublicId(ctx.path("publicId").value()), ctx.getUser());
    }

    private Playlist editable(Context ctx, AccessClaims caller) {
        return PlaylistViews.requireEditable(playlists.findByPublicId(ctx.path("publicId").value()), caller);
    }

    /** Only videos the caller can watch may be added. */
    private Video watchable(String publicId, AccessClaims caller) {
        if (publicId == null) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "videoId is required");
        }
        return VideoViews.requireViewable(videos.findByPublicId(publicId), caller);
    }

    private static String validTitle(String title) {
        String t = title == null ? "" : title.strip().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > 150) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "title must be 1 to 150 characters");
        }
        return t;
    }

    private static String validDescription(String description) {
        String d = description == null ? "" : description.strip();
        if (d.length() > 5000) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "description must be at most 5000 characters");
        }
        return d;
    }
}
