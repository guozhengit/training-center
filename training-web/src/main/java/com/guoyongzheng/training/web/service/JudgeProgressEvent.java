package com.guoyongzheng.training.web.service;

/**
 * Represents a stage event emitted during the judge lifecycle via SSE.
 */
public record JudgeProgressEvent(Stage stage, String message) {

    public enum Stage {
        VALIDATING,
        SANDBOX_PREPARING,
        SANDBOX_READY,
        JUDGE_RUNNING,
        PERSISTING,
        COMPLETED,
        FAILED
    }

    public static JudgeProgressEvent of(Stage stage, String message) {
        return new JudgeProgressEvent(stage, message);
    }
}
