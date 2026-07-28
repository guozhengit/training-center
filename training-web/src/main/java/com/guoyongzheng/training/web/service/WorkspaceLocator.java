package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.web.config.TrainingProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Component
public class WorkspaceLocator {
    private final String configuredWorkspace;

    public WorkspaceLocator(TrainingProperties trainingProperties) {
        this.configuredWorkspace = trainingProperties.workspace() == null
                ? "" : trainingProperties.workspace().trim();
    }

    public Path locate() {
        if (!configuredWorkspace.isBlank()) {
            return validate(Path.of(configuredWorkspace).toAbsolutePath().normalize());
        }
        return locateFrom(Path.of(System.getProperty("user.dir")));
    }

    Path locateFrom(Path start) {
        Path current = Objects.requireNonNull(start, "start").toAbsolutePath().normalize();
        while (current != null) {
            if (isWorkspaceRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate workspace root from " + start);
    }

    private static Path validate(Path candidate) {
        if (!isWorkspaceRoot(candidate)) {
            throw new IllegalStateException("Invalid training workspace: " + candidate);
        }
        return candidate;
    }

    private static boolean isWorkspaceRoot(Path candidate) {
        return Files.isDirectory(candidate.resolve("training-center/config"))
                && Files.isRegularFile(candidate.resolve("output/coding-ai-exam/catalog/questions.json"));
    }
}
