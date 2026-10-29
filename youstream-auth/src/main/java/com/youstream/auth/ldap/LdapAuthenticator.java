package com.youstream.auth.ldap;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.auth.ExternalIdentity;
import com.youstream.common.model.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Verifies a username/password against LDAP (OpenLDAP, lldap, Active Directory, the dev directory). */
public class LdapAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticator.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final int MAX_USERNAME = 128;

    private final LdapConfig config;
    private final LDAPURL url;
    private final SocketFactory socketFactory;

    public LdapAuthenticator(LdapConfig config) {
        this.config = config;
        try {
            this.url = new LDAPURL(config.getUrl());
        } catch (LDAPException e) {
            throw new IllegalArgumentException("invalid ldap.url: " + config.getUrl(), e);
        }
        boolean ssl = "ldaps".equalsIgnoreCase(url.getScheme());
        if (!ssl && !config.isAllowInsecure() && !LOCAL_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("ldap.url must use ldaps:// for non-local directories: " + config.getUrl());
        }
        if (config.getMode() == LdapConfig.Mode.UPN && isBlank(config.getUpnDomain())) {
            throw new IllegalArgumentException("ldap.upnDomain is required in upn mode");
        }
        try {
            this.socketFactory = ssl ? new SSLUtil(JVMDefaultTrustManager.getInstance()).createSSLSocketFactory() : null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot create LDAPS socket factory", e);
        }
    }

    /**
     * Authenticates the user and reads their directory attributes.
     *
     * @throws AuthException INVALID_CREDENTIALS, FORBIDDEN (not in the required group) or UNAVAILABLE
     */
    public ExternalIdentity authenticate(String username, String password) {
        // an empty password would be an anonymous ("unauthenticated") bind that many servers accept
        if (isBlank(username) || password == null || password.isEmpty() || username.length() > MAX_USERNAME) {
            throw invalid();
        }
        String login = username.trim().toLowerCase(Locale.ROOT);
        try (LDAPConnection conn = connect()) {
            SearchResultEntry entry = config.getMode() == LdapConfig.Mode.UPN
                ? bindUpn(conn, login, password)
                : searchAndBind(conn, login, password);
            return toIdentity(entry, login);
        } catch (LDAPException e) {
            log.warn("LDAP error authenticating '{}': {}", login, e.getResultCode());
            throw new AuthException(Reason.UNAVAILABLE, "directory unavailable", e);
        }
    }

    private SearchResultEntry searchAndBind(LDAPConnection conn, String login, String password) throws LDAPException {
        conn.bind(config.getBindDn(), config.getBindPassword());
        String filter = config.getUserFilter().replace("{0}", Filter.encodeValue(login));
        SearchResultEntry entry = findSingle(conn, filter);
        bindAsUser(conn, entry.getDN(), password);
        return entry;
    }

    private SearchResultEntry bindUpn(LDAPConnection conn, String login, String password) throws LDAPException {
        String upn = login.contains("@") ? login : login + "@" + config.getUpnDomain();
        bindAsUser(conn, upn, password);
        return findSingle(conn, "(userPrincipalName=" + Filter.encodeValue(upn) + ")");
    }

    private SearchResultEntry findSingle(LDAPConnection conn, String filter) throws LDAPException {
        SearchRequest request = new SearchRequest(config.getBaseDn(), SearchScope.SUB, Filter.create(filter),
            config.getIdAttribute(), config.getUsernameAttribute(), config.getDisplayNameAttribute(),
            config.getEmailAttribute(), config.getMemberOfAttribute());
        request.setSizeLimit(2);
        SearchResult result;
        try {
            result = conn.search(request);
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED) {
                log.warn("LDAP filter {} matched more than one entry", filter);
                throw invalid();
            }
            throw e;
        }
        if (result.getEntryCount() != 1) {
            throw invalid();
        }
        return result.getSearchEntries().getFirst();
    }

    private void bindAsUser(LDAPConnection conn, String bindName, String password) throws LDAPException {
        try {
            conn.bind(bindName, password);
        } catch (LDAPException e) {
            if (e.getResultCode() == ResultCode.INVALID_CREDENTIALS) {
                throw invalid();
            }
            throw e;
        }
    }

    private ExternalIdentity toIdentity(SearchResultEntry entry, String login) throws LDAPException {
        String[] groups = entry.getAttributeValues(config.getMemberOfAttribute());
        if (!isBlank(config.getRequiredGroup()) && !isMember(groups, config.getRequiredGroup())) {
            throw new AuthException(Reason.FORBIDDEN, "not a member of the required group");
        }
        String subject = subject(entry);
        if (subject == null) {
            throw new AuthException(Reason.UNAVAILABLE, "directory entry has no " + config.getIdAttribute());
        }
        String username = entry.getAttributeValue(config.getUsernameAttribute());
        String displayName = entry.getAttributeValue(config.getDisplayNameAttribute());
        return ExternalIdentity.builder()
            .provider(IdentityProvider.LDAP)
            .subject(subject)
            .username(username != null ? username.toLowerCase(Locale.ROOT) : login)
            .displayName(displayName != null ? displayName : login)
            .email(entry.getAttributeValue(config.getEmailAttribute()))
            .admin(!isBlank(config.getAdminGroup()) && isMember(groups, config.getAdminGroup()))
            .build();
    }

    /** objectGUID is binary in AD; everything else (entryUUID, uid, ...) is text. */
    private String subject(SearchResultEntry entry) {
        if ("objectGUID".equalsIgnoreCase(config.getIdAttribute())) {
            byte[] guid = entry.getAttributeValueBytes(config.getIdAttribute());
            return guid == null || guid.length != 16 ? null : guidToUuid(guid).toString();
        }
        return entry.getAttributeValue(config.getIdAttribute());
    }

    /** AD stores the first three GUID fields little-endian. */
    static UUID guidToUuid(byte[] guid) {
        ByteBuffer le = ByteBuffer.wrap(guid).order(ByteOrder.LITTLE_ENDIAN);
        long high = ((long) le.getInt(0) << 32) | ((le.getShort(4) & 0xFFFFL) << 16) | (le.getShort(6) & 0xFFFFL);
        long low = ByteBuffer.wrap(Arrays.copyOfRange(guid, 8, 16)).getLong();
        return new UUID(high, low);
    }

    private static boolean isMember(String[] groups, String group) throws LDAPException {
        if (groups == null) {
            return false;
        }
        DN wanted = new DN(group);
        for (String g : groups) {
            if (DN.isValidDN(g) && new DN(g).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private LDAPConnection connect() throws LDAPException {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(config.getTimeoutMillis());
        options.setResponseTimeoutMillis(config.getTimeoutMillis());
        if (socketFactory != null) {
            options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
        }
        return new LDAPConnection(socketFactory, options, url.getHost(), url.getPort());
    }

    private static AuthException invalid() {
        return new AuthException(Reason.INVALID_CREDENTIALS, "invalid username or password");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
