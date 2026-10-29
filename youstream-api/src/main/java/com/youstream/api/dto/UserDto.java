package com.youstream.api.dto;

import com.youstream.common.model.IdentityProvider;
import com.youstream.common.model.User;
import com.youstream.common.model.UserIdentity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** The signed-in user as returned by /api/me and the auth endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private long id;
    private String username;
    private String handle;
    private String displayName;
    private String email;
    private String bio;
    private boolean admin;
    private long subscriberCount;
    private Instant createdAt;
    /** Login methods linked to this account. */
    private List<IdentityProvider> identities;

    public static UserDto of(User user, List<UserIdentity> identities) {
        return UserDto.builder()
            .id(user.getId())
            .username(user.getUsername())
            .handle(user.getHandle())
            .displayName(user.getDisplayName())
            .email(user.getEmail())
            .bio(user.getBio())
            .admin(user.isAdmin())
            .subscriberCount(user.getSubscriberCount())
            .createdAt(user.getCreatedAt())
            .identities(identities.stream().map(UserIdentity::getProvider).toList())
            .build();
    }
}
