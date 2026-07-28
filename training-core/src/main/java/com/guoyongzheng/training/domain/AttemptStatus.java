package com.guoyongzheng.training.domain;

/** Legal persisted states for one question attempt. */
public enum AttemptStatus {
    CREATED,
    IN_PROGRESS,
    SUBMITTED,
    FINISHED,
    SKIPPED
}
