package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.SessionStatus;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.JudgementRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import com.guoyongzheng.training.persistence.SessionRepository;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SessionQueryService logic using a temp SQLite database.
 * Verifies stats aggregation, history retrieval, and judgement timeline assembly.
 */
class SessionQueryServiceTest {
    @TempDir
    Path tempDir;

    private TrainingDatabase database;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test-query.db");
        database = new TrainingDatabase(dbPath);
        database.migrate();
        connection = database.openConnection();
        seedData(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void recentAttemptsReturnsOrderedBySubmission() throws Exception {
        List<TrainingSessionService.TrainingAttemptCard> recent =
                SessionQueries.recentAttempts(connection, 10);

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).questionId()).isEqualTo("B002");
        assertThat(recent.get(1).questionId()).isEqualTo("B001");
        assertThat(recent.get(2).questionId()).isEqualTo("O001");
    }

    @Test
    void recentAttemptsRespectsLimit() throws Exception {
        List<TrainingSessionService.TrainingAttemptCard> recent =
                SessionQueries.recentAttempts(connection, 2);

        assertThat(recent).hasSize(2);
    }

    @Test
    void judgementRepositoryAppendAndQuery() throws Exception {
        JudgementRepository repository = new JudgementRepository();
        Instant now = Instant.now();

        repository.append(connection, "j-1", "att-1", now, JudgementStatus.QUEUED);
        repository.transition(connection, "j-1", JudgementStatus.RUNNING, now);
        repository.recordResult(connection, "j-1", JudgementStatus.PASSED, now, 0, 5, 0, 1200L, "ok", "");

        repository.append(connection, "j-2", "att-1", now.plusSeconds(10), JudgementStatus.QUEUED);
        repository.transition(connection, "j-2", JudgementStatus.RUNNING, now.plusSeconds(10));
        repository.recordResult(connection, "j-2", JudgementStatus.FAILED, now.plusSeconds(10), 1, 3, 2, 900L, "", "err");

        List<JudgementRepository.Judgement> judgements = repository.findByAttempt(connection, "att-1");
        assertThat(judgements).hasSize(2);
        assertThat(judgements.get(0).sequenceNo()).isEqualTo(1);
        assertThat(judgements.get(0).status()).isEqualTo(JudgementStatus.PASSED);
        assertThat(judgements.get(0).passedCount()).isEqualTo(5);
        assertThat(judgements.get(1).sequenceNo()).isEqualTo(2);
        assertThat(judgements.get(1).status()).isEqualTo(JudgementStatus.FAILED);
        assertThat(judgements.get(1).failedCount()).isEqualTo(2);
    }

    @Test
    void reviewRepositoryUpsertAndQuery() throws Exception {
        ReviewRepository repository = new ReviewRepository();
        Instant now = Instant.now();

        ReviewRepository.ReviewEntry entry = new ReviewRepository.ReviewEntry(
                "B001", 1, 0, "FAILED", now, now.plusSeconds(86400), 1);
        repository.saveReview(connection, entry);

        ReviewRepository.ReviewEntry found = repository.findReview(connection, "B001").orElseThrow();
        assertThat(found.wrongCount()).isEqualTo(1);
        assertThat(found.lastResult()).isEqualTo("FAILED");
        assertThat(found.intervalDays()).isEqualTo(1);

        ReviewRepository.ReviewEntry updated = new ReviewRepository.ReviewEntry(
                "B001", 1, 1, "PASSED", now.plusSeconds(86400), now.plusSeconds(86400 * 4), 3);
        repository.saveReview(connection, updated);

        found = repository.findReview(connection, "B001").orElseThrow();
        assertThat(found.reviewCount()).isEqualTo(1);
        assertThat(found.lastResult()).isEqualTo("PASSED");
        assertThat(found.intervalDays()).isEqualTo(3);
    }

    @Test
    void oralScoreInsertAndTotal() throws Exception {
        ReviewRepository repository = new ReviewRepository();

        repository.insertOralScore(connection, new ReviewRepository.OralScore("att-3", 2, 1, 2, 1, 2));

        ReviewRepository.OralScore score = repository.findOralScore(connection, "att-3").orElseThrow();
        assertThat(score.total()).isEqualTo(8);
        assertThat(score.correctness()).isEqualTo(2);
        assertThat(score.factRestraint()).isEqualTo(2);
    }

    @Test
    void scalarDoubleReturnsZeroForEmptyTable() throws Exception {
        double avg = SessionQueries.scalarDouble(connection,
                "SELECT AVG(duration_seconds) FROM attempts WHERE duration_seconds IS NOT NULL");
        assertThat(avg).isEqualTo(0.0);
    }

    private void seedData(Connection conn) throws Exception {
        List<QuestionDescriptor> questions = List.of(
                new QuestionDescriptor("B001", Track.CODING, "basic", "Two Sum", "array",
                        "easy", "java", "coding/B001.md", "starter/B001.java"),
                new QuestionDescriptor("B002", Track.CODING, "basic", "Valid Anagram", "string",
                        "easy", "java", "coding/B002.md", "starter/B002.java"),
                new QuestionDescriptor("O001", Track.ORAL, "round1", "HashMap原理", "java-basics",
                        "medium", "zh", "oral/O001.md", null));
        TrainingDatabaseProvider.syncQuestions(conn, questions);

        SessionRepository sessionRepo = new SessionRepository();
        AttemptRepository attemptRepo = new AttemptRepository();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        sessionRepo.insert(conn, new SessionRepository.Session(
                "qs-session", "CODING", 1L, base, null, null, SessionStatus.CREATED, "{}"));
        sessionRepo.transition(conn, "qs-session", SessionStatus.RUNNING, base);

        attemptRepo.insert(conn, new AttemptRepository.Attempt(
                "att-1", "qs-session", "B001", base, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        attemptRepo.transition(conn, "att-1", AttemptStatus.IN_PROGRESS, base);
        attemptRepo.transition(conn, "att-1", AttemptStatus.SUBMITTED, base.plusSeconds(300));
        attemptRepo.transition(conn, "att-1", AttemptStatus.FINISHED, base.plusSeconds(300));

        attemptRepo.insert(conn, new AttemptRepository.Attempt(
                "att-2", "qs-session", "B002", base.plusSeconds(10), null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        attemptRepo.transition(conn, "att-2", AttemptStatus.IN_PROGRESS, base.plusSeconds(10));
        attemptRepo.transition(conn, "att-2", AttemptStatus.SUBMITTED, base.plusSeconds(500));
        attemptRepo.transition(conn, "att-2", AttemptStatus.FINISHED, base.plusSeconds(500));

        attemptRepo.insert(conn, new AttemptRepository.Attempt(
                "att-3", "qs-session", "O001", base.plusSeconds(20), null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        attemptRepo.transition(conn, "att-3", AttemptStatus.IN_PROGRESS, base.plusSeconds(20));
    }
}
