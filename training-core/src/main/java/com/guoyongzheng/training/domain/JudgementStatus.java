package com.guoyongzheng.training.domain;

/** Legal persisted states for an isolated coding judgement. */
public enum JudgementStatus {
    QUEUED,
    RUNNING,
    PASSED,
    FAILED,
    TIMED_OUT,
    ENVIRONMENT_ERROR
}
