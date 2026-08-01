package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.SessionStatus;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import com.guoyongzheng.training.persistence.SessionRepository;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for TrainingSessionService using an in-memory-like temp SQLite database.
 * Verifies session creation, attempt submission, and state transitions without external catalog.
 */
class TrainingSessionServiceTest {
    @TempDir
    Path tempDir;

    private TrainingDatabase database;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test-training.db");
        database = new TrainingDatabase(dbPath);
        database.migrate();
        connection = database.openConnection();
        seedQuestions(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void sessionRepositoryInsertAndTransition() throws Exception {
        SessionRepository repository = new SessionRepository();
        java.time.Instant now = java.time.Instant.now();

        repository.insert(connection, new SessionRepository.Session(
                "test-session-1", "CODING", 42L, now, null, null, SessionStatus.CREATED, "{}"));

        SessionRepository.Session session = repository.findById(connection, "test-session-1").orElseThrow();
        assertThat(session.status()).isEqualTo(SessionStatus.CREATED);
        assertThat(session.mode()).isEqualTo("CODING");
        assertThat(session.seed()).isEqualTo(42L);

        repository.transition(connection, "test-session-1", SessionStatus.RUNNING, now);
        session = repository.findById(connection, "test-session-1").orElseThrow();
        assertThat(session.status()).isEqualTo(SessionStatus.RUNNING);

        repository.transition(connection, "test-session-1", SessionStatus.COMPLETED, now);
        session = repository.findById(connection, "test-session-1").orElseThrow();
        assertThat(session.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.finishedAt()).isNotNull();
    }

    @Test
    void sessionTransitionRejectsIllegalPath() throws Exception {
        SessionRepository repository = new SessionRepository();
        java.time.Instant now = java.time.Instant.now();

        repository.insert(connection, new SessionRepository.Session(
                "test-session-2", "ORAL", 1L, now, null, null, SessionStatus.CREATED, "{}"));

        assertThatThrownBy(() ->
                repository.transition(connection, "test-session-2", SessionStatus.COMPLETED, now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void attemptRepositoryFullLifecycle() throws Exception {
        SessionRepository sessionRepo = new SessionRepository();
        AttemptRepository repository = new AttemptRepository();
        java.time.Instant now = java.time.Instant.now();

        sessionRepo.insert(connection, new SessionRepository.Session(
                "att-sess", "CODING", 1L, now, null, null, SessionStatus.CREATED, "{}"));
        sessionRepo.transition(connection, "att-sess", SessionStatus.RUNNING, now);

        repository.insert(connection, new AttemptRepository.Attempt(
                "test-attempt-1", "att-sess", "B001", now, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));

        AttemptRepository.Attempt attempt = repository.findById(connection, "test-attempt-1").orElseThrow();
        assertThat(attempt.status()).isEqualTo(AttemptStatus.CREATED);
        assertThat(attempt.questionId()).isEqualTo("B001");

        repository.transition(connection, "test-attempt-1", AttemptStatus.IN_PROGRESS, now);
        attempt = repository.findById(connection, "test-attempt-1").orElseThrow();
        assertThat(attempt.status()).isEqualTo(AttemptStatus.IN_PROGRESS);

        repository.transition(connection, "test-attempt-1", AttemptStatus.SUBMITTED, now);
        repository.transition(connection, "test-attempt-1", AttemptStatus.FINISHED, now);
        attempt = repository.findById(connection, "test-attempt-1").orElseThrow();
        assertThat(attempt.status()).isEqualTo(AttemptStatus.FINISHED);
    }

    @Test
    void attemptTransitionRejectsSkipFromCreated() throws Exception {
        SessionRepository sessionRepo = new SessionRepository();
        AttemptRepository repository = new AttemptRepository();
        java.time.Instant now = java.time.Instant.now();

        sessionRepo.insert(connection, new SessionRepository.Session(
                "att-sess-2", "CODING", 1L, now, null, null, SessionStatus.CREATED, "{}"));
        sessionRepo.transition(connection, "att-sess-2", SessionStatus.RUNNING, now);

        repository.insert(connection, new AttemptRepository.Attempt(
                "test-attempt-2", "att-sess-2", "B002", now, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));

        assertThatThrownBy(() ->
                repository.transition(connection, "test-attempt-2", AttemptStatus.FINISHED, now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateLatestVerdictPersists() throws Exception {
        SessionRepository sessionRepo = new SessionRepository();
        AttemptRepository repository = new AttemptRepository();
        java.time.Instant now = java.time.Instant.now();

        sessionRepo.insert(connection, new SessionRepository.Session(
                "verd-sess", "CODING", 1L, now, null, null, SessionStatus.CREATED, "{}"));
        sessionRepo.transition(connection, "verd-sess", SessionStatus.RUNNING, now);

        repository.insert(connection, new AttemptRepository.Attempt(
                "test-attempt-3", "verd-sess", "B001", now, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        repository.transition(connection, "test-attempt-3", AttemptStatus.IN_PROGRESS, now);

        repository.updateLatestVerdict(connection, "test-attempt-3", "PASSED");
        AttemptRepository.Attempt attempt = repository.findById(connection, "test-attempt-3").orElseThrow();
        assertThat(attempt.verdict()).isEqualTo("PASSED");

        repository.updateLatestVerdict(connection, "test-attempt-3", "FAILED");
        attempt = repository.findById(connection, "test-attempt-3").orElseThrow();
        assertThat(attempt.verdict()).isEqualTo("FAILED");
    }

    @Test
    void sessionQueriesReadSessionReturnsAttempts() throws Exception {
        SessionRepository sessionRepo = new SessionRepository();
        AttemptRepository attemptRepo = new AttemptRepository();
        java.time.Instant now = java.time.Instant.now();

        sessionRepo.insert(connection, new SessionRepository.Session(
                "sq-session", "CODING", 99L, now, null, null, SessionStatus.CREATED, "{}"));
        sessionRepo.transition(connection, "sq-session", SessionStatus.RUNNING, now);
        attemptRepo.insert(connection, new AttemptRepository.Attempt(
                "sq-attempt-1", "sq-session", "B001", now, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        attemptRepo.transition(connection, "sq-attempt-1", AttemptStatus.IN_PROGRESS, now);
        attemptRepo.insert(connection, new AttemptRepository.Attempt(
                "sq-attempt-2", "sq-session", "B002", now, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        attemptRepo.transition(connection, "sq-attempt-2", AttemptStatus.IN_PROGRESS, now);

        TrainingSessionService.TrainingSessionResponse response =
                SessionQueries.readSession(connection, "sq-session");

        assertThat(response.id()).isEqualTo("sq-session");
        assertThat(response.mode()).isEqualTo("CODING");
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.attempts()).hasSize(2);
        assertThat(response.attempts().get(0).questionId()).isEqualTo("B001");
        assertThat(response.attempts().get(0).title()).isEqualTo("Two Sum");
    }

    @Test
    void sessionQueriesScalarIntWorks() throws Exception {
        int count = SessionQueries.scalarInt(connection, "SELECT COUNT(*) FROM questions");
        assertThat(count).isEqualTo(3);

        int filtered = SessionQueries.scalarInt(connection,
                "SELECT COUNT(*) FROM questions WHERE track = ?", "CODING");
        assertThat(filtered).isEqualTo(2);
    }

    @Test
    void fabricatingFactsForcesRetryAndRecordsReviewFocus() throws Exception {
        seedOralSessionAndAttempt("subm-fact", "att-fact");
        TrainingSessionService service = new TrainingSessionService(
                new TestTrainingDatabaseProvider(database), new ObjectMapper());

        TrainingSessionService.SubmitAttemptResponse response = service.submitAttempt(
                "att-fact",
                new TrainingSessionService.SubmitAttemptRequest(
                        "PASSED", 120, true, "复盘备注", "改进答案",
                        new TrainingSessionService.OralScoreRequest(2, 1, 2, 1, 0)));

        assertThat(response.verdict()).isEqualTo("FAILED");
        assertThat(response.oralTotal()).isEqualTo(6);
        assertThat(response.focusDimensions()).contains("factRestraint");
        assertThat(response.focusDimensions()).contains("structure");

        ReviewRepository.ReviewEntry review = new ReviewRepository()
                .findReview(connection, "O001").orElseThrow();
        assertThat(review.lastResult()).isEqualTo("FAILED");
        assertThat(review.intervalDays()).isEqualTo(1);
        assertThat(review.focusDimensions()).contains("factRestraint");
    }

    @Test
    void cleanOralPassWithoutWeakDimensionsAdvancesReview() throws Exception {
        seedOralSessionAndAttempt("subm-clean", "att-clean");
        TrainingSessionService service = new TrainingSessionService(
                new TestTrainingDatabaseProvider(database), new ObjectMapper());

        TrainingSessionService.SubmitAttemptResponse response = service.submitAttempt(
                "att-clean",
                new TrainingSessionService.SubmitAttemptRequest(
                        "PASSED", 90, false, null, null,
                        new TrainingSessionService.OralScoreRequest(2, 2, 2, 2, 2)));

        assertThat(response.verdict()).isEqualTo("PASSED");
        assertThat(response.oralTotal()).isEqualTo(10);
        assertThat(response.focusDimensions()).isNull();

        ReviewRepository.ReviewEntry review = new ReviewRepository()
                .findReview(connection, "O001").orElseThrow();
        assertThat(review.lastResult()).isEqualTo("PASSED");
        assertThat(review.intervalDays()).isEqualTo(3);
        assertThat(review.focusDimensions()).isNull();
    }

    @Test
    void weakDimensionOnSixPointPassKeepsIntervalInsteadOfAdvancing() throws Exception {
        seedOralSessionAndAttempt("subm-weak", "att-weak");
        TrainingSessionService service = new TrainingSessionService(
                new TestTrainingDatabaseProvider(database), new ObjectMapper());

        TrainingSessionService.SubmitAttemptResponse response = service.submitAttempt(
                "att-weak",
                new TrainingSessionService.SubmitAttemptRequest(
                        "PASSED", 100, false, null, null,
                        new TrainingSessionService.OralScoreRequest(2, 2, 1, 1, 2)));

        assertThat(response.verdict()).isEqualTo("PASSED");
        assertThat(response.focusDimensions()).isEqualTo("projectEvidence,tradeoff");

        ReviewRepository.ReviewEntry review = new ReviewRepository()
                .findReview(connection, "O001").orElseThrow();
        assertThat(review.intervalDays()).isEqualTo(1);
        assertThat(review.focusDimensions()).isEqualTo("projectEvidence,tradeoff");
    }

    private void seedOralSessionAndAttempt(String sessionId, String attemptId) throws Exception {
        SessionRepository sessionRepo = new SessionRepository();
        AttemptRepository attemptRepo = new AttemptRepository();
        Instant now = Instant.now();
        sessionRepo.insert(connection, new SessionRepository.Session(
                sessionId, "ORAL", 1L, now, null, null, SessionStatus.CREATED, "{}"));
        sessionRepo.transition(connection, sessionId, SessionStatus.RUNNING, now);
        attemptRepo.insert(connection, new AttemptRepository.Attempt(
                attemptId, sessionId, "O001", now, null, null,
                AttemptStatus.CREATED, null, false, null, null, null));
        attemptRepo.transition(connection, attemptId, AttemptStatus.IN_PROGRESS, now);
    }

    private void seedQuestions(Connection conn) throws Exception {
        List<QuestionDescriptor> questions = List.of(
                new QuestionDescriptor("B001", Track.CODING, "basic", "Two Sum", "array",
                        "easy", "java", "coding/B001.md", "java/src/main/java/com/guoyongzheng/exam/basic/B001TwoSum.java"),
                new QuestionDescriptor("B002", Track.CODING, "basic", "Valid Anagram", "string",
                        "easy", "java", "coding/B002.md", "java/src/main/java/com/guoyongzheng/exam/basic/B002ValidAnagram.java"),
                new QuestionDescriptor("O001", Track.ORAL, "round1", "HashMap原理", "java-basics",
                        "medium", "zh", "oral/O001.md", null));
        TrainingDatabaseProvider.syncQuestions(conn, questions);
    }
}
