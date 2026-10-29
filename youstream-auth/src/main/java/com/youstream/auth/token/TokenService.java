package com.youstream.auth.token;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.common.model.User;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Signs and verifies YouStream's own HS256 JWTs: API access tokens and OIDC login-state cookies. */
public class TokenService {

    public record AccessToken(String token, Instant expiresAt) {
    }

    private static final String ISSUER = "youstream";
    private static final String ACCESS_AUDIENCE = "youstream-api";
    private static final String LOGIN_STATE_AUDIENCE = "youstream-login-state";
    private static final Duration LOGIN_STATE_TTL = Duration.ofMinutes(10);

    private final Algorithm algorithm;
    private final Duration accessTtl;
    private final Clock clock;
    private final JWTVerifier accessVerifier;
    private final JWTVerifier loginStateVerifier;

    public TokenService(String secret, Duration accessTtl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt.secret must be at least 32 bytes");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.accessTtl = accessTtl;
        this.clock = clock;
        this.accessVerifier = verifier(ACCESS_AUDIENCE);
        this.loginStateVerifier = verifier(LOGIN_STATE_AUDIENCE);
    }

    public AccessToken issueAccessToken(User user) {
        Instant now = clock.instant();
        Instant expires = now.plus(accessTtl);
        String token = JWT.create()
            .withIssuer(ISSUER)
            .withAudience(ACCESS_AUDIENCE)
            .withSubject(Long.toString(user.getId()))
            .withClaim("handle", user.getHandle())
            .withClaim("adm", user.isAdmin())
            .withIssuedAt(now)
            .withExpiresAt(expires)
            .sign(algorithm);
        return new AccessToken(token, expires);
    }

    public AccessClaims verifyAccessToken(String token) {
        DecodedJWT jwt = verify(accessVerifier, token);
        try {
            return new AccessClaims(Long.parseLong(jwt.getSubject()), jwt.getClaim("handle").asString(),
                Boolean.TRUE.equals(jwt.getClaim("adm").asBoolean()));
        } catch (NumberFormatException e) {
            throw new AuthException(Reason.INVALID_TOKEN, "invalid token subject");
        }
    }

    public String issueLoginState(LoginState state) {
        Instant now = clock.instant();
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(LOGIN_STATE_AUDIENCE)
            .withClaim("st", state.getState())
            .withClaim("nn", state.getNonce())
            .withClaim("cv", state.getCodeVerifier())
            .withClaim("rt", state.getReturnTo())
            .withClaim("lu", state.getLinkUserId())
            .withIssuedAt(now)
            .withExpiresAt(now.plus(LOGIN_STATE_TTL))
            .sign(algorithm);
    }

    public LoginState verifyLoginState(String token) {
        DecodedJWT jwt = verify(loginStateVerifier, token);
        return LoginState.builder()
            .state(jwt.getClaim("st").asString())
            .nonce(jwt.getClaim("nn").asString())
            .codeVerifier(jwt.getClaim("cv").asString())
            .returnTo(jwt.getClaim("rt").asString())
            .linkUserId(jwt.getClaim("lu").asLong())
            .build();
    }

    private JWTVerifier verifier(String audience) {
        return ((JWTVerifier.BaseVerification) JWT.require(algorithm).withIssuer(ISSUER).withAudience(audience))
            .build(clock);
    }

    private static DecodedJWT verify(JWTVerifier verifier, String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException(Reason.INVALID_TOKEN, "missing token");
        }
        try {
            return verifier.verify(token);
        } catch (JWTVerificationException e) {
            throw new AuthException(Reason.INVALID_TOKEN, "invalid token", e);
        }
    }
}
