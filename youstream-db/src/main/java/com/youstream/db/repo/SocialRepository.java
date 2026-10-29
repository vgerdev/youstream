package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.User;
import com.youstream.db.Db;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * Likes/dislikes, channel subscriptions and view counting. Each change updates the denormalized counter
 * ({@code like_count}, {@code dislike_count}, {@code subscriber_count}, {@code view_count}) in the same transaction.
 */
@RequiredArgsConstructor
public class SocialRepository {

    public record Counts(long likes, long dislikes) {
    }

    private final Db db;

    // ------------------------------------------------------------------ reactions

    /** The user's reaction: +1, -1, or empty. */
    public Optional<Integer> reaction(long videoId, long userId) {
        return db.run(s -> s
            .sql("SELECT value FROM video_reactions WHERE video_id = ? AND user_id = ?")
            .set(videoId).set(userId)
            .select(new ListOutcome<>(rs -> rs.getInt(1)))
            .stream().findFirst());
    }

    /** Sets the reaction to +1 (like), -1 (dislike) or 0 (none). */
    public Counts react(long videoId, long userId, int value) {
        if (value < -1 || value > 1) {
            throw new IllegalArgumentException("reaction must be -1, 0 or 1");
        }
        return db.tx(s -> {
            // lock the video row so concurrent reactions serialize on the counters
            s.sql("SELECT id FROM videos WHERE id = ? FOR UPDATE").set(videoId).select(Outcome.NOT_EMPTY);
            int previous = reaction(videoId, userId).orElse(0);
            if (previous != value) {
                if (value == 0) {
                    s.sql("DELETE FROM video_reactions WHERE video_id = ? AND user_id = ?").set(videoId).set(userId)
                        .update(Outcome.VOID);
                } else {
                    s.sql("INSERT INTO video_reactions (video_id, user_id, value) VALUES (?, ?, ?)"
                            + " ON CONFLICT (video_id, user_id) DO UPDATE SET value = EXCLUDED.value, created_at = now()")
                        .set(videoId).set(userId).set(value).update(Outcome.VOID);
                }
                int likes = (value == 1 ? 1 : 0) - (previous == 1 ? 1 : 0);
                int dislikes = (value == -1 ? 1 : 0) - (previous == -1 ? 1 : 0);
                s.sql("UPDATE videos SET like_count = like_count + ?, dislike_count = dislike_count + ? WHERE id = ?")
                    .set(likes).set(dislikes).set(videoId).update(Outcome.VOID);
            }
            return s.sql("SELECT like_count, dislike_count FROM videos WHERE id = ?").set(videoId)
                .select(new ListOutcome<>(rs -> new Counts(rs.getLong(1), rs.getLong(2)))).getFirst();
        });
    }

    // ------------------------------------------------------------------ subscriptions

    public boolean isSubscribed(long subscriberId, long channelUserId) {
        return db.run(s -> s
            .sql("SELECT 1 FROM subscriptions WHERE subscriber_id = ? AND channel_user_id = ?")
            .set(subscriberId).set(channelUserId)
            .select(Outcome.NOT_EMPTY));
    }

    /** @return the channel's subscriber count afterwards */
    public long subscribe(long subscriberId, long channelUserId) {
        if (subscriberId == channelUserId) {
            throw new IllegalArgumentException("cannot subscribe to your own channel");
        }
        return db.tx(s -> {
            boolean added = !s.sql("INSERT INTO subscriptions (subscriber_id, channel_user_id) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING RETURNING 1")
                .set(subscriberId).set(channelUserId)
                .select(new ListOutcome<>(rs -> rs.getInt(1))).isEmpty();
            if (added) {
                s.sql("UPDATE users SET subscriber_count = subscriber_count + 1 WHERE id = ?").set(channelUserId)
                    .update(Outcome.VOID);
            }
            return subscriberCount(channelUserId);
        });
    }

    /** @return the channel's subscriber count afterwards */
    public long unsubscribe(long subscriberId, long channelUserId) {
        return db.tx(s -> {
            int removed = s.sql("DELETE FROM subscriptions WHERE subscriber_id = ? AND channel_user_id = ?")
                .set(subscriberId).set(channelUserId).update(Outcome.UPDATE_COUNT);
            if (removed > 0) {
                s.sql("UPDATE users SET subscriber_count = GREATEST(subscriber_count - 1, 0) WHERE id = ?")
                    .set(channelUserId).update(Outcome.VOID);
            }
            return subscriberCount(channelUserId);
        });
    }

    /** Channels the user is subscribed to, alphabetically. */
    public List<User> subscriptions(long subscriberId) {
        return db.run(s -> s
            .sql("SELECT u.id, u.username, u.display_name, u.email, u.handle, u.avatar_path, u.banner_path, u.bio,"
                + " u.is_admin, u.subscriber_count, u.created_at, u.last_login_at"
                + " FROM subscriptions sub JOIN users u ON u.id = sub.channel_user_id"
                + " WHERE sub.subscriber_id = ? ORDER BY lower(u.display_name), u.id")
            .set(subscriberId)
            .select(new ListOutcome<>(UserRepository::map)));
    }

    private long subscriberCount(long userId) {
        return db.run(s -> s.sql("SELECT subscriber_count FROM users WHERE id = ?").set(userId)
            .select(new ListOutcome<>(rs -> rs.getLong(1))).getFirst());
    }

    // ------------------------------------------------------------------ views

    /** View rows only deduplicate within a day; older ones are no longer needed. */
    public int deleteViewsBefore(java.time.LocalDate day) {
        return db.run(s -> s
            .sql("DELETE FROM video_views WHERE viewed_on < ?")
            .set(java.sql.Date.valueOf(day))
            .update(Outcome.UPDATE_COUNT));
    }

    /**
     * Counts a view at most once per viewer per video per day.
     *
     * @param viewerKey a user id or a hashed anonymous fingerprint
     * @return the video's view count afterwards
     */
    public long recordView(long videoId, String viewerKey) {
        return db.tx(s -> {
            boolean counted = !s.sql("INSERT INTO video_views (video_id, viewer_key) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING RETURNING 1")
                .set(videoId).set(viewerKey)
                .select(new ListOutcome<>(rs -> rs.getInt(1))).isEmpty();
            if (counted) {
                s.sql("UPDATE videos SET view_count = view_count + 1 WHERE id = ?").set(videoId).update(Outcome.VOID);
            }
            return s.sql("SELECT view_count FROM videos WHERE id = ?").set(videoId)
                .select(new ListOutcome<>(rs -> rs.getLong(1))).getFirst();
        });
    }
}
