package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.persistence.JudgementRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only query service for training statistics and attempt history.
 */
@Service
public class SessionQueryService {
    private final TrainingDatabaseProvider databaseProvider;
    private final JudgementRepository judgementRepository = new JudgementRepository();
    private final ReviewRepository reviewRepository = new ReviewRepository();
    private final Clock clock = Clock.systemUTC();

    public SessionQueryService(TrainingDatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
    }

    public TrainingSessionService.TrainingStatsResponse stats() {
        TrainingDatabase database = databaseProvider.readyDatabase();
        try (Connection connection = database.openConnection()) {
            return new TrainingSessionService.TrainingStatsResponse(
                    database.path().toString(),
                    SessionQueries.scalarInt(connection, "SELECT COUNT(*) FROM sessions"),
                    SessionQueries.scalarInt(connection, "SELECT COUNT(*) FROM attempts"),
                    SessionQueries.scalarInt(connection, "SELECT COUNT(*) FROM attempts WHERE status = 'FINISHED'"),
                    SessionQueries.scalarInt(connection, "SELECT COUNT(*) FROM attempts WHERE verdict = 'PASSED'"),
                    SessionQueries.scalarInt(connection, "SELECT COUNT(*) FROM attempts WHERE verdict = 'FAILED'"),
                    SessionQueries.scalarInt(connection, """
                            SELECT COUNT(*) FROM review_queue
                            WHERE last_result = 'FAILED' AND next_review_at <= ?
                            """, clock.instant().toString()),
                    SessionQueries.scalarDouble(connection,
                            "SELECT AVG(duration_seconds) FROM attempts WHERE duration_seconds IS NOT NULL"),
                    SessionQueries.scalarDouble(connection, """
                            SELECT AVG(correctness + structure + project_evidence + tradeoff + fact_restraint)
                            FROM oral_scores
                            """),
                    SessionQueries.recentAttempts(connection, 8));
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read training stats", exception);
        }
    }

    public TrainingSessionService.TrainingHistoryResponse history(int limit) {
        TrainingDatabase database = databaseProvider.readyDatabase();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try (Connection connection = database.openConnection()) {
            return new TrainingSessionService.TrainingHistoryResponse(
                    database.path().toString(),
                    safeLimit,
                    attemptHistory(connection, safeLimit));
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read training history", exception);
        }
    }

    public TrainingSessionService.TrainingSessionResponse session(String sessionId) {
        TrainingDatabase database = databaseProvider.readyDatabase();
        try (Connection connection = database.openConnection()) {
            return SessionQueries.readSession(connection, sessionId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read training session: " + sessionId, exception);
        }
    }

    private List<TrainingSessionService.AttemptHistoryCard> attemptHistory(Connection connection, int limit)
            throws SQLException {
        List<TrainingSessionService.TrainingAttemptCard> attempts =
                SessionQueries.recentAttempts(connection, limit);
        List<TrainingSessionService.AttemptHistoryCard> history = new ArrayList<>();
        for (TrainingSessionService.TrainingAttemptCard attempt : attempts) {
            List<TrainingSessionService.JudgementCard> judgements = new ArrayList<>();
            for (JudgementRepository.Judgement judgement : judgementRepository.findByAttempt(connection, attempt.id())) {
                judgements.add(new TrainingSessionService.JudgementCard(
                        judgement.id(),
                        judgement.sequenceNo(),
                        judgement.startedAt(),
                        judgement.finishedAt(),
                        judgement.status().name(),
                        judgement.exitCode(),
                        judgement.passedCount(),
                        judgement.failedCount(),
                        judgement.durationMillis(),
                        judgement.stdoutExcerpt(),
                        judgement.stderrExcerpt()));
            }
            TrainingSessionService.OralScoreView oralScore = null;
            var score = reviewRepository.findOralScore(connection, attempt.id()).orElse(null);
            if (score != null) {
                oralScore = new TrainingSessionService.OralScoreView(
                        score.correctness(),
                        score.structure(),
                        score.projectEvidence(),
                        score.tradeoff(),
                        score.factRestraint(),
                        score.total());
            }
            history.add(new TrainingSessionService.AttemptHistoryCard(
                    attempt, List.copyOf(judgements), oralScore));
        }
        return List.copyOf(history);
    }
}
