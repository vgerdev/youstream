package com.youstream.api.dto;

import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A video as seen by the caller; owner-only fields are null for everyone else. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Channel {
        private String handle;
        private String displayName;
        /** Watch page only. */
        private Long subscriberCount;
        /** Watch page only; null for anonymous callers and the owner. */
        private Boolean subscribed;

        public Channel(String handle, String displayName) {
            this(handle, displayName, null, null);
        }
    }

    private String publicId;
    private String title;
    private String description;
    private Visibility visibility;
    private VideoStatus status;
    private Long durationMs;
    private Integer width;
    private Integer height;
    private long viewCount;
    private long likeCount;
    private long dislikeCount;
    private long commentCount;
    private Instant createdAt;
    private Instant publishedAt;
    private Channel channel;
    /** Set once transcoding finished. */
    private String posterUrl;
    /** HLS master playlist; set once transcoding finished. */
    private String playbackUrl;
    private boolean canEdit;
    /** Watch page only: the caller's reaction (1, -1, 0); null when anonymous. */
    private Integer myReaction;

    // owner only
    private String originalFilename;
    private Long sizeBytes;
    /** 0..1 while PROCESSING. */
    private Float progress;
    private String error;
}
