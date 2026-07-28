package com.guoyongzheng.training.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentDoctorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsMissingJdk() throws IOException {
        TrainingPaths paths = completePaths();
        Files.delete(paths.jdkHome());

        EnvironmentReport report = new DefaultEnvironmentDoctor().inspect(paths);

        assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(EnvironmentIssue::code).contains("JDK_MISSING");
    }

    @Test
    void reportsMissingMaven() throws IOException {
        TrainingPaths paths = completePaths();
        Files.delete(paths.mavenExecutable());

        EnvironmentReport report = new DefaultEnvironmentDoctor().inspect(paths);

        assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(EnvironmentIssue::code).contains("MAVEN_MISSING");
    }

    @Test
    void reportsMissingPython() throws IOException {
        TrainingPaths paths = completePaths();
        Files.delete(paths.pythonExecutable());

        EnvironmentReport report = new DefaultEnvironmentDoctor().inspect(paths);

        assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(EnvironmentIssue::code).contains("PYTHON_MISSING");
    }

    @Test
    void reportsMissingCatalog() throws IOException {
        TrainingPaths paths = completePaths();
        Files.delete(paths.catalogRoot().resolve("questions.json"));

        EnvironmentReport report = new DefaultEnvironmentDoctor().inspect(paths);

        assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(EnvironmentIssue::code).contains("CATALOG_MISSING");
    }

    @Test
    void acceptsWritableRuntimeDirectoryAndRemovesItsWriteProbe() throws IOException {
        TrainingPaths paths = completePaths();

        EnvironmentReport report = new DefaultEnvironmentDoctor().inspect(paths);

        assertThat(report.ready()).isTrue();
        assertThat(report.issues()).isEmpty();
        try (var entries = Files.list(paths.runtimeRoot())) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void reportsUnavailableRuntimeRootWithoutLeakingAWriteProbe() throws IOException {
        TrainingPaths paths = completePaths();
        Files.delete(paths.runtimeRoot());
        Files.createFile(paths.runtimeRoot());

        EnvironmentReport report = new DefaultEnvironmentDoctor().inspect(paths);

        assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(EnvironmentIssue::code).contains("RUNTIME_NOT_WRITABLE");
        try (var siblings = Files.list(paths.runtimeRoot().getParent())) {
            assertThat(siblings)
                    .noneMatch(path -> path.getFileName().toString().startsWith("environment-doctor-"));
        }
    }

    @Test
    void exposesTheConfiguredDatabaseRoot() throws IOException {
        TrainingPaths paths = completePaths();

        assertThat(paths.databaseRoot()).isEqualTo(temporaryDirectory.resolve("workspace/output/training-runtime/database"));
    }

    private TrainingPaths completePaths() throws IOException {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path catalogRoot = Files.createDirectories(workspaceRoot.resolve("output/coding-ai-exam/catalog"));
        Files.writeString(catalogRoot.resolve("questions.json"), "{}\n");
        Path runtimeRoot = Files.createDirectory(workspaceRoot.resolve("output/training-runtime"));
        Path databaseRoot = runtimeRoot.resolve("database");
        Path jdkHome = Files.createDirectory(workspaceRoot.resolve("jdk"));
        Path mavenExecutable = Files.createFile(workspaceRoot.resolve("mvn.cmd"));
        Path pythonExecutable = Files.createFile(workspaceRoot.resolve("python.exe"));

        return new TrainingPaths(
                workspaceRoot,
                catalogRoot,
                runtimeRoot,
                runtimeRoot.resolve("sandboxes"),
                runtimeRoot.resolve("exports"),
                runtimeRoot.resolve("logs"),
                databaseRoot,
                jdkHome,
                mavenExecutable,
                pythonExecutable);
    }
}
