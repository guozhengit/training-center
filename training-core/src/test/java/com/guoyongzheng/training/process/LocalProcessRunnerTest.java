package com.guoyongzheng.training.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProcessRunnerTest {

    private static final int OUTPUT_CAP = 64 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsSuccessfulOutputAndEnvironmentOverrideWithoutExpandingTheRequestEnvironment() {
        ProcessRequest request = request(
                command("environment", "PYTEST_ADDOPTS"),
                Duration.ofSeconds(5),
                OUTPUT_CAP,
                Map.of("PYTEST_ADDOPTS", "--explicit-task7-option"));

        ProcessResult result = new LocalProcessRunner().run(request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.stdout()).isEqualTo("--explicit-task7-option");
        assertThat(result.stderr()).isEmpty();
        assertThat(request.environment())
                .containsExactly(Map.entry("PYTEST_ADDOPTS", "--explicit-task7-option"));
    }

    @Test
    void returnsCompletedNonzeroExitAsTestFailure() {
        ProcessResult result = new LocalProcessRunner().run(request(
                command("fail"),
                Duration.ofSeconds(5),
                OUTPUT_CAP,
                Map.of()));

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(result.stderr()).isEqualTo("expected failure");
    }

    @Test
    void returnsEnvironmentErrorWhenExecutableCannotLaunch() {
        ProcessResult result = new LocalProcessRunner().run(new ProcessRequest(
                List.of("task7-definitely-missing-executable-42"),
                temporaryDirectory,
                Duration.ofSeconds(2),
                OUTPUT_CAP,
                OUTPUT_CAP,
                Map.of()));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.ENVIRONMENT_ERROR);
        assertThat(result.stderr()).doesNotContain("PATH=").doesNotContain("TASK7_PROCESS_TOKEN");
    }

    @Test
    void returnsEnvironmentErrorForInvalidWorkingDirectory() {
        ProcessResult result = new LocalProcessRunner().run(new ProcessRequest(
                command("success"),
                temporaryDirectory.resolve("missing"),
                Duration.ofSeconds(2),
                OUTPUT_CAP,
                OUTPUT_CAP,
                Map.of()));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.failureKind()).isEqualTo(FailureKind.ENVIRONMENT_ERROR);
    }

    @Test
    void rejectsUnrepresentableTimeoutBeforeLaunchingAChild() throws Exception {
        Path pidFile = temporaryDirectory.resolve("oversized-timeout-pid.txt");
        try {
            ProcessResult result = new LocalProcessRunner().run(request(
                    command("record-pid-and-sleep", pidFile.toString()),
                    Duration.ofSeconds(Long.MAX_VALUE),
                    OUTPUT_CAP,
                    Map.of()));

            assertThat(result.exitCode()).isEqualTo(-1);
            assertThat(result.failureKind()).isEqualTo(FailureKind.ENVIRONMENT_ERROR);
            assertThat(pidFile).doesNotExist();
        } finally {
            terminateRecordedProcesses(pidFile);
        }
    }

    @Test
    void rejectsRepresentableTimeoutAboveTheSupportedBoundBeforeLaunching()
            throws Exception {
        Path pidFile = temporaryDirectory.resolve("bounded-timeout-pid.txt");
        try {
            ProcessResult result = new LocalProcessRunner().run(request(
                    command("record-pid-and-exit", pidFile.toString()),
                    Duration.ofDays(2),
                    OUTPUT_CAP,
                    Map.of()));

            assertThat(result.exitCode()).isEqualTo(-1);
            assertThat(result.failureKind()).isEqualTo(FailureKind.ENVIRONMENT_ERROR);
            assertThat(pidFile).doesNotExist();
        } finally {
            terminateRecordedProcesses(pidFile);
        }
    }

    @Test
    void removesUnrequestedParentAndJudgeEnvironmentVariables() throws Exception {
        String secondAllowed = isWindows() ? "TEMP" : "HOME";
        String secondAllowedValue = temporaryDirectory.toString();
        String javaHome = System.getenv("JAVA_HOME");
        assertThat(javaHome).isNotBlank();
        ProcessBuilder harness = new ProcessBuilder(command(
                "nested-environment",
                temporaryDirectory.toString(),
                "TASK7_PARENT_SENTINEL",
                "MAVEN_OPTS",
                "JAVA_TOOL_OPTIONS",
                "PYTHONPATH",
                "PYTEST_ADDOPTS",
                "PATH",
                secondAllowed,
                "JAVA_HOME"));
        harness.environment().put("TASK7_PARENT_SENTINEL", "parent-secret");
        harness.environment().put("MAVEN_OPTS", "-Dtask7.parent=true");
        harness.environment().put("JAVA_TOOL_OPTIONS", "-Dtask7.parent=true");
        harness.environment().put("PYTHONPATH", "task7-parent-pythonpath");
        harness.environment().put("PYTEST_ADDOPTS", "--task7-parent");
        harness.environment().put("PATH", "task7-minimal-path");
        harness.environment().put(secondAllowed, secondAllowedValue);
        harness.environment().put("JAVA_HOME", javaHome);

        Process process = harness.start();
        String stdout = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String stderr = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(stderr).isZero();
        assertThat(stdout).isEqualTo(
                "<missing>|<missing>|<missing>|<missing>|<missing>|"
                        + "task7-minimal-path|" + secondAllowedValue + "|" + javaHome);
    }

    @Test
    void mavenSmokeUsesPreservedJdk17JavaHomeWithARestrictedPath() {
        assertThat(Runtime.version().feature()).isEqualTo(17);
        String javaHome = System.getenv("JAVA_HOME");
        assertThat(javaHome).isNotBlank();

        ProcessResult result = new LocalProcessRunner().run(new ProcessRequest(
                List.of(mavenExecutable().toString(), "-version"),
                temporaryDirectory,
                Duration.ofSeconds(20),
                OUTPUT_CAP,
                OUTPUT_CAP,
                Map.of("PATH", restrictedPath())));

        assertThat(result.failureKind()).as(result.stderr()).isEqualTo(FailureKind.NONE);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).contains("Java version: 17");
    }

    @Test
    void windowsMinimalEnvironmentMatchesRequiredKeysCaseInsensitively() {
        Map<String, String> inherited = new LinkedHashMap<>();
        inherited.put("Path", "windows-path");
        inherited.put("java_home", "inherited-java");
        inherited.put("TASK7_PARENT_SENTINEL", "parent-secret");
        inherited.put("MAVEN_OPTS", "-Dparent=true");
        inherited.put("JAVA_TOOL_OPTIONS", "-Dparent=true");
        inherited.put("PYTHONPATH", "parent-pythonpath");
        inherited.put("PYTEST_ADDOPTS", "--parent");

        Map<String, String> selected = LocalProcessRunner.minimalEnvironment(
                inherited,
                Map.of(
                        "java_home", "explicit-java",
                        "PYTEST_ADDOPTS", "--explicit"),
                true);

        assertThat(selected).containsExactly(
                Map.entry("PATH", "windows-path"),
                Map.entry("JAVA_HOME", "explicit-java"),
                Map.entry("PYTEST_ADDOPTS", "--explicit"));
    }

    @Test
    void unixMinimalEnvironmentMatchesInheritedKeysExactly() {
        Map<String, String> inherited = new LinkedHashMap<>();
        inherited.put("Path", "shadow-path");
        inherited.put("PATH", "unix-path");
        inherited.put("java_home", "shadow-java");
        inherited.put("JAVA_HOME", "unix-java");
        inherited.put("TASK7_PARENT_SENTINEL", "parent-secret");

        Map<String, String> selected = LocalProcessRunner.minimalEnvironment(
                inherited,
                Map.of("Path", "explicit-shadow"),
                false);

        assertThat(selected).containsExactly(
                Map.entry("PATH", "unix-path"),
                Map.entry("JAVA_HOME", "unix-java"),
                Map.entry("Path", "explicit-shadow"));
    }

    @Test
    void capsBothStreamsAtExactUtf8BoundaryAndMarksTruncation() {
        ProcessResult result = new LocalProcessRunner().run(request(
                command("flood"),
                Duration.ofSeconds(10),
                OUTPUT_CAP,
                Map.of()));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stderrTruncated()).isTrue();
        assertThat(result.stdout()).endsWith(ProcessResult.TRUNCATION_MARKER);
        assertThat(result.stderr()).endsWith(ProcessResult.TRUNCATION_MARKER);
        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSize(OUTPUT_CAP);
        assertThat(result.stderr().getBytes(StandardCharsets.UTF_8)).hasSize(OUTPUT_CAP);
        assertThat(result.stdout()).doesNotContain("\uFFFD");
        assertThat(result.stderr()).doesNotContain("\uFFFD");
    }

    @Test
    void enforcesThe64KibHardCapWhenTheRequestAllowsMore() {
        ProcessResult result = new LocalProcessRunner().run(request(
                command("flood"),
                Duration.ofSeconds(10),
                OUTPUT_CAP * 2,
                Map.of()));

        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSize(OUTPUT_CAP);
        assertThat(result.stderr().getBytes(StandardCharsets.UTF_8)).hasSize(OUTPUT_CAP);
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stderrTruncated()).isTrue();
    }

    @Test
    void truncatesBeforeAPartialMultibyteCodePointAtAnOddLimit() {
        int oddLimit = ProcessResult.TRUNCATION_MARKER
                .getBytes(StandardCharsets.UTF_8).length + 2;

        ProcessResult result = new LocalProcessRunner().run(request(
                command("flood"),
                Duration.ofSeconds(10),
                oddLimit,
                Map.of()));

        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSize(oddLimit);
        assertThat(result.stderr().getBytes(StandardCharsets.UTF_8)).hasSize(oddLimit);
        assertThat(result.stdout()).isEqualTo("  " + ProcessResult.TRUNCATION_MARKER);
        assertThat(result.stderr()).isEqualTo("  " + ProcessResult.TRUNCATION_MARKER);
        assertThat(result.stdout()).doesNotContain("\uFFFD");
        assertThat(result.stderr()).doesNotContain("\uFFFD");
    }

    @Test
    void cleansUpObservedChildWhenItsParentExitsNormally() throws Exception {
        Path pidFile = temporaryDirectory.resolve("normal-exit-child-pid.txt");
        try {
            ProcessResult result = new LocalProcessRunner().run(request(
                    command("spawn-child-and-exit", pidFile.toString()),
                    Duration.ofSeconds(10),
                    OUTPUT_CAP,
                    Map.of()));

            assertThat(result.exitCode()).isZero();
            assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
            assertSingleRecordedProcessIsDead(pidFile);
            assertNoProcessReaderThreads();
        } finally {
            terminateRecordedProcesses(pidFile);
        }
    }

    @Test
    void timesOutAndTerminatesChildAndGrandchildBeforeReturning() throws Exception {
        Path pidFile = temporaryDirectory.resolve("timeout-pids.txt");

        ProcessResult result = new LocalProcessRunner().run(request(
                command("tree", pidFile.toString()),
                Duration.ofSeconds(2),
                OUTPUT_CAP,
                Map.of()));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.TIMED_OUT);
        assertRecordedProcessesAreDead(pidFile);
        assertNoProcessReaderThreads();
    }

    @Test
    void interruptionTerminatesTheTreeAndRestoresInterruptStatus() throws Exception {
        Path pidFile = temporaryDirectory.resolve("interrupt-pids.txt");
        AtomicReference<ProcessResult> result = new AtomicReference<>();
        AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            result.set(new LocalProcessRunner().run(request(
                    command("tree", pidFile.toString()),
                    Duration.ofSeconds(30),
                    OUTPUT_CAP,
                    Map.of())));
            interruptedOnReturn.set(Thread.currentThread().isInterrupted());
            finished.countDown();
        }, "task7-interrupted-caller");
        caller.start();
        awaitFile(pidFile, Duration.ofSeconds(5));

        caller.interrupt();

        assertThat(finished.await(8, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().exitCode()).isEqualTo(-1);
        assertThat(result.get().timedOut()).isFalse();
        assertThat(result.get().failureKind()).isEqualTo(FailureKind.ENVIRONMENT_ERROR);
        assertThat(interruptedOnReturn).isTrue();
        assertRecordedProcessesAreDead(pidFile);
        assertNoProcessReaderThreads();
    }

    @Test
    void processResultAcceptsOnlyTheFourExactOutcomeShapes() {
        assertThat(new ProcessResult(
                0, false, "", "", Duration.ZERO, FailureKind.NONE).failureKind())
                .isEqualTo(FailureKind.NONE);
        assertThat(new ProcessResult(
                7, false, "", "", Duration.ZERO, FailureKind.TEST_FAILURE).failureKind())
                .isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(new ProcessResult(
                -2, false, "", "", Duration.ZERO, FailureKind.TEST_FAILURE).failureKind())
                .isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(new ProcessResult(
                -1, true, "", "", Duration.ZERO, FailureKind.TIMED_OUT).failureKind())
                .isEqualTo(FailureKind.TIMED_OUT);
        assertThat(new ProcessResult(
                -1, false, "", "", Duration.ZERO, FailureKind.ENVIRONMENT_ERROR).failureKind())
                .isEqualTo(FailureKind.ENVIRONMENT_ERROR);
    }

    @Test
    void processResultRejectsEveryMismatchedOutcomeShape() {
        assertThatThrownBy(() -> result(1, false, FailureKind.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(-1, false, FailureKind.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(0, false, FailureKind.TEST_FAILURE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(-1, false, FailureKind.TEST_FAILURE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(1, true, FailureKind.TEST_FAILURE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(0, true, FailureKind.TIMED_OUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(-1, false, FailureKind.TIMED_OUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(0, false, FailureKind.ENVIRONMENT_ERROR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(-1, true, FailureKind.ENVIRONMENT_ERROR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProcessRequest request(
            List<String> command,
            Duration timeout,
            int cap,
            Map<String, String> environment) {
        return new ProcessRequest(
                command,
                temporaryDirectory,
                timeout,
                cap,
                cap,
                environment);
    }

    private static List<String> command(String... arguments) {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java");
        Path testClasses;
        Path productionClasses;
        try {
            testClasses = Path.of(
                    ChildMain.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
            productionClasses = Path.of(
                    LocalProcessRunner.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(testClasses
                + System.getProperty("path.separator")
                + productionClasses);
        command.add(ChildMain.class.getName());
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static Path mavenExecutable() {
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null || mavenHome.isBlank()) {
            mavenHome = System.getProperty("maven.home");
        }
        assertThat(mavenHome).as("MAVEN_HOME or maven.home").isNotBlank();
        Path executable = Path.of(
                mavenHome,
                "bin",
                isWindows() ? "mvn.cmd" : "mvn");
        assertThat(executable).isRegularFile();
        return executable;
    }

    private static String restrictedPath() {
        if (!isWindows()) {
            return "/usr/bin:/bin";
        }
        String systemRoot = System.getenv("SystemRoot");
        assertThat(systemRoot).isNotBlank();
        return Path.of(systemRoot, "System32").toString();
    }

    private static void awaitFile(Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(file).isRegularFile();
    }

    private static void assertRecordedProcessesAreDead(Path pidFile) throws Exception {
        awaitFile(pidFile, Duration.ofSeconds(2));
        List<Long> pids = Files.readAllLines(pidFile).stream()
                .filter(line -> !line.isBlank())
                .map(Long::parseLong)
                .toList();
        assertThat(pids).hasSize(2);
        for (long pid : pids) {
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    .as("PID %s must be dead", pid)
                    .isFalse();
        }
    }

    private static void assertSingleRecordedProcessIsDead(Path pidFile) throws Exception {
        awaitFile(pidFile, Duration.ofSeconds(2));
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("PID %s must be dead", pid)
                .isFalse();
    }

    private static void terminateRecordedProcesses(Path pidFile) throws Exception {
        if (!Files.isRegularFile(pidFile)) {
            return;
        }
        for (String line : Files.readAllLines(pidFile)) {
            if (line.isBlank()) {
                continue;
            }
            ProcessHandle.of(Long.parseLong(line.trim())).ifPresent(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            boolean anyAlive = Files.readAllLines(pidFile).stream()
                    .filter(line -> !line.isBlank())
                    .map(String::trim)
                    .mapToLong(Long::parseLong)
                    .anyMatch(pid ->
                            ProcessHandle.of(pid)
                                    .map(ProcessHandle::isAlive)
                                    .orElse(false));
            if (!anyAlive) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static void assertNoProcessReaderThreads() {
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread ->
                        thread.isAlive()
                                && thread.getName().startsWith("training-process-reader-"));
    }

    public static final class ChildMain {

        private ChildMain() {
        }

        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "success" -> System.out.print("success");
                case "environment" -> System.out.print(
                        System.getenv().getOrDefault(args[1], "<missing>"));
                case "fail" -> {
                    System.err.print("expected failure");
                    System.exit(7);
                }
                case "flood" -> flood();
                case "sleep" -> Thread.sleep(30_000);
                case "tree" -> startTree(Path.of(args[1]));
                case "child" -> startGrandchild(Path.of(args[1]));
                case "record-pid-and-sleep" -> recordPidAndSleep(Path.of(args[1]));
                case "record-pid-and-exit" -> Files.writeString(
                        Path.of(args[1]),
                        Long.toString(ProcessHandle.current().pid()),
                        StandardCharsets.UTF_8);
                case "spawn-child-and-exit" -> spawnChildAndExit(Path.of(args[1]));
                case "nested-environment" -> nestedEnvironment(args);
                case "environment-many" -> environmentMany(args);
                case "leaf" -> Thread.sleep(30_000);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }

        private static void flood() throws IOException {
            byte[] block = "汉".repeat(4096).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20; i++) {
                System.out.write(block);
                System.err.write(block);
            }
        }

        private static void startTree(Path pidFile) throws Exception {
            Process child = new ProcessBuilder(command("child", pidFile.toString())).start();
            child.waitFor();
        }

        private static void startGrandchild(Path pidFile) throws Exception {
            Process grandchild = new ProcessBuilder(command("leaf")).start();
            Files.writeString(
                    pidFile,
                    ProcessHandle.current().pid()
                            + System.lineSeparator()
                            + grandchild.pid()
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            grandchild.waitFor();
        }

        private static void recordPidAndSleep(Path pidFile) throws Exception {
            Files.writeString(
                    pidFile,
                    Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
            Thread.sleep(30_000);
        }

        private static void spawnChildAndExit(Path pidFile) throws Exception {
            Process child = new ProcessBuilder(command("leaf")).start();
            Files.writeString(
                    pidFile,
                    Long.toString(child.pid()),
                    StandardCharsets.UTF_8);
            Thread.sleep(300);
        }

        private static void nestedEnvironment(String[] args) {
            String[] childArguments = new String[args.length - 1];
            childArguments[0] = "environment-many";
            System.arraycopy(args, 2, childArguments, 1, args.length - 2);
            ProcessResult result = new LocalProcessRunner().run(new ProcessRequest(
                    command(childArguments),
                    Path.of(args[1]),
                    Duration.ofSeconds(5),
                    OUTPUT_CAP,
                    OUTPUT_CAP,
                    Map.of()));
            System.out.print(result.stdout());
            if (result.failureKind() != FailureKind.NONE) {
                System.err.print(result.stderr());
                System.exit(9);
            }
        }

        private static void environmentMany(String[] args) {
            System.out.print(java.util.stream.IntStream.range(1, args.length)
                    .mapToObj(index ->
                            System.getenv().getOrDefault(args[index], "<missing>"))
                    .collect(java.util.stream.Collectors.joining("|")));
        }
    }

    private static ProcessResult result(
            int exitCode,
            boolean timedOut,
            FailureKind failureKind) {
        return new ProcessResult(
                exitCode,
                timedOut,
                "",
                "",
                Duration.ZERO,
                failureKind);
    }
}
