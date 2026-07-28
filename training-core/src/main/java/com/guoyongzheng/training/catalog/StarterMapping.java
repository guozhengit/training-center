package com.guoyongzheng.training.catalog;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable mapping from a coding question to its isolated starter source. */
public record StarterMapping(
        String questionId,
        Path starterPath,
        Path sandboxSourcePath,
        RunnerKind runnerKind,
        String testSelector) {

    public StarterMapping {
        questionId = requireText(questionId, "questionId");
        starterPath = requireRelativeRawPath(starterPath, "starterPath").normalize();
        sandboxSourcePath = requireRelativeRawPath(sandboxSourcePath, "sandboxSourcePath").normalize();
        runnerKind = Objects.requireNonNull(runnerKind, "runnerKind");
        testSelector = requireText(testSelector, "testSelector");
    }

    private static Path requireRelativeRawPath(Path path, String name) {
        Objects.requireNonNull(path, name);
        if (path.getRoot() != null || path.isAbsolute() || path.getNameCount() == 0) {
            throw new IllegalArgumentException(name + " must be an unrooted relative path");
        }
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException(name + " must not contain '..'");
            }
        }
        return path;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
