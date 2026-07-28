package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.process.FailureKind;

import java.time.Duration;
import java.util.Objects;

/** Persistable terminal outcome from a judge runner. */
public record JudgementResult(
        JudgementStatus status,
        int exitCode,
        TestCounts counts,
        String stdout,
        String stderr,
        Duration duration,
        FailureKind failureKind) {

    public JudgementResult {
        status = Objects.requireNonNull(status, "status");
        counts = Objects.requireNonNull(counts, "counts");
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        duration = Objects.requireNonNull(duration, "duration");
        failureKind = Objects.requireNonNull(failureKind, "failureKind");
    }

    public Integer passedCount() {
        return counts.passedCount();
    }

    public Integer failedCount() {
        return counts.failedCount();
    }
}
