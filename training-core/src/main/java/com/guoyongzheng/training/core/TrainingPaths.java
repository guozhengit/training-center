package com.guoyongzheng.training.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The filesystem locations used by a local training-center installation.
 */
public record TrainingPaths(
        Path workspaceRoot,
        Path catalogRoot,
        Path runtimeRoot,
        Path sandboxRoot,
        Path exportRoot,
        Path logRoot,
        Path databaseRoot,
        Path jdkHome,
        Path mavenExecutable,
        Path pythonExecutable) {

    public TrainingPaths {
        workspaceRoot = requirePath(workspaceRoot, "workspaceRoot");
        catalogRoot = requirePath(catalogRoot, "catalogRoot");
        runtimeRoot = requirePath(runtimeRoot, "runtimeRoot");
        sandboxRoot = requirePath(sandboxRoot, "sandboxRoot");
        exportRoot = requirePath(exportRoot, "exportRoot");
        logRoot = requirePath(logRoot, "logRoot");
        databaseRoot = requirePath(databaseRoot, "databaseRoot");
        jdkHome = requirePath(jdkHome, "jdkHome");
        mavenExecutable = requirePath(mavenExecutable, "mavenExecutable");
        pythonExecutable = requirePath(pythonExecutable, "pythonExecutable");
    }

    private static Path requirePath(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
