package com.youstream.db;

import com.jcabi.jdbc.JdbcSession;
import com.jcabi.jdbc.Outcome;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Entry point for repositories: hands out jcabi-jdbc sessions and runs transactions.
 * Inside {@link #tx(SqlWork)} every repository call on the same thread shares one connection.
 */
public final class Db {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(JdbcSession session) throws SQLException;
    }

    private final DataSource dataSource;
    private final ThreadLocal<JdbcSession> current = new ThreadLocal<>();

    public Db(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Runs one unit of work in the current transaction, or in autocommit mode outside one. */
    public <T> T run(SqlWork<T> work) {
        JdbcSession session = current.get();
        try {
            return work.run(session != null ? session : new JdbcSession(dataSource));
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    /** Runs work in a transaction; nested calls join the outer transaction. */
    public <T> T tx(SqlWork<T> work) {
        if (current.get() != null) {
            return run(work);
        }
        JdbcSession session = new JdbcSession(dataSource).autocommit(false);
        current.set(session);
        try {
            T result = work.run(session);
            session.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(session, e);
            throw new DataAccessException(e);
        } catch (RuntimeException | Error e) {
            rollbackQuietly(session, e);
            throw e;
        } finally {
            current.remove();
        }
    }

    public void txVoid(SqlWorkVoid work) {
        tx(session -> {
            work.run(session);
            return null;
        });
    }

    @FunctionalInterface
    public interface SqlWorkVoid {
        void run(JdbcSession session) throws SQLException;
    }

    /** True when a trivial query succeeds. */
    public boolean isHealthy() {
        try {
            return run(s -> s.sql("SELECT 1").select(Outcome.NOT_EMPTY));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void rollbackQuietly(JdbcSession session, Throwable cause) {
        try {
            session.rollback();
        } catch (SQLException | RuntimeException e) {
            cause.addSuppressed(e);
        }
    }
}
