package com.youstream.api.auth;

import com.youstream.api.AppServices;
import com.youstream.api.dto.LoginRequest;
import com.youstream.api.dto.SessionResponse;
import com.youstream.api.dto.UserDto;
import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.auth.ExternalIdentity;
import com.youstream.auth.entra.EntraOidcService;
import com.youstream.auth.token.AccessClaims;
import com.youstream.auth.token.LoginState;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.common.model.User;
import io.jooby.Context;
import io.jooby.Cookie;
import io.jooby.Jooby;
import io.jooby.SameSite;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sign-in endpoints: LDAP username/password, "Sign in with Microsoft", refresh, logout, /me and account linking.
 * Refresh tokens live in an HttpOnly, SameSite=Strict cookie scoped to /api/auth; access tokens go to the SPA's memory.
 */
public final class AuthRoutes {

    static final String REFRESH_COOKIE = "ys_refresh";
    static final String OIDC_COOKIE = "ys_oidc";
    private static final String REFRESH_PATH = "/api/auth";
    private static final String OIDC_PATH = "/api/auth/microsoft";
    private static final Pattern SAFE_RETURN = Pattern.compile("^/(?![/\\\\])[^\\s]{0,500}$");
    private static final Logger log = LoggerFactory.getLogger(AuthRoutes.class);

    private final AppServices s;

    public AuthRoutes(AppServices services) {
        this.s = services;
    }

    public void install(Jooby app) {
        app.get("/api/auth/methods", ctx -> Map.of("ldap", s.getLdap() != null, "microsoft", s.getEntra() != null));

        app.post("/api/auth/login", this::ldapLogin);
        app.post("/api/auth/refresh", this::refresh);
        app.post("/api/auth/logout", this::logout);

        app.get("/api/auth/microsoft/login", this::microsoftLogin);
        app.get("/api/auth/microsoft/callback", this::microsoftCallback);

        app.get("/api/me", ctx -> me(CurrentUser.require(ctx).getUserId()));
        app.post("/api/me/identities/ldap", this::linkLdap);
        app.post("/api/me/identities/microsoft", this::linkMicrosoft);
    }

    // ------------------------------------------------------------------ LDAP

