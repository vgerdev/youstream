package com.youstream.auth;

import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void setUp() {
        TestDatabase.truncate();
    }

    @Test
    void sharedThrottleBlocksAcrossProcessesAndExpires() {
        // two instances stand in for two server processes using the same database
        LoginThrottle web = LoginThrottle.shared(TestDatabase.db(), clock);
        LoginThrottle sftp = LoginThrottle.shared(TestDatabase.db(), clock);

        for (int i = 0; i < 3; i++) {
            web.failure("10.0.0.1", "alice");
        }
        for (int i = 0; i < 2; i++) {
            sftp.failure("10.0.0.1", "Alice");
        }
        assertThat(web.isBlocked("10.0.0.1", "alice")).isTrue();
        assertThat(sftp.isBlocked("10.0.0.1", "alice")).isTrue();
        assertThat(web.isBlocked("10.0.0.2", "alice")).isFalse();

        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThat(sftp.isBlocked("10.0.0.1", "alice")).isFalse();
    }

    @Test
    void successClearsTheUsersFailures() {
        LoginThrottle throttle = LoginThrottle.shared(TestDatabase.db(), clock);
        for (int i = 0; i < 4; i++) {
            throttle.failure("10.0.0.1", "bob");
        }
        throttle.success("10.0.0.1", "bob");
        throttle.failure("10.0.0.1", "bob");
        assertThat(throttle.isBlocked("10.0.0.1", "bob")).isFalse();
    }

    @Test
    void memoryThrottleLimitsFailuresPerIp() {
        LoginThrottle throttle = new LoginThrottle(5, 3, Duration.ofMinutes(15), clock);
        throttle.failure("10.0.0.9", "a");
        throttle.failure("10.0.0.9", "b");
        assertThat(throttle.isBlocked("10.0.0.9", "c")).isFalse();
        throttle.failure("10.0.0.9", "c");
        assertThat(throttle.isBlocked("10.0.0.9", "d")).isTrue();
    }
}
