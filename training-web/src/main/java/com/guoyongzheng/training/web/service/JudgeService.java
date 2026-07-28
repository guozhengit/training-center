package com.guoyongzheng.training.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.catalog.StarterMapping;
import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.judge.JudgeRequest;
import com.guoyongzheng.training.judge.JudgeRunner;
import com.guoyongzheng.training.judge.JudgementResult;
import com.guoyongzheng.training.judge.MavenJudgeRunner;
import com.guoyongzheng.training.judge.PytestJudgeRunner;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.JudgementRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import com.guoyongzheng.training.persistence.SessionRepository;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import com.guoyongzheng.training.process.LocalProcessRunner;
import com.guoyongzheng.training.review.ReviewScheduler;
import com.guoyongzheng.training.sandbox.SandboxManifest;
import com.guoyongzheng.training.sandbox.SandboxPolicy;
import com.guoyongzheng.training.sandbox.SandboxService;
import com.guoyongzheng.training.web.config.TrainingProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles automatic judge execution: sandbox provisioning, Maven/Pytest invocation,
 * result persistence, and post-pass review scheduling.
 */
@Service
public class JudgeService {
    private final TrainingDatabaseProvider databaseProvider;
    private final TrainingProperties trainingProperties;
    private final ObjectMapper objectMapper;
    private final AttemptRepository attemptRepository = new AttemptRepository();
    private final JudgementRepository judgementRepository = new JudgementRepository();
    private final ReviewRepository reviewRepository = new ReviewRepository();
    private final SessionRepository sessionRepository = new SessionRepository();
    private final Clock clock = Clock.systemUTC();

    public JudgeService(TrainingDatabaseProvider databaseProvider,
                        TrainingProperties trainingProperties,
                        ObjectMapper objectMapper) {
        this.databaseProvider = databaseProvider;
        this.trainingProperties = trainingProperties;
        this.objectMapper = objectMapper;
    }

    public TrainingSessionService.JudgeAttemptResponse judgeAttempt(String attemptId) {
        Path workspace = databaseProvider.workspace();
        TrainingDatabase database = databaseProvider.readyDatabase();
        AttemptRepository.Attempt attempt = readAttempt(database, attemptId);
        if (attempt.status() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Attempt is not in progress: " + attemptId);
        }
        QuestionDescriptor question = readQuestion(database, attempt.questionId());
        if (question.track() != Track.CODING) {
            throw new IllegalArgumentException("Auto judge only supports CODING attempts: " + attemptId);
        }

        StarterMapping mapping = starterMapping(workspace, question.id());
        SandboxService sandboxService = sandboxService(workspace);
        SandboxManifest manifest = ensureSandbox(database, sandboxService, attempt, mapping);
        Path sandboxPath = sandboxRoot(workspace).resolve(attempt.sessionId()).resolve(attempt.id())
                .toAbsolutePath()
                .normalize();
        String judgementId = "web-judgement-" + UUID.randomUUID();
        Instant startedAt = clock.instant();
        appendRunningJudgement(database, judgementId, attempt.id(), startedAt);

        JudgeRunner runner = mapping.runnerKind() == RunnerKind.MAVEN
                ? new MavenJudgeRunner(new LocalProcessRunner(), sandboxService)
                : new PytestJudgeRunner(new LocalProcessRunner(), sandboxService);
        JudgementResult result = runner.judge(new JudgeRequest(
                sandboxPath,
                manifest,
                Duration.ofSeconds(90),
                judgeEnvironment(mapping.runnerKind())));
        Instant finishedAt = clock.instant();

        try {
            return database.inWriteTransaction(connection -> {
                judgementRepository.recordResult(
                        connection,
                        judgementId,
                        result.status(),
                        finishedAt,
                        result.exitCode(),
                        result.passedCount(),
                        result.failedCount(),
                        result.duration().toMillis(),
                        excerpt(result.stdout()),
                        excerpt(result.stderr()));

                String verdict = result.status() == JudgementStatus.PASSED ? "PASSED" : "FAILED";
                attemptRepository.updateLatestVerdict(connection, attempt.id(), verdict);
                boolean sessionCompleted = false;
                Instant nextReviewAt = null;
                if (result.status() == JudgementStatus.PASSED) {
                    attemptRepository.transition(connection, attempt.id(), AttemptStatus.SUBMITTED, finishedAt);
                    attemptRepository.transition(connection, attempt.id(), AttemptStatus.FINISHED, finishedAt);
                    Optional<ReviewRepository.ReviewEntry> currentReview =
                            reviewRepository.findReview(connection, attempt.questionId());
                    ReviewRepository.ReviewEntry nextReview = new ReviewScheduler(clock).schedule(
                            attempt.questionId(),
                            currentReview.orElse(null),
                            true,
                            null,
                            false);
                    reviewRepository.saveReview(connection, nextReview);
                    nextReviewAt = nextReview.nextReviewAt();
                    sessionCompleted = completeSessionIfReady(connection, attempt.sessionId(), finishedAt);
                }
                return new TrainingSessionService.JudgeAttemptResponse(
                        judgementId,
                        attempt.id(),
                        attempt.sessionId(),
                        question.id(),
                        mapping.runnerKind().name(),
                        sandboxPath.toString(),
                        result.status().name(),
                        verdict,
                        result.passedCount(),
                        result.failedCount(),
                        result.duration().toMillis(),
                        result.exitCode(),
                        excerpt(result.stdout()),
                        excerpt(result.stderr()),
                        nextReviewAt,
                        sessionCompleted,
                        readSession(connection, attempt.sessionId()));
            });
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot persist judge result: " + attemptId, exception);
        }
    }

