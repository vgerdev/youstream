package com.youstream.auth;

import com.youstream.db.Db;
import com.youstream.db.repo.LoginFailureRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for password logins (web and SFTP): limits failures per (IP, username) and per IP within a
 * sliding window. {@link #shared} keeps the failures in PostgreSQL so every server process sees the same counts;
 * the no-argument constructor keeps them in memory (tests, single process).
 */
public class LoginThrottle {

    /** Where failures are counted. */
    public interface Store {
        int count(String key, Instant since);

        void add(String key, Instant at);

        void clear(String key);
    }

    private static final int MAX_PER_USER = 5;
    private static final int MAX_PER_IP = 30;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final int maxPerUser;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final Store store;

    public LoginThrottle() {
        this(MAX_PER_USER, MAX_PER_IP, WINDOW, Clock.systemUTC());
    }

    public LoginThrottle(int maxPerUser, int maxPerIp, Duration window, Clock clock) {
        this(maxPerUser, maxPerIp, window, clock, new MemoryStore());
    }

    public LoginThrottle(int maxPerUser, int maxPerIp, Duration window, Clock clock, Store store) {
        this.maxPerUser = maxPerUser;
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
        this.store = store;
    }

    /** Throttle whose failures live in the database, shared by all processes. */
    public static LoginThrottle shared(Db db, Clock clock) {
        LoginFailureRepository repo = new LoginFailureRepository(db);
        return new LoginThrottle(MAX_PER_USER, MAX_PER_IP, WINDOW, clock, new Store() {
            @Override
            public int count(String key, Instant since) {
                return repo.count(key, since);
            }

            @Override
            public void add(String key, Instant at) {
                repo.add(key, at);
            }

            @Override
            public void clear(String key) {
                repo.clear(key);
            }
        });
    }

    /** True when the caller must wait before trying again. */
    public boolean isBlocked(String ip, String username) {
        Instant since = clock.instant().minus(window);
        return store.count(userKey(ip, username), since) >= maxPerUser || store.count(ipKey(ip), since) >= maxPerIp;
    }

    public void failure(String ip, String username) {
        Instant now = clock.instant();
        store.add(userKey(ip, username), now);
        store.add(ipKey(ip), now);
    }

    public void success(String ip, String username) {
        store.clear(userKey(ip, username));
    }

    private static String userKey(String ip, String username) {
        return "u|" + ip + "|" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "i|" + ip;
    }

    /** Per-JVM store; entries older than the asked-for window are dropped as they are read. */
    static class MemoryStore implements Store {
        private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

        @Override
        public int count(String key, Instant since) {
            Deque<Instant> q = failures.get(key);
            if (q == null) {
                return 0;
            }
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst().isBefore(since)) {
                    q.pollFirst();
                }
                return q.size();
            }
        }

        @Override
        public void add(String key, Instant at) {
            Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                q.addLast(at);
            }
        }

        @Override
        public void clear(String key) {
            failures.remove(key);
        }
    }
}
