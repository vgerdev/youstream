package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A queued or running FFmpeg transcode of one video. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeJob {
    private Long id;
    private Long videoId;
    @Builder.Default
    private JobStatus status = JobStatus.QUEUED;
    /** 0..1 */
    private float progress;
    private int attempts;
    @Builder.Default
    private int maxAttempts = 3;
    private String lockedBy;
    private Instant lockedAt;
    private String error;
    private Instant runAfter;
    private Instant createdAt;
    private Instant finishedAt;
}
