package com.guoyongzheng.training.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.catalog.StarterMapping;
import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.process.FailureKind;
import com.guoyongzheng.training.process.ProcessRequest;
import com.guoyongzheng.training.process.ProcessResult;
import com.guoyongzheng.training.process.ProcessRunner;
import com.guoyongzheng.training.sandbox.SandboxManifest;
import com.guoyongzheng.training.sandbox.SandboxPolicy;
import com.guoyongzheng.training.sandbox.SandboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeRunnerTest {

    private static final String HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void mavenUsesExactArgvAndContainedJavaWorkingDirectory() throws Exception {
        Fixture fixture = fixture(RunnerKind.MAVEN, "exam.SelectedTest");
        CapturingRunner process = new CapturingRunner(processResult(
                0,
                FailureKind.NONE,
                "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1"));

        JudgementResult result = new MavenJudgeRunner(process, path -> true)
                .judge(fixture.request());

        assertThat(process.request.get().command())
                .containsExactly("mvn.cmd", "-q", "-Dtest=exam.SelectedTest", "test");
        assertThat(process.request.get().workingDirectory())
                .isEqualTo(fixture.attempt.resolve("work/java").toAbsolutePath().normalize());
        assertThat(process.request.get().maxStdoutBytes()).isEqualTo(64 * 1024);
        assertThat(process.request.get().maxStderrBytes()).isEqualTo(64 * 1024);
        assertThat(result.status()).isEqualTo(JudgementStatus.PASSED);
        assertThat(result.counts()).isEqualTo(new TestCounts(2, 0));
    }

    @Test
    void pytestUsesExactArgvAndContainedWorkDirectory() throws Exception {
        Fixture fixture = fixture(RunnerKind.PYTEST, "python/tests/test_selected.py");
        CapturingRunner process = new CapturingRunner(processResult(
                0,
                FailureKind.NONE,
                "3 passed, 1 skipped in 0.18s"));

        JudgementResult result = new PytestJudgeRunner(process, path -> true)
                .judge(fixture.request());

        assertThat(process.request.get().command())
                .containsExactly(
                        "python",
                        "-m",
                        "pytest",
                        "-q",
                        "--tb=short",
                        "--junitxml=python/.training-judge-pytest.xml",
                        "python/tests/test_selected.py");
        assertThat(process.request.get().workingDirectory())
                .isEqualTo(fixture.attempt.resolve("work").toAbsolutePath().normalize());
        assertThat(result.status()).isEqualTo(JudgementStatus.PASSED);
        assertThat(result.counts()).isEqualTo(new TestCounts(3, 0));
    }

    @Test
    void pytestUsesJunitXmlCountsWhenCapturedOutputIsTruncated() throws Exception {
        Fixture fixture = fixture(RunnerKind.PYTEST, "python/tests/test_selected.py");

        JudgementResult result = new PytestJudgeRunner(
                request -> {
                    try {
                        Files.writeString(
                                request.workingDirectory()
                                        .resolve("python/.training-judge-pytest.xml"),
                                """
                                <?xml version="1.0" encoding="utf-8"?>
                                <testsuites>
                                  <testsuite name="pytest" tests="7" failures="2" errors="1" skipped="1" />
                                </testsuites>
                                """);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    return new ProcessResult(
                            1,
                            false,
                            "large failure output" + ProcessResult.TRUNCATION_MARKER,
                            "",
                            Duration.ofMillis(25),
                            FailureKind.TEST_FAILURE);
                },
                path -> true).judge(fixture.request());

        assertThat(result.status()).isEqualTo(JudgementStatus.FAILED);
        assertThat(result.counts()).isEqualTo(new TestCounts(3, 3));
    }

    @Test
    void completedTestFailureMapsToFailedAndCountsErrorsAsFailures() throws Exception {
        Fixture fixture = fixture(RunnerKind.PYTEST, "python/tests/test_selected.py");
        CapturingRunner process = new CapturingRunner(processResult(
                1,
                FailureKind.TEST_FAILURE,
                "2 passed, 1 failed, 1 error in 0.22s"));

        JudgementResult result = new PytestJudgeRunner(process, path -> true)
                .judge(fixture.request());

        assertThat(result.status()).isEqualTo(JudgementStatus.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.counts()).isEqualTo(new TestCounts(2, 2));
    }

    @Test
    void timeoutAndEnvironmentFailureMapToTerminalStatusesWithUnknownCounts()
            throws Exception {
        Fixture fixture = fixture(RunnerKind.MAVEN, "exam.SelectedTest");

        JudgementResult timedOut = new MavenJudgeRunner(
                request -> new ProcessResult(
                        -1,
                        true,
                        "",
                        "",
                        Duration.ofSeconds(2),
                        FailureKind.TIMED_OUT),
                path -> true).judge(fixture.request());
        JudgementResult environmentError = new MavenJudgeRunner(
                request -> new ProcessResult(
                        -1,
                        false,
                        "",
                        "missing executable",
                        Duration.ZERO,
                        FailureKind.ENVIRONMENT_ERROR),
                path -> true).judge(fixture.request());

        assertThat(timedOut.status()).isEqualTo(JudgementStatus.TIMED_OUT);
        assertThat(timedOut.counts()).isEqualTo(TestCounts.UNKNOWN);
        assertThat(environmentError.status()).isEqualTo(JudgementStatus.ENVIRONMENT_ERROR);
        assertThat(environmentError.counts()).isEqualTo(TestCounts.UNKNOWN);
    }

    @Test
    void leavesCountsUnknownWhenSummaryIsMissingOrAmbiguous() throws Exception {
        Fixture fixture = fixture(RunnerKind.MAVEN, "exam.SelectedTest");
        String twoSummaries = """
                Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
                Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
                """;

        JudgementResult ambiguous = new MavenJudgeRunner(
                request -> processResult(0, FailureKind.NONE, twoSummaries),
                path -> true).judge(fixture.request());
        JudgementResult missing = new MavenJudgeRunner(
                request -> processResult(0, FailureKind.NONE, "BUILD SUCCESS"),
                path -> true).judge(fixture.request());

        assertThat(ambiguous.counts()).isEqualTo(TestCounts.UNKNOWN);
        assertThat(missing.counts()).isEqualTo(TestCounts.UNKNOWN);
    }

    @Test
    void leavesCountsUnknownWhenEitherCapturedStreamWasTruncated() throws Exception {
        Fixture mavenFixture = fixture(RunnerKind.MAVEN, "exam.SelectedTest");
        Fixture pytestFixture = fixture(
                RunnerKind.PYTEST,
                "python/tests/test_selected.py");
        String marker = ProcessResult.TRUNCATION_MARKER;

        JudgementResult maven = new MavenJudgeRunner(
                request -> processResult(
                        0,
                        FailureKind.NONE,
                        "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n"
                                + marker),
                path -> true).judge(mavenFixture.request());
        JudgementResult pytest = new PytestJudgeRunner(
                request -> new ProcessResult(
                        0,
                        false,
                        "2 passed in 0.10s",
                        marker,
                        Duration.ofMillis(25),
                        FailureKind.NONE),
                path -> true).judge(pytestFixture.request());

        assertThat(maven.counts()).isEqualTo(TestCounts.UNKNOWN);
        assertThat(pytest.counts()).isEqualTo(TestCounts.UNKNOWN);
    }

    @Test
    void rejectsIncompleteSandboxManifestMismatchAndInvalidSelectorWithoutLaunching()
            throws Exception {
        Fixture fixture = fixture(RunnerKind.MAVEN, "exam.SelectedTest");
        CapturingRunner process = new CapturingRunner(processResult(0, FailureKind.NONE, ""));
        SandboxManifest mismatched = manifest(RunnerKind.MAVEN, "exam.OtherTest");
        JudgeRequest mismatchedRequest = new JudgeRequest(
                fixture.attempt,
                mismatched,
                Duration.ofSeconds(3),
                Map.of());

        JudgementResult incomplete = new MavenJudgeRunner(process, path -> false)
                .judge(fixture.request());
        JudgementResult mismatch = new MavenJudgeRunner(process, path -> true)
                .judge(mismatchedRequest);
        Fixture invalidFixture = fixture(RunnerKind.MAVEN, "../OutsideTest");
        JudgementResult invalid = new MavenJudgeRunner(process, path -> true)
                .judge(invalidFixture.request());

        assertThat(incomplete.status()).isEqualTo(JudgementStatus.ENVIRONMENT_ERROR);
        assertThat(mismatch.status()).isEqualTo(JudgementStatus.ENVIRONMENT_ERROR);
        assertThat(invalid.status()).isEqualTo(JudgementStatus.ENVIRONMENT_ERROR);
        assertThat(process.request).hasValue(null);
    }

    @Test
    void publicRunnerAcceptsGenuineTask6CompletionAndRejectsLaterTampering()
            throws Exception {
        Path root = temporaryDirectory.resolve("real-task6");
        Path examRoot = root.resolve("exam");
        Path starterRoot = root.resolve("starters");
        Path sandboxRoot = root.resolve("runtime/sandboxes");
        Files.createDirectories(examRoot.resolve("java/src/main/java/exam"));
        Files.createDirectories(examRoot.resolve("java/src/test/java/exam"));
        Files.createDirectories(starterRoot.resolve("java"));
        Files.writeString(examRoot.resolve("java/pom.xml"), "<project />\n");
        Files.writeString(
                examRoot.resolve("java/src/main/java/exam/Selected.java"),
                "package exam; public class Selected { }\n");
        Files.writeString(
                examRoot.resolve("java/src/test/java/exam/SelectedTest.java"),
                "package exam; class SelectedTest { }\n");
        Files.writeString(
                starterRoot.resolve("java/Selected.java"),
                "package exam; public class Selected { }\n");
        SandboxPolicy policy = new SandboxPolicy(examRoot, starterRoot, sandboxRoot);
        SandboxService sandboxService = new SandboxService(
                policy,
                Clock.fixed(
                        Instant.parse("2026-07-26T12:00:00Z"),
                        ZoneOffset.UTC));
        StarterMapping mapping = new StarterMapping(
                "B001",
                Path.of("java/Selected.java"),
                Path.of("java/src/main/java/exam/Selected.java"),
                RunnerKind.MAVEN,
                "exam.SelectedTest");
        SandboxManifest manifest =
                sandboxService.create("session-real", "attempt-real", mapping);
        Path attempt = sandboxRoot.resolve("session-real/attempt-real");
        JudgeRequest request = new JudgeRequest(
                attempt,
                manifest,
                Duration.ofSeconds(3),
                Map.of());
        CapturingRunner acceptedProcess =
                new CapturingRunner(processResult(0, FailureKind.NONE, ""));

        JudgementResult accepted =
                new MavenJudgeRunner(acceptedProcess, sandboxService).judge(request);

        assertThat(accepted.status()).isEqualTo(JudgementStatus.PASSED);
        assertThat(acceptedProcess.request.get()).isNotNull();

        Files.writeString(
                attempt.resolve("work/java/src/test/java/exam/SelectedTest.java"),
                "package exam; class TamperedTest { }\n");
        CapturingRunner rejectedProcess =
                new CapturingRunner(processResult(0, FailureKind.NONE, ""));

        JudgementResult rejected =
                new MavenJudgeRunner(rejectedProcess, sandboxService).judge(request);

        assertThat(rejected.status()).isEqualTo(JudgementStatus.ENVIRONMENT_ERROR);
        assertThat(rejectedProcess.request).hasValue(null);
    }

    private Fixture fixture(RunnerKind runnerKind, String selector) throws Exception {
        Path attempt = temporaryDirectory.resolve(
                runnerKind.name().toLowerCase() + "-" + Math.abs(selector.hashCode()));
        Path work = attempt.resolve("work");
        Files.createDirectories(work.resolve("java/src/test/java/exam"));
        Files.createDirectories(work.resolve("python/tests"));
        Files.writeString(
                work.resolve("java/src/test/java/exam/SelectedTest.java"),
                "class SelectedTest {}\n");
        Files.writeString(
                work.resolve("python/tests/test_selected.py"),
                "def test_selected(): pass\n");
        SandboxManifest manifest = manifest(runnerKind, selector);
        Files.createDirectories(attempt);
        writeManifest(attempt.resolve("manifest.json"), manifest);
        Files.writeString(attempt.resolve("COMPLETE"), "complete");
        return new Fixture(
                attempt,
                new JudgeRequest(
                        attempt,
                        manifest,
                        Duration.ofSeconds(3),
                        Map.of("TASK7_JUDGE_TOKEN", "override")));
    }

    private static SandboxManifest manifest(RunnerKind runnerKind, String selector) {
        return new SandboxManifest(
                "B001",
                runnerKind,
                HASH,
                HASH,
                HASH,
                HASH,
                HASH,
                selector,
                "owner",
                Instant.parse("2026-07-26T12:00:00Z"),
                "work",
                runnerKind == RunnerKind.MAVEN
                        ? "java/src/main/java/exam/Selected.java"
                        : "python/src/exam/selected.py",
                runnerKind == RunnerKind.MAVEN
                        ? "java/src/main/java/exam/Selected.java"
                        : "python/src/exam/selected.py");
    }

    private static void writeManifest(Path path, SandboxManifest manifest) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();
        json.put("questionId", manifest.questionId());
        json.put("runnerKind", manifest.runnerKind().name());
        json.put("originalProjectSha256", manifest.originalProjectSha256());
        json.put("originalSourceSha256", manifest.originalSourceSha256());
        json.put("starterSha256", manifest.starterSha256());
        json.put("sandboxSourceSha256", manifest.sandboxSourceSha256());
        json.put("workTreeSha256", manifest.workTreeSha256());
        json.put("selectedTest", manifest.selectedTest());
        json.put("ownershipToken", manifest.ownershipToken());
        json.put("createdAt", manifest.createdAt().toString());
        json.put("workPath", manifest.workPath());
        json.put("sandboxSourcePath", manifest.sandboxSourcePath());
        json.put("starterPath", manifest.starterPath());
        mapper.writeValue(path.toFile(), json);
    }

    private static ProcessResult processResult(
            int exitCode,
            FailureKind failureKind,
            String stdout) {
        return new ProcessResult(
                exitCode,
                false,
                stdout,
                "",
                Duration.ofMillis(25),
                failureKind);
    }

    private record Fixture(Path attempt, JudgeRequest request) {
    }

    private static final class CapturingRunner implements ProcessRunner {

        private final AtomicReference<ProcessRequest> request = new AtomicReference<>();
        private final ProcessResult result;

        private CapturingRunner(ProcessResult result) {
            this.result = result;
        }

        @Override
        public ProcessResult run(ProcessRequest request) {
            this.request.set(request);
            return result;
        }
    }
}