    public TrainingSessionService.OpenSandboxResponse openSandbox(String attemptId) {
        Path workspace = databaseProvider.workspace();
        TrainingDatabase database = databaseProvider.readyDatabase();
        AttemptRepository.Attempt attempt = readAttempt(database, attemptId);
        if (attempt.sandboxPath() == null || attempt.sandboxPath().isBlank()) {
            throw new IllegalArgumentException("Attempt has no sandbox path: " + attemptId);
        }
        Path sandboxPath = Path.of(attempt.sandboxPath()).toAbsolutePath().normalize();
        Path allowedRoot = sandboxRoot(workspace).toAbsolutePath().normalize();
        if (!sandboxPath.startsWith(allowedRoot)) {
            throw new IllegalArgumentException("Sandbox path is outside Web judge root: " + attemptId);
        }
        if (!Files.isDirectory(sandboxPath)) {
            throw new IllegalStateException("Sandbox directory is missing: " + sandboxPath);
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("windows")) {
            return new TrainingSessionService.OpenSandboxResponse(attemptId, sandboxPath.toString(), false,
                    "Open sandbox is only implemented for Windows in this local MVP");
        }
        try {
            new ProcessBuilder("explorer.exe", sandboxPath.toString()).start();
            return new TrainingSessionService.OpenSandboxResponse(attemptId, sandboxPath.toString(), true,
                    "Sandbox folder opened in Windows Explorer");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot open sandbox folder: " + sandboxPath, exception);
        }
    }

    // --- internal helpers ---

