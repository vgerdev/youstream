package com.youstream.auth.token;

import lombok.Builder;
import lombok.Value;

/** OIDC round-trip state kept in a short-lived signed cookie between /login and /callback. */
@Value
@Builder
public class LoginState {
    String state;
    String nonce;
    String codeVerifier;
    /** SPA path to return to after sign-in. */
    String returnTo;
    /** Set when an already signed-in user is linking a Microsoft account. */
    Long linkUserId;
}
