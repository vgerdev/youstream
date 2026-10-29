package com.youstream.auth.token;

import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.common.model.User;
import com.youstream.db.TestDatabase;
import com.youstream.db.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServicesTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";
    private static final User ALICE = User.builder().id(42L).handle("alice").admin(true).build();

    @Test
    void accessTokenRoundTrip() {
        TokenService tokens = new TokenService(SECRET, Duration.ofMinutes(15), Clock.systemUTC());

        AccessClaims claims = tokens.verifyAccessToken(tokens.issueAccessToken(ALICE).token());

        assertThat(claims).isEqualTo(new AccessClaims(42, "alice", true));
    }

    @Test
    void rejectsExpiredForeignAndTamperedTokens() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        TokenService old = new TokenService(SECRET, Duration.ofMinutes(15), past);
        TokenService tokens = new TokenService(SECRET, Duration.ofMinutes(15), Clock.systemUTC());
        TokenService other = new TokenService("another-secret-another-secret-123", Duration.ofMinutes(15), Clock.systemUTC());
        String valid = tokens.issueAccessToken(ALICE).token();

        assertInvalid(() -> tokens.verifyAccessToken(old.issueAccessToken(ALICE).token()));
        assertInvalid(() -> tokens.verifyAccessToken(other.issueAccessToken(ALICE).token()));
        assertInvalid(() -> tokens.verifyAccessToken(valid.substring(0, valid.length() - 2) + "xx"));
        assertInvalid(() -> tokens.verifyAccessToken(null));
    }

    @Test
    void loginStateCannotBeUsedAsAccessToken() {
        TokenService tokens = new TokenService(SECRET, Duration.ofMinutes(15), Clock.systemUTC());
        LoginState state = LoginState.builder().state("s").nonce("n").codeVerifier("v").returnTo("/x").linkUserId(7L).build();
        String cookie = tokens.issueLoginState(state);

        assertThat(tokens.verifyLoginState(cookie)).isEqualTo(state);
        assertInvalid(() -> tokens.verifyAccessToken(cookie));
        assertInvalid(() -> tokens.verifyLoginState(tokens.issueAccessToken(ALICE).token()));
    }

    @Test
    void rejectsShortSecrets() {
        assertThatThrownBy(() -> new TokenService("short", Duration.ofMinutes(1), Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshTokensRotateAndDetectReuse() {
        TestDatabase.truncate();
        long userId = new UserRepository(TestDatabase.db())
            .insert(User.builder().username("alice").displayName("Alice").handle("alice").build()).getId();
        RefreshTokenService refresh = new RefreshTokenService(TestDatabase.db(), Duration.ofDays(30), Clock.systemUTC());

        RefreshTokenService.Issued first = refresh.issue(userId, "agent");
        RefreshTokenService.Issued second = refresh.rotate(first.token(), "agent");
        RefreshTokenService.Issued third = refresh.rotate(second.token(), "agent");
        assertThat(third.userId()).isEqualTo(userId);

        // replaying an old token revokes the whole chain, including the newest token
        assertInvalid(() -> refresh.rotate(first.token(), "agent"));
        assertInvalid(() -> refresh.rotate(third.token(), "agent"));
        assertInvalid(() -> refresh.rotate("unknown", "agent"));
    }

    @Test
    void revokedAndExpiredRefreshTokensAreRejected() {
        TestDatabase.truncate();
        long userId = new UserRepository(TestDatabase.db())
            .insert(User.builder().username("bob").displayName("Bob").handle("bob").build()).getId();
        RefreshTokenService refresh = new RefreshTokenService(TestDatabase.db(), Duration.ofDays(30), Clock.systemUTC());
        RefreshTokenService expiring = new RefreshTokenService(TestDatabase.db(), Duration.ofSeconds(-1), Clock.systemUTC());

        RefreshTokenService.Issued loggedOut = refresh.issue(userId, null);
        refresh.revoke(loggedOut.token());
        assertInvalid(() -> refresh.rotate(loggedOut.token(), null));

        assertInvalid(() -> refresh.rotate(expiring.issue(userId, null).token(), null));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AuthException.class,
            e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_TOKEN));
    }
}
