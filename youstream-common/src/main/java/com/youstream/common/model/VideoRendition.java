package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One HLS quality level of a transcoded video. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class VideoRendition {
    private Long videoId;
    private int height;
    private int width;
    private int bitrateKbps;
    /** Storage key of the rendition's media playlist. */
    private String playlistPath;
}
