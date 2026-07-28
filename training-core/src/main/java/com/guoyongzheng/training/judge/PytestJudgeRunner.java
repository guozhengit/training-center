package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.process.ProcessRequest;
import com.guoyongzheng.training.process.ProcessResult;
import com.guoyongzheng.training.process.ProcessRunner;
import com.guoyongzheng.training.sandbox.SandboxService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one selected pytest file in a completed isolated sandbox. */
public final class PytestJudgeRunner implements JudgeRunner {

    private static final String PYTHON_EXECUTABLE =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")
                    ? "python" : "python3";

    private static final String JUNIT_XML = "python/.training-judge-pytest.xml";
    private static final long JUNIT_XML_CAP = 1024L * 1024L;
    private static final String COUNT_KIND =
            "passed|failed|errors?|skipped|xfailed|xpassed|deselected|warnings?";
    private static final Pattern SUMMARY = Pattern.compile(
            "(?m)^\\s*(?:=+\\s*)?"
                    + "((?:\\d+ (?:"
                    + COUNT_KIND
                    + "))(?:, \\d+ (?:"
                    + COUNT_KIND
                    + "))*) in [^\\r\\n]+?(?:\\s*=+)?\\s*$");
    private static final Pattern TOKEN = Pattern.compile(
            "(\\d+) (" + COUNT_KIND + ")");

    private final ProcessRunner processRunner;
    private final SandboxCompletionCheck completionCheck;

    public PytestJudgeRunner(ProcessRunner processRunner, SandboxService sandboxService) {
        this(processRunner, Objects.requireNonNull(sandboxService, "sandboxService")::isComplete);
    }

    PytestJudgeRunner(
            ProcessRunner processRunner,
            SandboxCompletionCheck completionCheck) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.completionCheck = Objects.requireNonNull(completionCheck, "completionCheck");
    }

    @Override
    public JudgementResult judge(JudgeRequest request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        try {
            JudgeSupport.PreparedJudge prepared =
                    JudgeSupport.prepare(request, RunnerKind.PYTEST, completionCheck);
            Path junitXml = prepared.workingDirectory().resolve(JUNIT_XML);
            Files.deleteIfExists(junitXml);
            ProcessResult process = processRunner.run(new ProcessRequest(
                    List.of(
                            PYTHON_EXECUTABLE,
                            "-m",
                            "pytest",
                            "-q",
                            "--tb=short",
                            "--junitxml=" + JUNIT_XML,
                            prepared.selector()),
                    prepared.workingDirectory(),
                    request.timeout(),
                    JudgeSupport.OUTPUT_CAP,
                    JudgeSupport.OUTPUT_CAP,
                    request.environment()));
            TestCounts counts = countsFromJunitXml(junitXml);
            if (!counts.known() && !process.stdoutTruncated() && !process.stderrTruncated()) {
                counts = counts(process.stdout(), process.stderr());
            }
            return JudgeSupport.result(process, counts);
        } catch (IOException | RuntimeException exception) {
            return JudgeSupport.environmentError(safeMessage(exception), started);
        }
    }

    private static TestCounts countsFromJunitXml(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                    || Files.size(path) > JUNIT_XML_CAP) {
                return TestCounts.UNKNOWN;
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
            Element suite = suite(root);
            int tests = requiredInt(suite, "tests");
            int failures = requiredInt(suite, "failures");
            int errors = requiredInt(suite, "errors");
            int skipped = requiredInt(suite, "skipped");
            int failed = Math.addExact(failures, errors);
            int passed = Math.subtractExact(Math.subtractExact(tests, failed), skipped);
            if (passed < 0) {
                return TestCounts.UNKNOWN;
            }
            return new TestCounts(passed, failed);
        } catch (IOException
                 | ParserConfigurationException
                 | RuntimeException
                 | SAXException exception) {
            return TestCounts.UNKNOWN;
        }
    }

    private static Element suite(Element root) throws IOException {
        if ("testsuite".equals(root.getTagName())) {
            return root;
        }
        if ("testsuites".equals(root.getTagName())) {
            NodeList suites = root.getElementsByTagName("testsuite");
            if (suites.getLength() == 1) {
                return (Element) suites.item(0);
            }
        }
        throw new IOException("unexpected pytest JUnit XML root");
    }

    private static int requiredInt(Element element, String attribute) throws IOException {
        String value = element.getAttribute(attribute);
        if (!value.matches("\\d+")) {
            throw new IOException("pytest JUnit XML count is invalid");
        }
        return Integer.parseInt(value);
    }

    private static TestCounts counts(String stdout, String stderr) {
        Matcher summaries = SUMMARY.matcher(stdout + "\n" + stderr);
        String tokens = null;
        while (summaries.find()) {
            if (tokens != null) {
                return TestCounts.UNKNOWN;
            }
            tokens = summaries.group(1);
        }
        if (tokens == null) {
            return TestCounts.UNKNOWN;
        }

        int passed = 0;
        int failed = 0;
        Set<String> seen = new HashSet<>();
        Matcher token = TOKEN.matcher(tokens);
        try {
            while (token.find()) {
                String kind = token.group(2);
                String category = normalize(kind);
                if (!seen.add(category)) {
                    return TestCounts.UNKNOWN;
                }
                int count = Integer.parseInt(token.group(1));
                if ("passed".equals(kind)) {
                    passed = count;
                } else if ("failed".equals(kind)
                        || "error".equals(kind)
                        || "errors".equals(kind)) {
                    failed = Math.addExact(failed, count);
                }
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            return TestCounts.UNKNOWN;
        }
        return new TestCounts(passed, failed);
    }

    private static String normalize(String kind) {
        return switch (kind) {
            case "error", "errors" -> "errors";
            case "warning", "warnings" -> "warnings";
            default -> kind;
        };
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "judge environment validation failed"
                : message;
    }
}
