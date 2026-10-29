package com.youstream.api.social;

import com.youstream.api.AppServices;
import com.youstream.api.dto.Page;
import com.youstream.api.dto.SocialDtos;
import com.youstream.api.video.VideoViews;
import com.youstream.auth.token.AccessClaims;
import com.youstream.db.SearchQuery;
import com.youstream.db.repo.PlaylistRepository;
import com.youstream.db.repo.SocialRepository;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.util.List;
import java.util.Optional;

/** {@code GET /api/search?q=&type=video|channel|playlist&cursor=} over public content only. */
public final class SearchRoutes {

    private static final int PAGE_SIZE = 20;
    private static final int MAX_OFFSET = 1_000;

    private final VideoRepository videos;
    private final UserRepository users;
    private final PlaylistRepository playlists;
    private final SocialRepository social;
    private final VideoViews videoViews;
    private final PlaylistViews playlistViews;

    public SearchRoutes(AppServices services, PlaylistViews playlistViews) {
        this.videos = new VideoRepository(services.getDb());
        this.users = new UserRepository(services.getDb());
        this.playlists = new PlaylistRepository(services.getDb());
        this.social = new SocialRepository(services.getDb());
        this.videoViews = new VideoViews(users, new TranscodeJobRepository(services.getDb()), services.getPlaybackTokens());
        this.playlistViews = playlistViews;
    }

    public void install(Jooby app) {
        app.get("/api/search", this::search);
    }

    private Page<?> search(Context ctx) {
        AccessClaims caller = ctx.getUser();
        Optional<String> query = SearchQuery.of(ctx.query("q").value(""));
        int offset = offset(ctx.query("cursor").valueOrNull());
        String type = ctx.query("type").value("video");
        if (query.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        String q = query.get();
        List<?> items = switch (type) {
            case "video" -> videoViews.dtos(videos.search(q, PAGE_SIZE, offset), caller);
            case "channel" -> users.searchChannels(q, PAGE_SIZE, offset).stream()
                .map(u -> SocialDtos.Channel.builder()
                    .handle(u.getHandle())
                    .displayName(u.getDisplayName())
                    .bio(u.getBio())
                    .subscriberCount(u.getSubscriberCount())
                    .videoCount(videos.countPublicByOwner(u.getId()))
                    .createdAt(u.getCreatedAt())
                    .subscribed(caller == null || caller.getUserId() == u.getId() ? null : social.isSubscribed(caller.getUserId(), u.getId()))
                    .mine(caller != null && caller.getUserId() == u.getId())
                    .build())
                .toList();
            case "playlist" -> playlists.search(q, PAGE_SIZE, offset).stream()
                .map(p -> playlistViews.summary(p, caller, null)).toList();
            default -> throw new StatusCodeException(StatusCode.BAD_REQUEST, "type must be video, channel or playlist");
        };
        boolean more = items.size() == PAGE_SIZE && offset + PAGE_SIZE < MAX_OFFSET;
        return new Page<>(items, more ? Integer.toString(offset + PAGE_SIZE) : null);
    }

    /** Search results are ranked, so paging is by offset (bounded). */
    private static int offset(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor);
            if (offset < 0 || offset > MAX_OFFSET) {
                throw new NumberFormatException();
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "invalid cursor");
        }
    }
}
