package com.youstream.db.repo;

import com.youstream.common.model.IdentityProvider;
import com.youstream.common.model.User;
import com.youstream.db.DataAccessException;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest {

    private Db db;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        users = new UserRepository(db);
    }

    @Test
    void insertsAndFindsUsers() {
        User alice = Fixtures.user(db, "alice");

        assertThat(alice.getId()).isPositive();
        assertThat(alice.getCreatedAt()).isNotNull();
        assertThat(alice.getBio()).isEmpty();
        assertThat(users.findById(alice.getId())).contains(alice);
        assertThat(users.findByUsername("alice")).contains(alice);
        assertThat(users.findByHandle("alice")).contains(alice);
        assertThat(users.findByUsername("nobody")).isEmpty();
        assertThat(users.usernameExists("alice")).isTrue();
        assertThat(users.handleExists("bob")).isFalse();
    }

    @Test
    void rejectsDuplicateUsername() {
        Fixtures.user(db, "alice");

        assertThatThrownBy(() -> users.insert(User.builder().username("alice").displayName("A").handle("alice2").build()))
            .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(e.isUniqueViolation()).isTrue());
    }

    @Test
    void rejectsInvalidHandle() {
        assertThatThrownBy(() -> users.insert(User.builder().username("carol").displayName("C").handle("../x").build()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void identitiesResolveToTheirUser() {
        User alice = Fixtures.user(db, "alice");
        users.addIdentity(alice.getId(), IdentityProvider.LDAP, "ldap-uuid-1");
        users.addIdentity(alice.getId(), IdentityProvider.ENTRA, "entra-oid-1");

        assertThat(users.findByIdentity(IdentityProvider.ENTRA, "entra-oid-1")).map(User::getId).contains(alice.getId());
        assertThat(users.findByIdentity(IdentityProvider.LDAP, "entra-oid-1")).isEmpty();
        assertThat(users.listIdentities(alice.getId())).extracting("provider")
            .containsExactly(IdentityProvider.LDAP, IdentityProvider.ENTRA);
    }

    @Test
    void recordsLoginAndSyncsProviderFields() {
        User alice = Fixtures.user(db, "alice");
        users.addIdentity(alice.getId(), IdentityProvider.LDAP, "ldap-uuid-1");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        users.recordLogin(alice.getId(), IdentityProvider.LDAP, "ldap-uuid-1", now);
        users.updateFromIdentity(alice.getId(), "Alice Liddell", "alice@wonder.land", true);

        User reloaded = users.findById(alice.getId()).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isEqualTo(now);
        assertThat(reloaded.getDisplayName()).isEqualTo("Alice Liddell");
        assertThat(reloaded.getEmail()).isEqualTo("alice@wonder.land");
        assertThat(reloaded.isAdmin()).isTrue();
        assertThat(users.listIdentities(alice.getId()).getFirst().getLastLoginAt()).isEqualTo(now);
    }

    @Test
    void deletingUserRemovesIdentities() {
        User alice = Fixtures.user(db, "alice");
        users.addIdentity(alice.getId(), IdentityProvider.LDAP, "ldap-uuid-1");

        assertThat(users.delete(alice.getId())).isTrue();
        assertThat(users.findByIdentity(IdentityProvider.LDAP, "ldap-uuid-1")).isEmpty();
    }
}
