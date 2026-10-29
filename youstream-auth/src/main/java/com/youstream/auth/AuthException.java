package com.youstream.auth;

import lombok.Getter;

/** Authentication or authorization failure; the HTTP layer maps {@link Reason} to a status code. */
@Getter
public class AuthException extends RuntimeException {

    public enum Reason {
        /** Wrong username/password or rejected login at the identity provider. */
        INVALID_CREDENTIALS,
        /** Authenticated, but not allowed (e.g. not in the required group). */
        FORBIDDEN,
        /** Missing, expired, tampered or reused token. */
        INVALID_TOKEN,
        /** The identity is already linked to a different user. */
        CONFLICT,
        /** The directory or identity provider could not be reached. */
        UNAVAILABLE
    }

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
