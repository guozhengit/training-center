package com.guoyongzheng.training.domain;

/** Legal persisted states for a training session. */
public enum SessionStatus {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    ABORTED
}
