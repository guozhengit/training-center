package com.guoyongzheng.training.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自研令牌桶限流器（Sentinel/Guava RateLimiter 的教学版实现）。
 * 桶容量 capacity，每秒填充 rate 个令牌；请求来一个取一个，取到则放行，取到则拒绝。
 * 支持突发：桶满时令牌攒着，瞬间可以放一批请求。
 */
@Service
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private double tokens;
    private final double capacity;
    private final double refillRatePerSec;
    private long lastRefillNanos;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();

    public TokenBucketRateLimiter() {
        this.capacity = 10;        // 桶容量：突发最多 10 个
        this.refillRatePerSec = 2; // 每秒补 2 个（QPS=2）
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            accepted.incrementAndGet();
            return true;
        }
        rejected.incrementAndGet();
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSec * refillRatePerSec);
        lastRefillNanos = now;
    }

    public Map<String, Object> stats() {
        refill();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacity", capacity);
        m.put("refillRatePerSec", refillRatePerSec);
        m.put("currentTokens", String.format("%.2f", tokens));
        m.put("accepted", accepted.get());
        m.put("rejected", rejected.get());
        return m;
    }

    /** 故障复现：连续打 N 次，看放行/拒绝比例 */
    public Map<String, Object> burst(int n) {
        log.info("DIAG[19-rate-limit] start burst={}", n);
        int ok = 0;
        int fail = 0;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (tryAcquire()) ok++; else fail++;
        }
        long ms = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("DIAG[19-rate-limit] done total={} accepted={} rejected={} cost={}ms", n, ok, fail, ms);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case", "19 令牌桶限流");
        m.put("rootCause", "令牌桶：桶容量 capacity，每秒补 rate 个；突发请求先消耗桶里攒的令牌，补不上就拒绝");
        m.put("observed", "总=" + n + "，放行=" + ok + "，拒绝=" + fail + "，耗时=" + ms + "ms（前 10 个突发放行，后面按 2/s 放行）");
        m.put("howToVerify", "日志里看 DIAG[19-rate-limit] accepted/rejected；对比漏桶（匀速）vs 令牌桶（允许突发）");
        m.put("stats", stats());
        return m;
    }
}
