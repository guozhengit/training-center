package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.domain.AttemptStatus;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.SessionStatus;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.SessionRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "start", mixinStandardHelpOptions = true, description = "Create a CLI training session.")
public final class StartCommand implements Callable<Integer> {
    @Option(names = {"-w", "--workspace"}, required = true, description = "Workspace root directory.")
    private Path workspace;

    @Option(names = {"--database"}, description = "SQLite database path. Defaults to output/training-runtime/database/training.db.")
    private Path database;

    @Option(names = {"-t", "--track"}, required = true, description = "CODING, ORAL, or PROJECT.")
    private Track track;

    @Option(names = {"-c", "--count"}, defaultValue = "5", description = "Number of questions.")
    private int count;

    @Option(names = {"--seed"}, description = "Deterministic selection seed.")
    private Long seed;

    @Option(names = {"--question-ids"}, description = "Comma-separated explicit question IDs.")
    private String questionIds;

    @Option(names = {"-d", "--difficulty"}, description = "Optional difficulty filter.")
    private String difficulty;

    @Option(names = {"--topic"}, description = "Optional topic filter.")
    private String topic;

    @Option(names = {"-l", "--language"}, description = "Optional language filter.")
    private String language;

    @Option(names = {"--minutes"}, defaultValue = "60", description = "Session deadline in minutes.")
    private long minutes;

    @Override
    public Integer call() {
        try {
            long actualSeed = seed == null ? System.currentTimeMillis() : seed;
            CliDatabaseSupport.ReadyContext context = CliDatabaseSupport.ready(workspace, database);
            Instant now = Instant.now();
            List<CreatedAttempt> selected = context.database().inWriteTransaction(connection -> {
                List<QuestionDescriptor> questions = CliDatabaseSupport.selectQuestions(
                        context.catalog(), connection, track, questionIds, count, actualSeed, difficulty, topic, language);
                if (questions.isEmpty()) {
                    throw new IllegalArgumentException("No questions matched the selection");
                }
                String sessionId = CliDatabaseSupport.nextId("cli-session");
                SessionRepository sessions = new SessionRepository();
                AttemptRepository attempts = new AttemptRepository();
                sessions.insert(connection, new SessionRepository.Session(
                        sessionId,
                        "CLI_" + track.name(),
                        actualSeed,
                        now,
                        now.plus(Duration.ofMinutes(minutes)),
                        null,
                        SessionStatus.CREATED,
                        "{\"source\":\"cli\",\"track\":\"" + track.name() + "\"}"));
                sessions.transition(connection, sessionId, SessionStatus.RUNNING, now);
                List<CreatedAttempt> created = new ArrayList<>();
                for (QuestionDescriptor question : questions) {
                    String attemptId = CliDatabaseSupport.nextId("cli-attempt");
                    attempts.insert(connection, new AttemptRepository.Attempt(
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
                    attempts.transition(connection, attemptId, AttemptStatus.IN_PROGRESS, now);
                    created.add(new CreatedAttempt(attemptId, question));
                }
                System.out.println("Session: " + sessionId);
                System.out.println("Seed: " + actualSeed);
                return List.copyOf(created);
            });
            System.out.printf("%-38s %-8s %-8s %-40s %s%n", "ATTEMPT", "ID", "TRACK", "TITLE", "TOPIC");
            for (CreatedAttempt created : selected) {
                QuestionDescriptor question = created.question();
                System.out.printf("%-38s %-8s %-8s %-40s %s%n",
                        created.attemptId(), question.id(), question.track(), truncate(question.title(), 40), question.topic());
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("Start failed: " + exception.getMessage());
            return 1;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    private record CreatedAttempt(String attemptId, QuestionDescriptor question) {
    }
}
