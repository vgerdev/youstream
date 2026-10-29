package com.youstream.auth;

import com.youstream.auth.AuthException.Reason;
import com.youstream.common.model.IdentityProvider;
import com.youstream.common.model.User;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceTest {

    private UserService service;

    @BeforeEach
    void setUp() {
        TestDatabase.truncate();
        service = new UserService(TestDatabase.db(), Clock.systemUTC());
    }

    private static ExternalIdentity identity(IdentityProvider provider, String subject, String username, boolean admin) {
        return ExternalIdentity.builder()
            .provider(provider).subject(subject).username(username)
            .displayName("Name of " + username).email(username + "@example.com").admin(admin)
            .build();
    }

    @Test
    void firstSignInCreatesUserAndLaterSignInsUpdateIt() {
        User first = service.signIn(identity(IdentityProvider.LDAP, "s-1", "alice", false));
        User again = service.signIn(identity(IdentityProvider.LDAP, "s-1", "alice", true));

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(first.getUsername()).isEqualTo("alice");
        assertThat(first.getHandle()).isEqualTo("alice");
        assertThat(first.isAdmin()).isFalse();
        assertThat(again.isAdmin()).isTrue();
        assertThat(again.getLastLoginAt()).isNotNull();
    }

    @Test
    void sameNameFromAnotherProviderGetsDistinctUser() {
        User ldap = service.signIn(identity(IdentityProvider.LDAP, "s-1", "alice", false));
        User entra = service.signIn(identity(IdentityProvider.ENTRA, "oid-1", "alice@contoso.com", false));

        assertThat(entra.getId()).isNotEqualTo(ldap.getId());
        assertThat(entra.getUsername()).isEqualTo("alice-2");
        assertThat(entra.getHandle()).isEqualTo("alice-2");
    }

    @Test
    void linkingLetsOneUserSignInBothWays() {
        User alice = service.signIn(identity(IdentityProvider.LDAP, "s-1", "alice", false));
        service.link(alice.getId(), identity(IdentityProvider.ENTRA, "oid-1", "alice@contoso.com", false));
        service.link(alice.getId(), identity(IdentityProvider.ENTRA, "oid-1", "alice@contoso.com", false));

        assertThat(service.signIn(identity(IdentityProvider.ENTRA, "oid-1", "alice@contoso.com", false)).getId())
            .isEqualTo(alice.getId());
    }

    @Test
    void cannotLinkIdentityOwnedByAnotherUser() {
        User alice = service.signIn(identity(IdentityProvider.LDAP, "s-1", "alice", false));
        service.signIn(identity(IdentityProvider.ENTRA, "oid-9", "bob@contoso.com", false));

        assertThatThrownBy(() -> service.link(alice.getId(), identity(IdentityProvider.ENTRA, "oid-9", "bob", false)))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.CONFLICT));
    }

    @Test
    void derivesValidNames() {
        assertThat(UserService.baseName(identity(IdentityProvider.ENTRA, "x", "José.Núñez@contoso.com", false)))
            .isEqualTo("jose.nunez");
        assertThat(UserService.baseName(identity(IdentityProvider.LDAP, "x", "--Weird  Name!!", false)))
            .isEqualTo("weird-name");
        assertThat(UserService.baseName(identity(IdentityProvider.LDAP, "x", "???", false))).isEqualTo("user");

        assertThat(UserService.unique("al", 3, 30, n -> false)).isEqualTo("al0");
        assertThat(UserService.unique("bob", 3, 30, Set.of("bob", "bob-2")::contains)).isEqualTo("bob-3");
        assertThat(UserService.unique("a".repeat(40), 3, 30, Set.of("a".repeat(30))::contains))
            .hasSize(30).endsWith("-2");
    }
}
