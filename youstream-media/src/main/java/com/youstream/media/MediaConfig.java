package com.youstream.media;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

/** FFmpeg and transcode worker settings. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MediaConfig {
    @Builder.Default
    private String ffmpeg = "ffmpeg";
    @Builder.Default
    private String ffprobe = "ffprobe";
    @Builder.Default
    private int workers = 2;
    @Builder.Default
    private int segmentSeconds = 6;
    /** x264 preset: faster presets encode quicker but produce larger files. */
    @Builder.Default
    private String preset = "veryfast";
    /** Running jobs locked longer than this are assumed dead and requeued. */
    @Builder.Default
    private Duration staleJobAfter = Duration.ofHours(6);
    @Builder.Default
    private Duration pollInterval = Duration.ofSeconds(2);
}
