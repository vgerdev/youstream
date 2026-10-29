package com.youstream.api;

import com.youstream.auth.token.AccessClaims;
import io.jooby.Context;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-caller request limits (signed-in user, else client IP) for write-heavy and expensive endpoints, as a sliding
 * window in memory. Exceeding a limit answers 429 with {@code Retry-After}. Login has its own throttle.
 */
public class RateLimits implements Route.Before {

    public record Rule(String name, Set<String> methods, Pattern path, int limit, Duration window) {
        public static Rule of(String name, String methods, String pathRegex, int limit, Duration window) {
            return new Rule(name, Set.of(methods.split(",")), Pattern.compile(pathRegex), limit, window);
        }
    }

    /** Defaults: generous for people, tight enough to stop scripts. */
    public static final List<Rule> DEFAULT_RULES = List.of(
        Rule.of("comment", "POST", "/api/videos/[^/]+/comments", 10, Duration.ofMinutes(1)),
        Rule.of("reaction", "PUT", "/api/videos/[^/]+/reaction", 60, Duration.ofMinutes(1)),
        Rule.of("view", "POST", "/api/videos/[^/]+/views", 120, Duration.ofMinutes(1)),
        Rule.of("upload", "POST", "/api/uploads", 30, Duration.ofHours(1)),
        Rule.of("ssh-key", "POST", "/api/me/ssh-keys", 20, Duration.ofHours(1)),
        Rule.of("search", "GET", "/api/search", 60, Duration.ofMinutes(1)),
        Rule.of("write", "POST,PUT,PATCH,DELETE", "/api/(?!auth/|uploads/[^/]+$).*", 300, Duration.ofMinutes(1)));

    private final List<Rule> rules;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimits(List<Rule> rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    @Override
    public void apply(Context ctx) {
        String method = ctx.getMethod();
        String path = ctx.getRequestPath();
        for (Rule rule : rules) {
            if (rule.methods().contains(method) && rule.path().matcher(path).matches()) {
                check(rule, caller(ctx));
            }
        }
    }

    private void check(Rule rule, String caller) {
        long now = clock.millis();
        long windowMs = rule.window().toMillis();
        Deque<Long> q = hits.computeIfAbsent(rule.name() + "|" + caller, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - windowMs) {
                q.pollFirst();
            }
            if (q.size() >= rule.limit()) {
                long retryAfter = Math.max(1, (q.peekFirst() + windowMs - now + 999) / 1000);
                throw new RateLimited(rule.name(), retryAfter);
            }
            q.addLast(now);
        }
        if (hits.size() > 100_000) {
            // bounded memory: forget idle callers
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast() <= now - Duration.ofHours(1).toMillis();
                }
            });
        }
    }

    /** Forgets all counts (tests). */
    public void reset() {
        hits.clear();
    }

    private static String caller(Context ctx) {
        AccessClaims user = ctx.getUser();
        return user != null ? "u" + user.getUserId() : "ip" + ctx.getRemoteAddress();
    }

    /** 429; the error handler adds {@code Retry-After}. */
    public static class RateLimited extends StatusCodeException {
        private final long retryAfterSeconds;

        RateLimited(String rule, long retryAfterSeconds) {
            super(StatusCode.TOO_MANY_REQUESTS, "too many requests (" + rule + "); try again in " + retryAfterSeconds + " s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
