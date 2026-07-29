package com.guoyongzheng.training.web.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Records judge execution metrics: attempt counts by outcome and execution duration.
 */
@Component
public class JudgeMetrics {
    private final Counter judgePassed;
    private final Counter judgeFailed;
    private final Counter judgeErrors;
    private final Timer judgeDuration;

    public JudgeMetrics(MeterRegistry registry) {
        this.judgePassed = Counter.builder("training.judge.outcome")
                .tag("result", "passed")
                .description("Number of judge runs that passed")
                .register(registry);
        this.judgeFailed = Counter.builder("training.judge.outcome")
                .tag("result", "failed")
                .description("Number of judge runs that failed tests")
                .register(registry);
        this.judgeErrors = Counter.builder("training.judge.outcome")
                .tag("result", "error")
                .description("Number of judge runs that errored")
                .register(registry);
        this.judgeDuration = Timer.builder("training.judge.duration")
                .description("Time spent executing judge runs")
                .register(registry);
    }

    public void recordPassed(Duration duration) {
        judgePassed.increment();
        judgeDuration.record(duration);
    }

    public void recordFailed(Duration duration) {
        judgeFailed.increment();
        judgeDuration.record(duration);
    }

    public void recordError() {
        judgeErrors.increment();
    }
}
