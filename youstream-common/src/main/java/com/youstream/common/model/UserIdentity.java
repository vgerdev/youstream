package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Links a user to an external login (LDAP entry or Entra ID object). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class UserIdentity {
    private IdentityProvider provider;
    /** Stable id at the provider: LDAP entryUUID/objectGUID or the Entra {@code oid} claim. */
    private String subject;
    private Long userId;
    private Instant createdAt;
    private Instant lastLoginAt;
}
