package com.youstream.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Test
    void tokenIsBoundToVideoAndExpires() {
        PlaybackTokens tokens = new PlaybackTokens(SECRET, Duration.ofHours(6), Clock.systemUTC());
        String token = tokens.issue("AAAAAAAAAAA");

        assertThat(token).matches("[0-9a-z]+\\.[A-Za-z0-9_-]{22}");
        assertThat(tokens.verify("AAAAAAAAAAA", token)).isTrue();
        assertThat(tokens.verify("BBBBBBBBBBB", token)).isFalse();
        assertThat(tokens.verify("AAAAAAAAAAA", token.replace('.', '-'))).isFalse();
        assertThat(tokens.verify("AAAAAAAAAAA", PlaybackTokens.PUBLIC)).isFalse();
        assertThat(tokens.verify("AAAAAAAAAAA", null)).isFalse();

        Clock later = Clock.fixed(Instant.now().plus(Duration.ofHours(7)), ZoneOffset.UTC);
        assertThat(new PlaybackTokens(SECRET, Duration.ofHours(6), later).verify("AAAAAAAAAAA", token)).isFalse();
        assertThat(new PlaybackTokens("other-secret-other-secret-other-123", Duration.ofHours(6), Clock.systemUTC())
            .verify("AAAAAAAAAAA", token)).isFalse();
    }
}
