package com.youstream.api.social;

import com.youstream.api.AppServices;
import com.youstream.api.auth.CurrentUser;
import com.youstream.api.dto.Page;
import com.youstream.api.dto.SocialDtos;
import com.youstream.api.dto.UserDto;
import com.youstream.api.dto.VideoDto;
import com.youstream.api.video.VideoViews;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.db.DataAccessException;
import com.youstream.db.repo.SocialRepository;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Channel pages, subscriptions, the subscription feed and profile edits. */
public final class ChannelRoutes {

    private static final int PAGE_SIZE = 24;
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9][a-z0-9._-]{2,29}");

    private final UserRepository users;
    private final VideoRepository videos;
    private final SocialRepository social;
    private final VideoViews views;

    public ChannelRoutes(AppServices services) {
        this.users = new UserRepository(services.getDb());
        this.videos = new VideoRepository(services.getDb());
        this.social = new SocialRepository(services.getDb());
        this.views = new VideoViews(users, new TranscodeJobRepository(services.getDb()), services.getPlaybackTokens());
    }

    public void install(Jooby app) {
        app.get("/api/channels/{handle}", ctx -> channel(channelUser(ctx), ctx.getUser()));
        app.get("/api/channels/{handle}/videos", this::channelVideos);
        app.put("/api/channels/{handle}/subscription", this::subscribe);
        app.delete("/api/channels/{handle}/subscription", this::unsubscribe);
        app.get("/api/feed/subscriptions", this::feed);
        app.get("/api/me/subscriptions", ctx -> social.subscriptions(CurrentUser.require(ctx).getUserId()).stream()
            .map(u -> channel(u, ctx.getUser())).toList());
        app.patch("/api/me", this::updateProfile);
    }

    private SocialDtos.Channel channel(User u, AccessClaims caller) {
        boolean mine = caller != null && caller.getUserId() == u.getId();
        return SocialDtos.Channel.builder()
            .handle(u.getHandle())
            .displayName(u.getDisplayName())
            .bio(u.getBio())
            .subscriberCount(u.getSubscriberCount())
            .videoCount(videos.countPublicByOwner(u.getId()))
            .createdAt(u.getCreatedAt())
            .subscribed(caller == null || mine ? null : social.isSubscribed(caller.getUserId(), u.getId()))
            .mine(mine)
            .build();
    }

    private Page<VideoDto> channelVideos(Context ctx) {
        User owner = channelUser(ctx);
        return page(ctx, (cursor) -> videos.listPublicByOwner(owner.getId(), cursor.before(), cursor.beforeId(), PAGE_SIZE));
    }

    private Page<VideoDto> feed(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        return page(ctx, (cursor) -> videos.listSubscriptionFeed(caller.getUserId(), cursor.before(), cursor.beforeId(), PAGE_SIZE));
    }

    private SocialDtos.Subscription subscribe(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        User channel = channelUser(ctx);
        if (channel.getId() == caller.getUserId()) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "you cannot subscribe to your own channel");
        }
        return new SocialDtos.Subscription(true, social.subscribe(caller.getUserId(), channel.getId()));
    }

    private SocialDtos.Subscription unsubscribe(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        User channel = channelUser(ctx);
        return new SocialDtos.Subscription(false, social.unsubscribe(caller.getUserId(), channel.getId()));
    }

    private UserDto updateProfile(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        User user = users.findById(caller.getUserId())
            .orElseThrow(() -> new StatusCodeException(StatusCode.UNAUTHORIZED, "user no longer exists"));
        SocialDtos.ProfileUpdate req = ctx.body(SocialDtos.ProfileUpdate.class);
        String handle = req.getHandle() == null ? user.getHandle() : req.getHandle().strip().toLowerCase(Locale.ROOT);
        String displayName = req.getDisplayName() == null ? user.getDisplayName() : req.getDisplayName().strip().replaceAll("\\s+", " ");
        String bio = req.getBio() == null ? user.getBio() : req.getBio().strip();
        if (!HANDLE.matcher(handle).matches()) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST,
                "handle must be 3 to 30 characters: lowercase letters, digits, '.', '_' or '-'");
        }
        if (displayName.isEmpty() || displayName.length() > 50) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "display name must be 1 to 50 characters");
        }
        if (bio.length() > 1000) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "bio must be at most 1000 characters");
        }
        try {
            users.updateProfile(user.getId(), handle, displayName, bio);
        } catch (DataAccessException e) {
            if (e.isUniqueViolation()) {
                throw new StatusCodeException(StatusCode.CONFLICT, "that handle is taken");
            }
            throw e;
        }
        return UserDto.of(users.findById(user.getId()).orElseThrow(), users.listIdentities(user.getId()));
    }

    private User channelUser(Context ctx) {
        return users.findByHandle(ctx.path("handle").value().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "channel not found"));
    }

    private record Cursor(Instant before, Long beforeId) {
    }

    /** Keyset paging on (published_at, id); cursor is {@code <publishedAtMillis>_<id>}. */
    private Page<VideoDto> page(Context ctx, Function<Cursor, List<Video>> query) {
        String raw = ctx.query("cursor").valueOrNull();
        Cursor cursor = new Cursor(null, null);
        if (raw != null) {
            try {
                String[] parts = raw.split("_", 2);
                cursor = new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw new StatusCodeException(StatusCode.BAD_REQUEST, "invalid cursor");
            }
        }
        List<Video> page = query.apply(cursor);
        Video last = page.size() == PAGE_SIZE ? page.getLast() : null;
        return new Page<>(views.dtos(page, ctx.getUser()),
            last == null ? null : last.getPublishedAt().toEpochMilli() + "_" + last.getId());
    }
}
