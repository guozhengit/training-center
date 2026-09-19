package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.process.FailureKind;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Persistable terminal outcome from a judge runner. */
public record JudgementResult(
        JudgementStatus status,
        int exitCode,
        TestCounts counts,
        String stdout,
        String stderr,
        Duration duration,
        FailureKind failureKind,
        List<CaseResult> cases) {

    public JudgementResult(
            JudgementStatus status,
            int exitCode,
            TestCounts counts,
            String stdout,
            String stderr,
            Duration duration,
            FailureKind failureKind) {
        this(status, exitCode, counts, stdout, stderr, duration, failureKind, List.of());
    }

    public JudgementResult {
        status = Objects.requireNonNull(status, "status");
        counts = Objects.requireNonNull(counts, "counts");
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        duration = Objects.requireNonNull(duration, "duration");
        failureKind = Objects.requireNonNull(failureKind, "failureKind");
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public Integer passedCount() {
        return counts.passedCount();
    }

    public Integer failedCount() {
        return counts.failedCount();
    }

    public JudgementResult withCases(List<CaseResult> newCases) {
        return new JudgementResult(
                status, exitCode, counts, stdout, stderr, duration, failureKind, newCases);
    }
}
