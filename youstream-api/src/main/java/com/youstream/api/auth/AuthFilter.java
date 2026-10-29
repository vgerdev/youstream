package com.youstream.api.auth;

import com.youstream.auth.token.TokenService;
import io.jooby.Context;
import io.jooby.Route;

/**
 * Decodes {@code Authorization: Bearer <access token>} into {@link Context#getUser()}.
 * A present but invalid token fails the request with 401 so the client knows to refresh.
 */
public class AuthFilter implements Route.Before {

    private static final String PREFIX = "Bearer ";

    private final TokenService tokens;

    public AuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public void apply(Context ctx) {
        String header = ctx.header("Authorization").valueOrNull();
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            ctx.setUser(tokens.verifyAccessToken(header.substring(PREFIX.length()).trim()));
        }
    }
}
