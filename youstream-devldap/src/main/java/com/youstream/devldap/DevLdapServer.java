package com.youstream.devldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldif.LDIFReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Embedded LDAP directory for development and tests, seeded from {@code dev-ldap.ldif}.
 *
 * <p>Run with {@code gradle :youstream-devldap:run} or {@code --args="--port 10389 --ldif my.ldif"}.
 */
public final class DevLdapServer implements AutoCloseable {

    public static final String BASE_DN = "dc=youstream,dc=local";
    public static final String SERVICE_DN = "cn=service,ou=system," + BASE_DN;
    public static final String SERVICE_PASSWORD = "service-secret";
    public static final String USERS_GROUP = "cn=youstream-users,ou=groups," + BASE_DN;
    public static final String ADMINS_GROUP = "cn=youstream-admins,ou=groups," + BASE_DN;
    public static final int DEFAULT_PORT = 10389;

    private static final Logger log = LoggerFactory.getLogger(DevLdapServer.class);

    private final InMemoryDirectoryServer server;

    private DevLdapServer(InMemoryDirectoryServer server) {
        this.server = server;
    }

    /** Starts on {@code port} (0 = any free port) with the bundled seed data. */
    public static DevLdapServer start(int port) throws LDAPException, IOException {
        try (InputStream ldif = DevLdapServer.class.getResourceAsStream("/dev-ldap.ldif")) {
            return start(port, ldif);
        }
    }

    public static DevLdapServer start(int port, InputStream ldif) throws LDAPException, IOException {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", port));
        // memberOf is not in the standard schema; this is a test directory, so skip schema checking
        config.setSchema(null);
        config.setEnforceAttributeSyntaxCompliance(false);
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        try (LDIFReader reader = new LDIFReader(ldif)) {
            server.importFromLDIF(true, reader);
        }
        server.startListening();
        return new DevLdapServer(server);
    }

    public int port() {
        return server.getListenPort();
    }

    public String url() {
        return "ldap://localhost:" + port();
    }

    public InMemoryDirectoryServer directory() {
        return server;
    }

    @Override
    public void close() {
        server.shutDown(true);
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        Path ldif = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--ldif" -> ldif = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        DevLdapServer server;
        if (ldif != null) {
            try (InputStream in = Files.newInputStream(ldif)) {
                server = start(port, in);
            }
        } else {
            server = start(port);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        log.info("Dev LDAP listening on {} (base {}, {} entries). Users: alice/alice-pass (admin), bob/bob-pass, carol/carol-pass",
            server.url(), BASE_DN, server.directory().countEntries());
        Thread.currentThread().join();
    }
}
