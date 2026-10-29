package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** An ordered list of videos owned by a user. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Playlist {
    private Long id;
    private String publicId;
    private Long ownerId;
    private String title;
    @Builder.Default
    private String description = "";
    @Builder.Default
    private Visibility visibility = Visibility.PRIVATE;
    private int itemCount;
    private Instant createdAt;
    private Instant updatedAt;
}
