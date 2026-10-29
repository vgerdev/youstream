package com.youstream.server;

import com.typesafe.config.Config;
import com.youstream.api.AppServices;
import com.youstream.api.YouStreamApp;
import com.youstream.db.Database;
import com.youstream.db.DbConfig;
import io.jooby.Environment;
import io.jooby.EnvironmentOptions;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.undertow.UndertowServer;

/**
 * Entry point: loads conf/application.conf (+ local.conf), migrates the database, then starts the HTTP server.
 * Later phases also start the SFTP server and transcode workers here.
 */
public final class YouStreamServer {

    private YouStreamServer() {
    }

    public static void main(String[] args) {
        Environment env = Environment.loadEnvironment(new EnvironmentOptions());
        Config conf = env.getConfig();

        Database database = new Database(dbConfig(conf));
        database.migrate();

        AppServices services = AppServices.builder()
            .databaseHealthy(database.db()::isHealthy)
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
}
