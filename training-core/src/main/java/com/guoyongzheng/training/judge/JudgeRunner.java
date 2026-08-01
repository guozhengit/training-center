package com.guoyongzheng.training.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.process.FailureKind;
import com.guoyongzheng.training.process.ProcessResult;
import com.guoyongzheng.training.sandbox.SandboxManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Judges one completed isolated coding attempt. */
@FunctionalInterface
public interface JudgeRunner {

    JudgementResult judge(JudgeRequest request);
}

final class JudgeSupport {

    static final int OUTPUT_CAP = 64 * 1024;
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "questionId",
            "runnerKind",
            "originalProjectSha256",
            "originalSourceSha256",
            "starterSha256",
            "sandboxSourceSha256",
            "workTreeSha256",
            "selectedTest",
            "ownershipToken",
            "createdAt",
            "workPath",
            "sandboxSourcePath",
            "starterPath");

    private JudgeSupport() {
    }

    static PreparedJudge prepare(
            JudgeRequest request,
            RunnerKind expectedKind,
            SandboxCompletionCheck completionCheck) throws IOException {
        if (request.manifest().runnerKind() != expectedKind) {
            throw new IOException("sandbox runner kind does not match judge runner");
        }
        Path attempt = request.attemptDirectory().toAbsolutePath().normalize();
        if (!completionCheck.isComplete(attempt)) {
            throw new IOException("sandbox is not complete");
        }
        Path manifestPath = attempt.resolve("manifest.json");
        JsonNode json = new ObjectMapper().readTree(manifestPath.toFile());
        if (!matches(json, request.manifest())) {
            throw new IOException("sandbox manifest does not match judge request");
        }

        Path attemptReal = attempt.toRealPath();
        Path work = containedDirectory(attemptReal, request.manifest().workPath());
        String selector = request.manifest().selectedTest();
        if (expectedKind == RunnerKind.MAVEN) {
            if (selector.contains("#")
                    || !selector.matches(
                            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                throw new IOException("Maven selector is invalid");
            }
            Path javaProject = containedDirectory(work, "java");
            Path selectedTest = javaProject.resolve(
                    "src/test/java/" + selector.replace('.', '/') + ".java");
            requireContainedFile(javaProject, selectedTest);
            return new PreparedJudge(javaProject, selector);
        }

        if (selector.contains("::")) {
            throw new IOException("pytest selector is invalid");
        }
        Path testPath;
        try {
            testPath = Path.of(selector);
        } catch (RuntimeException exception) {
            throw new IOException("pytest selector is invalid", exception);
        }
        if (testPath.isAbsolute()
                || testPath.getRoot() != null
                || hasParentSegment(testPath)
                || !testPath.startsWith(Path.of("python/tests"))
                || testPath.equals(Path.of("python/tests"))
                || !portable(testPath).endsWith(".py")) {
            throw new IOException("pytest selector is invalid");
        }
        requireContainedFile(work, work.resolve(testPath));
        return new PreparedJudge(work, portable(testPath));
    }

    static JudgementResult result(ProcessResult process, TestCounts counts) {
        JudgementStatus status = switch (process.failureKind()) {
            case NONE -> JudgementStatus.PASSED;
            case TEST_FAILURE -> JudgementStatus.FAILED;
            case TIMED_OUT -> JudgementStatus.TIMED_OUT;
            case ENVIRONMENT_ERROR -> JudgementStatus.ENVIRONMENT_ERROR;
        };
        TestCounts preserved = process.failureKind() == FailureKind.NONE
                        || process.failureKind() == FailureKind.TEST_FAILURE
                ? counts
                : TestCounts.UNKNOWN;
        return new JudgementResult(
                status,
                process.exitCode(),
                preserved,
                process.stdout(),
                process.stderr(),
                process.duration(),
                process.failureKind());
    }

    static JudgementResult environmentError(String message, long started) {
        return new JudgementResult(
                JudgementStatus.ENVIRONMENT_ERROR,
                -1,
                TestCounts.UNKNOWN,
                "",
                message,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - started)),
                FailureKind.ENVIRONMENT_ERROR);
    }

    private static Path containedDirectory(Path root, String relative) throws IOException {
        Path path;
        try {
            Path relativePath = Path.of(relative);
            if (relativePath.isAbsolute()
                    || relativePath.getRoot() != null
                    || hasParentSegment(relativePath)) {
                throw new IOException("sandbox directory is not contained");
            }
            path = root.resolve(relativePath).normalize();
        } catch (RuntimeException exception) {
            throw new IOException("sandbox directory is invalid", exception);
        }
        if (!Files.isDirectory(path) || Files.isSymbolicLink(path)) {
            throw new IOException("sandbox directory is missing");
        }
        Path real = path.toRealPath();
        if (!real.startsWith(root) || real.equals(root)) {
            throw new IOException("sandbox directory is not contained");
        }
        return real;
    }

    private static void requireContainedFile(Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException("selected test is missing");
        }
        if (!file.toRealPath().startsWith(root)) {
            throw new IOException("selected test is not contained");
        }
    }

    private static boolean matches(JsonNode json, SandboxManifest manifest) {
        if (json == null || !json.isObject()) {
            return false;
        }
        HashSet<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(MANIFEST_FIELDS)
                || fields.stream().anyMatch(field -> !json.path(field).isTextual())) {
            return false;
        }
        return text(json, "questionId").equals(manifest.questionId())
                && text(json, "runnerKind").equals(manifest.runnerKind().name())
                && text(json, "originalProjectSha256")
                        .equals(manifest.originalProjectSha256())
                && text(json, "originalSourceSha256")
                        .equals(manifest.originalSourceSha256())
                && text(json, "starterSha256").equals(manifest.starterSha256())
                && text(json, "sandboxSourceSha256")
                        .equals(manifest.sandboxSourceSha256())
                && text(json, "workTreeSha256").equals(manifest.workTreeSha256())
                && text(json, "selectedTest").equals(manifest.selectedTest())
                && text(json, "ownershipToken").equals(manifest.ownershipToken())
                && text(json, "createdAt").equals(manifest.createdAt().toString())
                && text(json, "workPath").equals(manifest.workPath())
                && text(json, "sandboxSourcePath").equals(manifest.sandboxSourcePath())
                && text(json, "starterPath").equals(manifest.starterPath());
    }

    private static String text(JsonNode json, String field) {
        return json.path(field).asText();
    }

    private static boolean hasParentSegment(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String portable(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    record PreparedJudge(Path workingDirectory, String selector) {
    }
}
