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

    // owner only
    private String originalFilename;
    private Long sizeBytes;
    /** 0..1 while PROCESSING. */
    private Float progress;
    private String error;
}
