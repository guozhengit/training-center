package com.guoyongzheng.training.starter;

import com.guoyongzheng.training.process.FailureKind;
import com.guoyongzheng.training.process.LocalProcessRunner;
import com.guoyongzheng.training.process.ProcessRequest;
import com.guoyongzheng.training.process.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StarterMatrixTest {

    private static final int OUTPUT_CAP = 64 * 1024;
    private static final Set<String> COPY_EXCLUSIONS =
            Set.of("target", ".pytest_cache", "__pycache__");

    @TempDir
    Path temporaryDirectory;

    static Stream<StarterTestSupport.MappingRow> selectedMappings() {
        MatrixMode mode = MatrixMode.parse(System.getProperty("starter.mode"));
        return mode == null
                ? Stream.of((StarterTestSupport.MappingRow) null)
                : StarterTestSupport.selectedMappingStream();
    }

    @Test
    void modeParserDisablesBlankAndStrictlyRejectsUnsupportedNonblankValues() {
        assertThat(MatrixMode.parse(null)).isNull();
        assertThat(MatrixMode.parse("")).isNull();
        assertThat(MatrixMode.parse(" \t")).isNull();
        assertThat(MatrixMode.parse("contract")).isEqualTo(MatrixMode.CONTRACT);
        assertThat(MatrixMode.parse("starter-fails")).isEqualTo(MatrixMode.STARTER_FAILS);
        assertThat(MatrixMode.parse("reference-passes")).isEqualTo(MatrixMode.REFERENCE_PASSES);
        for (String invalid : new String[]{"CONTRACT", "contract ", "unknown"}) {
            assertThatIllegalArgumentException()
                    .as(String.valueOf(invalid))
                    .isThrownBy(() -> MatrixMode.parse(invalid))
                    .withMessageContaining("blank disables")
                    .withMessageContaining("nonblank");
        }
        assertThat(childEnvironment()).doesNotContainKey("MAVEN_OPTS");
    }

    @Test
    void blankModeDisablesParameterizedMatrixBeforeStarterLookup() {
        String previous = System.getProperty("starter.mode");
        try {
            System.clearProperty("starter.mode");
            assertThat(selectedMappings().toList()).containsExactly(
                    (StarterTestSupport.MappingRow) null);
            System.setProperty("starter.mode", " \t");
            assertThat(selectedMappings().toList()).containsExactly(
                    (StarterTestSupport.MappingRow) null);
            System.setProperty("starter.mode", "not-a-mode");
            assertThatIllegalArgumentException().isThrownBy(StarterMatrixTest::selectedMappings);
        } finally {
            restoreProperty("starter.mode", previous);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectedMappings")
    void selectedStarterSatisfiesRequestedMatrixMode(StarterTestSupport.MappingRow mapping)
            throws IOException {
        MatrixMode mode = MatrixMode.parse(System.getProperty("starter.mode"));
        Assumptions.assumeTrue(mode != null, "Starter matrix disabled without explicit mode");
        Path starter = StarterTestSupport.resolvePortable(
                StarterTestSupport.workspaceRoot(), mapping.starterPath());
        assertThat(Files.isRegularFile(starter, LinkOption.NOFOLLOW_LINKS))
                .withFailMessage("Missing Starter for %s: %s",
                        mapping.questionId(), mapping.starterPath())
                .isTrue();
        StarterTestSupport.assertSafeExisting(
                StarterTestSupport.starterRoot(),
                mapping.sandboxSourcePath(),
                "Starter " + mapping.questionId());

        Path isolatedExam = temporaryDirectory.resolve(mapping.questionId() + "-exam");
        copyOfficialTree(isolatedExam);
        if (mode != MatrixMode.REFERENCE_PASSES) {
            Path destination = StarterTestSupport.resolvePortable(
                    isolatedExam, mapping.sandboxSourcePath());
            Files.copy(starter, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        switch (mode) {
            case CONTRACT -> assertContract(mapping, isolatedExam);
            case STARTER_FAILS -> {
                assertContract(mapping, isolatedExam);
                assertExpectedBehavioralFailure(
                        mapping, isolatedExam, runSelectedTest(mapping, isolatedExam));
            }
            case REFERENCE_PASSES -> assertReferenceSuccess(
                    mapping, isolatedExam, runSelectedTest(mapping, isolatedExam));
        }
    }

    @Test
    void reportBindingRejectsExitZeroWithNoSelectedTestsAndUnrelatedMavenXml()
            throws IOException {
        StarterTestSupport.MappingRow python = new StarterTestSupport.MappingRow(
                "I001", "training-center/starters/python/src/ai_exam/i001.py",
                "python/src/ai_exam/i001.py", "PYTEST", "python/tests/test_i001.py");
        Path pythonXml = pytestReportPath(temporaryDirectory, python);
        Files.createDirectories(pythonXml.getParent());
        Files.writeString(pythonXml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites><testsuite name="pytest" tests="0" failures="0"
                  errors="0" skipped="0"/></testsuites>
                """, StandardCharsets.UTF_8);
        ProcessResult success = new ProcessResult(
                0, false, "", "", Duration.ofMillis(1), FailureKind.NONE);
        assertThatThrownBy(() -> assertReferenceSuccess(
                python, temporaryDirectory, success))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("zero selected tests");

        StarterTestSupport.MappingRow maven = new StarterTestSupport.MappingRow(
                "B001", "training-center/starters/java/src/main/java/Example.java",
                "java/src/main/java/Example.java", "MAVEN", "ExpectedTest");
        Path reports = temporaryDirectory.resolve("java/target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-UnrelatedTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="other.UnrelatedTest" tests="1" failures="1"
                  errors="0" skipped="0">
                  <testcase name="wrong"><failure>AssertionFailedError expected</failure></testcase>
                </testsuite>
                """, StandardCharsets.UTF_8);
        ProcessResult failure = new ProcessResult(
                1, false, "", "", Duration.ofMillis(1), FailureKind.TEST_FAILURE);
        assertThatThrownBy(() -> assertExpectedBehavioralFailure(
                maven, temporaryDirectory, failure))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exact selected Maven report");
    }

    @Test
    void boundMavenReportSuppliesCountsAndFailureEvidence() throws IOException {
        StarterTestSupport.MappingRow mapping = new StarterTestSupport.MappingRow(
                "B001", "training-center/starters/java/src/main/java/Example.java",
                "java/src/main/java/Example.java", "MAVEN", "ExpectedTest");
        Path reports = temporaryDirectory.resolve("java/target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-example.ExpectedTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="example.ExpectedTest" tests="2" failures="1"
                  errors="0" skipped="0">
                  <testcase name="fails">
                    <failure type="org.opentest4j.AssertionFailedError">expected: 1 but was: 0</failure>
                  </testcase>
                  <testcase name="passes"/>
                </testsuite>
                """, StandardCharsets.UTF_8);
        ProcessResult failure = new ProcessResult(
                1, false, "", "", Duration.ofMillis(1), FailureKind.TEST_FAILURE);

        assertExpectedBehavioralFailure(mapping, temporaryDirectory, failure);
    }

    private static void assertContract(
            StarterTestSupport.MappingRow mapping, Path isolatedExam) {
        ProcessResult result;
        if ("MAVEN".equals(mapping.runnerKind())) {
            result = run(mapping, isolatedExam, List.of(
                    executable("starter.maven.executable", "mvn.cmd"),
                    "-q",
                    "-f",
                    "java/pom.xml",
                    "-DskipTests",
                    "test-compile"));
        } else {
            String source = mapping.sandboxSourcePath();
            String module = source.substring("python/src/".length(), source.length() - 3)
                    .replace('/', '.');
            result = run(mapping, isolatedExam, List.of(
                    executable("starter.python.executable", "python"),
                    "-c",
                    "import importlib,sys;"
                            + "sys.path.insert(0,'python/src');"
                            + "importlib.import_module(sys.argv[1])",
                    module));
        }
        assertSuccessful(mapping, "contract", result);
    }

    private static ProcessResult runSelectedTest(
            StarterTestSupport.MappingRow mapping, Path isolatedExam) throws IOException {
        if ("MAVEN".equals(mapping.runnerKind())) {
            return run(mapping, isolatedExam, List.of(
                    executable("starter.maven.executable", "mvn.cmd"),
                    "-q",
                    "-f",
                    "java/pom.xml",
                    "-Dtest=" + mapping.testSelector(),
                    "test"));
        }
        Path report = pytestReportPath(isolatedExam, mapping);
        Files.deleteIfExists(report);
        return run(mapping, isolatedExam, List.of(
                executable("starter.python.executable", "python"),
                "-m",
                "pytest",
                "-q",
                "--junitxml=" + isolatedExam.relativize(pytestReportPath(
                        isolatedExam, mapping)).toString().replace('\\', '/'),
                mapping.testSelector()));
    }

    private static ProcessResult run(
            StarterTestSupport.MappingRow mapping, Path workingDirectory, List<String> argv) {
        Duration timeout = Duration.ofSeconds(Long.getLong("starter.timeout.seconds", 60L));
        return new LocalProcessRunner().run(new ProcessRequest(
                argv,
                workingDirectory,
                timeout,
                OUTPUT_CAP,
                OUTPUT_CAP,
                childEnvironment()));
    }

    private static Map<String, String> childEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        copyEnvironment(environment, "JAVA_HOME");
        return Map.copyOf(environment);
    }

    private static void copyEnvironment(Map<String, String> target, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String executable(String property, String fallback) {
        String configured = System.getProperty(property);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static void assertSuccessful(
            StarterTestSupport.MappingRow mapping, String stage, ProcessResult result) {
        assertThat(result.failureKind())
                .withFailMessage(diagnostic(mapping, stage, result))
                .isEqualTo(FailureKind.NONE);
        assertThat(result.exitCode())
                .withFailMessage(diagnostic(mapping, stage, result))
                .isZero();
    }

    private static void assertExpectedBehavioralFailure(
            StarterTestSupport.MappingRow mapping,
            Path isolatedExam,
            ProcessResult result) throws IOException {
        String diagnostic = diagnostic(mapping, "starter-fails", result);
        assertThat(result.failureKind()).withFailMessage(diagnostic)
                .isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(result.exitCode()).withFailMessage(diagnostic).isNotZero();

        String output = (result.stdout() + "\n" + result.stderr()).toLowerCase(Locale.ROOT);
        List<String> structuralFailures = List.of(
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
                "dependencyresolutionexception");
        assertThat(structuralFailures.stream().filter(output::contains).toList())
                .withFailMessage("Structural/environment failure for %s:%n%s",
                        mapping.questionId(), diagnostic)
                .isEmpty();
        assertThat(output)
                .withFailMessage("Missing pytest fixture for %s:%n%s",
                        mapping.questionId(), diagnostic)
                .doesNotContainPattern("(?s)fixture\\s+['\"].+?['\"]\\s+not found");

        TestReport report = "MAVEN".equals(mapping.runnerKind())
                ? exactMavenReport(mapping, isolatedExam)
                : exactPytestReport(mapping, isolatedExam);
        assertThat(report.tests()).withFailMessage(
                "%s ran zero selected tests", mapping.questionId()).isPositive();
        assertReportBound(mapping, report);
        assertThat(report.failures() + report.errors())
                .withFailMessage("%s selected report had no failures/errors",
                        mapping.questionId())
                .isPositive();
        assertThat(report.tests() - report.skipped())
                .withFailMessage("%s selected report only contained skips",
                        mapping.questionId())
                .isPositive();
        String evidence = report.body().toLowerCase(Locale.ROOT);
        assertThat(evidence)
                .withFailMessage("Bound selected report lacks unimplemented/assertion evidence for %s",
                        mapping.questionId())
                .containsAnyOf(
                        "unsupportedoperationexception",
                        "notimplementederror",
                        "assertionfailederror",
                        "assertionerror",
                        "expected:");
    }

    private static void assertReferenceSuccess(
            StarterTestSupport.MappingRow mapping,
            Path isolatedExam,
            ProcessResult result) throws IOException {
        assertSuccessful(mapping, "reference-passes", result);
        TestReport report = "MAVEN".equals(mapping.runnerKind())
                ? exactMavenReport(mapping, isolatedExam)
                : exactPytestReport(mapping, isolatedExam);
        assertThat(report.tests()).withFailMessage(
                "%s reference ran zero selected tests", mapping.questionId()).isPositive();
        assertReportBound(mapping, report);
        assertThat(report.failures() + report.errors())
                .withFailMessage("%s reference selected report was not green",
                        mapping.questionId())
                .isZero();
        assertThat(report.tests() - report.skipped())
                .withFailMessage("%s reference selected report had no passed tests",
                        mapping.questionId())
                .isPositive();
    }

    private static TestReport exactMavenReport(
            StarterTestSupport.MappingRow mapping,
            Path isolatedExam) throws IOException {
        Path reports = isolatedExam.resolve("java/target/surefire-reports");
        List<TestReport> matching = new ArrayList<>();
        if (Files.isDirectory(reports)) {
            try (Stream<Path> paths = Files.list(reports)) {
                for (Path path : paths
                        .filter(candidate -> candidate.getFileName().toString().startsWith("TEST-"))
                        .filter(path -> path.getFileName().toString().endsWith(".xml"))
                        .toList()) {
                    TestReport report = readReport(path);
                    String suite = report.suiteName();
                    String simple = suite.substring(suite.lastIndexOf('.') + 1);
                    if (simple.equals(mapping.testSelector())) {
                        matching.add(report);
                    }
                }
            }
        }
        assertThat(matching)
                .withFailMessage("Expected exactly one exact selected Maven report for %s",
                        mapping.testSelector())
                .hasSize(1);
        return matching.get(0);
    }

    private static TestReport exactPytestReport(
            StarterTestSupport.MappingRow mapping, Path isolatedExam) throws IOException {
        Path path = pytestReportPath(isolatedExam, mapping);
        assertThat(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .withFailMessage("Missing exact selected pytest JUnit XML for %s: %s",
                        mapping.questionId(), path)
                .isTrue();
        TestReport report = readReport(path);
        assertThat(report.suiteName()).isEqualTo("pytest");
        return report;
    }

    private static void assertReportBound(
            StarterTestSupport.MappingRow mapping, TestReport report) {
        if (!"PYTEST".equals(mapping.runnerKind())) {
            return;
        }
        String filename = mapping.testSelector().substring(
                mapping.testSelector().lastIndexOf('/') + 1);
        String module = filename.substring(0, filename.length() - ".py".length())
                .toLowerCase(Locale.ROOT);
        assertThat(report.body().toLowerCase(Locale.ROOT))
                .withFailMessage("pytest JUnit XML is not bound to selected path %s",
                        mapping.testSelector())
                .contains(module);
    }

    private static Path pytestReportPath(
            Path isolatedExam, StarterTestSupport.MappingRow mapping) {
        return isolatedExam.resolve(
                "python/.training-matrix-" + mapping.questionId() + ".xml");
    }

    private static TestReport readReport(Path path) throws IOException {
        try {
            if (Files.size(path) > 1024 * 1024) {
                throw new IOException("JUnit XML exceeds 1 MiB cap: " + path);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
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
                    throw new IOException("JUnit XML must contain exactly one testsuite: " + path);
                }
                suite = (Element) suites.item(0);
            } else {
                throw new IOException("Unexpected JUnit XML root: " + root.getTagName());
            }
            return new TestReport(
                    suite.getAttribute("name"),
                    requiredCount(suite, "tests", path),
                    requiredCount(suite, "failures", path),
                    requiredCount(suite, "errors", path),
                    requiredCount(suite, "skipped", path),
                    Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Cannot parse bounded JUnit XML: " + path, exception);
        }
    }

    private static int requiredCount(Element suite, String name, Path path)
            throws IOException {
        String value = suite.getAttribute(name);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid JUnit " + name + " count in " + path, exception);
        }
    }

    private static String diagnostic(
            StarterTestSupport.MappingRow mapping, String stage, ProcessResult result) {
        return "%s %s failed: kind=%s exit=%d timeout=%s duration=%s%nstdout:%n%s%nstderr:%n%s"
                .formatted(
                        mapping.questionId(),
                        stage,
                        result.failureKind(),
                        result.exitCode(),
                        result.timedOut(),
                        result.duration(),
                        result.stdout(),
                        result.stderr());
    }

    private static void copyOfficialTree(Path destination) throws IOException {
        Path source = StarterTestSupport.examRoot().toRealPath();
        Files.createDirectory(destination);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source)
                        && COPY_EXCLUSIONS.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Official tree contains a symbolic directory: " + directory);
                }
                Path relative = source.relativize(directory);
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Official tree contains an unsafe file node: " + file);
                }
                if (!file.getFileName().toString().endsWith(".pyc")) {
                    Files.copy(file, destination.resolve(source.relativize(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private enum MatrixMode {
        CONTRACT,
        STARTER_FAILS,
        REFERENCE_PASSES;

        static MatrixMode parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            if (!value.equals(value.trim())) {
                throw invalid(value);
            }
            return switch (value) {
                case "contract" -> CONTRACT;
                case "starter-fails" -> STARTER_FAILS;
                case "reference-passes" -> REFERENCE_PASSES;
                default -> throw invalid(value);
            };
        }

        private static IllegalArgumentException invalid(String value) {
            return new IllegalArgumentException(
                    "blank disables starter.mode; a nonblank value must be exactly contract, "
                            + "starter-fails, or reference-passes; was "
                            + String.valueOf(value));
        }
    }

    private record TestReport(
            String suiteName,
            int tests,
            int failures,
            int errors,
            int skipped,
            String body) {
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
