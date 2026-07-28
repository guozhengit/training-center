package com.guoyongzheng.training.process;

/** Stable classification of a bounded process execution outcome. */
public enum FailureKind {
    NONE,
    TEST_FAILURE,
    TIMED_OUT,
    ENVIRONMENT_ERROR
}
