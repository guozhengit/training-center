package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.ReviewRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit a CLI attempt result.")
public final class SubmitCommand implements Callable<Integer> {
    @Option(names = {"-w", "--workspace"}, required = true, description = "Workspace root directory.")
    private Path workspace;

    @Option(names = {"--database"}, description = "SQLite database path.")
    private Path database;

    @Option(names = {"--attempt"}, required = true, description = "Attempt ID.")
    private String attemptId;

    @Option(names = {"--verdict"}, defaultValue = "PASSED", description = "PASSED or FAILED.")
    private String verdict;

    @Option(names = {"--duration-seconds"}, defaultValue = "0", description = "Attempt duration.")
    private long durationSeconds;

    @Option(names = {"--notes"}, defaultValue = "", description = "Review notes.")
    private String notes;

    @Option(names = {"--improved-answer"}, defaultValue = "", description = "Improved answer.")
    private String improvedAnswer;

    @Option(names = {"--score"}, description = "Five oral/project scores, e.g. 2,2,1,2,2.")
    private String score;

    @Override
    public Integer call() {
        try {
            String normalizedVerdict = normalizeVerdict(verdict);
            boolean passed = "PASSED".equals(normalizedVerdict);
            CliDatabaseSupport.ReadyContext context = CliDatabaseSupport.ready(workspace, database);
            context.database().inWriteTransaction(connection -> {
                AttemptRepository attempts = new AttemptRepository();
                AttemptRepository.Attempt attempt = attempts.findById(connection, attemptId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown attempt: " + attemptId));
                CliDatabaseSupport.updateAttemptSubmission(
                        connection, attemptId, normalizedVerdict, durationSeconds, notes, improvedAnswer);
                boolean oralOrProject = context.catalog().require(attempt.questionId()).track() == Track.ORAL
                        || context.catalog().require(attempt.questionId()).track() == Track.PROJECT;
                Integer oralTotal = null;
                if (score != null && !score.isBlank()) {
                    int[] values = parseScore(score);
                    new ReviewRepository().insertOralScore(connection, new ReviewRepository.OralScore(
                            attemptId, values[0], values[1], values[2], values[3], values[4]));
                    oralOrProject = true;
                    oralTotal = values[0] + values[1] + values[2] + values[3] + values[4];
                }
                CliDatabaseSupport.finishAttemptAndReview(
                        connection, attemptId, passed, oralOrProject ? oralTotal : null, Instant.now());
                return null;
            });
            System.out.println("Submitted: " + attemptId + " verdict=" + normalizedVerdict);
            return 0;
        } catch (Exception exception) {
            System.err.println("Submit failed: " + exception.getMessage());
            return 1;
        }
    }

    private static String normalizeVerdict(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("PASSED") && !normalized.equals("FAILED")) {
            throw new IllegalArgumentException("verdict must be PASSED or FAILED");
        }
        return normalized;
    }

    private static int[] parseScore(String value) {
        String[] parts = value.split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException("score must contain five comma-separated integers");
        }
        int[] scores = new int[5];
        for (int i = 0; i < parts.length; i++) {
            scores[i] = Integer.parseInt(parts[i].trim());
            if (scores[i] < 0 || scores[i] > 2) {
                throw new IllegalArgumentException("each score must be between 0 and 2");
            }
        }
        return scores;
    }
}
