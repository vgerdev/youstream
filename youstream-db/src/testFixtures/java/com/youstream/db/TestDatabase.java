package com.youstream.db;

import com.jcabi.jdbc.Outcome;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Assumptions;

import java.io.File;

/**
 * Shared database for integration tests: the {@code db.testSchema} schema of the database in conf/local.conf.
 * The schema is cleaned and migrated once per JVM; {@link #truncate()} empties all tables between tests.
 * Tests are skipped when no test database is configured.
 */
public final class TestDatabase {

    private static Database database;

    private TestDatabase() {
    }

    public static synchronized Db db() {
        if (database == null) {
            DbConfig config = loadConfig();
            Assumptions.assumeTrue(config != null, "no test database configured (db.testSchema in conf/local.conf)");
            Database created = new Database(config);
            created.clean();
            created.migrate();
            Runtime.getRuntime().addShutdownHook(new Thread(created::close));
            database = created;
        }
        return database.db();
    }

    /** Removes all rows from every table. */
    public static void truncate() {
        db().run(s -> s
            .sql("TRUNCATE users, user_identities, refresh_tokens, ssh_keys, videos, video_renditions, transcode_jobs,"
                + " upload_sessions, comments, video_reactions, subscriptions, playlists, playlist_items, video_views,"
                + " login_failures"
                + " RESTART IDENTITY CASCADE")
            .execute());
    }

    public static boolean exists(String sql, Object... args) {
        return db().run(s -> {
            s.sql(sql);
            for (Object arg : args) {
                s.set(arg);
            }
            return s.select(Outcome.NOT_EMPTY);
        });
    }

    private static DbConfig loadConfig() {
        String path = System.getProperty("youstream.conf", "conf/local.conf");
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        Config conf = ConfigFactory.parseFile(file).resolve();
        if (!conf.hasPath("db.url") || !conf.hasPath("db.testSchema")) {
            return null;
        }
        return DbConfig.builder()
            .url(conf.getString("db.url"))
            .user(conf.getString("db.user"))
            .password(conf.getString("db.password"))
            .schema(conf.getString("db.testSchema"))
            .poolSize(4)
            .build();
    }
}
