package com.guoyongzheng.exam.ai.retry;

import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

public final class RetryExecutor {
    private RetryExecutor() {}

    public static <T> T execute(
            CheckedOperation<T> operation,
            RetryPolicy policy,
            Sleeper sleeper,
            DoubleSupplier random)
            throws Exception {
        throw new UnsupportedOperationException("TODO");
    }

    @FunctionalInterface
    public interface CheckedOperation<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long delayMillis) throws Exception;
    }

    public record RetryPolicy(
            int maxAttempts,
            long initialDelayMillis,
            long maxDelayMillis,
            Predicate<? super Exception> transientClassifier) {}
}
