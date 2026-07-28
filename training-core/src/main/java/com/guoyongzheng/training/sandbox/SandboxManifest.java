package com.guoyongzheng.training.sandbox;

import com.guoyongzheng.training.catalog.RunnerKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Complete provenance for one successfully created sandbox. */
public record SandboxManifest(
        String questionId,
        RunnerKind runnerKind,
        String originalProjectSha256,
        String originalSourceSha256,
        String starterSha256,
        String sandboxSourceSha256,
        String workTreeSha256,
        String selectedTest,
        String ownershipToken,
        Instant createdAt,
        String workPath,
        String sandboxSourcePath,
        String starterPath) {

    public SandboxManifest {
        questionId = text(questionId, "questionId");
        runnerKind = Objects.requireNonNull(runnerKind, "runnerKind");
        originalProjectSha256 = hash(originalProjectSha256, "originalProjectSha256");
        originalSourceSha256 = hash(originalSourceSha256, "originalSourceSha256");
        starterSha256 = hash(starterSha256, "starterSha256");
        sandboxSourceSha256 = hash(sandboxSourceSha256, "sandboxSourceSha256");
        workTreeSha256 = hash(workTreeSha256, "workTreeSha256");
        selectedTest = text(selectedTest, "selectedTest");
        ownershipToken = text(ownershipToken, "ownershipToken");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        workPath = relative(workPath, "workPath");
        sandboxSourcePath = relative(sandboxSourcePath, "sandboxSourcePath");
        starterPath = relative(starterPath, "starterPath");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String hash(String value, String name) {
        value = text(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return value;
    }

    private static String relative(String value, String name) {
        value = text(value, name).replace('\\', '/');
        Path path = Path.of(value);
        if (path.getRoot() != null || path.isAbsolute() || hasParentSegment(path)) {
            throw new IllegalArgumentException(name + " must be a contained relative path");
        }
        return path.normalize().toString().replace('\\', '/');
    }

    private static boolean hasParentSegment(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
