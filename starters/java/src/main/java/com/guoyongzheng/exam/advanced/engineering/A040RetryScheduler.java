package com.guoyongzheng.exam.advanced.engineering;

import java.util.List;
import java.util.Optional;

public final class A040RetryScheduler {

    public A040RetryScheduler(long baseDelay, long maximumDelay, int maximumAttempts) {
    }

    public RetryAttempt fail(String id, long timestamp) {
        throw new UnsupportedOperationException("TODO");
    }

    public Optional<RetryAttempt> fail(String id, long observedGeneration, long timestamp) {
        throw new UnsupportedOperationException("TODO");
    }

    public List<RetryAttempt> pollDue(long timestamp, int limit) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean succeed(String id, long observedGeneration) {
        throw new UnsupportedOperationException("TODO");
    }

    public int size() {
        throw new UnsupportedOperationException("TODO");
    }

    public record RetryAttempt(String id, int attempt, long dueAt, long generation) {
    }
}
