package com.youstream.server;

import com.typesafe.config.Config;
import com.youstream.api.AppServices;
import com.youstream.api.YouStreamApp;
import com.youstream.api.auth.SshKeyRoutes;
import com.youstream.auth.LoginThrottle;
import com.youstream.auth.SshKeyService;
import com.youstream.auth.UserService;
import com.youstream.auth.entra.EntraConfig;
import com.youstream.auth.entra.EntraOidcService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.ldap.LdapConfig;
import com.youstream.auth.token.PlaybackTokens;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.common.storage.LocalDiskStorage;
import com.youstream.db.Database;
import com.youstream.db.DbConfig;
import com.youstream.media.Ffprobe;
import com.youstream.media.IngestService;
import com.youstream.media.MediaConfig;
import com.youstream.media.ProcessRunner;
import com.youstream.media.TranscodeWorkerPool;
import com.youstream.sftp.SftpConfig;
import com.youstream.sftp.YouStreamSftpServer;
import io.jooby.Environment;
import io.jooby.EnvironmentOptions;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.undertow.UndertowServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;

/**
 * Entry point: loads conf/application.conf (+ local.conf), migrates the database, then starts the HTTP server and
 * the transcode workers and the SFTP upload server.
 */
public final class YouStreamServer {

    private static final Logger log = LoggerFactory.getLogger(YouStreamServer.class);

    private YouStreamServer() {
    }

    public static void main(String[] args) throws IOException {
        Environment env = Environment.loadEnvironment(new EnvironmentOptions());
        Config conf = env.getConfig();
        Clock clock = Clock.systemUTC();

        Database database = new Database(dbConfig(conf));
        database.migrate();

        LocalDiskStorage storage = new LocalDiskStorage(Path.of(conf.getString("storage.root")));
        log.info("Storing media in {}", storage.root());
        MediaConfig media = mediaConfig(conf);
        ProcessRunner runner = new ProcessRunner();
        TranscodeWorkerPool workers = new TranscodeWorkerPool(database.db(), storage, media, runner, clock);

        IngestService ingest = new IngestService(database.db(), storage, new Ffprobe(media.getFfprobe(), runner));
        LdapAuthenticator ldap = ldap(conf);
        UserService users = new UserService(database.db(), clock);
        SshKeyService sshKeys = new SshKeyService(database.db(), clock);
        // one throttle for web and SFTP password logins
        LoginThrottle throttle = new LoginThrottle();

        YouStreamSftpServer sftp = null;
        SshKeyRoutes.SftpEndpoint sftpEndpoint = SshKeyRoutes.SftpEndpoint.disabled();
        if (conf.getBoolean("sftp.enabled")) {
            SftpConfig sftpConfig = sftpConfig(conf);
            sftp = new YouStreamSftpServer(sftpConfig, storage, ingest, users, sshKeys, ldap, throttle);
            sftp.start();
            sftpEndpoint = new SshKeyRoutes.SftpEndpoint(true, conf.getString("sftp.publicHost"), sftp.port(),
                sftp.hostKeyFingerprint(), sftpConfig.getMaxFileBytes());
        }
        YouStreamSftpServer sftpServer = sftp;

        String secret = conf.getString("jwt.secret");
        AppServices services = AppServices.builder()
            .databaseHealthy(database.db()::isHealthy)
            .db(database.db())
            .storage(storage)
            .ingest(ingest)
            .ldap(ldap)
            .entra(entra(conf))
            .users(users)
            .sshKeys(sshKeys)
            .sftp(sftpEndpoint)
            .loginThrottle(throttle)
            .tokens(new TokenService(secret, conf.getDuration("jwt.accessTtl"), clock))
            .refreshTokens(new RefreshTokenService(database.db(), conf.getDuration("jwt.refreshTtl"), clock))
            .playbackTokens(new PlaybackTokens(secret, conf.getDuration("media.playbackTokenTtl"), clock))
            .secureCookies(conf.getBoolean("auth.secureCookies"))
            .frontendUrl(conf.getString("auth.frontendUrl").replaceAll("/+$", ""))
            .maxUploadBytes(conf.getBytes("upload.maxBytes"))
            .uploadChunkBytes(conf.getBytes("upload.chunkBytes"))
            .build();

        // WORKER mode: handlers do blocking JDBC/LDAP/file work
        Jooby.runApp(args, new UndertowServer(), ExecutionMode.WORKER, () -> {
            YouStreamApp app = new YouStreamApp(services);
            app.setEnvironment(env);
            app.onStarted(workers::start);
            app.onStop(() -> {
                if (sftpServer != null) {
                    sftpServer.close();
                }
                // workers release their running jobs, which needs the database
                workers.close();
                database.close();
            });
            return app;
        });
    }

    static SftpConfig sftpConfig(Config conf) {
        Config c = conf.getConfig("sftp");
        return SftpConfig.builder()
            .port(c.getInt("port"))
            .hostKey(Path.of(c.getString("hostKey")))
            .maxFileBytes(c.getBytes("maxFileBytes"))
            .idleTimeout(c.getDuration("idleTimeout"))
            .publicUrl(conf.getString("app.publicUrl").replaceAll("/+$", ""))
            .build();
    }

    static MediaConfig mediaConfig(Config conf) {
        Config c = conf.getConfig("media");
        return MediaConfig.builder()
            .ffmpeg(c.getString("ffmpeg"))
            .ffprobe(c.getString("ffprobe"))
            .workers(c.getInt("workers"))
            .segmentSeconds(c.getInt("segmentSeconds"))
            .preset(c.getString("preset"))
            .build();
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
