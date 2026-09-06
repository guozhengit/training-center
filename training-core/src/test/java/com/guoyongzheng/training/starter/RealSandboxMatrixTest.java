package com.guoyongzheng.training.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.catalog.StarterMapping;
import com.guoyongzheng.training.domain.JudgementStatus;
import com.guoyongzheng.training.judge.JudgeRequest;
import com.guoyongzheng.training.judge.JudgementResult;
import com.guoyongzheng.training.judge.JudgeRunner;
import com.guoyongzheng.training.judge.MavenJudgeRunner;
import com.guoyongzheng.training.judge.PytestJudgeRunner;
import com.guoyongzheng.training.process.FailureKind;
import com.guoyongzheng.training.process.LocalProcessRunner;
import com.guoyongzheng.training.process.ProcessRequest;
import com.guoyongzheng.training.process.ProcessResult;
import com.guoyongzheng.training.sandbox.SandboxManifest;
import com.guoyongzheng.training.sandbox.SandboxPolicy;
import com.guoyongzheng.training.sandbox.SandboxService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in, real-process verification of every Starter through the production
 * sandbox and judge contracts. Ordinary Maven test runs exercise only the
 * bounded contract tests below and skip the 360-process matrix.
 */
@Tag("integration")
class RealSandboxMatrixTest {

