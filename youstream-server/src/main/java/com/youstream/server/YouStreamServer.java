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
import com.youstream.media.MaintenanceJobs;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Entry point. Loads conf/application.conf (+ local.conf) and runs one command:
 * <ul>
 *   <li>{@code serve} (default): migrates the database (unless {@code db.migrate = false}) and runs the configured
 *       {@link Roles}: HTTP server, SFTP server, transcode workers. Every process answers {@code /api/health}.</li>
 *   <li>{@code migrate}: applies database migrations and exits (Kubernetes runs it as a pre-upgrade Job).</li>
 *   <li>{@code maintenance}: one cleanup pass, then exit (a CronJob, instead of the in-process timer).</li>
 *   <li>{@code hostkey}: creates the SFTP host key if missing and prints its fingerprint.</li>
 * </ul>
 */
public final class YouStreamServer {

    private static final Logger log = LoggerFactory.getLogger(YouStreamServer.class);

    private YouStreamServer() {
    }

    public static void main(String[] args) throws IOException {
        // Jooby takes key=value arguments; a first argument without '=' is a command
        boolean hasCommand = args.length > 0 && !args[0].contains("=");
        String command = hasCommand ? args[0] : "serve";
        String[] rest = hasCommand ? Arrays.copyOfRange(args, 1, args.length) : args;
        Environment env = Environment.loadEnvironment(new EnvironmentOptions());
        Config conf = env.getConfig();
        switch (command) {
            case "serve" -> serve(env, conf, rest);
            case "migrate" -> {
                try (Database database = new Database(dbConfig(conf))) {
                    log.info("Applied {} migration(s)", database.migrate());
                }
            }
            case "maintenance" -> {
                try (Database database = new Database(dbConfig(conf))) {
                    MaintenanceJobs jobs = new MaintenanceJobs(database.db(), storage(conf), Clock.systemUTC());
                    log.info("Maintenance: {}", jobs.runOnce());
                }
            }
            case "hostkey" -> {
                Path hostKey = Path.of(conf.getString("sftp.hostKey"));
                System.out.println(hostKey.toAbsolutePath() + " " + YouStreamSftpServer.generateHostKey(hostKey));
            }
            default -> {
                System.err.println("Unknown command: " + command + " (use serve, migrate, maintenance or hostkey)");
                System.exit(2);
            }
        }
    }

    private static void serve(Environment env, Config conf, String[] args) throws IOException {
        Set<Roles> roles = Roles.from(conf);
        log.info("Starting roles {}", roles);
        Clock clock = Clock.systemUTC();

        Database database = new Database(dbConfig(conf));
        if (conf.getBoolean("db.migrate")) {
            database.migrate();
        }

        LocalDiskStorage storage = storage(conf);
        log.info("Storing media in {}", storage.root());
        MediaConfig media = mediaConfig(conf);
        ProcessRunner runner = new ProcessRunner();
        TranscodeWorkerPool workers = roles.contains(Roles.TRANSCODE)
            ? new TranscodeWorkerPool(database.db(), storage, media, runner, clock) : null;

        IngestService ingest = new IngestService(database.db(), storage, new Ffprobe(media.getFfprobe(), runner),
            conf.getBytes("upload.quotaBytes"));
        Duration maintenanceEvery = conf.getDuration("maintenance.every");
        MaintenanceJobs maintenance = maintenanceEvery.isZero() ? null : new MaintenanceJobs(database.db(), storage, clock);
        LdapAuthenticator ldap = ldap(conf);
        UserService users = new UserService(database.db(), clock);
        SshKeyService sshKeys = new SshKeyService(database.db(), clock);
        // one throttle for web and SFTP password logins, kept in the database so every process shares it
        LoginThrottle throttle = LoginThrottle.shared(database.db(), clock);

        YouStreamSftpServer sftp = null;
        if (roles.contains(Roles.SFTP) && conf.getBoolean("sftp.enabled")) {
            sftp = new YouStreamSftpServer(sftpConfig(conf), storage, ingest, users, sshKeys, ldap, throttle);
            sftp.start();
        }
        YouStreamSftpServer sftpServer = sftp;

        AppServices services = roles.contains(Roles.WEB)
            ? webServices(conf, database, storage, ingest, ldap, users, sshKeys, throttle, sftpEndpoint(conf, sftp), clock)
            // the other roles only answer health checks
            : AppServices.builder().databaseHealthy(database.db()::isHealthy).build();

        // WORKER mode: handlers do blocking JDBC/LDAP/file work
        Jooby.runApp(args, new UndertowServer(), ExecutionMode.WORKER, () -> {
            YouStreamApp app = new YouStreamApp(services);
            app.setEnvironment(env);
            if (workers != null) {
                app.onStarted(workers::start);
            }
            if (maintenance != null) {
                app.onStarted(() -> maintenance.start(maintenanceEvery));
            }
            app.onStop(() -> {
                if (maintenance != null) {
                    maintenance.close();
                }
                if (sftpServer != null) {
                    sftpServer.close();
                }
                // workers release their running jobs, which needs the database
                if (workers != null) {
                    workers.close();
                }
                database.close();
            });
            return app;
        });
    }

    private static AppServices webServices(Config conf, Database database, LocalDiskStorage storage, IngestService ingest,
                                           LdapAuthenticator ldap, UserService users, SshKeyService sshKeys,
                                           LoginThrottle throttle, SshKeyRoutes.SftpEndpoint sftpEndpoint, Clock clock) {
        String secret = conf.getString("jwt.secret");
        return AppServices.builder()
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
            .publicUrl(conf.getString("app.publicUrl").replaceAll("/+$", ""))
            .trustedProxyHops(conf.getInt("server.trustedProxyHops"))
            .maxUploadBytes(conf.getBytes("upload.maxBytes"))
            .uploadChunkBytes(conf.getBytes("upload.chunkBytes"))
            .build();
    }

    /**
     * What the Account page tells users about SFTP. A web process without the SFTP role reads the fingerprint from
     * the shared host key file; the port is the public one (a load balancer may map 22 to 2222).
     */
    static SshKeyRoutes.SftpEndpoint sftpEndpoint(Config conf, YouStreamSftpServer running) {
        if (!conf.getBoolean("sftp.enabled")) {
            return SshKeyRoutes.SftpEndpoint.disabled();
        }
        Config c = conf.getConfig("sftp");
        int port = c.getInt("publicPort") > 0 ? c.getInt("publicPort") : running != null ? running.port() : c.getInt("port");
        String fingerprint = running != null
            ? running.hostKeyFingerprint()
            : YouStreamSftpServer.hostKeyFingerprint(Path.of(c.getString("hostKey")));
        return new SshKeyRoutes.SftpEndpoint(true, c.getString("publicHost"), port, fingerprint, c.getBytes("maxFileBytes"));
    }

    static LocalDiskStorage storage(Config conf) throws IOException {
        return new LocalDiskStorage(Path.of(conf.getString("storage.root")));
    }

    static SftpConfig sftpConfig(Config conf) {
        Config c = conf.getConfig("sftp");
        return SftpConfig.builder()
            .port(c.getInt("port"))
            .hostKey(Path.of(c.getString("hostKey")))
            .maxFileBytes(c.getBytes("maxFileBytes"))
            .idleTimeout(c.getDuration("idleTimeout"))
            .proxyProtocol(c.getBoolean("proxyProtocol"))
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
