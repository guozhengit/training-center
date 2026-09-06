package com.guoyongzheng.training.persistence;

import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** Owns SQLite startup policy, migrations, configured connections, and transaction boundaries. */
public final class TrainingDatabase {
    public static final int SUPPORTED_SCHEMA_VERSION = 3;
    public static final int BUSY_TIMEOUT_MILLIS = 10_000;
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private final Path path;
    private final Clock clock;
    private final SQLiteDataSource dataSource;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private Path lastBackup;

    public TrainingDatabase(Path path) {
        this(path, Clock.systemUTC());
    }

    TrainingDatabase(Path path, Clock clock) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dataSource = configuredDataSource(this.path);
    }

    public Path path() {
        return path;
    }

    public Optional<Path> lastBackup() {
        return Optional.ofNullable(lastBackup);
    }

    public void migrate() {
        lastBackup = null;
        boolean existed = Files.exists(path);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            SchemaVersions versions = existed ? inspectExistingDatabase() : new SchemaVersions(0, 0);
            int newestVersion = Math.max(versions.userVersion(), versions.flywayVersion());
            if (newestVersion > SUPPORTED_SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "Database schema version " + newestVersion
                                + " is newer than supported version " + SUPPORTED_SCHEMA_VERSION);
            }
            if (versions.userVersion() > 0 && versions.flywayVersion() == 0) {
                throw new IllegalStateException(
                        "Database schema version is inconsistent: user_version is "
                                + versions.userVersion() + " but Flyway history is absent");
            }
            if (versions.flywayVersion() == SUPPORTED_SCHEMA_VERSION) {
                validateFlyway();
                if (versions.userVersion() != SUPPORTED_SCHEMA_VERSION) {
                    setUserVersion();
                }
                return;
            }
            if (existed) {
                lastBackup = createBackup();
            }

            Flyway flyway = flyway();
            flyway.migrate();
            setUserVersion();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Database migration failed for " + path, exception);
        }
    }

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
            throw new SQLException("Connection health check failed for " + path, exception);
        }
        return connection;
    }

    public <T> T inTransaction(TransactionWork<T> work) throws Exception {
        return inWriteTransaction(work);
    }

    /**
     * Runs an entire write workflow under {@code BEGIN IMMEDIATE}.
     *
     * <p>Writes are serialized via a fair {@link ReentrantLock} so that concurrent callers
     * queue in FIFO order rather than contending on SQLite's busy handler. The write
     * reservation is acquired before user work starts, so a busy wait or failure occurs at
     * the whole-transaction boundary where retrying is safe.</p>
     */
    public <T> T inWriteTransaction(TransactionWork<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        writeLock.lock();
        try (Connection connection = openConnection()) {
            executeTransactionControl(connection, "BEGIN IMMEDIATE");
            try {
                T result = work.execute(connection);
                executeTransactionControl(connection, "COMMIT");
                return result;
            } catch (Exception | Error exception) {
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        } finally {
            writeLock.unlock();
        }
    }

    private SchemaVersions inspectExistingDatabase() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                    throw new IllegalStateException("Database integrity check failed for " + path);
                }
            }
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (!version.next()) {
                    throw new IllegalStateException("Database integrity check failed: missing schema version");
                }
                return new SchemaVersions(version.getInt(1), readFlywayVersion(connection));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Database integrity check failed for " + path, exception);
        }
    }

    private int readFlywayVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet table = statement.executeQuery("""
                     SELECT 1 FROM sqlite_master
                     WHERE type = 'table' AND name = 'flyway_schema_history'
                     """)) {
            if (!table.next()) {
                return 0;
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version FROM flyway_schema_history
                     WHERE success = 1 AND version IS NOT NULL
                     ORDER BY installed_rank DESC LIMIT 1
                     """)) {
            if (!result.next()) {
                return 0;
            }
            String version = result.getString(1);
            try {
                return Integer.parseInt(version);
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Unsupported Flyway schema version: " + version, exception);
            }
        }
    }

    private Path createBackup() throws IOException, SQLException {
        String timestamp = BACKUP_TIMESTAMP.format(Instant.now(clock));
        Path backup = path.resolveSibling(path.getFileName() + ".backup-" + timestamp);
        Files.createFile(backup);
        boolean completed = false;
        try (Connection connection = dataSource.getConnection()) {
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int result = sqlite.getDatabase().backup("main", backup.toString(), null);
            if (result != 0) {
                throw new SQLException("SQLite online backup failed with result code " + result);
            }
            completed = true;
            return backup;
        } finally {
            if (!completed) {
                Files.deleteIfExists(backup);
            }
        }
    }

    private void validateFlyway() {
        flyway().validate();
    }

    private void setUserVersion() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + SUPPORTED_SCHEMA_VERSION);
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load();
    }

    static SQLiteDataSource configuredDataSource(Path databasePath) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        SQLiteDataSource configured = new SQLiteDataSource(config);
        configured.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
        return configured;
    }

    private static void rollbackAfterFailure(Connection connection, Throwable failure) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void executeTransactionControl(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }

    private record SchemaVersions(int userVersion, int flywayVersion) {
    }
}
