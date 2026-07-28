package com.guoyongzheng.training.persistence;

import com.guoyongzheng.training.domain.SessionStatus;
import com.guoyongzheng.training.domain.StateTransitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Connection-scoped persistence for training sessions. */
public final class SessionRepository {

    public void insert(Connection connection, Session session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sessions
                    (id, mode, seed, started_at, deadline_at, finished_at, status, config_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, session.id());
            statement.setString(2, session.mode());
            statement.setLong(3, session.seed());
            statement.setString(4, utc(session.startedAt()));
            statement.setString(5, utc(session.deadlineAt()));
            statement.setString(6, utc(session.finishedAt()));
            statement.setString(7, session.status().name());
            statement.setString(8, session.configJson());
            statement.executeUpdate();
        }
    }

    public Optional<Session> findById(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM sessions WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    public void transition(Connection connection, String id, SessionStatus target, Instant transitionAt)
            throws SQLException {
        Session current = findById(connection, id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + id));
        StateTransitions.requireLegal(current.status(), target);
        boolean terminal = target == SessionStatus.COMPLETED || target == SessionStatus.ABORTED;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE sessions
                SET status = ?, finished_at = CASE WHEN ? THEN ? ELSE finished_at END
                WHERE id = ? AND status = ?
                """)) {
            statement.setString(1, target.name());
            statement.setBoolean(2, terminal);
            statement.setString(3, terminal ? utc(transitionAt) : null);
            statement.setString(4, id);
            statement.setString(5, current.status().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Concurrent session state change: " + id);
            }
        }
    }

    private static Session map(ResultSet result) throws SQLException {
        return new Session(
                result.getString("id"),
                result.getString("mode"),
                result.getLong("seed"),
                instant(result.getString("started_at")),
                instant(result.getString("deadline_at")),
                instant(result.getString("finished_at")),
                SessionStatus.valueOf(result.getString("status")),
                result.getString("config_json"));
    }

    private static String utc(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record Session(
            String id,
            String mode,
            long seed,
            Instant startedAt,
            Instant deadlineAt,
            Instant finishedAt,
            SessionStatus status,
            String configJson) {
    }
}
