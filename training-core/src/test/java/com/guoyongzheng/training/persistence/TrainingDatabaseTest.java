package com.guoyongzheng.training.persistence;

import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.domain.SessionStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingDatabaseTest {
    private static final Instant NOW = Instant.parse("2026-07-26T12:34:56Z");

    @Test
    void migratesSchemaAndConfiguresEveryOpenedConnection(@TempDir Path directory) throws Exception {
        TrainingDatabase database = new TrainingDatabase(directory.resolve("training.db"));

        database.migrate();

        assertThat(database.lastBackup()).isEmpty();
        try (Connection connection = database.openConnection()) {
            assertThat(pragmaLong(connection, "foreign_keys")).isEqualTo(1);
            assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(pragmaLong(connection, "busy_timeout")).isEqualTo(10_000);
            assertThat(pragmaLong(connection, "user_version")).isEqualTo(1);
            assertThat(tableNames(connection)).contains(
                    "questions", "sessions", "attempts", "judgements",
                    "oral_scores", "review_queue", "plan_days", "plan_tasks",
                    "flyway_schema_history");
        }
    }

    @Test
    void migrationRejectsConnectionWithoutRequiredSqlitePolicy(@TempDir Path directory) {
        Path path = directory.resolve("unconfigured.db");

        assertThatThrownBy(() -> Flyway.configure()
                .dataSource("jdbc:sqlite:" + path, "", "")
                .locations("classpath:db/migration")
                .load()
                .migrate())
                .hasStackTraceContaining("migration_connection_policy");
    }

    @Test
    void enforcesForeignKeysAndSchemaChecks(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO attempts
                        (id, session_id, question_id, started_at, status, answer_unlocked)
                    VALUES ('a-missing', 'missing-session', 'missing-question',
                            '2026-07-26T12:34:56Z', 'CREATED', 0)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("FOREIGN KEY");
        }
    }

    @Test
    void enforcesEveryDesignedForeignKeyAndCheckConstraint(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("constraints.db"));
        try (Connection connection = database.openConnection()) {
            insertQuestion(connection, "q-constraints");
            execute(connection, """
                    INSERT INTO sessions
                        (id, mode, seed, started_at, status, config_json)
                    VALUES ('s-constraints', 'MIXED', 1, '2026-07-26T12:34:56Z', 'CREATED', '{}')
                    """);
            insertAttemptRow(connection, "a-constraints", "s-constraints", "q-constraints", 0);

            assertSqlRejected(connection, """
                    INSERT INTO attempts
                        (id, session_id, question_id, started_at, status, answer_unlocked)
                    VALUES ('a-missing-session', 'missing', 'q-constraints',
                            '2026-07-26T12:34:56Z', 'CREATED', 0)
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO attempts
                        (id, session_id, question_id, started_at, status, answer_unlocked)
                    VALUES ('a-missing-question', 's-constraints', 'missing',
                            '2026-07-26T12:34:56Z', 'CREATED', 0)
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO judgements
                        (id, attempt_id, sequence_no, started_at, status)
                    VALUES ('j-missing-attempt', 'missing', 1,
                            '2026-07-26T12:34:56Z', 'QUEUED')
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO oral_scores
                        (attempt_id, correctness, structure, project_evidence, tradeoff, fact_restraint)
                    VALUES ('missing', 1, 1, 1, 1, 1)
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO review_queue
                        (question_id, wrong_count, review_count, next_review_at, interval_days)
                    VALUES ('missing', 0, 0, '2026-07-27T12:34:56Z', 1)
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO plan_tasks
                        (id, plan_day_id, task_order, task_type, title, target_count, completed_count)
                    VALUES ('task-missing-day', 'missing', 1, 'CODING', 'Task', 1, 0)
                    """);

            assertThat(execute(connection, """
                    INSERT INTO questions
                        (id, track, group_name, title, topic, difficulty, language,
                         source_ref, active)
                    VALUES ('q-active-0', 'ORAL', 'g', 't', 'topic', 'BASIC',
                            'none', 'q0.md', 0)
                    """)).isEqualTo(1);
            assertThat(execute(connection, """
                    INSERT INTO questions
                        (id, track, group_name, title, topic, difficulty, language,
                         source_ref, active)
                    VALUES ('q-active-1', 'PROJECT', 'g', 't', 'topic', 'BASIC',
                            'none', 'q1.md', 1)
                    """)).isEqualTo(1);
            assertSqlRejected(connection, """
                    INSERT INTO questions
                        (id, track, group_name, title, topic, difficulty, language,
                         source_ref, active)
                    VALUES ('q-active-2', 'CODING', 'g', 't', 'topic', 'BASIC',
                            'java', 'q2.md', 2)
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO questions
                        (id, track, group_name, title, topic, difficulty, language,
                         source_ref, active)
                    VALUES ('q-bad-track', 'UNKNOWN', 'g', 't', 'topic', 'BASIC',
                            'none', 'qx.md', 1)
                    """);

            assertThat(execute(connection,
                    "UPDATE attempts SET answer_unlocked = 1, duration_seconds = 0 "
                            + "WHERE id = 'a-constraints'")).isEqualTo(1);
            assertSqlRejected(connection,
                    "UPDATE attempts SET answer_unlocked = 2 WHERE id = 'a-constraints'");
            assertSqlRejected(connection,
                    "UPDATE attempts SET duration_seconds = -1 WHERE id = 'a-constraints'");

            execute(connection, """
                    INSERT INTO oral_scores
                        (attempt_id, correctness, structure, project_evidence, tradeoff, fact_restraint)
                    VALUES ('a-constraints', 1, 1, 1, 1, 1)
                    """);
            for (String scoreColumn : List.of(
                    "correctness", "structure", "project_evidence", "tradeoff", "fact_restraint")) {
                assertThat(execute(connection,
                        "UPDATE oral_scores SET " + scoreColumn + " = 0 "
                                + "WHERE attempt_id = 'a-constraints'")).isEqualTo(1);
                assertThat(execute(connection,
                        "UPDATE oral_scores SET " + scoreColumn + " = 2 "
                                + "WHERE attempt_id = 'a-constraints'")).isEqualTo(1);
                assertSqlRejected(connection,
                        "UPDATE oral_scores SET " + scoreColumn + " = -1 "
                                + "WHERE attempt_id = 'a-constraints'");
                assertSqlRejected(connection,
                        "UPDATE oral_scores SET " + scoreColumn + " = 3 "
                                + "WHERE attempt_id = 'a-constraints'");
            }

            execute(connection, """
                    INSERT INTO review_queue
                        (question_id, wrong_count, review_count, next_review_at, interval_days)
                    VALUES ('q-constraints', 0, 0, '2026-07-27T12:34:56Z', 1)
                    """);
            for (int interval : List.of(1, 3, 7, 14)) {
                assertThat(execute(connection,
                        "UPDATE review_queue SET interval_days = " + interval
                                + " WHERE question_id = 'q-constraints'")).isEqualTo(1);
            }
            for (int interval : List.of(0, 2, 15)) {
                assertSqlRejected(connection,
                        "UPDATE review_queue SET interval_days = " + interval
                                + " WHERE question_id = 'q-constraints'");
            }
            assertSqlRejected(connection,
                    "UPDATE review_queue SET wrong_count = -1 WHERE question_id = 'q-constraints'");
            assertSqlRejected(connection,
                    "UPDATE review_queue SET review_count = -1 WHERE question_id = 'q-constraints'");

            execute(connection, """
                    INSERT INTO judgements
                        (id, attempt_id, sequence_no, started_at, status)
                    VALUES ('j-constraints', 'a-constraints', 1,
                            '2026-07-26T12:34:56Z', 'QUEUED')
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO judgements
                        (id, attempt_id, sequence_no, started_at, status)
                    VALUES ('j-duplicate-sequence', 'a-constraints', 1,
                            '2026-07-26T12:34:57Z', 'QUEUED')
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO judgements
                        (id, attempt_id, sequence_no, started_at, status)
                    VALUES ('j-zero-sequence', 'a-constraints', 0,
                            '2026-07-26T12:34:57Z', 'QUEUED')
                    """);
            assertNegativeJudgementMetricRejected(connection, "passed_count", 2);
            assertNegativeJudgementMetricRejected(connection, "failed_count", 2);
            assertNegativeJudgementMetricRejected(connection, "duration_millis", 2);

            execute(connection, """
                    INSERT INTO plan_days
                        (id, plan_id, day_number, plan_date, theme, completed)
                    VALUES ('day-constraints', 'plan-constraints', 1,
                            '2026-07-26', 'Theme', 0)
                    """);
            assertThat(execute(connection,
                    "UPDATE plan_days SET day_number = 14, completed = 1 "
                            + "WHERE id = 'day-constraints'")).isEqualTo(1);
            assertSqlRejected(connection,
                    "UPDATE plan_days SET day_number = 0 WHERE id = 'day-constraints'");
            assertSqlRejected(connection,
                    "UPDATE plan_days SET day_number = 15 WHERE id = 'day-constraints'");
            assertSqlRejected(connection,
                    "UPDATE plan_days SET completed = 2 WHERE id = 'day-constraints'");
            assertSqlRejected(connection, """
                    INSERT INTO plan_days
                        (id, plan_id, day_number, plan_date, theme, completed)
                    VALUES ('day-duplicate-number', 'plan-constraints', 14,
                            '2026-07-27', 'Duplicate', 0)
                    """);

            execute(connection, """
                    INSERT INTO plan_tasks
                        (id, plan_day_id, task_order, task_type, title, target_count, completed_count)
                    VALUES ('task-constraints', 'day-constraints', 1, 'CODING', 'Task', 2, 0)
                    """);
            assertThat(execute(connection,
                    "UPDATE plan_tasks SET completed_count = 2 WHERE id = 'task-constraints'"))
                    .isEqualTo(1);
            assertSqlRejected(connection,
                    "UPDATE plan_tasks SET completed_count = -1 WHERE id = 'task-constraints'");
            assertSqlRejected(connection,
                    "UPDATE plan_tasks SET completed_count = 3 WHERE id = 'task-constraints'");
            assertSqlRejected(connection,
                    "UPDATE plan_tasks SET target_count = -1 WHERE id = 'task-constraints'");
            assertSqlRejected(connection, """
                    INSERT INTO plan_tasks
                        (id, plan_day_id, task_order, task_type, title, target_count, completed_count)
                    VALUES ('task-order-zero', 'day-constraints', 0, 'CODING', 'Task', 0, 0)
                    """);
            assertSqlRejected(connection, """
                    INSERT INTO plan_tasks
                        (id, plan_day_id, task_order, task_type, title, target_count, completed_count)
                    VALUES ('task-duplicate-order', 'day-constraints', 1,
                            'CODING', 'Duplicate', 0, 0)
                    """);
        }
    }

    @Test
    void databaseConstraintsRejectDirectIllegalStateTransitions(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        SessionRepository sessions = new SessionRepository();
        AttemptRepository attempts = new AttemptRepository();
        JudgementRepository judgements = new JudgementRepository();

        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            sessions.insert(connection, session("s-1"));
            attempts.insert(connection, attempt("a-1", "s-1", "q-1"));
            judgements.append(connection, "j-1", "a-1", NOW, JudgementStatus.QUEUED);
            return null;
        });

        try (Connection connection = database.openConnection()) {
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE sessions SET status = 'COMPLETED' WHERE id = 's-1'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("illegal session state transition");
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE attempts SET status = 'FINISHED' WHERE id = 'a-1'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("illegal attempt state transition");
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE judgements SET status = 'PASSED' WHERE id = 'j-1'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("illegal judgement state transition");
        }
    }

    @Test
    void databaseAllowsOnlyDesignedInitialStates(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("initial-states.db"));
        try (Connection connection = database.openConnection()) {
            insertQuestion(connection, "q-initial");
            for (SessionStatus status : SessionStatus.values()) {
                String sql = """
                        INSERT INTO sessions
                            (id, mode, seed, started_at, status, config_json)
                        VALUES ('session-%s', 'MIXED', 1, '2026-07-26T12:34:56Z', '%s', '{}')
                        """.formatted(status, status);
                assertInsertAllowedExactlyFor(connection, sql, status == SessionStatus.CREATED);
            }
            execute(connection, """
                    INSERT INTO sessions
                        (id, mode, seed, started_at, status, config_json)
                    VALUES ('attempt-parent', 'MIXED', 1, '2026-07-26T12:34:56Z', 'CREATED', '{}')
                    """);
            for (AttemptStatus status : AttemptStatus.values()) {
                String sql = """
                        INSERT INTO attempts
                            (id, session_id, question_id, started_at, status, answer_unlocked)
                        VALUES ('attempt-%s', 'attempt-parent', 'q-initial',
                                '2026-07-26T12:34:56Z', '%s', 0)
                        """.formatted(status, status);
                assertInsertAllowedExactlyFor(connection, sql, status == AttemptStatus.CREATED);
            }
            for (JudgementStatus status : JudgementStatus.values()) {
                String sql = """
                        INSERT INTO judgements
                            (id, attempt_id, sequence_no, started_at, status)
                        VALUES ('judgement-%s', 'attempt-CREATED', %d,
                                '2026-07-26T12:34:56Z', '%s')
                        """.formatted(status, status.ordinal() + 1, status);
                assertInsertAllowedExactlyFor(connection, sql, status == JudgementStatus.QUEUED);
            }
        }
    }

    @ParameterizedTest(name = "session direct SQL {0}->{1} legal={2}")
    @MethodSource("sessionTransitionCases")
    void databaseSessionTransitionsMatchStateMachine(
            SessionStatus from, SessionStatus to, boolean legal, @TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("session-state.db"));
        try (Connection connection = database.openConnection()) {
            execute(connection, """
                    INSERT INTO sessions
                        (id, mode, seed, started_at, status, config_json)
                    VALUES ('s-state', 'MIXED', 1, '2026-07-26T12:34:56Z', 'CREATED', '{}')
                    """);
            advanceSessionTo(connection, from);
            assertDirectTransition(connection, "sessions", "s-state", to.name(), legal);
        }
    }

    @ParameterizedTest(name = "attempt direct SQL {0}->{1} legal={2}")
    @MethodSource("attemptTransitionCases")
    void databaseAttemptTransitionsMatchStateMachine(
            AttemptStatus from, AttemptStatus to, boolean legal, @TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("attempt-state.db"));
        try (Connection connection = database.openConnection()) {
            insertQuestion(connection, "q-state");
            execute(connection, """
                    INSERT INTO sessions
                        (id, mode, seed, started_at, status, config_json)
                    VALUES ('s-state', 'MIXED', 1, '2026-07-26T12:34:56Z', 'CREATED', '{}')
                    """);
            execute(connection, """
                    INSERT INTO attempts
                        (id, session_id, question_id, started_at, status, answer_unlocked)
                    VALUES ('a-state', 's-state', 'q-state',
                            '2026-07-26T12:34:56Z', 'CREATED', 0)
                    """);
            advanceAttemptTo(connection, from);
            assertDirectTransition(connection, "attempts", "a-state", to.name(), legal);
        }
    }

    @ParameterizedTest(name = "judgement direct SQL {0}->{1} legal={2}")
    @MethodSource("judgementTransitionCases")
    void databaseJudgementTransitionsMatchStateMachine(
            JudgementStatus from, JudgementStatus to, boolean legal, @TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("judgement-state.db"));
        seedAttemptAndJudgement(database);
        try (Connection connection = database.openConnection()) {
            advanceJudgementTo(connection, from);
            assertDirectTransition(connection, "judgements", "j-1", to.name(), legal);
        }
    }

    @Test
    void backsUpOnlyPreExistingDatabaseBeforeMigration(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("training.db");
        try (Connection ignored = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            // A valid pre-existing version-zero database.
        }
        TrainingDatabase database = new TrainingDatabase(path);

        database.migrate();

        Path backup = database.lastBackup().orElseThrow();
        assertThat(backup).exists();
        try (Connection backupConnection = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertThat(pragmaText(backupConnection, "integrity_check")).isEqualTo("ok");
            assertThat(pragmaLong(backupConnection, "user_version")).isZero();
        }
        database.migrate();
        assertThat(database.lastBackup()).isEmpty();
    }

    @Test
    void backupIncludesCommittedPagesStillResidentInWal(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("training.db");
        try (Connection keeper = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = keeper.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA wal_autocheckpoint = 0");
            statement.execute("PRAGMA application_id = 424242");
            Path wal = path.resolveSibling(path.getFileName() + "-wal");
            assertThat(wal).exists();
            assertThat(Files.size(wal)).isPositive();

            TrainingDatabase database = new TrainingDatabase(path);
            database.migrate();

            try (Connection backup = DriverManager.getConnection(
                    "jdbc:sqlite:" + database.lastBackup().orElseThrow())) {
                assertThat(pragmaLong(backup, "application_id")).isEqualTo(424242);
                assertThat(pragmaText(backup, "integrity_check")).isEqualTo("ok");
            }
        }
    }

    @Test
    void refusesNewerSchemaWithoutCreatingBackup(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("training.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 2");
        }
        TrainingDatabase database = new TrainingDatabase(path);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(database::migrate)
                .withMessageContaining("newer");
        assertThat(database.lastBackup()).isEmpty();
        assertThat(backupFiles(directory)).isEmpty();
    }

    @Test
    void repairsMissingUserVersionWhenFlywayHistoryIsCurrentWithoutBackup(@TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("training.db");
        TrainingDatabase database = migrated(path);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 0");
        }

        database.migrate();

        assertThat(database.lastBackup()).isEmpty();
        assertThat(backupFiles(directory)).isEmpty();
        try (Connection connection = database.openConnection()) {
            assertThat(pragmaLong(connection, "user_version")).isEqualTo(1);
        }
    }

    @Test
    void refusesDatabaseThatClaimsCurrentVersionWithoutFlywaySchema(@TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("training.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 1");
        }
        TrainingDatabase database = new TrainingDatabase(path);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(database::migrate)
                .withMessageContaining("inconsistent");
        assertThat(database.lastBackup()).isEmpty();
        assertThat(backupFiles(directory)).isEmpty();
    }

    @Test
    void refusesCorruptDatabaseWithoutOverwritingOrBackingItUp(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("training.db");
        byte[] corrupt = "not-a-sqlite-database".getBytes(StandardCharsets.UTF_8);
        Files.write(path, corrupt);
        TrainingDatabase database = new TrainingDatabase(path);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(database::migrate)
                .withMessageContaining("integrity");
        assertThat(Files.readAllBytes(path)).isEqualTo(corrupt);
        assertThat(database.lastBackup()).isEmpty();
        assertThat(backupFiles(directory)).isEmpty();
    }

    @Test
    void rollsBackMultiRepositoryTransactionAndRepositoriesDoNotCloseConnection(@TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        SessionRepository sessions = new SessionRepository();
        AttemptRepository attempts = new AttemptRepository();
        database.inTransaction(connection -> {
            insertQuestion(connection, "q-rollback");
            return null;
        });

        assertThatThrownBy(() -> database.inTransaction(connection -> {
            sessions.insert(connection, session("s-rollback"));
            attempts.insert(connection, attempt("a-rollback", "s-rollback", "q-rollback"));
            throw new IOException("force rollback");
        })).isInstanceOf(IOException.class);

        try (Connection connection = database.openConnection()) {
            assertThat(sessions.findById(connection, "s-rollback")).isEmpty();
            assertThat(attempts.findById(connection, "a-rollback")).isEmpty();
            connection.setAutoCommit(false);
            sessions.insert(connection, session("s-caller"));
            assertThat(connection.isClosed()).isFalse();
            connection.rollback();
            connection.setAutoCommit(true);
            assertThat(sessions.findById(connection, "s-caller")).isEmpty();
        }
    }

    @Test
    void failedFlywayMigrationRollsBackAllUserDdlButMayRetainHistoryTable(@TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("failing-migration.db");
        var dataSource = TrainingDatabase.configuredDataSource(path);

        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/failure")
                .load()
                .migrate())
                .hasStackTraceContaining("fixture_missing_table");

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableNames(connection))
                    .contains("flyway_schema_history")
                    .doesNotContain("should_rollback_parent", "should_rollback_child");
        }
    }

    @Test
    void appendsJudgementsWithPerAttemptSequenceAndNeverOverwritesFirst(@TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        SessionRepository sessions = new SessionRepository();
        AttemptRepository attempts = new AttemptRepository();
        JudgementRepository judgements = new JudgementRepository();

        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            sessions.insert(connection, session("s-1"));
            attempts.insert(connection, attempt("a-1", "s-1", "q-1"));
            JudgementRepository.Judgement first = judgements.append(
                    connection, "j-1", "a-1", NOW, JudgementStatus.QUEUED);
            JudgementRepository.Judgement second = judgements.append(
                    connection, "j-2", "a-1", NOW.plusSeconds(1), JudgementStatus.QUEUED);

            assertThat(first.sequenceNo()).isEqualTo(1);
            assertThat(second.sequenceNo()).isEqualTo(2);
            return null;
        });

        try (Connection connection = database.openConnection()) {
            List<JudgementRepository.Judgement> stored = judgements.findByAttempt(connection, "a-1");
            assertThat(stored).extracting(JudgementRepository.Judgement::id)
                    .containsExactly("j-1", "j-2");
            assertThat(stored).extracting(JudgementRepository.Judgement::sequenceNo)
                    .containsExactly(1, 2);
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE judgements SET sequence_no = 99 WHERE id = 'j-1'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
            assertThat(judgements.findById(connection, "j-1")).isPresent();
        }
    }

    @Test
    void insertOrReplaceCannotReplaceJudgementWithExistingId(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        seedAttemptAndJudgement(database);

        try (Connection connection = database.openConnection()) {
            assertThatThrownBy(() -> execute(connection, """
                    INSERT OR REPLACE INTO judgements
                        (id, attempt_id, sequence_no, started_at, status)
                    VALUES ('j-1', 'a-1', 2, '2026-07-26T12:35:00Z', 'QUEUED')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("judgement id already exists");
            assertThat(new JudgementRepository().findById(connection, "j-1").orElseThrow().sequenceNo())
                    .isEqualTo(1);
        }
    }

    @Test
    void insertOrReplaceCannotReplaceJudgementWithExistingAttemptSequence(@TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        seedAttemptAndJudgement(database);

        try (Connection connection = database.openConnection()) {
            assertThatThrownBy(() -> execute(connection, """
                    INSERT OR REPLACE INTO judgements
                        (id, attempt_id, sequence_no, started_at, status)
                    VALUES ('j-replacement', 'a-1', 1, '2026-07-26T12:35:00Z', 'QUEUED')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("judgement sequence already exists");
            assertThat(new JudgementRepository().findById(connection, "j-1")).isPresent();
            assertThat(new JudgementRepository().findById(connection, "j-replacement")).isEmpty();
        }
    }

    @Test
    void explicitDeleteCannotRemoveJudgement(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        seedAttemptAndJudgement(database);

        try (Connection connection = database.openConnection()) {
            assertThatThrownBy(() -> execute(connection, "DELETE FROM judgements WHERE id = 'j-1'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
            assertThat(new JudgementRepository().findById(connection, "j-1")).isPresent();
        }
    }

    @Test
    void immediateWriteTransactionsSerializeConcurrentJudgementAppends(@TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            new SessionRepository().insert(connection, session("s-1"));
            new AttemptRepository().insert(connection, attempt("a-1", "s-1", "q-1"));
            return null;
        });

        JudgementRepository judgements = new JudgementRepository();
        CyclicBarrier simultaneousStart = new CyclicBarrier(2);
        CountDownLatch firstHasWriteLock = new CountDownLatch(1);
        CountDownLatch secondIsAttemptingWriteTransaction = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JudgementRepository.Judgement> first = executor.submit(() -> {
                simultaneousStart.await(5, TimeUnit.SECONDS);
                return database.inWriteTransaction(connection -> {
                    firstHasWriteLock.countDown();
                    if (!secondIsAttemptingWriteTransaction.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Second writer did not attempt transaction");
                    }
                    Thread.sleep(150);
                    return judgements.append(
                            connection, "j-concurrent-1", "a-1", NOW, JudgementStatus.QUEUED);
                });
            });
            Future<JudgementRepository.Judgement> second = executor.submit(() -> {
                simultaneousStart.await(5, TimeUnit.SECONDS);
                if (!firstHasWriteLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First writer did not acquire transaction");
                }
                secondIsAttemptingWriteTransaction.countDown();
                return database.inWriteTransaction(connection -> judgements.append(
                        connection, "j-concurrent-2", "a-1", NOW.plusSeconds(1),
                        JudgementStatus.QUEUED));
            });

            assertThat(List.of(first.get(10, TimeUnit.SECONDS).sequenceNo(),
                    second.get(10, TimeUnit.SECONDS).sequenceNo()))
                    .containsExactlyInAnyOrder(1, 2);
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = database.openConnection()) {
            assertThat(judgements.findByAttempt(connection, "a-1"))
                    .extracting(JudgementRepository.Judgement::id)
                    .containsExactly("j-concurrent-1", "j-concurrent-2");
        }
    }

    @Test
    void tenConcurrentWritersAllSucceedWithoutDataLoss(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            new SessionRepository().insert(connection, session("s-1"));
            new AttemptRepository().insert(connection, attempt("a-1", "s-1", "q-1"));
            return null;
        });

        int threadCount = 10;
        JudgementRepository judgements = new JudgementRepository();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    JudgementRepository.Judgement judgement = database.inWriteTransaction(connection ->
                            judgements.append(connection, "j-stress-" + index, "a-1",
                                    NOW.plusSeconds(index), JudgementStatus.QUEUED));
                    return judgement.sequenceNo();
                }));
            }

            List<Integer> sequenceNumbers = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                sequenceNumbers.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(sequenceNumbers).hasSize(threadCount);
            assertThat(sequenceNumbers).doesNotHaveDuplicates();
            assertThat(sequenceNumbers).allSatisfy(seq ->
                    assertThat(seq).isBetween(1, threadCount));
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = database.openConnection()) {
            List<JudgementRepository.Judgement> stored = judgements.findByAttempt(connection, "a-1");
            assertThat(stored).hasSize(threadCount);
            assertThat(stored).extracting(JudgementRepository.Judgement::sequenceNo)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void freezesTerminalJudgementPayloadAndRejectsPrematureResultMutation(@TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        SessionRepository sessions = new SessionRepository();
        AttemptRepository attempts = new AttemptRepository();
        JudgementRepository judgements = new JudgementRepository();

        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            sessions.insert(connection, session("s-1"));
            attempts.insert(connection, attempt("a-1", "s-1", "q-1"));
            judgements.append(connection, "j-1", "a-1", NOW, JudgementStatus.QUEUED);
            judgements.transition(connection, "j-1", JudgementStatus.RUNNING, NOW.plusSeconds(1));
            judgements.recordResult(
                    connection, "j-1", JudgementStatus.PASSED, NOW.plusSeconds(2),
                    0, 7, 0, 123L, "original stdout", "original stderr");
            judgements.append(connection, "j-2", "a-1", NOW.plusSeconds(3), JudgementStatus.QUEUED);
            judgements.transition(connection, "j-2", JudgementStatus.RUNNING, NOW.plusSeconds(4));
            return null;
        });

        try (Connection connection = database.openConnection()) {
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE judgements SET stdout_excerpt = 'tampered' WHERE id = 'j-1'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("terminal judgement is immutable");
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE judgements SET exit_code = 99 WHERE id = 'j-2'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("result fields require terminal transition");

            JudgementRepository.Judgement first = judgements.findById(connection, "j-1").orElseThrow();
            assertThat(first.status()).isEqualTo(JudgementStatus.PASSED);
            assertThat(first.exitCode()).isZero();
            assertThat(first.passedCount()).isEqualTo(7);
            assertThat(first.failedCount()).isZero();
            assertThat(first.durationMillis()).isEqualTo(123L);
            assertThat(first.stdoutExcerpt()).isEqualTo("original stdout");
            assertThat(first.stderrExcerpt()).isEqualTo("original stderr");
            assertThat(judgements.findById(connection, "j-2")).isPresent();
        }
    }

    @Test
    void persistsUtcTimestampsAndEnforcesRepositoryStateTransitions(@TempDir Path directory)
            throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        SessionRepository sessions = new SessionRepository();
        AttemptRepository attempts = new AttemptRepository();
        JudgementRepository judgements = new JudgementRepository();

        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            sessions.insert(connection, session("s-1"));
            sessions.transition(connection, "s-1", SessionStatus.RUNNING, NOW.plusSeconds(1));
            attempts.insert(connection, attempt("a-1", "s-1", "q-1"));
            attempts.transition(connection, "a-1", AttemptStatus.IN_PROGRESS, NOW.plusSeconds(2));
            JudgementRepository.Judgement judgement = judgements.append(
                    connection, "j-1", "a-1", NOW, JudgementStatus.QUEUED);
            judgements.transition(connection, judgement.id(), JudgementStatus.RUNNING, NOW.plusSeconds(3));
            return null;
        });

        try (Connection connection = database.openConnection()) {
            assertThat(singleText(connection,
                    "SELECT started_at FROM sessions WHERE id = 's-1'"))
                    .isEqualTo("2026-07-26T12:34:56Z");
            assertThat(sessions.findById(connection, "s-1").orElseThrow().status())
                    .isEqualTo(SessionStatus.RUNNING);
            assertThat(attempts.findById(connection, "a-1").orElseThrow().status())
                    .isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(judgements.findById(connection, "j-1").orElseThrow().status())
                    .isEqualTo(JudgementStatus.RUNNING);
            assertThatThrownBy(() ->
                    sessions.transition(connection, "s-1", SessionStatus.CREATED, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNNING->CREATED");
        }
    }

    @Test
    void storesOralReviewAndPlanRecordsWithinCallerTransaction(@TempDir Path directory) throws Exception {
        TrainingDatabase database = migrated(directory.resolve("training.db"));
        SessionRepository sessions = new SessionRepository();
        AttemptRepository attempts = new AttemptRepository();
        ReviewRepository reviews = new ReviewRepository();
        PlanRepository plans = new PlanRepository();

        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            sessions.insert(connection, session("s-1"));
            attempts.insert(connection, attempt("a-1", "s-1", "q-1"));
            reviews.insertOralScore(connection,
                    new ReviewRepository.OralScore("a-1", 2, 1, 2, 1, 2));
            reviews.saveReview(connection, new ReviewRepository.ReviewEntry(
                    "q-1", 1, 0, "FAILED", NOW, NOW.plusSeconds(86_400), 1));
            plans.insertDay(connection, new PlanRepository.PlanDay(
                    "d-1", "plan-1", 1, LocalDate.of(2026, 7, 26), "Foundations", false));
            plans.insertTask(connection, new PlanRepository.PlanTask(
                    "t-1", "d-1", 1, "CODING", "Solve arrays", 3, 0, null));
            return null;
        });

        try (Connection connection = database.openConnection()) {
            assertThat(reviews.findOralScore(connection, "a-1").orElseThrow().total())
                    .isEqualTo(8);
            assertThat(reviews.findReview(connection, "q-1").orElseThrow().intervalDays())
                    .isEqualTo(1);
            assertThat(plans.findDays(connection, "plan-1")).hasSize(1);
            assertThat(plans.findTasks(connection, "d-1")).hasSize(1);
        }
    }

    private static Stream<Arguments> sessionTransitionCases() {
        return transitionCases(
                SessionStatus.values(),
                Set.of(
                        "CREATED->RUNNING",
                        "RUNNING->PAUSED",
                        "RUNNING->COMPLETED",
                        "RUNNING->ABORTED",
                        "PAUSED->RUNNING"));
    }

    private static Stream<Arguments> attemptTransitionCases() {
        return transitionCases(
                AttemptStatus.values(),
                Set.of(
                        "CREATED->IN_PROGRESS",
                        "IN_PROGRESS->SUBMITTED",
                        "IN_PROGRESS->SKIPPED",
                        "SUBMITTED->FINISHED"));
    }

    private static Stream<Arguments> judgementTransitionCases() {
        return transitionCases(
                JudgementStatus.values(),
                Set.of(
                        "QUEUED->RUNNING",
                        "RUNNING->PASSED",
                        "RUNNING->FAILED",
                        "RUNNING->TIMED_OUT",
                        "RUNNING->ENVIRONMENT_ERROR"));
    }

    private static <T extends Enum<T>> Stream<Arguments> transitionCases(T[] values, Set<String> legal) {
        return Stream.of(values)
                .flatMap(from -> Stream.of(values)
                        .map(to -> Arguments.of(
                                from,
                                to,
                                legal.contains(from.name() + "->" + to.name()))));
    }

    private static void assertInsertAllowedExactlyFor(Connection connection, String sql, boolean allowed)
            throws SQLException {
        if (allowed) {
            assertThat(execute(connection, sql)).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class);
        }
    }

    private static void assertSqlRejected(Connection connection, String sql) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class);
    }

    private static void assertNegativeJudgementMetricRejected(
            Connection connection, String column, int sequence) {
        assertSqlRejected(connection, """
                INSERT INTO judgements
                    (id, attempt_id, sequence_no, started_at, status, %s)
                VALUES ('j-negative-%s', 'a-constraints', %d,
                        '2026-07-26T12:34:57Z', 'QUEUED', -1)
                """.formatted(column, column, sequence));
    }

    private static void assertDirectTransition(
            Connection connection, String table, String id, String target, boolean legal) throws SQLException {
        String sql = "UPDATE " + table + " SET status = '" + target + "' WHERE id = '" + id + "'";
        if (legal) {
            assertThat(execute(connection, sql)).isEqualTo(1);
            assertThat(singleText(connection,
                    "SELECT status FROM " + table + " WHERE id = '" + id + "'")).isEqualTo(target);
        } else {
            assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class);
        }
    }

    private static void advanceSessionTo(Connection connection, SessionStatus status) throws SQLException {
        if (status == SessionStatus.CREATED) {
            return;
        }
        execute(connection, "UPDATE sessions SET status = 'RUNNING' WHERE id = 's-state'");
        if (status != SessionStatus.RUNNING) {
            execute(connection, "UPDATE sessions SET status = '" + status + "' WHERE id = 's-state'");
        }
    }

    private static void advanceAttemptTo(Connection connection, AttemptStatus status) throws SQLException {
        if (status == AttemptStatus.CREATED) {
            return;
        }
        execute(connection, "UPDATE attempts SET status = 'IN_PROGRESS' WHERE id = 'a-state'");
        if (status == AttemptStatus.SUBMITTED || status == AttemptStatus.FINISHED) {
            execute(connection, "UPDATE attempts SET status = 'SUBMITTED' WHERE id = 'a-state'");
        }
        if (status == AttemptStatus.FINISHED) {
            execute(connection, "UPDATE attempts SET status = 'FINISHED' WHERE id = 'a-state'");
        } else if (status == AttemptStatus.SKIPPED) {
            execute(connection, "UPDATE attempts SET status = 'SKIPPED' WHERE id = 'a-state'");
        }
    }

    private static void advanceJudgementTo(Connection connection, JudgementStatus status) throws SQLException {
        if (status == JudgementStatus.QUEUED) {
            return;
        }
        execute(connection, "UPDATE judgements SET status = 'RUNNING' WHERE id = 'j-1'");
        if (status != JudgementStatus.RUNNING) {
            execute(connection, "UPDATE judgements SET status = '" + status + "' WHERE id = 'j-1'");
        }
    }

    private static TrainingDatabase migrated(Path path) {
        TrainingDatabase database = new TrainingDatabase(path);
        database.migrate();
        return database;
    }

    private static SessionRepository.Session session(String id) {
        return new SessionRepository.Session(
                id, "MIXED", 42L, NOW, NOW.plusSeconds(3_600), null,
                SessionStatus.CREATED, "{}");
    }

    private static AttemptRepository.Attempt attempt(String id, String sessionId, String questionId) {
        return new AttemptRepository.Attempt(
                id, sessionId, questionId, NOW, null, null, AttemptStatus.CREATED,
                null, false, null, null, null);
    }

    private static void seedAttemptAndJudgement(TrainingDatabase database) throws Exception {
        database.inTransaction(connection -> {
            insertQuestion(connection, "q-1");
            new SessionRepository().insert(connection, session("s-1"));
            new AttemptRepository().insert(connection, attempt("a-1", "s-1", "q-1"));
            new JudgementRepository().append(connection, "j-1", "a-1", NOW, JudgementStatus.QUEUED);
            return null;
        });
    }

    private static void insertAttemptRow(
            Connection connection, String id, String sessionId, String questionId, int answerUnlocked)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO attempts
                    (id, session_id, question_id, started_at, status, answer_unlocked)
                VALUES (?, ?, ?, '2026-07-26T12:34:56Z', 'CREATED', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setString(3, questionId);
            statement.setInt(4, answerUnlocked);
            statement.executeUpdate();
        }
    }

    private static void insertQuestion(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO questions
                    (id, track, group_name, title, topic, difficulty, language,
                     source_ref, starter_ref, active)
                VALUES (?, 'CODING', 'algorithms', 'Question', 'arrays', 'BASIC',
                        'java', 'content/q.md', 'starters/q', 1)
                """)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static long pragmaLong(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String pragmaText(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            var tables = new java.util.HashSet<String>();
            while (result.next()) {
                tables.add(result.getString(1));
            }
            return tables;
        }
    }

    private static List<Path> backupFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().contains(".backup-")).toList();
        }
    }

    private static int execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static String singleText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
