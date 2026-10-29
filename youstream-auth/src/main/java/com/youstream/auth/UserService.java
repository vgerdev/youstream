package com.youstream.auth;

import com.youstream.auth.AuthException.Reason;
import com.youstream.common.model.User;
import com.youstream.db.Db;
import com.youstream.db.repo.UserRepository;

import java.text.Normalizer;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/** Turns an authenticated {@link ExternalIdentity} into a YouStream user, creating it on first sign-in. */
public class UserService {

    private final Db db;
    private final UserRepository users;
    private final Clock clock;

    public UserService(Db db, Clock clock) {
        this.db = db;
        this.users = new UserRepository(db);
        this.clock = clock;
    }

    public User signIn(ExternalIdentity identity) {
        return db.tx(s -> {
            Optional<User> existing = users.findByIdentity(identity.getProvider(), identity.getSubject());
            long userId;
            if (existing.isPresent()) {
                userId = existing.get().getId();
                // the display name is the user's own (editable on the Account page); email and admin follow the provider
                users.updateFromIdentity(userId, existing.get().getDisplayName(), identity.getEmail(), identity.isAdmin());
            } else {
                String base = baseName(identity);
                userId = users.insert(User.builder()
                    .username(unique(base, 2, 64, users::usernameExists))
                    .handle(unique(base, 3, 30, users::handleExists))
                    .displayName(displayName(identity))
                    .email(identity.getEmail())
                    .admin(identity.isAdmin())
                    .build()).getId();
                users.addIdentity(userId, identity.getProvider(), identity.getSubject());
            }
            users.recordLogin(userId, identity.getProvider(), identity.getSubject(), clock.instant());
            return users.findById(userId).orElseThrow();
        });
    }

    /** Adds another login method to a signed-in user. */
    public void link(long userId, ExternalIdentity identity) {
        db.txVoid(s -> {
            Optional<User> owner = users.findByIdentity(identity.getProvider(), identity.getSubject());
            if (owner.isPresent()) {
                if (owner.get().getId() != userId) {
                    throw new AuthException(Reason.CONFLICT, "this account is already linked to another YouStream user");
                }
                return;
            }
            users.addIdentity(userId, identity.getProvider(), identity.getSubject());
        });
    }

    public Optional<User> findById(long userId) {
        return users.findById(userId);
    }

    public UserRepository repository() {
        return users;
    }

    private static String displayName(ExternalIdentity identity) {
        String name = identity.getDisplayName();
        return name == null || name.isBlank() ? identity.getUsername() : name.strip();
    }

    /** Local part of the login name (or e-mail), reduced to [a-z0-9._-]. */
    static String baseName(ExternalIdentity identity) {
        String raw = identity.getUsername() != null ? identity.getUsername() : identity.getEmail();
        if (raw == null) {
            raw = "user";
        }
        int at = raw.indexOf('@');
        if (at > 0) {
            raw = raw.substring(0, at);
        }
        String ascii = Normalizer.normalize(raw, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[^a-z0-9]+", "")
            .replaceAll("[-.]{2,}", "-")
            .replaceAll("[^a-z0-9]+$", "");
        return slug.isEmpty() ? "user" : slug;
    }

    /** base, base-2, base-3, ... cut to fit {@code [min, max]} characters, first one not taken. */
    static String unique(String base, int min, int max, Predicate<String> taken) {
        String name = pad(base, min, max);
        for (int n = 2; taken.test(name); n++) {
            String suffix = "-" + n;
            name = pad(base, min, max - suffix.length()) + suffix;
        }
        return name;
    }

    private static String pad(String base, int min, int max) {
        String s = base.length() > max ? base.substring(0, max).replaceAll("[^a-z0-9]+$", "") : base;
        while (s.length() < min) {
            s = s + "0";
        }
        return s;
    }
}
