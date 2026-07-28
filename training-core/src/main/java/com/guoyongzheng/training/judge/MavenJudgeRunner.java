package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.process.ProcessRequest;
import com.guoyongzheng.training.process.ProcessResult;
import com.guoyongzheng.training.process.ProcessRunner;
import com.guoyongzheng.training.sandbox.SandboxService;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one selected Maven test class in a completed isolated sandbox. */
public final class MavenJudgeRunner implements JudgeRunner {

    private static final String MVN_EXECUTABLE =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")
                    ? "mvn.cmd" : "mvn";

    private static final Pattern SUMMARY = Pattern.compile(
            "(?m)^(?:\\[[^\\r\\n]+]\\s*)?Tests run: (\\d+), "
                    + "Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)\\s*$");

    private final ProcessRunner processRunner;
    private final SandboxCompletionCheck completionCheck;

    public MavenJudgeRunner(ProcessRunner processRunner, SandboxService sandboxService) {
        this(processRunner, Objects.requireNonNull(sandboxService, "sandboxService")::isComplete);
    }

    MavenJudgeRunner(
            ProcessRunner processRunner,
            SandboxCompletionCheck completionCheck) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.completionCheck = Objects.requireNonNull(completionCheck, "completionCheck");
    }

    @Override
    public JudgementResult judge(JudgeRequest request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        try {
            JudgeSupport.PreparedJudge prepared =
                    JudgeSupport.prepare(request, RunnerKind.MAVEN, completionCheck);
            ProcessResult process = processRunner.run(new ProcessRequest(
                    List.of(
                            MVN_EXECUTABLE,
                            "-q",
                            "-Dtest=" + prepared.selector(),
                            "test"),
                    prepared.workingDirectory(),
                    request.timeout(),
                    JudgeSupport.OUTPUT_CAP,
                    JudgeSupport.OUTPUT_CAP,
                    request.environment()));
            TestCounts counts = process.stdoutTruncated() || process.stderrTruncated()
                    ? TestCounts.UNKNOWN
                    : counts(process.stdout(), process.stderr());
            return JudgeSupport.result(process, counts);
        } catch (IOException | RuntimeException exception) {
            return JudgeSupport.environmentError(safeMessage(exception), started);
        }
    }

    private static TestCounts counts(String stdout, String stderr) {
        Matcher matcher = SUMMARY.matcher(stdout + "\n" + stderr);
        TestCounts counts = null;
        while (matcher.find()) {
            if (counts != null) {
                return TestCounts.UNKNOWN;
            }
            try {
                int run = Integer.parseInt(matcher.group(1));
                int failures = Integer.parseInt(matcher.group(2));
                int errors = Integer.parseInt(matcher.group(3));
                int skipped = Integer.parseInt(matcher.group(4));
                int failed = Math.addExact(failures, errors);
                int passed = Math.subtractExact(
                        Math.subtractExact(run, failed),
                        skipped);
                if (passed < 0) {
                    return TestCounts.UNKNOWN;
                }
                counts = new TestCounts(passed, failed);
            } catch (ArithmeticException | NumberFormatException exception) {
                return TestCounts.UNKNOWN;
            }
        }
        return counts == null ? TestCounts.UNKNOWN : counts;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "judge environment validation failed"
                : message;
    }
}
