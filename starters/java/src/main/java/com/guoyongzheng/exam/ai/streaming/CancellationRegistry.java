package com.guoyongzheng.exam.ai.streaming;

public final class CancellationRegistry {
    public CancellationToken register(String requestId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean cancel(String requestId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean isCancelled(String requestId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean complete(String requestId, CancellationToken expectedToken) {
        throw new UnsupportedOperationException("TODO");
    }

    public static final class CancellationToken {
        public boolean cancel() {
            throw new UnsupportedOperationException("TODO");
        }

        public boolean isCancelled() {
            throw new UnsupportedOperationException("TODO");
        }
    }
}
