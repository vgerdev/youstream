package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A YouStream account; also the owner of a channel. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String handle;
    private String avatarPath;
    private String bannerPath;
    @Builder.Default
    private String bio = "";
    private boolean admin;
    private long subscriberCount;
    private Instant createdAt;
    private Instant lastLoginAt;
}
