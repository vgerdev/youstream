package com.youstream.api.social;

import com.youstream.api.dto.PlaylistDtos;
import com.youstream.api.dto.VideoDto;
import com.youstream.api.video.VideoViews;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.model.Playlist;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.db.repo.PlaylistRepository;
import com.youstream.db.repo.UserRepository;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Playlist access rules and DTOs. Same model as videos: private = owner and admins only. */
public class PlaylistViews {

    private final PlaylistRepository playlists;
    private final UserRepository users;
    private final VideoViews videoViews;

    public PlaylistViews(PlaylistRepository playlists, UserRepository users, VideoViews videoViews) {
        this.playlists = playlists;
        this.users = users;
        this.videoViews = videoViews;
    }

    public static boolean canEdit(Playlist p, AccessClaims caller) {
        return caller != null && (caller.isAdmin() || caller.getUserId() == p.getOwnerId());
    }

    public static Playlist requireViewable(Optional<Playlist> playlist, AccessClaims caller) {
        return playlist.filter(p -> p.getVisibility() != Visibility.PRIVATE || canEdit(p, caller))
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "playlist not found"));
    }

    public static Playlist requireEditable(Optional<Playlist> playlist, AccessClaims caller) {
        Playlist p = requireViewable(playlist, caller);
        if (!canEdit(p, caller)) {
            throw new StatusCodeException(StatusCode.FORBIDDEN, "only the owner can change this playlist");
        }
        return p;
    }

    /** Items the caller may actually watch: ready, and not someone else's private video. */
    public List<Video> playableItems(Playlist p, AccessClaims caller) {
        return playlists.items(p.getId()).stream()
            .filter(v -> v.getStatus() == VideoStatus.READY && VideoViews.canView(v, caller))
            .toList();
    }

    public PlaylistDtos.Playlist summary(Playlist p, AccessClaims caller, Set<Long> containing) {
        List<Video> items = playableItems(p, caller);
        return base(p, caller, items.isEmpty() ? null : videoViews.dto(items.getFirst(), caller).getPosterUrl())
            .containsVideo(containing == null ? null : containing.contains(p.getId()))
            .build();
    }

    public PlaylistDtos.Playlist detail(Playlist p, AccessClaims caller) {
        List<VideoDto> items = videoViews.dtos(playableItems(p, caller), caller);
        return base(p, caller, items.isEmpty() ? null : items.getFirst().getPosterUrl()).items(items).build();
    }

    private PlaylistDtos.Playlist.PlaylistBuilder base(Playlist p, AccessClaims caller, String thumbnail) {
        User owner = users.findById(p.getOwnerId()).orElse(null);
        return PlaylistDtos.Playlist.builder()
            .publicId(p.getPublicId())
            .title(p.getTitle())
            .description(p.getDescription())
            .visibility(p.getVisibility())
            .itemCount(p.getItemCount())
            .owner(owner == null ? null : new VideoDto.Channel(owner.getHandle(), owner.getDisplayName()))
            .thumbnailUrl(thumbnail)
            .createdAt(p.getCreatedAt())
            .updatedAt(p.getUpdatedAt())
            .canEdit(canEdit(p, caller));
    }
}
