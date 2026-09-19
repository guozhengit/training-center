package com.guoyongzheng.training.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Facade over a {@link DatabaseEngine}. Keeps the historical API stable so
 * repositories, CLI commands, and tests can use either backend unchanged.
 *
 * <p>SQLite (default, local development): file-backed with WAL and online backups.
 * PostgreSQL (server deployment): connection pool with Flyway migrations.</p>
 */
public final class TrainingDatabase {
    private final DatabaseEngine engine;

    public TrainingDatabase(Path path) {
        this(new SqliteDatabaseEngine(path));
    }

    TrainingDatabase(Path path, Clock clock) {
        this(new SqliteDatabaseEngine(path, clock));
    }

    public TrainingDatabase(PostgresSettings settings) {
        this(new PostgresDatabaseEngine(settings));
    }

    private TrainingDatabase(DatabaseEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Backend identifier path; for PostgreSQL use {@link #describe()} for the JDBC URL. */
    public Path path() {
        return engine.path();
    }

    /** Human-readable backend description (database file path or JDBC URL). */
    public String describe() {
        return engine.describe();
    }

    public Optional<Path> lastBackup() {
        return engine.lastBackup();
    }

    public void migrate() {
        engine.migrate();
    }

    public Connection openConnection() throws SQLException {
        return engine.openConnection();
    }

    public <T> T inTransaction(TransactionWork<T> work) throws Exception {
        return engine.inTransaction(work::execute);
    }

    public <T> T inWriteTransaction(TransactionWork<T> work) throws Exception {
        return engine.inWriteTransaction(work::execute);
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
