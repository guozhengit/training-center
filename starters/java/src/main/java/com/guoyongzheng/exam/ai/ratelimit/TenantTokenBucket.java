package com.guoyongzheng.exam.ai.ratelimit;

public final class TenantTokenBucket {
    public TenantTokenBucket(long capacity, long refillTokensPerSecond) {}

    public boolean tryAcquire(String tenant, long tokens, long nowNanos) {
        throw new UnsupportedOperationException("TODO");
    }
}
