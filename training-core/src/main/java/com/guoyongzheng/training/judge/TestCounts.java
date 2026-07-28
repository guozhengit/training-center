package com.guoyongzheng.training.judge;

/** Conservatively extracted test counts; null/null means unknown. */
public record TestCounts(Integer passedCount, Integer failedCount) {

    public static final TestCounts UNKNOWN = new TestCounts(null, null);

    public TestCounts {
        if ((passedCount == null) != (failedCount == null)) {
            throw new IllegalArgumentException("test counts must both be known or both unknown");
        }
        if (passedCount != null && (passedCount < 0 || failedCount < 0)) {
            throw new IllegalArgumentException("test counts must not be negative");
        }
    }

    public boolean known() {
        return passedCount != null;
    }
}
