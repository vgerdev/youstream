package com.youstream.media;

import lombok.Builder;
import lombok.Value;

/** What ffprobe reports about a file; width/height are as displayed (rotation applied). */
@Value
@Builder(toBuilder = true)
public class ProbeResult {
    /** 0 when the container does not record a duration. */
    long durationMs;
    int width;
    int height;
    boolean audio;
    String videoCodec;
    String format;
}
