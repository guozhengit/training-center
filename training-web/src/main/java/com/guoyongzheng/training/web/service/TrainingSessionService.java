package com.guoyongzheng.training.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.catalog.QuestionFilter;
import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.SessionStatus;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import com.guoyongzheng.training.persistence.SessionRepository;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import com.guoyongzheng.training.review.ReviewScheduler;
import com.guoyongzheng.training.selection.QuestionSelector;
import com.guoyongzheng.training.selection.SelectionRequest;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages training session lifecycle: creation (with question selection) and attempt submission.
 * Query/stats operations are in SessionQueryService; judge operations are in JudgeService.
 */
@Service
public class TrainingSessionService {
    private static final int MAX_SESSION_COUNT = 20;

    private final TrainingDatabaseProvider databaseProvider;
    private final ObjectMapper objectMapper;
    private final SessionRepository sessionRepository = new SessionRepository();
    private final AttemptRepository attemptRepository = new AttemptRepository();
    private final ReviewRepository reviewRepository = new ReviewRepository();
    private final Clock clock = Clock.systemUTC();

    public TrainingSessionService(TrainingDatabaseProvider databaseProvider, ObjectMapper objectMapper) {
        this.databaseProvider = databaseProvider;
        this.objectMapper = objectMapper;
    }

    public TrainingSessionResponse createSession(CreateSessionRequest request) {
        TrainingDatabase database = databaseProvider.readyDatabase();
        List<QuestionDescriptor> catalogQuestions = databaseProvider.loadCatalog();
        Track track = parseTrack(defaultText(request.track(), Track.CODING.name()));
        int count = Math.max(1, Math.min(request.count() == null ? 3 : request.count(), MAX_SESSION_COUNT));
        long seed = request.seed() == null ? System.nanoTime() : request.seed();
        Instant now = clock.instant();
        String sessionId = "web-session-" + UUID.randomUUID();

        try {
            return database.inWriteTransaction(connection -> {
                TrainingDatabaseProvider.syncQuestions(connection, catalogQuestions);
                Map<String, ReviewRepository.ReviewEntry> reviews = reviewMap(connection);
                List<QuestionDescriptor> selected = hasRequestedQuestionIds(request)
                        ? selectRequestedQuestions(catalogQuestions, track, request.questionIds(), count)
                        : new QuestionSelector(clock).select(
                                new SelectionRequest(
                                        new QuestionFilter(track, null, null, null, null),
                                        count,
                                        seed,
                                        request.prioritizeWrongAnswers() == null || request.prioritizeWrongAnswers(),
                                        Boolean.TRUE.equals(request.dueReviewOnly()),
                                        Set.of()),
                                catalogQuestions,
                                reviews);
                if (selected.isEmpty()) {
                    throw new IllegalArgumentException("No selectable questions for track: " + track.name());
                }

                sessionRepository.insert(connection, new SessionRepository.Session(
                        sessionId,
                        track.name(),
                        seed,
                        now,
                        null,
                        null,
                        SessionStatus.CREATED,
                        writeJson(new StoredSessionConfig(track.name(), count, selected.size(),
                                Boolean.TRUE.equals(request.dueReviewOnly()),
                                request.prioritizeWrongAnswers() == null || request.prioritizeWrongAnswers()))));

                for (QuestionDescriptor question : selected) {
                    String attemptId = "web-attempt-" + UUID.randomUUID();
                    attemptRepository.insert(connection, new AttemptRepository.Attempt(
                            attemptId,
                            sessionId,
                            question.id(),
                            now,
                            null,
                            null,
                            AttemptStatus.CREATED,
                            null,
                            false,
                            null,
                            null,
                            null));
                    attemptRepository.transition(connection, attemptId, AttemptStatus.IN_PROGRESS, now);
                }
                sessionRepository.transition(connection, sessionId, SessionStatus.RUNNING, now);
                return SessionQueries.readSession(connection, sessionId);
            });
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create training session", exception);
        }
    }

