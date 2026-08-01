package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.persistence.AttemptRepository;
import com.guoyongzheng.training.persistence.JudgementRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "judge", mixinStandardHelpOptions = true, description = "Record a CLI judgement result.")
public final class JudgeCommand implements Callable<Integer> {
    @Option(names = {"-w", "--workspace"}, required = true, description = "Workspace root directory.")
    private Path workspace;

    @Option(names = {"--database"}, description = "SQLite database path.")
    private Path database;

    @Option(names = {"--attempt"}, required = true, description = "Attempt ID.")
    private String attemptId;

    @Option(names = {"--status"}, defaultValue = "PASSED", description = "PASSED, FAILED, TIMED_OUT, or ENVIRONMENT_ERROR.")
    private String status;

    @Option(names = {"--passed-count"}, defaultValue = "0")
    private int passedCount;

    @Option(names = {"--failed-count"}, defaultValue = "0")
    private int failedCount;

    @Option(names = {"--exit-code"}, defaultValue = "0")
    private int exitCode;

    @Option(names = {"--duration-ms"}, defaultValue = "0")
    private long durationMillis;

    @Option(names = {"--stdout"}, defaultValue = "")
    private String stdout;

    @Option(names = {"--stderr"}, defaultValue = "")
    private String stderr;

    @Override
    public Integer call() {
        try {
            JudgementStatus target = parseStatus(status);
            CliDatabaseSupport.ReadyContext context = CliDatabaseSupport.ready(workspace, database);
            String judgementId = CliDatabaseSupport.nextId("cli-judgement");
            Instant now = Instant.now();
            context.database().inWriteTransaction(connection -> {
                JudgementRepository judgements = new JudgementRepository();
                judgements.append(connection, judgementId, attemptId, now, JudgementStatus.QUEUED);
                judgements.transition(connection, judgementId, JudgementStatus.RUNNING, now);
                judgements.recordResult(
                        connection,
                        judgementId,
                        target,
                        now,
                        exitCode,
                        passedCount,
                        failedCount,
                        durationMillis,
                        stdout,
                        stderr);
                String verdict = target == JudgementStatus.PASSED ? "PASSED" : "FAILED";
                AttemptRepository attempts = new AttemptRepository();
                attempts.updateLatestVerdict(connection, attemptId, verdict);
                return null;
            });
            System.out.println("Judgement: " + judgementId + " status=" + target);
            return 0;
        } catch (Exception exception) {
            System.err.println("Judge failed: " + exception.getMessage());
            return 1;
        }
    }

    private static JudgementStatus parseStatus(String value) {
        JudgementStatus parsed = JudgementStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        if (parsed == JudgementStatus.QUEUED || parsed == JudgementStatus.RUNNING) {
            throw new IllegalArgumentException("status must be terminal");
        }
        return parsed;
    }
}