    private SessionResponse ldapLogin(Context ctx) {
        if (s.getLdap() == null) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "LDAP sign-in is disabled");
        }
        LoginRequest req = ctx.body(LoginRequest.class);
        ExternalIdentity identity = authenticateLdap(ctx, req);
        return startSession(ctx, s.getUsers().signIn(identity));
    }

    private UserDto linkLdap(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        if (s.getLdap() == null) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "LDAP sign-in is disabled");
        }
        s.getUsers().link(caller.getUserId(), authenticateLdap(ctx, ctx.body(LoginRequest.class)));
        return me(caller.getUserId());
    }

    private ExternalIdentity authenticateLdap(Context ctx, LoginRequest req) {
        String ip = ctx.getRemoteAddress();
        if (s.getLoginThrottle().isBlocked(ip, req.getUsername())) {
            throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "too many failed sign-in attempts; try again later");
        }
        try {
            ExternalIdentity identity = s.getLdap().authenticate(req.getUsername(), req.getPassword());
            s.getLoginThrottle().success(ip, req.getUsername());
            return identity;
        } catch (AuthException e) {
            if (e.getReason() == Reason.INVALID_CREDENTIALS) {
                s.getLoginThrottle().failure(ip, req.getUsername());
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ Microsoft

    private Context microsoftLogin(Context ctx) {
        EntraOidcService entra = requireEntra();
        EntraOidcService.Authorization auth = entra.begin(safeReturnTo(ctx.query("returnTo").valueOrNull()), null);
        setOidcCookie(ctx, auth.state());
        return ctx.sendRedirect(StatusCode.FOUND, auth.uri().toString());
    }

    private Map<String, String> linkMicrosoft(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        EntraOidcService.Authorization auth = requireEntra().begin("/account", caller.getUserId());
        setOidcCookie(ctx, auth.state());
        return Map.of("url", auth.uri().toString());
    }

    /** Browser navigation from Microsoft: always answers with a redirect, never JSON. */
    private Context microsoftCallback(Context ctx) {
        EntraOidcService entra = requireEntra();
        String cookie = ctx.cookie(OIDC_COOKIE).valueOrNull();
        clearCookie(ctx, OIDC_COOKIE, OIDC_PATH);
        if (cookie == null) {
            return redirectToLogin(ctx, "session_expired");
        }
        LoginState state;
        try {
            state = s.getTokens().verifyLoginState(cookie);
        } catch (AuthException e) {
            return redirectToLogin(ctx, "session_expired");
        }
        String error = ctx.query("error").valueOrNull();
        if (error != null) {
            log.info("Microsoft sign-in returned {}: {}", error, ctx.query("error_description").valueOrNull());
            return redirectAfter(ctx, state, "access_denied".equals(error) ? "cancelled" : "microsoft_error");
        }
        try {
            ExternalIdentity identity = entra.complete(ctx.query("code").valueOrNull(), ctx.query("state").valueOrNull(), state);
            if (state.getLinkUserId() != null) {
                s.getUsers().link(state.getLinkUserId(), identity);
            } else {
                setRefreshCookie(ctx, s.getRefreshTokens().issue(s.getUsers().signIn(identity).getId(), userAgent(ctx)));
            }
            return ctx.sendRedirect(StatusCode.FOUND, s.getFrontendUrl() + safeReturnTo(state.getReturnTo()));
        } catch (AuthException e) {
            return redirectAfter(ctx, state, e.getReason().name().toLowerCase(Locale.ROOT));
        }
    }

    private EntraOidcService requireEntra() {
        if (s.getEntra() == null) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Microsoft sign-in is disabled");
        }
        return s.getEntra();
    }

    private Context redirectAfter(Context ctx, LoginState state, String error) {
        if (state.getLinkUserId() != null) {
            return ctx.sendRedirect(StatusCode.FOUND, s.getFrontendUrl() + "/account?error=" + encode(error));
        }
        return redirectToLogin(ctx, error);
    }

    private Context redirectToLogin(Context ctx, String error) {
        return ctx.sendRedirect(StatusCode.FOUND, s.getFrontendUrl() + "/login?error=" + encode(error));
    }

    // ------------------------------------------------------------------ session

    private SessionResponse refresh(Context ctx) {
        RefreshTokenService.Issued next;
        try {
            next = s.getRefreshTokens().rotate(ctx.cookie(REFRESH_COOKIE).valueOrNull(), userAgent(ctx));
        } catch (AuthException e) {
            clearCookie(ctx, REFRESH_COOKIE, REFRESH_PATH);
            throw e;
        }
        User user = s.getUsers().findById(next.userId())
            .orElseThrow(() -> new AuthException(Reason.INVALID_TOKEN, "user no longer exists"));
        setRefreshCookie(ctx, next);
        return session(user);
    }

    private Context logout(Context ctx) {
        s.getRefreshTokens().revoke(ctx.cookie(REFRESH_COOKIE).valueOrNull());
        clearCookie(ctx, REFRESH_COOKIE, REFRESH_PATH);
        return ctx.send(StatusCode.NO_CONTENT);
    }

    private SessionResponse startSession(Context ctx, User user) {
        setRefreshCookie(ctx, s.getRefreshTokens().issue(user.getId(), userAgent(ctx)));
        return session(user);
    }

    private SessionResponse session(User user) {
        TokenService.AccessToken access = s.getTokens().issueAccessToken(user);
        return new SessionResponse(access.token(), access.expiresAt(), dto(user));
    }

    private UserDto me(long userId) {
        User user = s.getUsers().findById(userId)
            .orElseThrow(() -> new StatusCodeException(StatusCode.UNAUTHORIZED, "user no longer exists"));
        return dto(user);
    }

    private UserDto dto(User user) {
        return UserDto.of(user, s.getUsers().repository().listIdentities(user.getId()));
    }

    // ------------------------------------------------------------------ cookies & helpers

    private void setRefreshCookie(Context ctx, RefreshTokenService.Issued token) {
        ctx.setResponseCookie(new Cookie(REFRESH_COOKIE, token.token())
            .setPath(REFRESH_PATH)
            .setHttpOnly(true)
            .setSecure(s.isSecureCookies())
            .setSameSite(SameSite.STRICT)
            .setMaxAge(s.getRefreshTokens().ttl()));
    }

    private void setOidcCookie(Context ctx, LoginState state) {
        // Lax: the cookie must accompany the top-level redirect back from login.microsoftonline.com
        ctx.setResponseCookie(new Cookie(OIDC_COOKIE, s.getTokens().issueLoginState(state))
            .setPath(OIDC_PATH)
            .setHttpOnly(true)
            .setSecure(s.isSecureCookies())
            .setSameSite(SameSite.LAX)
            .setMaxAge(Duration.ofMinutes(10)));
    }

    private void clearCookie(Context ctx, String name, String path) {
        ctx.setResponseCookie(new Cookie(name, "")
            .setPath(path)
            .setHttpOnly(true)
            .setSecure(s.isSecureCookies())
            .setMaxAge(0));
    }

    static String safeReturnTo(String returnTo) {
        return returnTo != null && SAFE_RETURN.matcher(returnTo).matches() ? returnTo : "/";
    }

    private static String userAgent(Context ctx) {
        return ctx.header("User-Agent").valueOrNull();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
