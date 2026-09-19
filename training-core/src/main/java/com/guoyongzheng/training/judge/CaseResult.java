package com.guoyongzheng.training.judge;

/**
 * One executed test case, parsed from a surefire XML report.
 *
 * <p>{@code expected} / {@code actual} are best-effort: they are extracted from the assertion
 * message text produced by JUnit 5 and stay {@code null} when the message uses a shape we do not
 * recognise. Callers must fall back to {@code message} in that case.
 */
public record CaseResult(
        String name,
        String className,
        CaseStatus status,
        Long durationMillis,
        String message,
        String expected,
        String actual) {

    public CaseResult {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("case name is required");
        }
        status = status == null ? CaseStatus.PASSED : status;
        message = message == null || message.isBlank() ? null : message;
        expected = expected == null || expected.isBlank() ? null : expected;
        actual = actual == null || actual.isBlank() ? null : actual;
    }

    public static CaseResult passed(String name, String className, Long durationMillis) {
        return new CaseResult(name, className, CaseStatus.PASSED, durationMillis, null, null, null);
    }

    public boolean failed() {
        return status == CaseStatus.FAILED || status == CaseStatus.ERROR;
    }
}
