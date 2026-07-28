package com.guoyongzheng.training.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Connection-scoped oral scoring and spaced-review persistence. */
public final class ReviewRepository {

    public void insertOralScore(Connection connection, OralScore score) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO oral_scores
                    (attempt_id, correctness, structure, project_evidence, tradeoff, fact_restraint)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, score.attemptId());
            statement.setInt(2, score.correctness());
            statement.setInt(3, score.structure());
            statement.setInt(4, score.projectEvidence());
            statement.setInt(5, score.tradeoff());
            statement.setInt(6, score.factRestraint());
            statement.executeUpdate();
        }
    }

    public Optional<OralScore> findOralScore(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM oral_scores WHERE attempt_id = ?")) {
            statement.setString(1, attemptId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OralScore(
                        result.getString("attempt_id"),
                        result.getInt("correctness"),
                        result.getInt("structure"),
                        result.getInt("project_evidence"),
                        result.getInt("tradeoff"),
                        result.getInt("fact_restraint")));
            }
        }
    }

    public void saveReview(Connection connection, ReviewEntry review) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review_queue
                    (question_id, wrong_count, review_count, last_result,
                     last_attempt_at, next_review_at, interval_days)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(question_id) DO UPDATE SET
                    wrong_count = excluded.wrong_count,
                    review_count = excluded.review_count,
                    last_result = excluded.last_result,
                    last_attempt_at = excluded.last_attempt_at,
                    next_review_at = excluded.next_review_at,
                    interval_days = excluded.interval_days
                """)) {
            statement.setString(1, review.questionId());
            statement.setInt(2, review.wrongCount());
            statement.setInt(3, review.reviewCount());
            statement.setString(4, review.lastResult());
            statement.setString(5, utc(review.lastAttemptAt()));
            statement.setString(6, utc(review.nextReviewAt()));
            statement.setInt(7, review.intervalDays());
            statement.executeUpdate();
        }
    }

    public Optional<ReviewEntry> findReview(Connection connection, String questionId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM review_queue WHERE question_id = ?")) {
            statement.setString(1, questionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReviewEntry(
                        result.getString("question_id"),
                        result.getInt("wrong_count"),
                        result.getInt("review_count"),
                        result.getString("last_result"),
                        instant(result.getString("last_attempt_at")),
                        instant(result.getString("next_review_at")),
                        result.getInt("interval_days")));
            }
        }
    }

    private static String utc(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record OralScore(
            String attemptId,
            int correctness,
            int structure,
            int projectEvidence,
            int tradeoff,
            int factRestraint) {

        public int total() {
            return correctness + structure + projectEvidence + tradeoff + factRestraint;
        }
    }

    public record ReviewEntry(
            String questionId,
            int wrongCount,
            int reviewCount,
            String lastResult,
            Instant lastAttemptAt,
            Instant nextReviewAt,
            int intervalDays) {
    }
}
