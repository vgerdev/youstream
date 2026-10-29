package com.youstream.api.auth;

import com.youstream.auth.token.AccessClaims;
import io.jooby.Context;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

/** Access to the caller set by {@link AuthFilter}. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @throws StatusCodeException 401 when the request is anonymous */
    public static AccessClaims require(Context ctx) {
        AccessClaims user = ctx.getUser();
        if (user == null) {
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "sign in required");
        }
        return user;
    }
}
