package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** An uploaded video and its metadata. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Video {
    private Long id;
    private String publicId;
    private Long ownerId;
    private String title;
    @Builder.Default
    private String description = "";
    @Builder.Default
    private Visibility visibility = Visibility.PRIVATE;
    @Builder.Default
    private VideoStatus status = VideoStatus.UPLOADED;
    private VideoSource source;
    private String originalFilename;
    /** Storage key of the original upload. */
    private String originalPath;
    private long sizeBytes;
    private Long durationMs;
    private Integer width;
    private Integer height;
    private String error;
    private long viewCount;
    private long likeCount;
    private long dislikeCount;
    private long commentCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
}
