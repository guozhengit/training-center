package com.guoyongzheng.training.persistence;

import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.StateTransitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Connection-scoped persistence for complete question attempts. */
public final class AttemptRepository {

    public void insert(Connection connection, Attempt attempt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO attempts
                    (id, session_id, question_id, started_at, submitted_at, duration_seconds,
                     status, verdict, answer_unlocked, sandbox_path, notes, improved_answer)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, attempt.id());
            statement.setString(2, attempt.sessionId());
            statement.setString(3, attempt.questionId());
            statement.setString(4, utc(attempt.startedAt()));
            statement.setString(5, utc(attempt.submittedAt()));
            nullableLong(statement, 6, attempt.durationSeconds());
            statement.setString(7, attempt.status().name());
            statement.setString(8, attempt.verdict());
            statement.setBoolean(9, attempt.answerUnlocked());
            statement.setString(10, attempt.sandboxPath());
            statement.setString(11, attempt.notes());
            statement.setString(12, attempt.improvedAnswer());
            statement.executeUpdate();
        }
    }

    public Optional<Attempt> findById(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM attempts WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public void transition(Connection connection, String id, AttemptStatus target, Instant transitionAt)
            throws SQLException {
        Attempt current = findById(connection, id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown attempt: " + id));
        StateTransitions.requireLegal(current.status(), target);
        boolean submitted = target == AttemptStatus.SUBMITTED;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE attempts
                SET status = ?, submitted_at = CASE WHEN ? THEN ? ELSE submitted_at END
                WHERE id = ? AND status = ?
                """)) {
            statement.setString(1, target.name());
            statement.setBoolean(2, submitted);
            statement.setString(3, submitted ? utc(transitionAt) : null);
            statement.setString(4, id);
            statement.setString(5, current.status().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Concurrent attempt state change: " + id);
            }
        }
    }

    public void updateLatestVerdict(Connection connection, String id, String verdict) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("UPDATE attempts SET verdict = ? WHERE id = ?")) {
            statement.setString(1, verdict);
            statement.setString(2, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Unknown attempt: " + id);
            }
        }
    }

    public void unlockAnswer(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("UPDATE attempts SET answer_unlocked = 1 WHERE id = ?")) {
            statement.setString(1, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Unknown attempt: " + id);
            }
        }
    }

    private static Attempt map(ResultSet result) throws SQLException {
        return new Attempt(
                result.getString("id"),
                result.getString("session_id"),
                result.getString("question_id"),
                instant(result.getString("started_at")),
                instant(result.getString("submitted_at")),
                nullableLong(result, "duration_seconds"),
                AttemptStatus.valueOf(result.getString("status")),
                result.getString("verdict"),
                result.getBoolean("answer_unlocked"),
                result.getString("sandbox_path"),
                result.getString("notes"),
                result.getString("improved_answer"));
    }

    private static void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
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

    public record Attempt(
            String id,
            String sessionId,
            String questionId,
            Instant startedAt,
            Instant submittedAt,
            Long durationSeconds,
            AttemptStatus status,
            String verdict,
            boolean answerUnlocked,
            String sandboxPath,
            String notes,
            String improvedAnswer) {
    }
}