    private AttemptRepository.Attempt readAttempt(TrainingDatabase database, String attemptId) {
        try (Connection connection = database.openConnection()) {
            return attemptRepository.findById(connection, attemptId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown attempt: " + attemptId));
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read attempt: " + attemptId, exception);
        }
    }

    private QuestionDescriptor readQuestion(TrainingDatabase database, String questionId) {
        try (Connection connection = database.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM questions WHERE id = ?")) {
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
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read question: " + questionId, exception);
        }
    }

    private TrainingSessionService.TrainingSessionResponse readSession(Connection connection, String sessionId)
            throws SQLException {
        SessionRepository.Session session = sessionRepository.findById(connection, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        return new TrainingSessionService.TrainingSessionResponse(
                session.id(),
                session.mode(),
                session.seed(),
                session.status().name(),
                session.startedAt(),
                session.finishedAt(),
                SessionQueries.attempts(connection, sessionId));
    }

    private boolean completeSessionIfReady(Connection connection, String sessionId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM attempts
                WHERE session_id = ? AND status NOT IN ('FINISHED', 'SKIPPED')
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) != 0) {
                    return false;
                }
            }
        }
        SessionRepository.Session session = sessionRepository.findById(connection, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        if (session.status() == com.guoyongzheng.training.domain.SessionStatus.RUNNING) {
            sessionRepository.transition(connection, sessionId,
                    com.guoyongzheng.training.domain.SessionStatus.COMPLETED, now);
            return true;
        }
        return session.status() == com.guoyongzheng.training.domain.SessionStatus.COMPLETED;
    }

    private SandboxManifest ensureSandbox(TrainingDatabase database, SandboxService service,
                                          AttemptRepository.Attempt attempt, StarterMapping mapping) {
        Path sandboxPath = sandboxRoot(databaseProvider.workspace())
                .resolve(attempt.sessionId()).resolve(attempt.id())
                .toAbsolutePath().normalize();
        Path manifestPath = sandboxPath.resolve("manifest.json");
        if (attempt.sandboxPath() != null && !attempt.sandboxPath().isBlank()
                && Files.isRegularFile(manifestPath)) {
            try {
                return objectMapper.readValue(manifestPath.toFile(), SandboxManifest.class);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read existing sandbox manifest: " + manifestPath, exception);
            }
        }
        try {
            SandboxManifest manifest = service.create(attempt.sessionId(), attempt.id(), mapping);
            try {
                database.inWriteTransaction(connection -> {
                    updateSandboxPath(connection, attempt.id(), sandboxPath.toString());
                    return null;
                });
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot persist sandbox path: " + attempt.id(), exception);
            }
            return manifest;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create sandbox for attempt: " + attempt.id(), exception);
        }
    }

    private void appendRunningJudgement(TrainingDatabase database, String judgementId, String attemptId,
                                        Instant startedAt) {
        try {
            database.inWriteTransaction(connection -> {
                judgementRepository.append(connection, judgementId, attemptId, startedAt, JudgementStatus.QUEUED);
                judgementRepository.transition(connection, judgementId, JudgementStatus.RUNNING, startedAt);
                return null;
            });
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot append judgement: " + judgementId, exception);
        }
    }

    private StarterMapping starterMapping(Path workspace, String questionId) {
        Path mappingPath = workspace.resolve("training-center/config/starter-mapping.json");
        try {
            JsonNode root = objectMapper.readTree(mappingPath.toFile());
            if (!root.isArray()) {
                throw new IllegalArgumentException("starter-mapping.json must be an array");
            }
            for (JsonNode item : root) {
                if (questionId.equals(item.path("question_id").asText())) {
                    Path sandboxSourcePath = Path.of(item.path("sandbox_source_path").asText());
                    RunnerKind runnerKind = RunnerKind.valueOf(item.path("runner_kind").asText());
                    return new StarterMapping(
                            item.path("question_id").asText(),
                            starterRelativePath(item.path("starter_path").asText()),
                            sandboxSourcePath,
                            runnerKind,
                            testSelector(runnerKind, sandboxSourcePath, item.path("test_selector").asText()));
                }
            }
            throw new IllegalArgumentException("Missing starter mapping for question: " + questionId);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read starter mapping: " + mappingPath, exception);
        }
    }

    private SandboxService sandboxService(Path workspace) {
        return new SandboxService(new SandboxPolicy(
                workspace.resolve("output/coding-ai-exam"),
                workspace.resolve("training-center/starters"),
                sandboxRoot(workspace)), Clock.systemUTC());
    }

    private Map<String, String> judgeEnvironment(RunnerKind runnerKind) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", trainingProperties.jdkHome().toString());
        String path = System.getenv("PATH");
        String jdkBin = trainingProperties.jdkBin().toString();
        String mavenBin = trainingProperties.mavenBin().toString();
        environment.put("PATH", jdkBin + java.io.File.pathSeparator + mavenBin
                + (path == null || path.isBlank() ? "" : java.io.File.pathSeparator + path));
        if (runnerKind == RunnerKind.PYTEST) {
            environment.put("PYTHONUTF8", "1");
            environment.put("PYTHONIOENCODING", "utf-8");
        }
        return environment;
    }

    private static Path sandboxRoot(Path workspace) {
        return workspace.resolve("output/training-runtime/sandboxes/web-judge")
                .toAbsolutePath()
                .normalize();
    }

    private static Path starterRelativePath(String value) {
        Path raw = Path.of(value);
        Path prefix = Path.of("training-center", "starters");
        if (raw.startsWith(prefix) && raw.getNameCount() > prefix.getNameCount()) {
            return prefix.relativize(raw);
        }
        return raw;
    }

    private static String testSelector(RunnerKind runnerKind, Path sandboxSourcePath, String selector) {
        if (runnerKind != RunnerKind.MAVEN || selector.contains(".")) {
            return selector;
        }
        Path sourceRoot = Path.of("java", "src", "main", "java");
        if (!sandboxSourcePath.startsWith(sourceRoot) || sandboxSourcePath.getNameCount() <= sourceRoot.getNameCount()) {
            return selector;
        }
        Path packagePath = sourceRoot.relativize(sandboxSourcePath).getParent();
        if (packagePath == null || packagePath.getNameCount() == 0) {
            return selector;
        }
        return packagePath.toString().replace('\\', '.').replace('/', '.') + "." + selector;
    }

    private static void updateSandboxPath(Connection connection, String attemptId, String sandboxPath)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE attempts SET sandbox_path = ? WHERE id = ?
                """)) {
            statement.setString(1, sandboxPath);
            statement.setString(2, attemptId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Unknown attempt: " + attemptId);
            }
        }
    }

    private static String excerpt(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int limit = 4_000;
        return value.length() <= limit ? value : value.substring(0, limit) + "\n...[truncated]\n";
    }
}
