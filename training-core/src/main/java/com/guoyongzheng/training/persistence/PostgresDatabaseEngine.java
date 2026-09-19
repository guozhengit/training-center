package com.guoyongzheng.training.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL database engine: server-backed connection pool (HikariCP), Flyway
 * migrations from {@code db/migration-postgres}, and standard ACID transactions.
 *
 * <p>State-machine and immutability guards that SQLite enforced with triggers are
 * enforced by the application layer ({@code StateTransitions} and repository
 * invariants) for this backend.</p>
 */
final class PostgresDatabaseEngine implements DatabaseEngine {

    private final PostgresSettings settings;
    private final HikariDataSource dataSource;

    PostgresDatabaseEngine(PostgresSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dataSource = configuredDataSource(settings);
    }

    @Override
    public void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration-postgres")
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
    }

    @Override
    public Connection openConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new SQLException("Connection health check failed for " + settings.jdbcUrl(), exception);
        }
        return connection;
    }

    @Override
    public <T> T inTransaction(TransactionWork<T> work) throws Exception {
        return inWriteTransaction(work);
    }

    @Override
    public <T> T inWriteTransaction(TransactionWork<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception | Error exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
    }

    @Override
    public Path path() {
        // No filesystem path for a server-backed database; describe() carries the JDBC URL.
        return Path.of("postgresql");
    }

    @Override
    public String describe() {
        return settings.jdbcUrl();
    }

    @Override
    public Optional<Path> lastBackup() {
        // Server-side backups are handled out of band (pg_dump); no file backup here.
        return Optional.empty();
    }

    private static HikariDataSource configuredDataSource(PostgresSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(3_000);
        config.setPoolName("training-pg");
        return new HikariDataSource(config);
    }
}
