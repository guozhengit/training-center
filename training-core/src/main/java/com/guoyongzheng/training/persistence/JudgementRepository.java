package com.guoyongzheng.training.persistence;

import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.domain.StateTransitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Append-only submission history with mutable execution state and immutable identity. */
public final class JudgementRepository {

    public Judgement append(
            Connection connection,
            String id,
            String attemptId,
            Instant startedAt,
            JudgementStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO judgements (id, attempt_id, sequence_no, started_at, status)
                SELECT ?, ?, COALESCE(MAX(sequence_no), 0) + 1, ?, ?
                FROM judgements WHERE attempt_id = ?
                """)) {
            statement.setString(1, id);
            statement.setString(2, attemptId);
            statement.setString(3, utc(startedAt));
            statement.setString(4, status.name());
            statement.setString(5, attemptId);
            statement.executeUpdate();
        }
        return findById(connection, id).orElseThrow();
    }

    public Optional<Judgement> findById(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM judgements WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public List<Judgement> findByAttempt(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM judgements
                WHERE attempt_id = ?
                ORDER BY sequence_no
                """)) {
            statement.setString(1, attemptId);
            try (ResultSet result = statement.executeQuery()) {
                List<Judgement> judgements = new ArrayList<>();
                while (result.next()) {
                    judgements.add(map(result));
                }
                return List.copyOf(judgements);
            }
        }
    }

    public void transition(Connection connection, String id, JudgementStatus target, Instant transitionAt)
            throws SQLException {
        Judgement current = findById(connection, id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown judgement: " + id));
        StateTransitions.requireLegal(current.status(), target);
        boolean terminal = target != JudgementStatus.RUNNING;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE judgements
                SET status = ?, finished_at = CASE WHEN ? THEN ? ELSE finished_at END
                WHERE id = ? AND status = ?
                """)) {
            statement.setString(1, target.name());
            statement.setBoolean(2, terminal);
            statement.setString(3, terminal ? utc(transitionAt) : null);
            statement.setString(4, id);
            statement.setString(5, current.status().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Concurrent judgement state change: " + id);
            }
        }
    }

    public void recordResult(
            Connection connection,
            String id,
            JudgementStatus target,
            Instant finishedAt,
            Integer exitCode,
            Integer passedCount,
            Integer failedCount,
            Long durationMillis,
            String stdoutExcerpt,
            String stderrExcerpt) throws SQLException {
        Judgement current = findById(connection, id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown judgement: " + id));
        StateTransitions.requireLegal(current.status(), target);
        if (target == JudgementStatus.RUNNING) {
            throw new IllegalArgumentException("recordResult requires a terminal status");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE judgements
                SET status = ?, finished_at = ?, exit_code = ?, passed_count = ?,
                    failed_count = ?, duration_millis = ?, stdout_excerpt = ?, stderr_excerpt = ?
                WHERE id = ? AND status = ?
                """)) {
            statement.setString(1, target.name());
            statement.setString(2, utc(finishedAt));
            nullableInt(statement, 3, exitCode);
            nullableInt(statement, 4, passedCount);
            nullableInt(statement, 5, failedCount);
            nullableLong(statement, 6, durationMillis);
            statement.setString(7, stdoutExcerpt);
            statement.setString(8, stderrExcerpt);
            statement.setString(9, id);
            statement.setString(10, current.status().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Concurrent judgement state change: " + id);
            }
        }
    }

    /**
     * Marks judgements still in RUNNING that started before {@code olderThan}
     * as ENVIRONMENT_ERROR. Used at startup to recover rows left dangling by a
     * crash or forced shutdown; the single UPDATE keeps the database triggers
     * satisfied (RUNNING -> terminal with result fields in the same statement).
     *
     * @return the number of recovered judgements
     */
    public int recoverStaleRunning(
            Connection connection, Instant olderThan, Instant recoveredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE judgements
                SET status = ?, finished_at = ?, stderr_excerpt = ?
                WHERE status = ? AND started_at < ?
                """)) {
            statement.setString(1, JudgementStatus.ENVIRONMENT_ERROR.name());
            statement.setString(2, utc(recoveredAt));
            statement.setString(3, "interrupted before completion; recovered after restart");
            statement.setString(4, JudgementStatus.RUNNING.name());
            statement.setString(5, utc(olderThan));
            return statement.executeUpdate();
        }
    }

    private static Judgement map(ResultSet result) throws SQLException {
        return new Judgement(
                result.getString("id"),
                result.getString("attempt_id"),
                result.getInt("sequence_no"),
                instant(result.getString("started_at")),
                instant(result.getString("finished_at")),
                JudgementStatus.valueOf(result.getString("status")),
                nullableInt(result, "exit_code"),
                nullableInt(result, "passed_count"),
                nullableInt(result, "failed_count"),
                nullableLong(result, "duration_millis"),
                result.getString("stdout_excerpt"),
                result.getString("stderr_excerpt"));
    }

    private static void nullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String utc(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record Judgement(
            String id,
            String attemptId,
            int sequenceNo,
            Instant startedAt,
            Instant finishedAt,
            JudgementStatus status,
            Integer exitCode,
            Integer passedCount,
            Integer failedCount,
            Long durationMillis,
            String stdoutExcerpt,
            String stderrExcerpt) {
    }
}
