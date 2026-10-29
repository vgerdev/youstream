package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A comment on a video; replies have a {@link #parentId} (one level deep). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Comment {
    private Long id;
    private Long videoId;
    private Long userId;
    private Long parentId;
    private String body;
    private int replyCount;
    private Instant createdAt;
    private Instant editedAt;
    private Instant deletedAt;
    /** Joined from users when listing. */
    private String authorHandle;
    private String authorDisplayName;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
