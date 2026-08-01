package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.SessionRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared query helpers for reading sessions and attempts from the database.
 * Used by JudgeService, SessionQueryService, and TrainingSessionService.
 */
final class SessionQueries {
    private SessionQueries() {
    }

    static TrainingSessionService.TrainingSessionResponse readSession(Connection connection, String sessionId)
            throws SQLException {
        SessionRepository.Session session = new SessionRepository().findById(connection, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        return new TrainingSessionService.TrainingSessionResponse(
                session.id(),
                session.mode(),
                session.seed(),
                session.status().name(),
                session.startedAt(),
                session.finishedAt(),
                attempts(connection, sessionId));
    }

    static List<TrainingSessionService.TrainingAttemptCard> attempts(Connection connection, String sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.*, q.track, q.group_name, q.title, q.topic, q.difficulty, q.language,
                       q.source_ref, q.starter_ref
                FROM attempts a
                JOIN questions q ON q.id = a.question_id
                WHERE a.session_id = ?
                ORDER BY a.started_at, a.id
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                List<TrainingSessionService.TrainingAttemptCard> attempts = new ArrayList<>();
                while (result.next()) {
                    attempts.add(attemptCard(result));
                }
                return attempts;
            }
        }
    }

    static List<TrainingSessionService.TrainingAttemptCard> recentAttempts(Connection connection, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.*, q.track, q.group_name, q.title, q.topic, q.difficulty, q.language,
                       q.source_ref, q.starter_ref
                FROM attempts a
                JOIN questions q ON q.id = a.question_id
                ORDER BY COALESCE(a.submitted_at, a.started_at) DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<TrainingSessionService.TrainingAttemptCard> attempts = new ArrayList<>();
                while (result.next()) {
                    attempts.add(attemptCard(result));
                }
                return attempts;
            }
        }
    }

    static QuestionDescriptor question(Connection connection, String questionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM questions WHERE id = ?")) {
            statement.setString(1, questionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown question: " + questionId);
                }
                return new QuestionDescriptor(
                        result.getString("id"),
                        Track.valueOf(result.getString("track")),
                        result.getString("group_name"),
                        result.getString("title"),
                        result.getString("topic"),
                        result.getString("difficulty"),
                        result.getString("language"),
                        result.getString("source_ref"),
                        result.getString("starter_ref"));
            }
        }
    }

    static TrainingSessionService.TrainingAttemptCard attemptCard(ResultSet result) throws SQLException {
        return new TrainingSessionService.TrainingAttemptCard(
                result.getString("id"),
                result.getString("session_id"),
                result.getString("question_id"),
                result.getString("track"),
                result.getString("group_name"),
                result.getString("title"),
                result.getString("topic"),
                result.getString("difficulty"),
                result.getString("language"),
                result.getString("source_ref"),
                result.getString("starter_ref"),
                result.getString("status"),
                result.getString("verdict"),
                instant(result.getString("started_at")),
                instant(result.getString("submitted_at")),
                nullableLong(result, "duration_seconds"),
                result.getBoolean("answer_unlocked"),
                result.getString("sandbox_path"),
                result.getString("notes"),
                result.getString("improved_answer"));
    }

    static int scalarInt(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    static int scalarInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    static double scalarDouble(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return 0.0D;
            }
            double value = result.getDouble(1);
            return result.wasNull() ? 0.0D : value;
        }
    }

    static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
