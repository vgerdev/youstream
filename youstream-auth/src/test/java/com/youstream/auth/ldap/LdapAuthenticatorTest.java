package com.youstream.auth.ldap;

import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.auth.ExternalIdentity;
import com.youstream.common.model.IdentityProvider;
import com.youstream.devldap.DevLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapAuthenticatorTest {

    private static DevLdapServer ldap;

    @BeforeAll
    static void start() throws Exception {
        ldap = DevLdapServer.start(0);
    }

    @AfterAll
    static void stop() {
        ldap.close();
    }

    private static LdapConfig.LdapConfigBuilder config() {
        return LdapConfig.builder()
            .url(ldap.url())
            .baseDn(DevLdapServer.BASE_DN)
            .bindDn(DevLdapServer.SERVICE_DN)
            .bindPassword(DevLdapServer.SERVICE_PASSWORD)
            .adminGroup(DevLdapServer.ADMINS_GROUP);
    }

    @Test
    void authenticatesAndMapsAttributes() {
        ExternalIdentity alice = new LdapAuthenticator(config().build()).authenticate("Alice", "alice-pass");

        assertThat(alice.getProvider()).isEqualTo(IdentityProvider.LDAP);
        assertThat(alice.getSubject()).matches("[0-9a-f-]{36}");
        assertThat(alice.getUsername()).isEqualTo("alice");
        assertThat(alice.getDisplayName()).isEqualTo("Alice Admin");
        assertThat(alice.getEmail()).isEqualTo("alice@youstream.local");
        assertThat(alice.isAdmin()).isTrue();
    }

    @Test
    void subjectIsStableAcrossLogins() {
        LdapAuthenticator auth = new LdapAuthenticator(config().build());

        assertThat(auth.authenticate("bob", "bob-pass").getSubject())
            .isEqualTo(auth.authenticate("bob", "bob-pass").getSubject());
        assertThat(auth.authenticate("bob", "bob-pass").isAdmin()).isFalse();
    }

    @Test
    void rejectsBadCredentials() {
        LdapAuthenticator auth = new LdapAuthenticator(config().build());

        assertInvalid(() -> auth.authenticate("alice", "wrong"));
        assertInvalid(() -> auth.authenticate("nobody", "alice-pass"));
        assertInvalid(() -> auth.authenticate("alice", ""));
        assertInvalid(() -> auth.authenticate("", "x"));
        assertInvalid(() -> auth.authenticate(null, null));
    }

    @Test
    void filterInjectionDoesNotMatchOtherUsers() {
        LdapAuthenticator auth = new LdapAuthenticator(config().build());

        assertInvalid(() -> auth.authenticate("*", "alice-pass"));
        assertInvalid(() -> auth.authenticate("alice)(uid=*", "alice-pass"));
    }

    @Test
    void requiredGroupIsEnforced() {
        LdapAuthenticator auth = new LdapAuthenticator(config().requiredGroup(DevLdapServer.USERS_GROUP).build());

        assertThat(auth.authenticate("bob", "bob-pass").getUsername()).isEqualTo("bob");
        assertThatThrownBy(() -> auth.authenticate("carol", "carol-pass"))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.FORBIDDEN));
    }

    @Test
    void unreachableDirectoryIsUnavailable() {
        LdapAuthenticator auth = new LdapAuthenticator(config().url("ldap://localhost:1").timeoutMillis(2000).build());

        assertThatThrownBy(() -> auth.authenticate("alice", "alice-pass"))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE));
    }

    @Test
    void refusesPlainLdapToRemoteHosts() {
        assertThatThrownBy(() -> new LdapAuthenticator(config().url("ldap://ldap.example.com:389").build()))
            .isInstanceOf(IllegalArgumentException.class);
        new LdapAuthenticator(config().url("ldaps://ldap.example.com:636").build());
    }

    @Test
    void convertsActiveDirectoryGuid() {
        // objectGUID bytes for {00112233-4455-6677-8899-aabbccddeeff}
        byte[] guid = {0x33, 0x22, 0x11, 0x00, 0x55, 0x44, 0x77, 0x66,
            (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff};

        assertThat(LdapAuthenticator.guidToUuid(guid)).isEqualTo(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AuthException.class,
            e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_CREDENTIALS));
    }
}
