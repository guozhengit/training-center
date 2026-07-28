package com.guoyongzheng.training.review;

import com.guoyongzheng.training.persistence.ReviewRepository.ReviewEntry;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Applies the fixed 1/3/7/14-day spaced-review policy without persistence concerns. */
public final class ReviewScheduler {
    private static final int[] INTERVALS = {1, 3, 7, 14};

    private final Clock clock;

    public ReviewScheduler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReviewEntry schedule(String questionId, ReviewEntry current, boolean passed, Integer oralTotal,
                                boolean answerUnlocked) {
        if (questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("questionId must not be blank");
        }
        if (oralTotal != null && (oralTotal < 0 || oralTotal > 10)) {
            throw new IllegalArgumentException("oralTotal must be between 0 and 10");
        }

        int currentInterval = current == null ? INTERVALS[0] : current.intervalDays();
        int intervalIndex = intervalIndex(currentInterval);
        boolean failure = oralTotal != null ? oralTotal < 6 : !passed;
        boolean advance = oralTotal != null ? oralTotal >= 8 : passed;
        int nextInterval = failure ? INTERVALS[0]
                : advance && !answerUnlocked ? INTERVALS[Math.min(intervalIndex + 1, INTERVALS.length - 1)]
                : currentInterval;
        Instant now = clock.instant();

        return new ReviewEntry(
                questionId,
                (current == null ? 0 : current.wrongCount()) + (failure ? 1 : 0),
                (current == null ? 0 : current.reviewCount()) + 1,
                failure ? "FAILED" : "PASSED",
                now,
                now.plusSeconds(nextInterval * 86_400L),
                nextInterval);
    }

    private static int intervalIndex(int intervalDays) {
        for (int index = 0; index < INTERVALS.length; index++) {
            if (INTERVALS[index] == intervalDays) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unsupported review interval: " + intervalDays);
    }
}
