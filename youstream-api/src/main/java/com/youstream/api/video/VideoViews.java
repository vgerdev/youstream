package com.youstream.api.video;

import com.youstream.api.dto.VideoDto;
import com.youstream.auth.token.AccessClaims;
import com.youstream.auth.token.PlaybackTokens;
import com.youstream.common.model.TranscodeJob;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.UserRepository;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Access rules for videos and their conversion to {@link VideoDto}. */
public class VideoViews {

    private final UserRepository users;
    private final TranscodeJobRepository jobs;
    private final PlaybackTokens playbackTokens;

    public VideoViews(UserRepository users, TranscodeJobRepository jobs, PlaybackTokens playbackTokens) {
        this.users = users;
        this.jobs = jobs;
        this.playbackTokens = playbackTokens;
    }

    /** Public and unlisted videos are watchable by anyone with the link; private ones by owner and admins. */
    public static boolean canView(Video video, AccessClaims caller) {
        return video.getVisibility() != Visibility.PRIVATE || canEdit(video, caller);
    }

    public static boolean canEdit(Video video, AccessClaims caller) {
        return caller != null && (caller.isAdmin() || caller.getUserId() == video.getOwnerId());
    }

    /** 404 (not 403) for videos the caller may not see, so private ids do not leak. */
    public static Video requireViewable(Optional<Video> video, AccessClaims caller) {
        return video.filter(v -> canView(v, caller))
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "video not found"));
    }

    public static Video requireEditable(Optional<Video> video, AccessClaims caller) {
        Video v = requireViewable(video, caller);
        if (!canEdit(v, caller)) {
            throw new StatusCodeException(StatusCode.FORBIDDEN, "only the owner can change this video");
        }
        return v;
    }

    public VideoDto dto(Video video, AccessClaims caller) {
        return dtos(List.of(video), caller).getFirst();
    }

    public List<VideoDto> dtos(List<Video> videos, AccessClaims caller) {
        Map<Long, User> owners = new HashMap<>();
        return videos.stream().map(v -> {
            User owner = owners.computeIfAbsent(v.getOwnerId(), id -> users.findById(id).orElse(null));
            return toDto(v, owner, caller);
        }).toList();
    }

    private VideoDto toDto(Video v, User owner, AccessClaims caller) {
        boolean editable = canEdit(v, caller);
        VideoDto.VideoDtoBuilder dto = VideoDto.builder()
            .publicId(v.getPublicId())
            .title(v.getTitle())
            .description(v.getDescription())
            .visibility(v.getVisibility())
            .status(v.getStatus())
            .durationMs(v.getDurationMs())
            .width(v.getWidth())
            .height(v.getHeight())
            .viewCount(v.getViewCount())
            .likeCount(v.getLikeCount())
            .dislikeCount(v.getDislikeCount())
            .commentCount(v.getCommentCount())
            .createdAt(v.getCreatedAt())
            .publishedAt(v.getPublishedAt())
            .channel(owner == null ? null : new VideoDto.Channel(owner.getHandle(), owner.getDisplayName()))
            .canEdit(editable);
        if (v.getStatus() == VideoStatus.READY) {
            String token = v.getVisibility() == Visibility.PUBLIC ? PlaybackTokens.PUBLIC : playbackTokens.issue(v.getPublicId());
            dto.posterUrl("/thumbs/" + v.getPublicId() + "/" + token + "/poster.jpg");
            dto.playbackUrl("/media/" + v.getPublicId() + "/" + token + "/master.m3u8");
        }
        if (editable) {
            dto.originalFilename(v.getOriginalFilename()).sizeBytes(v.getSizeBytes()).error(v.getError());
            if (v.getStatus() == VideoStatus.PROCESSING || v.getStatus() == VideoStatus.UPLOADED) {
                dto.progress(jobs.findLatestForVideo(v.getId()).map(TranscodeJob::getProgress).orElse(0f));
            }
        }
        return dto.build();
    }
}
