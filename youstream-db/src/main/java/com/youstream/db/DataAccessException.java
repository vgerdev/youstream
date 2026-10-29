package com.youstream.db;

import java.sql.SQLException;

/** Unchecked wrapper for {@link SQLException} thrown by repositories. */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(SQLException cause) {
        super(cause.getMessage(), cause);
    }

    /** PostgreSQL SQLSTATE of the underlying error, or null. jcabi-jdbc re-wraps driver exceptions, so walk the chain. */
    public String sqlState() {
        for (Throwable t = getCause(); t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof SQLException e && e.getSQLState() != null) {
                return e.getSQLState();
            }
        }
        return null;
    }

    public boolean isUniqueViolation() {
        return "23505".equals(sqlState());
    }
}
