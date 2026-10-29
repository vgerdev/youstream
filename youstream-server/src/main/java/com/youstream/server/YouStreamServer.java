package com.youstream.server;

import com.typesafe.config.Config;
import com.youstream.api.AppServices;
import com.youstream.api.YouStreamApp;
import com.youstream.auth.UserService;
import com.youstream.auth.entra.EntraConfig;
import com.youstream.auth.entra.EntraOidcService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.ldap.LdapConfig;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.db.Database;
import com.youstream.db.DbConfig;
import io.jooby.Environment;
import io.jooby.EnvironmentOptions;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.undertow.UndertowServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Locale;

/**
 * Entry point: loads conf/application.conf (+ local.conf), migrates the database, then starts the HTTP server.
 * Later phases also start the SFTP server and transcode workers here.
 */
public final class YouStreamServer {

    private static final Logger log = LoggerFactory.getLogger(YouStreamServer.class);

    private YouStreamServer() {
    }

    public static void main(String[] args) {
        Environment env = Environment.loadEnvironment(new EnvironmentOptions());
        Config conf = env.getConfig();
        Clock clock = Clock.systemUTC();

        Database database = new Database(dbConfig(conf));
        database.migrate();

        AppServices services = AppServices.builder()
            .databaseHealthy(database.db()::isHealthy)
            .ldap(ldap(conf))
            .entra(entra(conf))
            .users(new UserService(database.db(), clock))
            .tokens(new TokenService(conf.getString("jwt.secret"), conf.getDuration("jwt.accessTtl"), clock))
            .refreshTokens(new RefreshTokenService(database.db(), conf.getDuration("jwt.refreshTtl"), clock))
            .secureCookies(conf.getBoolean("auth.secureCookies"))
            .frontendUrl(conf.getString("auth.frontendUrl").replaceAll("/+$", ""))
            .build();

        // WORKER mode: handlers do blocking JDBC/LDAP/file work
        Jooby.runApp(args, new UndertowServer(), ExecutionMode.WORKER, () -> {
            YouStreamApp app = new YouStreamApp(services);
            app.setEnvironment(env);
            app.onStop(database);
            return app;
        });
    }

    static DbConfig dbConfig(Config conf) {
        if (!conf.hasPath("db.url")) {
            throw new IllegalStateException("db.url is not configured; copy conf/local.conf.example to conf/local.conf");
        }
        return DbConfig.builder()
            .url(conf.getString("db.url"))
            .user(conf.getString("db.user"))
            .password(conf.getString("db.password"))
            .poolSize(conf.getInt("db.poolSize"))
            .schema(conf.hasPath("db.schema") ? conf.getString("db.schema") : "public")
            .build();
    }

    static LdapAuthenticator ldap(Config conf) {
        if (!conf.getBoolean("ldap.enabled")) {
            log.info("LDAP sign-in disabled");
            return null;
        }
        Config c = conf.getConfig("ldap");
        LdapConfig config = LdapConfig.builder()
            .url(c.getString("url"))
            .mode(LdapConfig.Mode.valueOf(c.getString("mode").toUpperCase(Locale.ROOT)))
            .baseDn(c.getString("baseDn"))
            .bindDn(c.getString("bindDn"))
            .bindPassword(c.getString("bindPassword"))
            .userFilter(c.getString("userFilter"))
            .upnDomain(c.getString("upnDomain"))
            .idAttribute(c.getString("attributes.id"))
            .usernameAttribute(c.getString("attributes.username"))
            .displayNameAttribute(c.getString("attributes.displayName"))
            .emailAttribute(c.getString("attributes.email"))
            .memberOfAttribute(c.getString("attributes.memberOf"))
            .requiredGroup(c.getString("requiredGroup"))
            .adminGroup(c.getString("adminGroup"))
            .build();
        log.info("LDAP sign-in enabled ({}, {} mode)", config.getUrl(), config.getMode());
        return new LdapAuthenticator(config);
    }

    static EntraOidcService entra(Config conf) {
        if (!conf.getBoolean("entra.enabled") || !conf.hasPath("entra.tenantId") || conf.getString("entra.tenantId").isBlank()) {
            log.info("Sign in with Microsoft disabled");
            return null;
        }
        Config c = conf.getConfig("entra");
        EntraConfig config = EntraConfig.builder()
            .tenantId(c.getString("tenantId"))
            .clientId(c.getString("clientId"))
            .clientSecret(c.getString("clientSecret"))
            .redirectUri(c.getString("redirectUri"))
            .scopes(c.getString("scopes"))
            .adminRole(c.getString("adminRole"))
            .build();
        log.info("Sign in with Microsoft enabled (tenant {}, redirect {})", config.getTenantId(), config.getRedirectUri());
        return new EntraOidcService(config);
    }
}