    public SubmitAttemptResponse submitAttempt(String attemptId, SubmitAttemptRequest request) {
        TrainingDatabase database = databaseProvider.readyDatabase();
        Instant now = clock.instant();

        try {
            return database.inWriteTransaction(connection -> {
                AttemptRepository.Attempt attempt = attemptRepository.findById(connection, attemptId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown attempt: " + attemptId));
                if (attempt.status() != AttemptStatus.IN_PROGRESS) {
                    throw new IllegalStateException("Attempt is not in progress: " + attemptId);
                }
                QuestionDescriptor question = SessionQueries.question(connection, attempt.questionId());
                ReviewRepository.OralScore oralScore = request.oralScore() == null
                        ? null : request.oralScore().toRepository(attemptId);
                Integer oralTotal = oralScore == null ? null : oralScore.total();
                // Interview discipline rule: fabricating metrics or overclaiming ownership
                // (factRestraint = 0) forces a retry regardless of the other dimensions.
                boolean factRestraintCritical = oralScore != null && oralScore.factRestraint() == 0;
                boolean passed = oralTotal == null
                        ? "PASSED".equalsIgnoreCase(defaultText(request.verdict(), "FAILED"))
                        : oralTotal >= 6 && !factRestraintCritical;
                String verdict = passed ? "PASSED" : "FAILED";

                attemptRepository.transition(connection, attemptId, AttemptStatus.SUBMITTED, now);
                updateAttemptSubmission(connection, attemptId, verdict, request.durationSeconds(),
                        Boolean.TRUE.equals(request.answerUnlocked()), request.notes(), request.improvedAnswer());
                if (oralScore != null) {
                    reviewRepository.insertOralScore(connection, oralScore);
                }
                attemptRepository.transition(connection, attemptId, AttemptStatus.FINISHED, now);

                Optional<ReviewRepository.ReviewEntry> currentReview =
                        reviewRepository.findReview(connection, attempt.questionId());
                ReviewRepository.ReviewEntry nextReview = new ReviewScheduler(clock).schedule(
                        attempt.questionId(),
                        currentReview.orElse(null),
                        passed,
                        oralTotal,
                        Boolean.TRUE.equals(request.answerUnlocked()),
                        focusDimensions(oralScore),
                        factRestraintCritical);
                reviewRepository.saveReview(connection, nextReview);

                boolean sessionCompleted = completeSessionIfReady(connection, attempt.sessionId(), now);
                return new SubmitAttemptResponse(
                        attemptId,
                        attempt.sessionId(),
                        question.id(),
                        verdict,
                        oralTotal,
                        nextReview.nextReviewAt(),
                        sessionCompleted,
                        nextReview.focusDimensions(),
                        SessionQueries.readSession(connection, attempt.sessionId()));
            });
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot submit attempt: " + attemptId, exception);
        }
    }

    // --- internal helpers ---

    private static boolean hasRequestedQuestionIds(CreateSessionRequest request) {
        return request.questionIds() != null
                && request.questionIds().stream().anyMatch(value -> value != null && !value.isBlank());
    }

    private static List<QuestionDescriptor> selectRequestedQuestions(
            List<QuestionDescriptor> catalogQuestions,
            Track track,
            List<String> requestedQuestionIds,
            int limit) {
        Map<String, QuestionDescriptor> byId = new LinkedHashMap<>();
        for (QuestionDescriptor question : catalogQuestions) {
            byId.put(question.id(), question);
        }
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String rawId : requestedQuestionIds) {
            if (rawId != null && !rawId.isBlank()) {
                uniqueIds.add(rawId.trim());
            }
        }
        List<QuestionDescriptor> selected = new ArrayList<>();
        for (String questionId : uniqueIds) {
            QuestionDescriptor question = byId.get(questionId);
            if (question == null) {
                throw new IllegalArgumentException("Unknown requested question: " + questionId);
            }
            if (question.track() != track) {
                throw new IllegalArgumentException("Requested question track mismatch: "
                        + questionId + " is " + question.track().name() + " but session track is " + track.name());
            }
            selected.add(question);
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private boolean completeSessionIfReady(Connection connection, String sessionId, Instant now)
            throws SQLException {
        int openAttempts = SessionQueries.scalarInt(connection, """
                SELECT COUNT(*) FROM attempts
                WHERE session_id = ? AND status NOT IN ('FINISHED', 'SKIPPED')
                """, sessionId);
        if (openAttempts != 0) {
            return false;
        }
        SessionRepository.Session session = sessionRepository.findById(connection, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        if (session.status() == SessionStatus.RUNNING) {
            sessionRepository.transition(connection, sessionId, SessionStatus.COMPLETED, now);
            return true;
        }
        return session.status() == SessionStatus.COMPLETED;
    }

    private static void updateAttemptSubmission(Connection connection, String attemptId, String verdict,
                                                Integer durationSeconds, boolean answerUnlocked,
                                                String notes, String improvedAnswer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE attempts
                SET verdict = ?, duration_seconds = ?, answer_unlocked = ?, notes = ?, improved_answer = ?
                WHERE id = ?
                """)) {
            statement.setString(1, verdict);
            nullableInt(statement, 2, durationSeconds);
            statement.setBoolean(3, answerUnlocked);
            statement.setString(4, notes);
            statement.setString(5, improvedAnswer);
            statement.setString(6, attemptId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Unknown attempt: " + attemptId);
            }
        }
    }

    private static Map<String, ReviewRepository.ReviewEntry> reviewMap(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM review_queue");
             ResultSet result = statement.executeQuery()) {
            Map<String, ReviewRepository.ReviewEntry> reviews = new LinkedHashMap<>();
            while (result.next()) {
                ReviewRepository.ReviewEntry review = new ReviewRepository.ReviewEntry(
                        result.getString("question_id"),
                        result.getInt("wrong_count"),
                        result.getInt("review_count"),
                        result.getString("last_result"),
                        SessionQueries.instant(result.getString("last_attempt_at")),
                        SessionQueries.instant(result.getString("next_review_at")),
                        result.getInt("interval_days"),
                        result.getString("focus_dimensions"));
                reviews.put(review.questionId(), review);
            }
            return reviews;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize session config", exception);
        }
    }

    private static Track parseTrack(String value) {
        try {
            return Track.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unknown track: " + value, exception);
        }
    }

    /** CSV of oral dimensions scored 0-1 (weak), or null when every dimension is strong. */
    private static String focusDimensions(ReviewRepository.OralScore score) {
        if (score == null) {
            return null;
        }
        List<String> weak = new ArrayList<>();
        if (score.correctness() <= 1) {
            weak.add("correctness");
        }
        if (score.structure() <= 1) {
            weak.add("structure");
        }
        if (score.projectEvidence() <= 1) {
            weak.add("projectEvidence");
        }
        if (score.tradeoff() <= 1) {
            weak.add("tradeoff");
        }
        if (score.factRestraint() <= 1) {
            weak.add("factRestraint");
        }
        return weak.isEmpty() ? null : String.join(",", weak);
    }

    private static void nullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, Math.max(0, value));
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // --- DTO records (shared across services) ---

    public record CreateSessionRequest(
            String track,
            Integer count,
            Long seed,
            Boolean prioritizeWrongAnswers,
            Boolean dueReviewOnly,
            List<String> questionIds) {
    }

    public record SubmitAttemptRequest(
            String verdict,
            Integer durationSeconds,
            Boolean answerUnlocked,
            String notes,
            String improvedAnswer,
            OralScoreRequest oralScore) {
    }

    public record OralScoreRequest(
            int correctness,
            int structure,
            int projectEvidence,
            int tradeoff,
            int factRestraint) {
        public OralScoreRequest {
            requireScore(correctness, "correctness");
            requireScore(structure, "structure");
            requireScore(projectEvidence, "projectEvidence");
            requireScore(tradeoff, "tradeoff");
            requireScore(factRestraint, "factRestraint");
        }

        public int total() {
            return correctness + structure + projectEvidence + tradeoff + factRestraint;
        }

        ReviewRepository.OralScore toRepository(String attemptId) {
            return new ReviewRepository.OralScore(
                    attemptId,
                    correctness,
                    structure,
                    projectEvidence,
                    tradeoff,
                    factRestraint);
        }

        private static void requireScore(int value, String name) {
            if (value < 0 || value > 2) {
                throw new IllegalArgumentException(name + " must be between 0 and 2");
            }
        }
    }

    public record TrainingSessionResponse(
            String id,
            String mode,
            long seed,
            String status,
            Instant startedAt,
            Instant finishedAt,
            List<TrainingAttemptCard> attempts) {
    }

    public record TrainingAttemptCard(
            String id,
            String sessionId,
            String questionId,
            String track,
            String groupName,
            String title,
            String topic,
            String difficulty,
            String language,
            String sourceRef,
            String starterRef,
            String status,
            String verdict,
            Instant startedAt,
            Instant submittedAt,
            Long durationSeconds,
            boolean answerUnlocked,
            String sandboxPath,
            String notes,
            String improvedAnswer) {
    }

    public record SubmitAttemptResponse(
            String attemptId,
            String sessionId,
            String questionId,
            String verdict,
            Integer oralTotal,
            Instant nextReviewAt,
            boolean sessionCompleted,
            String focusDimensions,
            TrainingSessionResponse session) {
    }

    public record TrainingStatsResponse(
            String databasePath,
            int sessions,
            int attempts,
            int finishedAttempts,
            int passedAttempts,
            int failedAttempts,
            int dueReviews,
            double averageDurationSeconds,
            double averageOralScore,
            List<TrainingAttemptCard> recentAttempts) {
    }

    public record TrainingHistoryResponse(
            String databasePath,
            int limit,
            List<AttemptHistoryCard> attempts) {
    }

    public record AttemptHistoryCard(
            TrainingAttemptCard attempt,
            List<JudgementCard> judgements,
            OralScoreView oralScore) {
    }

    public record OralScoreView(
            int correctness,
            int structure,
            int projectEvidence,
            int tradeoff,
            int factRestraint,
            int total) {
    }

    public record JudgementCard(
            String id,
            int sequenceNo,
            Instant startedAt,
            Instant finishedAt,
            String status,
            Integer exitCode,
            Integer passedCount,
            Integer failedCount,
            Long durationMillis,
            String stdoutExcerpt,
            String stderrExcerpt) {
    }

    public record JudgeAttemptResponse(
            String judgementId,
            String attemptId,
            String sessionId,
            String questionId,
            String runnerKind,
            String sandboxPath,
            String status,
            String verdict,
            Integer passedCount,
            Integer failedCount,
            long durationMillis,
            int exitCode,
            String stdoutExcerpt,
            String stderrExcerpt,
            Instant nextReviewAt,
            boolean sessionCompleted,
            TrainingSessionResponse session) {
    }

    public record OpenSandboxResponse(
            String attemptId,
            String sandboxPath,
            boolean opened,
            String message) {
    }

    private record StoredSessionConfig(
            String track,
            int requestedCount,
            int selectedCount,
            boolean dueReviewOnly,
            boolean prioritizeWrongAnswers) {
    }
}
