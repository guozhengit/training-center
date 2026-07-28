package com.guoyongzheng.training.process;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** The complete bounded result of one local child process. */
public record ProcessResult(
        int exitCode,
        boolean timedOut,
        String stdout,
        String stderr,
        Duration duration,
        FailureKind failureKind) {

    public static final String TRUNCATION_MARKER = "\n...[truncated]\n";
    static final int TRUNCATION_MARKER_BYTES =
            TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;

    public ProcessResult {
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        duration = Objects.requireNonNull(duration, "duration");
        failureKind = Objects.requireNonNull(failureKind, "failureKind");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        boolean valid = switch (failureKind) {
            case NONE -> !timedOut && exitCode == 0;
            case TEST_FAILURE -> !timedOut && exitCode != 0 && exitCode != -1;
            case TIMED_OUT -> timedOut && exitCode == -1;
            case ENVIRONMENT_ERROR -> !timedOut && exitCode == -1;
        };
        if (!valid) {
            throw new IllegalArgumentException("exit code, timeout, and failure kind disagree");
        }
    }

    public boolean stdoutTruncated() {
        return stdout.endsWith(TRUNCATION_MARKER);
    }

    public boolean stderrTruncated() {
        return stderr.endsWith(TRUNCATION_MARKER);
    }
}
