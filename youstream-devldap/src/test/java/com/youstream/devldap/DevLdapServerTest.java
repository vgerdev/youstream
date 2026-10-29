package com.youstream.devldap;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevLdapServerTest {

    private static DevLdapServer ldap;

    @BeforeAll
    static void start() throws Exception {
        ldap = DevLdapServer.start(0);
    }

    @AfterAll
    static void stop() {
        ldap.close();
    }

    @Test
    void usersCanBindWithTheirPassword() throws Exception {
        try (LDAPConnection conn = new LDAPConnection("localhost", ldap.port())) {
            assertThat(conn.bind("uid=alice,ou=people," + DevLdapServer.BASE_DN, "alice-pass").getResultCode())
                .isEqualTo(ResultCode.SUCCESS);
        }
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        try (LDAPConnection conn = new LDAPConnection("localhost", ldap.port())) {
            assertThatThrownBy(() -> conn.bind("uid=bob,ou=people," + DevLdapServer.BASE_DN, "nope"))
                .isInstanceOfSatisfying(LDAPException.class,
                    e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.INVALID_CREDENTIALS));
        }
    }

    @Test
    void entriesHaveEntryUuidAndMemberOf() throws Exception {
        try (LDAPConnection conn = new LDAPConnection("localhost", ldap.port())) {
            conn.bind(DevLdapServer.SERVICE_DN, DevLdapServer.SERVICE_PASSWORD);
            SearchResultEntry alice = conn.getEntry("uid=alice,ou=people," + DevLdapServer.BASE_DN, "entryUUID", "memberOf");
            assertThat(alice.getAttributeValue("entryUUID")).matches("[0-9a-f-]{36}");
            assertThat(alice.getAttributeValues("memberOf")).contains(DevLdapServer.ADMINS_GROUP);
        }
    }
}
