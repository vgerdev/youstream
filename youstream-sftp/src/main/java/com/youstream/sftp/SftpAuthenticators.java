package com.youstream.sftp;

import com.youstream.auth.AuthException;
import com.youstream.auth.LoginThrottle;
import com.youstream.auth.SshKeyService;
import com.youstream.auth.UserService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.common.model.SshKey;
import com.youstream.common.model.User;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;

/**
 * SFTP sign-in: the directory (LDAP) password, or an SSH key registered on the Account page. Either way the
 * session is bound to a YouStream user id ({@link #USER_ID}), which decides the user's private upload folder.
 */
final class SftpAuthenticators {

    static final AttributeKey<Long> USER_ID = new AttributeKey<>();

    private static final Logger log = LoggerFactory.getLogger(SftpAuthenticators.class);

    private SftpAuthenticators() {
    }

    /** LDAP username/password; creates the YouStream user on first sign-in, like the web login. */
    static PasswordAuthenticator password(LdapAuthenticator ldap, UserService users, LoginThrottle throttle) {
        return (username, password, session) -> {
            String ip = ip(session.getClientAddress());
            if (throttle.isBlocked(ip, username)) {
                log.warn("SFTP password login for '{}' from {} throttled", username, ip);
                return false;
            }
            try {
                User user = users.signIn(ldap.authenticate(username, password));
                throttle.success(ip, username);
                session.setAttribute(USER_ID, user.getId());
                log.info("SFTP login '{}' (user {}) with password from {}", username, user.getId(), ip);
                return true;
            } catch (AuthException e) {
                if (e.getReason() == AuthException.Reason.INVALID_CREDENTIALS) {
                    throttle.failure(ip, username);
                }
                log.info("SFTP password login for '{}' from {} rejected: {}", username, ip, e.getReason());
                return false;
            }
        };
    }

    /** A registered key, and the login name must be that key owner's YouStream username. */
    static PublickeyAuthenticator publicKey(SshKeyService keys, UserService users) {
        return (username, key, session) -> {
            Optional<SshKey> registered = keys.findByKey(key);
            Optional<User> owner = registered.flatMap(k -> users.findById(k.getUserId()));
            if (owner.isEmpty() || !owner.get().getUsername().equalsIgnoreCase(username)) {
                return false;
            }
            session.setAttribute(USER_ID, owner.get().getId());
            keys.markUsed(registered.get().getId());
            log.debug("SFTP key {} accepted for '{}'", SshKeyService.fingerprint(key), username);
            return true;
        };
    }

    static long userId(ServerSession session) {
        Long id = session.getAttribute(USER_ID);
        if (id == null) {
            throw new IllegalStateException("SFTP session without an authenticated user");
        }
        return id;
    }

    private static String ip(SocketAddress address) {
        return address instanceof InetSocketAddress inet ? inet.getAddress().getHostAddress() : String.valueOf(address);
    }
}
