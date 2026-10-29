package com.youstream.auth.ldap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Settings for username/password login against an LDAP directory. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LdapConfig {

    public enum Mode {
        /** Bind as the service account, find the user with {@link #userFilter}, then bind as that DN. */
        SEARCH,
        /** Active Directory: bind directly as {@code username@upnDomain}. */
        UPN
    }

    /** {@code ldap://host:port} (localhost only unless {@link #allowInsecure}) or {@code ldaps://host:port}. */
    private String url;
    @Builder.Default
    private Mode mode = Mode.SEARCH;
    private String baseDn;
    private String bindDn;
    private String bindPassword;
    /** Search filter; {@code {0}} is replaced with the escaped username. */
    @Builder.Default
    private String userFilter = "(&(objectClass=inetOrgPerson)(uid={0}))";
    private String upnDomain;

    @Builder.Default
    private String idAttribute = "entryUUID";
    @Builder.Default
    private String usernameAttribute = "uid";
    @Builder.Default
    private String displayNameAttribute = "cn";
    @Builder.Default
    private String emailAttribute = "mail";
    @Builder.Default
    private String memberOfAttribute = "memberOf";

    /** Group DN users must belong to; blank = anyone in the directory. */
    private String requiredGroup;
    /** Group DN whose members are YouStream admins; blank = none. */
    private String adminGroup;

    /** Permit plain ldap:// to a non-local host. Never enable in production. */
    private boolean allowInsecure;
    @Builder.Default
    private int timeoutMillis = 10_000;
}
