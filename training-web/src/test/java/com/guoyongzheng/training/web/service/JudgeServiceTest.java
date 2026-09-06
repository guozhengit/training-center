package com.guoyongzheng.training.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import com.guoyongzheng.training.web.config.TrainingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JudgeServiceTest {
    @TempDir
    Path tempDir;

    private TrainingDatabase database;

    @BeforeEach
    void setUp() {
        database = new TrainingDatabase(tempDir.resolve("training.db"));
        database.migrate();
    }

    @Test
    void sweepStaleSandboxesIgnoresInternalTrashSessionDirectories() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path trashAttempt = workspace
                .resolve("output/training-runtime/sandboxes/web-judge")
                .resolve(".trash-web-session-1")
                .resolve("web-attempt-1");
        Files.createDirectories(trashAttempt);
        Files.setLastModifiedTime(trashAttempt, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));

        JudgeService service = new JudgeService(
                new WorkspaceBackedDatabaseProvider(workspace, database),
                new TrainingProperties(workspace.toString(), null, null, null, 1),
                new ObjectMapper(),
                new JudgeMetrics(new SimpleMeterRegistry()));

        assertThatCode(service::sweepStaleSandboxes).doesNotThrowAnyException();
        assertThat(trashAttempt).isDirectory();
    }

    private static final class WorkspaceBackedDatabaseProvider extends TrainingDatabaseProvider {
        private final Path workspace;
        private final TrainingDatabase database;

        private WorkspaceBackedDatabaseProvider(Path workspace, TrainingDatabase database) {
            super(null, null);
            this.workspace = workspace;
            this.database = database;
        }

        @Override
        public Path workspace() {
            return workspace;
        }

        @Override
        public synchronized TrainingDatabase readyDatabase() {
            return database;
        }
    }
}
