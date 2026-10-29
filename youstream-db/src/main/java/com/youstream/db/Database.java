package com.youstream.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the HikariCP pool and applies Flyway migrations. */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final DbConfig config;
    private final HikariDataSource dataSource;
    private final Db db;

    public Database(DbConfig config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalArgumentException("db.url is not configured (set it in conf/local.conf or DB_URL)");
        }
        this.config = config;
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("youstream");
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUser());
        hikari.setPassword(config.getPassword());
        hikari.setMaximumPoolSize(config.getPoolSize());
        hikari.setSchema(config.getSchema());
        this.dataSource = new HikariDataSource(hikari);
        this.db = new Db(dataSource);
    }

    public Db db() {
        return db;
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    /** Applies pending migrations and returns how many ran. */
    public int migrate() {
        MigrateResult result = flyway(false).migrate();
        log.info("Database schema '{}' at version {} ({} migrations applied)",
            config.getSchema(), result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion,
            result.migrationsExecuted);
        return result.migrationsExecuted;
    }

    /** Drops every object in the schema. Only for test schemas. */
    public void clean() {
        flyway(true).clean();
    }

    private Flyway flyway(boolean allowClean) {
        return Flyway.configure()
            .dataSource(dataSource)
            .schemas(config.getSchema())
            .locations("classpath:db/migration")
            .cleanDisabled(!allowClean)
            .load();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
