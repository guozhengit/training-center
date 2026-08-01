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

    /** Backward-compatible scheduling without per-dimension focus emphasis. */
    public ReviewEntry schedule(String questionId, ReviewEntry current, boolean passed, Integer oralTotal,
                                boolean answerUnlocked) {
        return schedule(questionId, current, passed, oralTotal, answerUnlocked, null);
    }

    /**
     * Schedules the next review. A non-blank {@code focusDimensions} (CSV of weak oral
     * dimensions, each scored 0-1) prevents interval advancement even on a pass so the
     * question is re-emphasized sooner until the weak dimensions are resolved.
     */
    public ReviewEntry schedule(String questionId, ReviewEntry current, boolean passed, Integer oralTotal,
                                boolean answerUnlocked, String focusDimensions) {
        return schedule(questionId, current, passed, oralTotal, answerUnlocked, focusDimensions, false);
    }

    /**
     * Schedules the next review with an explicit critical-failure flag. Used when the
     * caller overrides an otherwise passing oral total (for example a {@code factRestraint}
     * score of 0), forcing a reset to the one-day interval and a FAILED result.
     */
    public ReviewEntry schedule(String questionId, ReviewEntry current, boolean passed, Integer oralTotal,
                                boolean answerUnlocked, String focusDimensions, boolean criticalFailure) {
        if (questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("questionId must not be blank");
        }
        if (oralTotal != null && (oralTotal < 0 || oralTotal > 10)) {
            throw new IllegalArgumentException("oralTotal must be between 0 and 10");
        }

        int currentInterval = current == null ? INTERVALS[0] : current.intervalDays();
        int intervalIndex = intervalIndex(currentInterval);
        boolean failure = (oralTotal != null ? oralTotal < 6 : !passed) || criticalFailure;
        boolean weak = focusDimensions != null && !focusDimensions.isBlank();
        boolean advance = oralTotal != null ? oralTotal >= 8 && !weak : passed;
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
                nextInterval,
                focusDimensions);
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
