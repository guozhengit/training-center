package com.guoyongzheng.training.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public interface EnvironmentDoctor {
    EnvironmentReport inspect(TrainingPaths paths);

    static EnvironmentDoctor create() {
        return new DefaultEnvironmentDoctor();
    }
}

final class DefaultEnvironmentDoctor implements EnvironmentDoctor {
    @Override
    public EnvironmentReport inspect(TrainingPaths paths) {
        List<EnvironmentIssue> issues = new ArrayList<>();
        checkDirectory(paths.jdkHome(), "JDK_MISSING", "JDK home is missing or is not a directory.", issues);
        checkFile(paths.mavenExecutable(), "MAVEN_MISSING", "Maven executable is missing or is not a file.", issues);
        checkFile(paths.pythonExecutable(), "PYTHON_MISSING", "Python executable is missing or is not a file.", issues);
        checkFile(paths.catalogRoot().resolve("questions.json"), "CATALOG_MISSING",
                "Catalog questions.json is missing or is not a file.", issues);
        checkRuntimeWritable(paths.runtimeRoot(), issues);
        return new EnvironmentReport(issues.isEmpty(), issues);
    }

    private void checkDirectory(Path path, String code, String message, List<EnvironmentIssue> issues) {
        if (!Files.isDirectory(path)) {
            issues.add(error(code, message));
        }
    }

    private void checkFile(Path path, String code, String message, List<EnvironmentIssue> issues) {
        if (!Files.isRegularFile(path)) {
            issues.add(error(code, message));
        }
    }

    private void checkRuntimeWritable(Path runtimeRoot, List<EnvironmentIssue> issues) {
        if (!Files.isDirectory(runtimeRoot)) {
            issues.add(error("RUNTIME_NOT_WRITABLE", "Runtime root is missing or is not a directory."));
            return;
        }

        Path probe = null;
        try {
            probe = Files.createTempFile(runtimeRoot, "environment-doctor-", ".probe");
            Files.writeString(probe, "probe", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            issues.add(error("RUNTIME_NOT_WRITABLE", "Runtime root cannot be written."));
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException exception) {
                    issues.add(error("RUNTIME_NOT_WRITABLE", "Runtime write probe could not be removed."));
                }
            }
        }
    }

    private EnvironmentIssue error(String code, String message) {
        return new EnvironmentIssue(code, message, Severity.ERROR);
    }
}
