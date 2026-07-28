package com.guoyongzheng.training.sandbox;

import com.guoyongzheng.training.catalog.RunnerKind;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable roots and project boundaries used by the sandbox service. */
public record SandboxPolicy(Path examRoot, Path starterRoot, Path sandboxRoot) {

    public SandboxPolicy {
        examRoot = absolute(examRoot, "examRoot");
        starterRoot = absolute(starterRoot, "starterRoot");
        sandboxRoot = absolute(sandboxRoot, "sandboxRoot");
        requireSeparate(sandboxRoot, examRoot, "examRoot");
        requireSeparate(sandboxRoot, starterRoot, "starterRoot");
    }

    public Path projectRoot(RunnerKind runnerKind) {
        Objects.requireNonNull(runnerKind, "runnerKind");
        return examRoot.resolve(runnerKind == RunnerKind.MAVEN ? "java" : "python");
    }

    private static Path absolute(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static void requireSeparate(Path sandbox, Path immutableRoot, String name) {
        if (sandbox.startsWith(immutableRoot) || immutableRoot.startsWith(sandbox)) {
            throw new IllegalArgumentException("sandboxRoot must be separate from " + name);
        }
    }
}
