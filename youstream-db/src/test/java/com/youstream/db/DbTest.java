package com.youstream.db;

import com.youstream.common.model.User;
import com.youstream.db.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTest {

    private Db db;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        users = new UserRepository(db);
    }

    @Test
    void transactionCommitsAllRepositoryCalls() {
        db.txVoid(s -> {
            users.insert(user("alice"));
            users.insert(user("bob"));
        });

        assertThat(users.usernameExists("alice")).isTrue();
        assertThat(users.usernameExists("bob")).isTrue();
    }

    @Test
    void transactionRollsBackOnFailure() {
        assertThatThrownBy(() -> db.txVoid(s -> {
            users.insert(user("alice"));
            users.insert(user("alice"));
        })).isInstanceOf(DataAccessException.class);

        assertThat(users.usernameExists("alice")).isFalse();
    }

    @Test
    void rollsBackOnRuntimeException() {
        assertThatThrownBy(() -> db.txVoid(s -> {
            users.insert(user("carol"));
            throw new IllegalStateException("abort");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(users.usernameExists("carol")).isFalse();
    }

    @Test
    void reportsHealthy() {
        assertThat(db.isHealthy()).isTrue();
    }

    private static User user(String name) {
        return User.builder().username(name).displayName(name).handle(name).build();
    }
}
