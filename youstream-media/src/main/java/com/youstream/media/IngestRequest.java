package com.youstream.media;

import com.youstream.common.model.VideoSource;
import com.youstream.common.model.Visibility;
import lombok.Builder;
import lombok.Value;

/** A finished upload to turn into a video. */
@Value
@Builder
public class IngestRequest {
    long ownerId;
    /** Storage key of the uploaded file; it is moved, not copied. */
    String sourceKey;
    String originalFilename;
    /** Optional; defaults to the file name without extension. */
    String title;
    @Builder.Default
    String description = "";
    @Builder.Default
    Visibility visibility = Visibility.PRIVATE;
    VideoSource source;
}
