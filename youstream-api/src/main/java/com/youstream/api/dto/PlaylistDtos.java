package com.youstream.api.dto;

import com.youstream.common.model.Visibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** Playlist bodies. */
public final class PlaylistDtos {

    private PlaylistDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Playlist {
        private String publicId;
        private String title;
        private String description;
        private Visibility visibility;
        private int itemCount;
        private VideoDto.Channel owner;
        /** Poster of the first playable item. */
        private String thumbnailUrl;
        private Instant createdAt;
        private Instant updatedAt;
        private boolean canEdit;
        /** Detail view only: the items the caller may watch, in order. */
        private List<VideoDto> items;
        /** With {@code ?contains=}: whether the video is in this playlist. */
        private Boolean containsVideo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edit {
        private String title;
        private String description;
        private Visibility visibility;
        /** Create only: first video to add. */
        private String videoId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String videoId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        private List<String> videoIds;
    }
}
