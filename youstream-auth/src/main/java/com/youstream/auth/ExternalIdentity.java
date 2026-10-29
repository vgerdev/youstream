package com.youstream.auth;

import com.youstream.common.model.IdentityProvider;
import lombok.Builder;
import lombok.Value;

/** A user as asserted by LDAP or Entra ID after a successful login. */
@Value
@Builder
public class ExternalIdentity {
    IdentityProvider provider;
    /** Stable id at the provider (entryUUID, objectGUID or Entra oid). */
    String subject;
    /** Preferred login name; becomes the YouStream username on first sign-in. */
    String username;
    String displayName;
    String email;
    boolean admin;
}
