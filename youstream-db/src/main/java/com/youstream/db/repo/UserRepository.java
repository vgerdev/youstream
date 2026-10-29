package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.IdentityProvider;
import com.youstream.common.model.User;
import com.youstream.common.model.UserIdentity;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class UserRepository {

    private static final String COLUMNS = "u.id, u.username, u.display_name, u.email, u.handle, u.avatar_path, u.banner_path,"
        + " u.bio, u.is_admin, u.subscriber_count, u.created_at, u.last_login_at";

    private final Db db;

    public User insert(User user) {
        return db.run(s -> s
            .sql("INSERT INTO users AS u (username, display_name, email, handle, bio, is_admin)"
                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)
            .set(user.getUsername())
            .set(user.getDisplayName())
            .set(user.getEmail())
            .set(user.getHandle())
            .set(user.getBio())
            .set(user.isAdmin())
            .select(new ListOutcome<>(UserRepository::map))
            .getFirst());
    }

    public Optional<User> findById(long id) {
        return findOne("u.id = ?", id);
    }

    public Optional<User> findByUsername(String username) {
        return findOne("u.username = ?", username);
    }

    public Optional<User> findByHandle(String handle) {
        return findOne("u.handle = ?", handle);
    }

    public Optional<User> findByIdentity(IdentityProvider provider, String subject) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM users u JOIN user_identities i ON i.user_id = u.id"
                + " WHERE i.provider = ? AND i.subject = ?")
            .set(provider.name())
            .set(subject)
            .select(new ListOutcome<>(UserRepository::map))
            .stream().findFirst());
    }

    /** Channels whose handle or display name matches (prefix terms, 'simple' dictionary), most subscribed first. */
    public List<User> searchChannels(String tsQuery, int limit, int offset) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM users u WHERE u.search @@ to_tsquery('simple', ?)"
                + " ORDER BY u.subscriber_count DESC, ts_rank(u.search, to_tsquery('simple', ?)) DESC, u.id LIMIT ? OFFSET ?")
            .set(tsQuery).set(tsQuery).set(limit).set(offset)
            .select(new ListOutcome<>(UserRepository::map)));
    }

    public boolean usernameExists(String username) {
        return db.run(s -> s.sql("SELECT 1 FROM users WHERE username = ?").set(username).select(Outcome.NOT_EMPTY));
    }

    public boolean handleExists(String handle) {
        return db.run(s -> s.sql("SELECT 1 FROM users WHERE handle = ?").set(handle).select(Outcome.NOT_EMPTY));
    }

    public void addIdentity(long userId, IdentityProvider provider, String subject) {
        db.run(s -> s
            .sql("INSERT INTO user_identities (provider, subject, user_id) VALUES (?, ?, ?)")
            .set(provider.name())
            .set(subject)
            .set(userId)
            .update(Outcome.VOID));
    }

    public List<UserIdentity> listIdentities(long userId) {
        return db.run(s -> s
            .sql("SELECT provider, subject, user_id, created_at, last_login_at FROM user_identities"
                + " WHERE user_id = ? ORDER BY created_at")
            .set(userId)
            .select(new ListOutcome<>(rs -> UserIdentity.builder()
                .provider(Rows.enumValue(rs, "provider", IdentityProvider.class))
                .subject(rs.getString("subject"))
                .userId(rs.getLong("user_id"))
                .createdAt(Rows.instant(rs, "created_at"))
                .lastLoginAt(Rows.instant(rs, "last_login_at"))
                .build())));
    }

    /** Records a successful sign-in through the given identity. */
    public void recordLogin(long userId, IdentityProvider provider, String subject, Instant at) {
        db.txVoid(s -> {
            s.sql("UPDATE users SET last_login_at = ? WHERE id = ?")
                .set(Rows.ts(at)).set(userId).update(Outcome.VOID);
            s.sql("UPDATE user_identities SET last_login_at = ? WHERE provider = ? AND subject = ?")
                .set(Rows.ts(at)).set(provider.name()).set(subject).update(Outcome.VOID);
        });
    }

    /** Refreshes the fields the identity provider is authoritative for. */
    public void updateFromIdentity(long userId, String displayName, String email, boolean admin) {
        db.run(s -> s
            .sql("UPDATE users SET display_name = ?, email = ?, is_admin = ? WHERE id = ?")
            .set(displayName)
            .set(email)
            .set(admin)
            .set(userId)
            .update(Outcome.VOID));
    }

    public void updateProfile(long userId, String handle, String displayName, String bio) {
        db.run(s -> s
            .sql("UPDATE users SET handle = ?, display_name = ?, bio = ? WHERE id = ?")
            .set(handle)
            .set(displayName)
            .set(bio)
            .set(userId)
            .update(Outcome.VOID));
    }

    public void updateImages(long userId, String avatarPath, String bannerPath) {
        db.run(s -> s
            .sql("UPDATE users SET avatar_path = ?, banner_path = ? WHERE id = ?")
            .set(avatarPath)
            .set(bannerPath)
            .set(userId)
            .update(Outcome.VOID));
    }

    public boolean delete(long userId) {
        return db.run(s -> s.sql("DELETE FROM users WHERE id = ?").set(userId).update(Outcome.UPDATE_COUNT)) > 0;
    }

    private Optional<User> findOne(String where, Object value) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM users u WHERE " + where)
            .set(value)
            .select(new ListOutcome<>(UserRepository::map))
            .stream().findFirst());
    }

    static User map(ResultSet rs) throws SQLException {
        return User.builder()
            .id(rs.getLong("id"))
            .username(rs.getString("username"))
            .displayName(rs.getString("display_name"))
            .email(rs.getString("email"))
            .handle(rs.getString("handle"))
            .avatarPath(rs.getString("avatar_path"))
            .bannerPath(rs.getString("banner_path"))
            .bio(rs.getString("bio"))
            .admin(rs.getBoolean("is_admin"))
            .subscriberCount(rs.getLong("subscriber_count"))
            .createdAt(Rows.instant(rs, "created_at"))
            .lastLoginAt(Rows.instant(rs, "last_login_at"))
            .build();
    }
}
