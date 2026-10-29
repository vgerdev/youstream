package com.youstream.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for password logins (web and SFTP): limits failures per (IP, username) and
 * per IP within a sliding window. Per-JVM only, which is enough for a single server.
 */
public class LoginThrottle {

    private final int maxPerUser;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle() {
        this(5, 30, Duration.ofMinutes(15), Clock.systemUTC());
    }

    public LoginThrottle(int maxPerUser, int maxPerIp, Duration window, Clock clock) {
        this.maxPerUser = maxPerUser;
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
    }

    /** True when the caller must wait before trying again. */
    public boolean isBlocked(String ip, String username) {
        return count(userKey(ip, username)) >= maxPerUser || count(ipKey(ip)) >= maxPerIp;
    }

    public void failure(String ip, String username) {
        add(userKey(ip, username));
        add(ipKey(ip));
    }

    public void success(String ip, String username) {
        failures.remove(userKey(ip, username));
    }

    private int count(String key) {
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            prune(q);
            return q.size();
        }
    }

    private void add(String key) {
        Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = clock.instant().minus(window);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
    }

    private static String userKey(String ip, String username) {
        return "u|" + ip + "|" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "i|" + ip;
    }
}
