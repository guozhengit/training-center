package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.catalog.CatalogLoader;
import com.guoyongzheng.training.catalog.QuestionFilter;
import com.guoyongzheng.training.catalog.TrainingCatalog;
import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.SessionStatus;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.JudgementRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import com.guoyongzheng.training.persistence.SessionRepository;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import com.guoyongzheng.training.review.ReviewScheduler;
import com.guoyongzheng.training.selection.QuestionSelector;
import com.guoyongzheng.training.selection.SelectionRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class CliDatabaseSupport {
    private CliDatabaseSupport() {
    }

    static Path defaultDatabase(Path workspace) {
        return workspace.toAbsolutePath().normalize()
                .resolve("output/training-runtime/database/training.db");
    }

    static ReadyContext ready(Path workspace, Path databasePath) {
        Path normalizedWorkspace = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath()
                .normalize();
        TrainingCatalog catalog = new CatalogLoader().load(normalizedWorkspace);
        TrainingDatabase database = new TrainingDatabase(
                databasePath == null ? defaultDatabase(normalizedWorkspace) : databasePath);
        database.migrate();
        try (Connection connection = database.openConnection()) {
            syncQuestions(connection, catalog.find(null));
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot sync question catalog", exception);
        }
        return new ReadyContext(normalizedWorkspace, catalog, database);
    }

    static List<QuestionDescriptor> selectQuestions(
            TrainingCatalog catalog,
            Connection connection,
            Track track,
            String questionIds,
            int count,
            long seed,
            String difficulty,
            String topic,
            String language) throws SQLException {
        if (questionIds != null && !questionIds.isBlank()) {
            List<QuestionDescriptor> selected = new ArrayList<>();
            for (String id : splitIds(questionIds)) {
                QuestionDescriptor question = catalog.require(id);
                if (track != null && question.track() != track) {
                    throw new IllegalArgumentException("Question " + id + " is not in track " + track);
                }
                selected.add(question);
            }
            return List.copyOf(selected);
        }

        QuestionFilter filter = new QuestionFilter(track, null, topic, difficulty, language);
        List<QuestionDescriptor> candidates = catalog.find(filter);
        Map<String, ReviewRepository.ReviewEntry> reviews = reviewMap(connection);
        return new QuestionSelector(Clock.systemUTC()).select(
                new SelectionRequest(filter, count, seed, true, false, Set.of()),
                candidates,
                reviews);
    }

    static void maybeCompleteSession(Connection connection, String sessionId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM attempts
                WHERE session_id = ? AND status NOT IN ('FINISHED', 'SKIPPED')
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) {
                    return;
                }
            }
        }
        SessionRepository sessions = new SessionRepository();
        SessionRepository.Session session = sessions.findById(connection, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        if (session.status() == SessionStatus.RUNNING) {
            sessions.transition(connection, sessionId, SessionStatus.COMPLETED, now);
        }
    }

    static void updateAttemptSubmission(
            Connection connection,
            String attemptId,
            String verdict,
            long durationSeconds,
            String notes,
            String improvedAnswer) throws SQLException {
        AttemptRepository attempts = new AttemptRepository();
        AttemptRepository.Attempt attempt = attempts.findById(connection, attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown attempt: " + attemptId));
        if (attempt.status() == AttemptStatus.CREATED) {
            attempts.transition(connection, attemptId, AttemptStatus.IN_PROGRESS, Instant.now());
            attempt = attempts.findById(connection, attemptId).orElseThrow();
        }
        if (attempt.status() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Attempt is not in progress: " + attemptId);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE attempts
                SET status = 'SUBMITTED',
                    submitted_at = ?,
                    duration_seconds = ?,
                    verdict = ?,
                    notes = ?,
                    improved_answer = ?
                WHERE id = ? AND status = 'IN_PROGRESS'
                """)) {
            statement.setString(1, Instant.now().toString());
            statement.setLong(2, durationSeconds);
            statement.setString(3, verdict);
            statement.setString(4, notes);
            statement.setString(5, improvedAnswer);
            statement.setString(6, attemptId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Cannot submit attempt: " + attemptId);
            }
        }
    }

    static void finishAttemptAndReview(
            Connection connection,
            String attemptId,
            boolean passed,
            Integer oralTotal,
            Instant now) throws SQLException {
        AttemptRepository attempts = new AttemptRepository();
        AttemptRepository.Attempt attempt = attempts.findById(connection, attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown attempt: " + attemptId));
        if (attempt.status() == AttemptStatus.SUBMITTED) {
            attempts.transition(connection, attemptId, AttemptStatus.FINISHED, now);
        }
        ReviewRepository reviews = new ReviewRepository();
        Optional<ReviewRepository.ReviewEntry> current = reviews.findReview(connection, attempt.questionId());
        ReviewRepository.ReviewEntry next = new ReviewScheduler(Clock.systemUTC()).schedule(
                attempt.questionId(),
                current.orElse(null),
                passed,
                oralTotal,
                oralTotal != null);
        reviews.saveReview(connection, next);
        maybeCompleteSession(connection, attempt.sessionId(), now);
    }

    static List<HistoryRow> history(Connection connection, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    a.id AS attempt_id,
                    a.session_id,
                    a.question_id,
                    a.status AS attempt_status,
                    COALESCE(a.verdict, '') AS verdict,
                    a.started_at,
                    a.submitted_at,
                    a.duration_seconds,
                    q.track,
                    q.title,
                    q.topic,
                    (
                        SELECT j.status
                        FROM judgements j
                        WHERE j.attempt_id = a.id
                        ORDER BY j.sequence_no DESC
                        LIMIT 1
                    ) AS latest_judgement
                FROM attempts a
                JOIN questions q ON q.id = a.question_id
                ORDER BY a.started_at DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<HistoryRow> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new HistoryRow(
                            result.getString("attempt_id"),
                            result.getString("session_id"),
                            result.getString("question_id"),
                            result.getString("track"),
                            result.getString("title"),
                            result.getString("topic"),
                            result.getString("attempt_status"),
                            emptyToNull(result.getString("verdict")),
                            result.getString("latest_judgement"),
                            result.getString("started_at"),
                            result.getString("submitted_at"),
                            nullableLong(result, "duration_seconds")));
                }
                return List.copyOf(rows);
            }
        }
    }

    static void writeExport(Path output, String format, List<HistoryRow> rows) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        String normalized = format.toLowerCase(Locale.ROOT);
        String content = switch (normalized) {
            case "csv" -> toCsv(rows);
            case "md", "markdown" -> toMarkdown(rows);
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        };
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static void syncQuestions(Connection connection, List<QuestionDescriptor> questions) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO questions
                    (id, track, group_name, title, topic, difficulty, language, source_ref, starter_ref, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(id) DO UPDATE SET
                    track = excluded.track,
                    group_name = excluded.group_name,
                    title = excluded.title,
                    topic = excluded.topic,
                    difficulty = excluded.difficulty,
                    language = excluded.language,
                    source_ref = excluded.source_ref,
                    starter_ref = excluded.starter_ref,
                    active = 1
                """)) {
            for (QuestionDescriptor question : questions) {
                statement.setString(1, question.id());
                statement.setString(2, question.track().name());
                statement.setString(3, question.groupName());
                statement.setString(4, question.title());
                statement.setString(5, question.topic());
                statement.setString(6, question.difficulty());
                statement.setString(7, question.language());
                statement.setString(8, question.sourceRef());
                statement.setString(9, question.starterRef());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Map<String, ReviewRepository.ReviewEntry> reviewMap(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM review_queue");
             ResultSet result = statement.executeQuery()) {
            Map<String, ReviewRepository.ReviewEntry> reviews = new LinkedHashMap<>();
            while (result.next()) {
                reviews.put(result.getString("question_id"), new ReviewRepository.ReviewEntry(
                        result.getString("question_id"),
                        result.getInt("wrong_count"),
                        result.getInt("review_count"),
                        result.getString("last_result"),
                        instant(result.getString("last_attempt_at")),
                        instant(result.getString("next_review_at")),
                        result.getInt("interval_days")));
            }
            return reviews;
        }
    }

    private static Set<String> splitIds(String questionIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : questionIds.split(",")) {
            String id = raw.trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("question ids must not be empty");
        }
        return ids;
    }

    private static String toCsv(List<HistoryRow> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("attemptId,sessionId,questionId,track,title,status,verdict,latestJudgement,durationSeconds");
        for (HistoryRow row : rows) {
            lines.add(String.join(",",
                    csv(row.attemptId()),
                    csv(row.sessionId()),
                    csv(row.questionId()),
                    csv(row.track()),
                    csv(row.title()),
                    csv(row.status()),
                    csv(row.verdict()),
                    csv(row.latestJudgement()),
                    csv(row.durationSeconds() == null ? "" : row.durationSeconds().toString())));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String toMarkdown(List<HistoryRow> rows) {
        StringBuilder builder = new StringBuilder("# Training History").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("| Attempt | Question | Track | Status | Verdict | Latest Judge | Duration |").append(System.lineSeparator());
        builder.append("|---|---|---|---|---|---|---|").append(System.lineSeparator());
        for (HistoryRow row : rows) {
            builder.append("| ")
                    .append(row.attemptId()).append(" | ")
                    .append(row.questionId()).append(" ").append(escapePipe(row.title())).append(" | ")
                    .append(row.track()).append(" | ")
                    .append(row.status()).append(" | ")
                    .append(row.verdict() == null ? "-" : row.verdict()).append(" | ")
                    .append(row.latestJudgement() == null ? "-" : row.latestJudgement()).append(" | ")
                    .append(row.durationSeconds() == null ? "-" : row.durationSeconds()).append(" |")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String escapePipe(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    record ReadyContext(Path workspace, TrainingCatalog catalog, TrainingDatabase database) {
    }

    record HistoryRow(
            String attemptId,
            String sessionId,
            String questionId,
            String track,
            String title,
            String topic,
            String status,
            String verdict,
            String latestJudgement,
            String startedAt,
            String submittedAt,
            Long durationSeconds) {
    }
}