    private static final String ENABLE_PROPERTY = "real.sandbox.matrix";
    private static final int MAVEN_LIMIT = 2;
    private static final int PYTEST_LIMIT = 4;
    private static final int LOG_LIMIT_BYTES = 8 * 1024;
    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(180);
    private static final String EXPECTED_OFFICIAL_HASH =
            "788F8BECF03F4C1886276BD449A0403706BBB5D98792A9D13C5E29E82E8C4FAC";
    private static final String EXPECTED_STARTER_HASH =
            "CB8A2ECCEEEC00312E3C608544F7CF14D5504CF6698B357261CBD5C8212F9E48";
    private static final String STARTER_PREFIX = "training-center/starters/";
    private static final Clock MANIFEST_CLOCK = Clock.fixed(
            Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC);
    private static final Set<String> TREE_EXCLUSIONS = Set.of(
            "target", ".pytest_cache", "__pycache__", ".venv");
    private static final List<String> STRUCTURAL_FAILURES = List.of(
            "compilation failure",
            "compilation error",
            "syntaxerror",
            "modulenotfounderror",
            "importerror",
            "error collecting",
            "no tests ran",
            "no tests were executed",
            "no tests matching pattern",
            "classnotfoundexception",
            "nosuchfileexception",
            "could not find artifact",
            "could not resolve dependencies",
            "dependencyresolutionexception",
            "fixture");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern KNOWN_QUARANTINE = Pattern.compile(
            "\\A\\.trash-(task21-(?:contract|starter-fails|reference-passes))-"
                    + "((?:B|A|I)\\d{3})-"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void verificationScriptIsOptInAndResolvesWorkspaceFromItsOwnLocation() throws IOException {
        Path script = StarterTestSupport.workspaceRoot()
                .resolve("training-center/tools/verify_all_starters.ps1");
        assertThat(script).isRegularFile();
        String body = Files.readString(script, StandardCharsets.UTF_8);
        assertThat(body)
                .contains("$PSScriptRoot")
                .contains("-Dreal.sandbox.matrix=true")
                .contains("-Dtest=RealSandboxMatrixTest")
                .contains("exit $mavenExit")
                .doesNotContain("Start-Process");
    }

    @Test
    void fullMatrixIsDisabledUnlessTheExactOptInValueIsTrue() {
        assertThat(matrixEnabled(null)).isFalse();
        assertThat(matrixEnabled("")).isFalse();
        assertThat(matrixEnabled("TRUE")).isFalse();
        assertThat(matrixEnabled(" true")).isFalse();
        assertThat(matrixEnabled("true")).isTrue();
    }

    @Test
    void concurrencyTrackerRejectsAnObservedLimitBreach() {
        ConcurrencyTracker tracker = new ConcurrencyTracker(1);
        tracker.enter();
        assertThatThrownBy(tracker::enter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concurrency limit exceeded");
        tracker.exit();
        assertThat(tracker.maximum()).isEqualTo(1);
    }

    @Test
    void reportPublicationIsAtomicAndRefusesAnIncompleteMatrix() throws IOException {
        Path report = temporaryDirectory.resolve("report.json");
        Files.writeString(report, "prior", StandardCharsets.UTF_8);
        MatrixReport incomplete = new MatrixReport(
                new TreeDigest(387, EXPECTED_OFFICIAL_HASH),
                new TreeDigest(387, EXPECTED_OFFICIAL_HASH),
                1,
                1,
                List.of(new Outcome(
                        "B001", "MAVEN", MatrixMode.CONTRACT, "PASS",
                        1, 0, 0, 0, "logs/B001.log",
                        "0".repeat(64), "1".repeat(64), "2".repeat(64))));

        assertThatThrownBy(() -> publishReport(report, incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 360");
        assertThat(Files.readString(report, StandardCharsets.UTF_8)).isEqualTo("prior");
        try (Stream<Path> children = Files.list(temporaryDirectory)) {
            assertThat(children.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")))
                    .isTrue();
        }
    }

    @Test
    void reportSchemaContainsNoProcessBodiesEnvironmentOrAbsolutePaths() {
        Outcome outcome = new Outcome(
                "B001", "MAVEN", MatrixMode.CONTRACT, "PASS",
                12, 0, 0, 0,
                "output/training-runtime/logs/starter-matrix/contract/B001.log",
                "0".repeat(64), "1".repeat(64), "2".repeat(64));
        List<Outcome> outcomes = new ArrayList<>();
        for (MatrixMode mode : MatrixMode.values()) {
            for (int index = 0; index < 120; index++) {
                outcomes.add(new Outcome(
                        "B001", "MAVEN", mode, "PASS", 12,
                        mode == MatrixMode.CONTRACT ? 0 : 1,
                        0, mode == MatrixMode.STARTER_FAILS ? 1 : 0,
                        outcome.logPath(), outcome.logSha256(),
                        outcome.sourceSha256(), outcome.starterSha256()));
            }
        }
        JsonNode report = reportJson(new MatrixReport(
                new TreeDigest(387, EXPECTED_OFFICIAL_HASH),
                new TreeDigest(387, EXPECTED_OFFICIAL_HASH),
                2,
                4,
                outcomes));
        String serialized = report.toString();
        assertThat(serialized)
                .contains("\"schemaVersion\":\"starter-matrix-report/v1\"")
                .doesNotContain("stdout")
                .doesNotContain("stderr")
                .doesNotContain("environment")
                .doesNotContain(StarterTestSupport.workspaceRoot().toString());
    }

    @Test
    void referenceSandboxIsPublishedFromTheFormalSourceWithoutPostPublicationMutation()
            throws IOException {
        StarterTestSupport.MappingRow row = StarterTestSupport.loadMappings().get(0);
        StarterMapping mapping = referenceMapping(row);
        Path examRoot = StarterTestSupport.examRoot();
        Path formalSource = examRoot.resolve(row.sandboxSourcePath());
        String before = sha256(formalSource);
        Path sandboxRoot = temporaryDirectory.resolve("reference-sandbox");
        SandboxService service = new SandboxService(
                new SandboxPolicy(examRoot, examRoot, sandboxRoot), MANIFEST_CLOCK);
        SandboxManifest manifest = null;
        try {
            manifest = service.create("task21-contract", row.questionId(), mapping);
            Path attempt = sandboxRoot.resolve("task21-contract").resolve(row.questionId());
            Path published = attempt.resolve("work").resolve(row.sandboxSourcePath());
            assertThat(service.isComplete(attempt)).isTrue();
            assertThat(manifest.starterSha256()).isEqualToIgnoringCase(before);
            assertThat(manifest.sandboxSourceSha256()).isEqualToIgnoringCase(before);
            assertThat(sha256(published)).isEqualTo(before);
            Path interruptedQuarantine = sandboxRoot.resolve(
                    ".trash-task21-contract-B001-00000000-0000-0000-0000-000000000001");
            Files.move(attempt, interruptedQuarantine, StandardCopyOption.ATOMIC_MOVE);
            recoverKnownQuarantines(
                    sandboxRoot, examRoot, examRoot, Set.of(row.questionId()));
            assertThat(Files.exists(interruptedQuarantine, LinkOption.NOFOLLOW_LINKS))
                    .isFalse();
        } finally {
            if (manifest != null || Files.exists(
                    sandboxRoot.resolve("task21-contract").resolve(row.questionId()),
                    LinkOption.NOFOLLOW_LINKS)) {
                Path attempt = sandboxRoot.resolve(
                        "task21-contract").resolve(row.questionId());
                if (Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) {
                    service.cleanup("task21-contract", row.questionId());
                }
            }
        }
        assertThat(sha256(formalSource)).isEqualTo(before);
    }

    @Test
    void cleanupRecoversInterruptedQuarantineInsidePerQuestionServiceRoot()
            throws IOException {
        StarterTestSupport.MappingRow row = StarterTestSupport.loadMappings().stream()
                .filter(item -> item.questionId().equals("B001"))
                .findFirst()
                .orElseThrow();
        Path sandboxRoot = temporaryDirectory.resolve("starter-verification");
        Path serviceRoot = sandboxRoot.resolve("contract-B001");
        SandboxService service = new SandboxService(
                new SandboxPolicy(
                        StarterTestSupport.examRoot(),
                        StarterTestSupport.starterRoot(),
                        serviceRoot),
                MANIFEST_CLOCK);
        SandboxManifest manifest = service.create(
                "task21-contract", row.questionId(), starterMapping(row));
        Path attempt = serviceRoot.resolve("task21-contract").resolve(row.questionId());
        Path quarantine = serviceRoot.resolve(
                ".trash-task21-contract-B001-00000000-0000-0000-0000-000000000001");

        try {
            assertThat(service.isComplete(attempt)).isTrue();
            assertThat(manifest.questionId()).isEqualTo(row.questionId());
            Files.move(attempt, quarantine, StandardCopyOption.ATOMIC_MOVE);

            cleanupKnownVerificationResidue(sandboxRoot, List.of(row));

            assertThat(Files.exists(sandboxRoot, LinkOption.NOFOLLOW_LINKS))
                    .isFalse();
        } finally {
            if (Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) {
                service.cleanup("task21-contract", row.questionId());
            }
            removeEmptyVerificationDirectories(sandboxRoot);
        }
    }

    @Test
    void starterExpectedFailureAllowsNotImplementedEvidence() {
        String output = "E NotImplementedError: TODO".toLowerCase(Locale.ROOT);

        assertThat(STRUCTURAL_FAILURES.stream()
                .filter(output::contains)
                .toList()).isEmpty();
        assertThat(output).contains("notimplementederror");
    }

    @Test
    void i013StarterExpectedFailureProducesPytestCounts() throws Exception {
        verifyPytestStarterExpectedFailure("I013");
    }

    @ParameterizedTest
    @ValueSource(strings = {"I021", "I023", "I026"})
    void aiStarterExpectedFailuresProducePytestCounts(String questionId) throws Exception {
        verifyPytestStarterExpectedFailure(questionId);
    }

    private void verifyPytestStarterExpectedFailure(String questionId) throws Exception {
        StarterTestSupport.MappingRow row = StarterTestSupport.loadMappings().stream()
                .filter(item -> item.questionId().equals(questionId))
                .findFirst()
                .orElseThrow();
        Path sandboxRoot = temporaryDirectory.resolve(questionId.toLowerCase(Locale.ROOT) + "-sandboxes");
        SandboxService service = new SandboxService(
                new SandboxPolicy(
                        StarterTestSupport.examRoot(),
                        StarterTestSupport.starterRoot(),
                        sandboxRoot),
                MANIFEST_CLOCK);
        SandboxManifest manifest = service.create(
                "task21-starter-fails", row.questionId(), starterMapping(row));
        Path attempt = sandboxRoot.resolve("task21-starter-fails")
                .resolve(row.questionId());

        try {
            JudgementResult result = judge(row, attempt, manifest, service);

            assertThat(result.passedCount())
                    .withFailMessage(
                            "stdoutLength=%s stderrLength=%s stdoutTruncated=%s "
                                    + "stderrTruncated=%s stdoutTail=%s stderrTail=%s",
                            result.stdout().length(),
                            result.stderr().length(),
                            result.stdout().endsWith(ProcessResult.TRUNCATION_MARKER),
                            result.stderr().endsWith(ProcessResult.TRUNCATION_MARKER),
                            tail(result.stdout()),
                            tail(result.stderr()))
                    .isNotNull();
            assertThat(result.failedCount()).isNotNull();
            assertThat(result.status()).isEqualTo(JudgementStatus.FAILED);
            assertThat(result.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
            Counts counts = exactCounts(row, attempt, result);
            assertThat(counts.tests()).isPositive();
            assertThat(counts.failed()).isPositive();
            assertThat((result.stdout() + "\n" + result.stderr())
                    .toLowerCase(Locale.ROOT)).contains("notimplementederror");
        } finally {
            service.cleanup("task21-starter-fails", row.questionId());
            removeEmptyVerificationDirectories(sandboxRoot);
        }
    }

    @Test
    void runAllRealSandboxChecks() throws Exception {
        Assumptions.assumeTrue(
                matrixEnabled(System.getProperty(ENABLE_PROPERTY)),
                "360-check real sandbox matrix requires -Dreal.sandbox.matrix=true");

        Path workspace = StarterTestSupport.workspaceRoot();
        Path report = workspace.resolve(
                "output/training-runtime/logs/starter-matrix-report.json");
        Files.createDirectories(report.getParent());
        Files.deleteIfExists(report);

        List<StarterTestSupport.MappingRow> mappings = StarterTestSupport.loadMappings();
        assertThat(mappings).hasSize(120);
        assertThat(treeDigest(StarterTestSupport.starterRoot()).sha256())
                .isEqualTo(EXPECTED_STARTER_HASH);
        TreeDigest before = treeDigest(StarterTestSupport.examRoot());
        assertOfficial(before);

        Path sandboxRoot = workspace.resolve(
                "output/training-runtime/sandboxes/starter-verification");
        cleanupKnownVerificationResidue(sandboxRoot, mappings);
        requireAbsentOrEmpty(sandboxRoot);
        Path logRoot = workspace.resolve(
                "output/training-runtime/logs/starter-matrix");
        Files.createDirectories(logRoot);

        ConcurrencyTracker mavenTracker = new ConcurrencyTracker(MAVEN_LIMIT);
        ConcurrencyTracker pytestTracker = new ConcurrencyTracker(PYTEST_LIMIT);
        List<Outcome> outcomes = new ArrayList<>(360);
        List<String> failures = new ArrayList<>();
        long fullStarted = System.nanoTime();
        try {
            for (MatrixMode mode : MatrixMode.values()) {
                System.out.printf("TASK21 mode=%s start mappings=120%n", mode.value());
                List<Outcome> group = executeMode(
                        mappings, mode, sandboxRoot, logRoot,
                        mavenTracker, pytestTracker);
                outcomes.addAll(group);
                group.stream()
                        .filter(outcome -> !"PASS".equals(outcome.status()))
                        .map(outcome -> outcome.questionId() + "/" + outcome.mode().value())
                        .forEach(failures::add);
                System.out.printf(
                        "TASK21 mode=%s complete passed=%d/120 elapsedSeconds=%d%n",
                        mode.value(),
                        group.stream().filter(item -> "PASS".equals(item.status())).count(),
                        Duration.ofNanos(System.nanoTime() - fullStarted).toSeconds());
            }
        } finally {
            removeEmptyVerificationDirectories(sandboxRoot);
        }

        TreeDigest after = treeDigest(StarterTestSupport.examRoot());
        assertOfficial(after);
        assertThat(after).isEqualTo(before);
        assertThat(treeDigest(StarterTestSupport.starterRoot()).sha256())
                .isEqualTo(EXPECTED_STARTER_HASH);
        assertThat(mavenTracker.maximum()).isBetween(1, MAVEN_LIMIT);
        assertThat(pytestTracker.maximum()).isBetween(1, PYTEST_LIMIT);
        assertThat(outcomes).hasSize(360);
        assertThat(failures)
                .withFailMessage("Real sandbox matrix failures: %s", failures)
                .isEmpty();
        assertThat(Files.exists(sandboxRoot, LinkOption.NOFOLLOW_LINKS)).isFalse();

        publishReport(report, new MatrixReport(
                before, after, mavenTracker.maximum(), pytestTracker.maximum(), outcomes));
        assertThat(report).isRegularFile();
        System.out.printf(
                "TASK21 all modes complete checks=360 durationSeconds=%d reportSha256=%s%n",
                Duration.ofNanos(System.nanoTime() - fullStarted).toSeconds(),
                sha256(report));
    }

    @Test
    void runJavaAndPythonRealSandboxSmoke() throws Exception {
        Assumptions.assumeTrue(
                "true".equals(System.getProperty("real.sandbox.smoke")),
                "real Java/Python smoke requires -Dreal.sandbox.smoke=true");
        List<StarterTestSupport.MappingRow> selected =
                StarterTestSupport.loadMappings().stream()
                        .filter(row -> row.questionId().equals("B001")
                                || row.questionId().equals("I001"))
                        .toList();
        assertThat(selected).hasSize(2);
        Path sandboxRoot = temporaryDirectory.resolve("smoke-sandboxes");
        Path logRoot = temporaryDirectory.resolve("smoke-logs");
        ConcurrencyTracker maven = new ConcurrencyTracker(MAVEN_LIMIT);
        ConcurrencyTracker pytest = new ConcurrencyTracker(PYTEST_LIMIT);
        for (MatrixMode mode : MatrixMode.values()) {
            List<Outcome> outcomes = executeMode(
                    selected, mode, sandboxRoot, logRoot, maven, pytest);
            assertThat(outcomes)
                    .extracting(Outcome::status)
                    .containsOnly("PASS");
        }
        removeEmptyVerificationDirectories(sandboxRoot);
        assertThat(Files.exists(sandboxRoot, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    private static List<Outcome> executeMode(
            List<StarterTestSupport.MappingRow> mappings,
            MatrixMode mode,
            Path sandboxRoot,
            Path logRoot,
            ConcurrencyTracker mavenTracker,
            ConcurrencyTracker pytestTracker) throws InterruptedException {
        ExecutorService mavenPool = Executors.newFixedThreadPool(MAVEN_LIMIT);
        ExecutorService pytestPool = Executors.newFixedThreadPool(PYTEST_LIMIT);
        try {
            List<IndexedFuture> submitted = new ArrayList<>();
            for (int index = 0; index < mappings.size(); index++) {
                StarterTestSupport.MappingRow row = mappings.get(index);
                ConcurrencyTracker tracker = "MAVEN".equals(row.runnerKind())
                        ? mavenTracker : pytestTracker;
                Callable<Outcome> task = () -> verifyOne(
                        row, mode, sandboxRoot, logRoot, tracker);
                ExecutorService pool = "MAVEN".equals(row.runnerKind())
                        ? mavenPool : pytestPool;
                submitted.add(new IndexedFuture(index, pool.submit(task)));
            }
            List<Outcome> ordered = new ArrayList<>(mappings.size());
            submitted.sort(Comparator.comparingInt(IndexedFuture::index));
            for (IndexedFuture item : submitted) {
                try {
                    ordered.add(item.future().get());
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    throw new IllegalStateException(
                            "matrix worker escaped its bounded failure handling",
                            cause == null ? exception : cause);
                }
            }
            return List.copyOf(ordered);
        } finally {
            shutdown(mavenPool);
            shutdown(pytestPool);
        }
    }

    private static Outcome verifyOne(
            StarterTestSupport.MappingRow row,
            MatrixMode mode,
            Path sandboxRoot,
            Path logRoot,
            ConcurrencyTracker tracker) {
        long started = System.nanoTime();
        String session = "task21-" + mode.value();
        String attemptId = row.questionId();
        Path serviceRoot = sandboxRoot.resolve(mode.value() + "-" + row.questionId());
        Path attempt = serviceRoot.resolve(session).resolve(attemptId);
        Path log = logRoot.resolve(mode.value()).resolve(row.questionId() + ".log");
        String status = "FAIL";
        int tests = 0;
        int passed = 0;
        int failed = 0;
        String sourceHash = "0".repeat(64);
        String starterHash = "0".repeat(64);
        String failureCode = "NONE";
        SandboxService service = null;
        try {
            boolean reference = mode == MatrixMode.REFERENCE_PASSES;
            Path examRoot = StarterTestSupport.examRoot();
            Path starterRoot = reference ? examRoot : StarterTestSupport.starterRoot();
            StarterMapping mapping = reference ? referenceMapping(row) : starterMapping(row);
            service = new SandboxService(
                    new SandboxPolicy(examRoot, starterRoot, serviceRoot), MANIFEST_CLOCK);
            SandboxManifest manifest = service.create(session, attemptId, mapping);
            sourceHash = manifest.originalSourceSha256();
            starterHash = manifest.starterSha256();
            assertThat(service.isComplete(attempt)).isTrue();

            tracker.enter();
            Counts counts;
            try {
                counts = switch (mode) {
                    case CONTRACT -> verifyContract(row, attempt);
                    case STARTER_FAILS ->
                            verifyStarterFailure(row, attempt, manifest, service);
                    case REFERENCE_PASSES ->
                            verifyReferencePass(row, attempt, manifest, service);
                };
            } finally {
                tracker.exit();
            }
            tests = counts.tests();
            passed = counts.passed();
            failed = counts.failed();
            status = "PASS";
        } catch (Throwable exception) {
            failureCode = safeFailureCode(exception);
        } finally {
            if (service != null && Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    service.cleanup(session, attemptId);
                } catch (Throwable cleanupFailure) {
                    status = "FAIL";
                    failureCode = "CLEANUP_" + cleanupFailure.getClass().getSimpleName();
                }
            }
            try {
                deleteIfEmpty(serviceRoot.resolve(session));
                deleteIfEmpty(serviceRoot);
            } catch (Throwable directoryFailure) {
                status = "FAIL";
                failureCode = "EMPTY_DIRECTORY_" + directoryFailure.getClass().getSimpleName();
            }
        }

        long durationMillis = Duration.ofNanos(
                Math.max(0L, System.nanoTime() - started)).toMillis();
        String relativeLog = "output/training-runtime/logs/starter-matrix/"
                + mode.value() + "/" + row.questionId() + ".log";
        try {
            writeBoundedLog(log, List.of(
                    "schema=starter-matrix-log/v1",
                    "questionId=" + row.questionId(),
                    "runnerKind=" + row.runnerKind(),
                    "mode=" + mode.value(),
                    "status=" + status,
                    "durationMillis=" + durationMillis,
                    "tests=" + tests,
                    "passed=" + passed,
                    "failed=" + failed,
                    "failureCode=" + failureCode,
                    "sourceSha256=" + sourceHash,
                    "starterSha256=" + starterHash));
            return new Outcome(
                    row.questionId(), row.runnerKind(), mode, status,
                    durationMillis, tests, passed, failed, relativeLog,
                    sha256(log), sourceHash, starterHash);
        } catch (IOException logFailure) {
            return new Outcome(
                    row.questionId(), row.runnerKind(), mode, "FAIL",
                    durationMillis, tests, passed, failed, relativeLog,
                    "0".repeat(64), sourceHash, starterHash);
        }
    }

    private static Counts verifyContract(
            StarterTestSupport.MappingRow row, Path attempt) {
        List<String> command;
        Path working;
        if ("MAVEN".equals(row.runnerKind())) {
            command = List.of("mvn.cmd", "-q", "-DskipTests", "test-compile");
            working = attempt.resolve("work/java");
        } else {
            String source = row.sandboxSourcePath();
            String module = source.substring(
                            "python/src/".length(), source.length() - ".py".length())
                    .replace('/', '.');
            command = List.of(
                    "python", "-c",
                    "import importlib,sys;"
                            + "sys.path.insert(0,'python/src');"
                            + "importlib.import_module(sys.argv[1])",
                    module);
            working = attempt.resolve("work");
        }
        ProcessResult result = new LocalProcessRunner().run(new ProcessRequest(
                command, working, PROCESS_TIMEOUT,
                OUTPUT_LIMIT_BYTES, OUTPUT_LIMIT_BYTES, childEnvironment()));
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        return Counts.ZERO;
    }

    private static Counts verifyStarterFailure(
            StarterTestSupport.MappingRow row,
            Path attempt,
            SandboxManifest manifest,
            SandboxService service) throws IOException {
        JudgementResult result = judge(row, attempt, manifest, service);
        assertThat(result.status()).isEqualTo(JudgementStatus.FAILED);
        assertThat(result.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(result.exitCode()).isNotZero();
        String output = (result.stdout() + "\n" + result.stderr())
                .toLowerCase(Locale.ROOT);
        assertThat(STRUCTURAL_FAILURES.stream()
                .filter(item -> output.contains(item))
                .toList()).isEmpty();

        Counts counts = exactCounts(row, attempt, result);
        assertThat(counts.tests()).isPositive();
        assertThat(counts.failed()).isPositive();
        assertThat(counts.tests() - counts.skipped()).isPositive();
        String evidence = "MAVEN".equals(row.runnerKind())
                ? exactMavenReport(row, attempt).body().toLowerCase(Locale.ROOT)
                : output;
        assertThat(evidence).containsAnyOf(
                "unsupportedoperationexception",
                "notimplementederror",
                "assertionfailederror",
                "assertionerror",
                "expected:");
        return counts;
    }

    private static Counts verifyReferencePass(
            StarterTestSupport.MappingRow row,
            Path attempt,
            SandboxManifest manifest,
            SandboxService service) throws IOException {
        JudgementResult result = judge(row, attempt, manifest, service);
        assertThat(result.status()).isEqualTo(JudgementStatus.PASSED);
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.exitCode()).isZero();
        Counts counts = exactCounts(row, attempt, result);
        assertThat(counts.tests()).isPositive();
        assertThat(counts.failed()).isZero();
        assertThat(counts.passed()).isPositive();
        return counts;
    }

    private static JudgementResult judge(
            StarterTestSupport.MappingRow row,
            Path attempt,
            SandboxManifest manifest,
            SandboxService service) {
        JudgeRunner runner = "MAVEN".equals(row.runnerKind())
                ? new MavenJudgeRunner(new LocalProcessRunner(), service)
                : new PytestJudgeRunner(new LocalProcessRunner(), service);
        return runner.judge(new JudgeRequest(
                attempt, manifest, PROCESS_TIMEOUT, childEnvironment()));
    }

    private static Counts exactCounts(
            StarterTestSupport.MappingRow row,
            Path attempt,
            JudgementResult result) throws IOException {
        if ("MAVEN".equals(row.runnerKind())) {
            return exactMavenReport(row, attempt).counts();
        }
        assertThat(result.passedCount()).isNotNull();
        assertThat(result.failedCount()).isNotNull();
        int passed = result.passedCount();
        int failed = result.failedCount();
        return new Counts(Math.addExact(passed, failed), passed, failed, 0);
    }

    private static BoundReport exactMavenReport(
            StarterTestSupport.MappingRow row, Path attempt) throws IOException {
        Path reports = attempt.resolve("work/java/target/surefire-reports");
        List<BoundReport> matches = new ArrayList<>();
        if (Files.isDirectory(reports)) {
            try (Stream<Path> files = Files.list(reports)) {
                for (Path file : files
                        .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                        .filter(path -> path.getFileName().toString().endsWith(".xml"))
                        .toList()) {
                    BoundReport report = readReport(file);
                    String simple = report.suiteName().substring(
                            report.suiteName().lastIndexOf('.') + 1);
                    if (simple.equals(row.testSelector())) {
                        matches.add(report);
                    }
                }
            }
        }
        assertThat(matches)
                .withFailMessage("Expected one exact Maven report for %s", row.questionId())
                .hasSize(1);
        return matches.get(0);
    }

    private static BoundReport readReport(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) {
            throw new IOException("selected JUnit report exceeds 1 MiB");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(path.toFile());
            Element root = document.getDocumentElement();
            Element suite;
            if ("testsuite".equals(root.getTagName())) {
                suite = root;
            } else if ("testsuites".equals(root.getTagName())) {
                NodeList suites = root.getElementsByTagName("testsuite");
                if (suites.getLength() != 1) {
                    throw new IOException("selected JUnit report must contain one suite");
                }
                suite = (Element) suites.item(0);
            } else {
                throw new IOException("unexpected selected JUnit report root");
            }
            Counts counts = new Counts(
                    count(suite, "tests"),
                    count(suite, "tests")
                            - count(suite, "failures")
                            - count(suite, "errors")
                            - count(suite, "skipped"),
                    count(suite, "failures") + count(suite, "errors"),
                    count(suite, "skipped"));
            assertThat(counts.passed()).isNotNegative();
            return new BoundReport(
                    suite.getAttribute("name"),
                    counts,
                    Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("cannot parse selected JUnit report", exception);
        }
    }

    private static int count(Element suite, String attribute) throws IOException {
        try {
            int value = Integer.parseInt(suite.getAttribute(attribute));
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IOException("invalid JUnit " + attribute + " count", exception);
        }
    }

    private static StarterMapping starterMapping(StarterTestSupport.MappingRow row) {
        if (!row.starterPath().startsWith(STARTER_PREFIX)) {
            throw new IllegalArgumentException("Starter path is outside the frozen root");
        }
        return new StarterMapping(
                row.questionId(),
                Path.of(row.starterPath().substring(STARTER_PREFIX.length())),
                Path.of(row.sandboxSourcePath()),
                RunnerKind.valueOf(row.runnerKind()),
                runtimeSelector(row));
    }

    private static StarterMapping referenceMapping(StarterTestSupport.MappingRow row) {
        return new StarterMapping(
                row.questionId(),
                Path.of(row.sandboxSourcePath()),
                Path.of(row.sandboxSourcePath()),
                RunnerKind.valueOf(row.runnerKind()),
                runtimeSelector(row));
    }

    private static String runtimeSelector(StarterTestSupport.MappingRow row) {
        if (!"MAVEN".equals(row.runnerKind())) {
            return row.testSelector();
        }
        StarterTestSupport.CatalogRow catalog = StarterTestSupport.loadCatalog().stream()
                .filter(item -> item.questionId().equals(row.questionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "catalog row missing for " + row.questionId()));
        String prefix = "java/src/test/java/";
        String testPath = catalog.testPath();
        if (!testPath.startsWith(prefix) || !testPath.endsWith(".java")) {
            throw new IllegalArgumentException(
                    "Java selected test path has an invalid catalog shape");
        }
        String selector = testPath
                .substring(prefix.length(), testPath.length() - ".java".length())
                .replace('/', '.');
        String simple = selector.substring(selector.lastIndexOf('.') + 1);
        if (!simple.equals(row.testSelector())) {
            throw new IllegalArgumentException(
                    "mapping selector does not match catalog test path for "
                            + row.questionId());
        }
        return selector;
    }

    private static Map<String, String> childEnvironment() {
        Map<String, String> values = new LinkedHashMap<>();
        copyEnvironment(values, "JAVA_HOME");
        copyEnvironment(values, "PATH");
        values.put("PYTHONUTF8", "1");
        values.put("PYTHONIOENCODING", "utf-8");
        return Map.copyOf(values);
    }

    private static void copyEnvironment(Map<String, String> values, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            values.put(name, value);
        }
    }

    private static void writeBoundedLog(Path log, List<String> lines) throws IOException {
        Files.createDirectories(log.getParent());
        String content = String.join("\n", lines) + "\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LOG_LIMIT_BYTES) {
            throw new IOException("bounded matrix log exceeds cap");
        }
        Files.write(
                log,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void publishReport(Path report, MatrixReport matrix) throws IOException {
        validateComplete(matrix);
        Files.createDirectories(report.getParent());
        byte[] bytes = JSON.writeValueAsBytes(reportJson(matrix));
        Path temporary = report.resolveSibling(
                "." + report.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        report,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("atomic report publication is unavailable", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateComplete(MatrixReport matrix) {
        if (matrix.outcomes().size() != 360) {
            throw new IllegalStateException("report requires exactly 360 outcomes");
        }
        for (MatrixMode mode : MatrixMode.values()) {
            long passing = matrix.outcomes().stream()
                    .filter(outcome -> outcome.mode() == mode)
                    .filter(outcome -> "PASS".equals(outcome.status()))
                    .count();
            if (passing != 120) {
                throw new IllegalStateException(
                        mode.value() + " report requires exactly 120 passes");
            }
        }
        assertOfficial(matrix.before());
        assertOfficial(matrix.after());
        if (!matrix.before().equals(matrix.after())) {
            throw new IllegalStateException("official tree changed");
        }
        if (matrix.mavenObserved() < 1 || matrix.mavenObserved() > MAVEN_LIMIT
                || matrix.pytestObserved() < 1 || matrix.pytestObserved() > PYTEST_LIMIT) {
            throw new IllegalStateException("observed concurrency is outside configured bounds");
        }
    }

    private static JsonNode reportJson(MatrixReport matrix) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "starter-matrix-report/v1");
        ObjectNode official = root.putObject("officialTree");
        official.put("beforeFileCount", matrix.before().fileCount());
        official.put("beforeSha256", matrix.before().sha256());
        official.put("afterFileCount", matrix.after().fileCount());
        official.put("afterSha256", matrix.after().sha256());
        ObjectNode concurrency = root.putObject("concurrency");
        ObjectNode configured = concurrency.putObject("configured");
        configured.put("maven", MAVEN_LIMIT);
        configured.put("pytest", PYTEST_LIMIT);
        ObjectNode observed = concurrency.putObject("observed");
        observed.put("maven", matrix.mavenObserved());
        observed.put("pytest", matrix.pytestObserved());
        ObjectNode summary = root.putObject("summary");
        addSummary(summary, "contract", matrix, MatrixMode.CONTRACT);
        addSummary(summary, "starterExpectedFailures", matrix, MatrixMode.STARTER_FAILS);
        addSummary(summary, "referencePasses", matrix, MatrixMode.REFERENCE_PASSES);
        ArrayNode results = root.putArray("results");
        matrix.outcomes().stream()
                .sorted(Comparator
                        .comparingInt((Outcome item) -> item.mode().ordinal())
                        .thenComparing(Outcome::questionId))
                .forEach(outcome -> {
                    ObjectNode item = results.addObject();
                    item.put("questionId", outcome.questionId());
                    item.put("runnerKind", outcome.runnerKind());
                    item.put("mode", outcome.mode().value());
                    item.put("status", outcome.status());
                    item.put("durationMillis", outcome.durationMillis());
                    item.put("tests", outcome.tests());
                    item.put("passed", outcome.passed());
                    item.put("failed", outcome.failed());
                    item.put("logPath", outcome.logPath());
                    item.put("logSha256", outcome.logSha256());
                    item.put("sourceSha256", outcome.sourceSha256());
                    item.put("starterSha256", outcome.starterSha256());
                });
        return root;
    }

    private static void addSummary(
            ObjectNode summary,
            String field,
            MatrixReport matrix,
            MatrixMode mode) {
        ObjectNode node = summary.putObject(field);
        node.put("passed", matrix.outcomes().stream()
                .filter(outcome -> outcome.mode() == mode)
                .filter(outcome -> "PASS".equals(outcome.status()))
                .count());
        node.put("total", 120);
    }

    private static TreeDigest treeDigest(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(normalized)) {
            files = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.getFileName().toString().endsWith(".pyc"))
                    .filter(path -> {
                        Path relative = normalized.relativize(path);
                        for (Path segment : relative) {
                            if (TREE_EXCLUSIONS.contains(segment.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(
                            path -> portable(normalized.relativize(path)),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
        MessageDigest aggregate = sha256Digest();
        for (Path file : files) {
            String entry = portable(normalized.relativize(file))
                    + ":" + sha256(file).toLowerCase(Locale.ROOT) + "\n";
            aggregate.update(entry.getBytes(StandardCharsets.UTF_8));
        }
        return new TreeDigest(files.size(), HexFormat.of().formatHex(
                aggregate.digest()).toUpperCase(Locale.ROOT));
    }

    private static void assertOfficial(TreeDigest digest) {
        if (digest.fileCount() != 387
                || !EXPECTED_OFFICIAL_HASH.equals(digest.sha256())) {
            throw new IllegalStateException(
                    "official tree freeze mismatch: files="
                            + digest.fileCount() + " sha256=" + digest.sha256());
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeFailureCode(Throwable exception) {
        String name = exception.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return "UNNAMED_FAILURE";
        }
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String tail(String value) {
        String compact = value.replace("\r", "\\r").replace("\n", "\\n");
        int start = Math.max(0, compact.length() - 800);
        return compact.substring(start);
    }

    private static boolean matrixEnabled(String value) {
        return "true".equals(value);
    }

    private static void requireAbsentOrEmpty(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IOException("verification sandbox root is not a plain directory");
        }
        try (Stream<Path> children = Files.list(root)) {
            if (children.findAny().isPresent()) {
                throw new IOException("verification sandbox root is not empty");
            }
        }
    }

    private static void cleanupKnownVerificationResidue(
            Path root,
            List<StarterTestSupport.MappingRow> mappings) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Set<String> ids = mappings.stream()
                .map(StarterTestSupport.MappingRow::questionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Path examRoot = StarterTestSupport.examRoot();
        Path starterRoot = StarterTestSupport.starterRoot();
        recoverKnownQuarantines(root, examRoot, starterRoot, ids);
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith("task21-")) {
                    cleanupAttemptsInServiceRoot(
                            root, child, examRoot, starterRoot, ids);
                } else if (MatrixMode.valuesAsPrefixes().stream()
                        .anyMatch(prefix -> name.startsWith(prefix))) {
                    recoverKnownQuarantines(child, examRoot, starterRoot, ids);
                    try (Stream<Path> sessions = Files.list(child)) {
                        for (Path session : sessions.toList()) {
                            cleanupAttemptsInServiceRoot(
                                    child, session, examRoot, starterRoot, ids);
                        }
                    }
                } else {
                    throw new IOException(
                            "verification root contains an unknown child");
                }
            }
        }
        removeEmptyVerificationDirectories(root);
    }

    private static void recoverKnownQuarantines(
            Path root,
            Path examRoot,
            Path starterRoot,
            Set<String> ids) throws IOException {
        List<Path> quarantines;
        try (Stream<Path> children = Files.list(root)) {
            quarantines = children
                    .filter(path -> path.getFileName().toString().startsWith(".trash-"))
                    .sorted()
                    .toList();
        }
        for (Path quarantine : quarantines) {
            Matcher matcher = KNOWN_QUARANTINE.matcher(
                    quarantine.getFileName().toString());
            if (!matcher.matches()
                    || !ids.contains(matcher.group(2))
                    || Files.isSymbolicLink(quarantine)
                    || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unknown verification quarantine");
            }
            String sessionName = matcher.group(1);
            String attemptId = matcher.group(2);
            Path session = root.resolve(sessionName);
            if (Files.exists(session, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(session)) {
                    throw new IOException("verification recovery session is unsafe");
                }
            } else {
                Files.createDirectory(session);
            }
            Path restored = session.resolve(attemptId);
            if (Files.exists(restored, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("verification recovery target already exists");
            }
            try {
                Files.move(quarantine, restored, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "atomic verification quarantine recovery unavailable", exception);
            }
            SandboxService service = new SandboxService(
                    new SandboxPolicy(examRoot, starterRoot, root), MANIFEST_CLOCK);
            service.cleanup(sessionName, attemptId);
        }
    }

    private static void cleanupAttemptsInServiceRoot(
            Path serviceRoot,
            Path session,
            Path examRoot,
            Path starterRoot,
            Set<String> ids) throws IOException {
        if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(session)
                || !session.getFileName().toString().startsWith("task21-")) {
            throw new IOException("verification service root contains an unknown session");
        }
        SandboxService service = new SandboxService(
                new SandboxPolicy(examRoot, starterRoot, serviceRoot), MANIFEST_CLOCK);
        try (Stream<Path> attempts = Files.list(session)) {
            for (Path attempt : attempts.toList()) {
                String id = attempt.getFileName().toString();
                if (!ids.contains(id)) {
                    throw new IOException("verification session contains an unknown attempt");
                }
                service.cleanup(session.getFileName().toString(), id);
            }
        }
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> children = Files.list(directory)) {
            if (children.findAny().isEmpty()) {
                Files.delete(directory);
            }
        }
    }

    private static void removeEmptyVerificationDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> directories;
        try (Stream<Path> paths = Files.walk(root)) {
            directories = paths
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        }
        for (Path directory : directories) {
            try (Stream<Path> children = Files.list(directory)) {
                if (children.findAny().isEmpty()) {
                    Files.delete(directory);
                }
            }
        }
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("matrix executor did not terminate");
            }
        }
    }

    private enum MatrixMode {
        CONTRACT("contract"),
        STARTER_FAILS("starter-fails"),
        REFERENCE_PASSES("reference-passes");

        private final String value;

        MatrixMode(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        private static List<String> valuesAsPrefixes() {
            return Stream.of(values())
                    .map(mode -> mode.value() + "-")
                    .toList();
        }
    }

    private static final class ConcurrencyTracker {

        private final int limit;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();

        private ConcurrencyTracker(int limit) {
            this.limit = limit;
        }

        private void enter() {
            int current = active.incrementAndGet();
            if (current > limit) {
                active.decrementAndGet();
                throw new IllegalStateException("concurrency limit exceeded: " + limit);
            }
            maximum.accumulateAndGet(current, Math::max);
        }

        private void exit() {
            int current = active.decrementAndGet();
            if (current < 0) {
                throw new IllegalStateException("concurrency tracker underflow");
            }
        }

        private int maximum() {
            return maximum.get();
        }
    }

    private record Counts(int tests, int passed, int failed, int skipped) {
        private static final Counts ZERO = new Counts(0, 0, 0, 0);
    }

    private record BoundReport(String suiteName, Counts counts, String body) {
    }

    private record Outcome(
            String questionId,
            String runnerKind,
            MatrixMode mode,
            String status,
            long durationMillis,
            int tests,
            int passed,
            int failed,
            String logPath,
            String logSha256,
            String sourceSha256,
            String starterSha256) {
    }

    private record TreeDigest(int fileCount, String sha256) {
    }

    private record MatrixReport(
            TreeDigest before,
            TreeDigest after,
            int mavenObserved,
            int pytestObserved,
            List<Outcome> outcomes) {
    }

    private record IndexedFuture(int index, Future<Outcome> future) {
    }
}
