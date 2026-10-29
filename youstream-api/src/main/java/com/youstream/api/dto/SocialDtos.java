package com.youstream.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Bodies for comments, reactions, channels, subscriptions and profile edits. */
public final class SocialDtos {

    private SocialDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Comment {
        private long id;
        private Long parentId;
        /** null when deleted */
        private String body;
        private boolean deleted;
        private Author author;
        private Instant createdAt;
        private Instant editedAt;
        private int replyCount;
        private boolean canEdit;
        private boolean canDelete;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Author {
        private String handle;
        private String displayName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewComment {
        private String body;
        /** Reply to this comment (replies to replies attach to the top-level comment). */
        private Long parentId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reaction {
        /** 1 like, -1 dislike, 0 none */
        private int value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReactionState {
        private int myReaction;
        private long likeCount;
        private long dislikeCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Views {
        private long viewCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Channel {
        private String handle;
        private String displayName;
        private String bio;
        private long subscriberCount;
        private long videoCount;
        private Instant createdAt;
        /** null for anonymous callers */
        private Boolean subscribed;
        private boolean mine;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Subscription {
        private boolean subscribed;
        private long subscriberCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileUpdate {
        private String handle;
        private String displayName;
        private String bio;
    }
}
