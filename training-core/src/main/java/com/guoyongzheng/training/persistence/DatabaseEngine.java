package com.guoyongzheng.training.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Abstraction over the database backend (SQLite file or PostgreSQL server).
 *
 * <p>Owns startup policy (migrations), configured connections, and transaction
 * boundaries for one training installation. Repository classes only consume
 * {@link Connection} instances, so switching the backend does not change them.
 */
public interface DatabaseEngine {

    /** Applies pending schema migrations and verifies the schema is supported. */
    void migrate();

    /** Returns a validated connection; callers own closing it. */
    Connection openConnection() throws SQLException;

    /** Runs work in a transaction. Same semantics as {@link #inWriteTransaction}. */
    <T> T inTransaction(TransactionWork<T> work) throws Exception;

    /** Runs an entire write workflow in a single transaction. */
    <T> T inWriteTransaction(TransactionWork<T> work) throws Exception;

    /**
     * Backend identifier path. For SQLite this is the database file; for
     * PostgreSQL it is a stable placeholder (the real target is {@link #describe()}).
     */
    Path path();

    /** Human-readable backend description (database file path or JDBC URL). */
    String describe();

    /** Most recent pre-migration backup, when the backend supports file backups. */
    Optional<Path> lastBackup();

    @FunctionalInterface
    interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
