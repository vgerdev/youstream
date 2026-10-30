package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/** Failed password logins per throttle key (see {@code LoginThrottle}). */
@RequiredArgsConstructor
public class LoginFailureRepository {

    private final Db db;

    public void add(String key, Instant at) {
        db.run(s -> s
            .sql("INSERT INTO login_failures (throttle_key, failed_at) VALUES (?, ?)")
            .set(key)
            .set(Rows.ts(at))
            .update(Outcome.VOID));
    }

    public int count(String key, Instant since) {
        return db.run(s -> s
            .sql("SELECT count(*) FROM login_failures WHERE throttle_key = ? AND failed_at >= ?")
            .set(key)
            .set(Rows.ts(since))
            .select(new ListOutcome<>(rs -> rs.getLong(1))).getFirst()).intValue();
    }

    public void clear(String key) {
        db.run(s -> s
            .sql("DELETE FROM login_failures WHERE throttle_key = ?")
            .set(key)
            .update(Outcome.VOID));
    }

    public int deleteBefore(Instant before) {
        return db.run(s -> s
            .sql("DELETE FROM login_failures WHERE failed_at < ?")
            .set(Rows.ts(before))
            .update(Outcome.UPDATE_COUNT));
    }
}
