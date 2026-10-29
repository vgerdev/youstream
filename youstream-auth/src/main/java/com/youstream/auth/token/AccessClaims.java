package com.youstream.auth.token;

import lombok.Value;

/** The authenticated caller, decoded from an access token. */
@Value
public class AccessClaims {
    long userId;
    String handle;
    boolean admin;
}
