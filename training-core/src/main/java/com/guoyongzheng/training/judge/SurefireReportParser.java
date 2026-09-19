package com.guoyongzheng.training.judge;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads per-case results out of the surefire XML reports a Maven test run leaves behind.
 *
 * <p>The judge only knows "N passed / M failed" from the console summary, which is not enough for
 * a training bench: the user has to see which case broke and why. The XML reports carry exactly
 * that, so they are read after the run instead of parsing the console output.
 *
 * <p>This parser is intentionally best-effort and never throws: a missing or malformed report
 * degrades to an empty list, and the caller still has the aggregate counts to fall back on.
 */
public final class SurefireReportParser {

    private static final Pattern EXPECTED_ACTUAL = Pattern.compile(
            "expected:\\s*<([\\s\\S]*?)>\\s*but was:\\s*<([\\s\\S]*?)>");
    private static final int MESSAGE_CAP = 2000;
    private static final int MAX_CASES = 500;

    private SurefireReportParser() {
    }

    /**
     * @param javaProject the Maven project directory that was tested, i.e. the one containing
     *                    {@code target/surefire-reports}
     */
    public static List<CaseResult> parse(Path javaProject) {
        if (javaProject == null) {
            return List.of();
        }
        Path reports = javaProject.resolve("target").resolve("surefire-reports");
        if (!Files.isDirectory(reports)) {
            return List.of();
        }
        List<CaseResult> cases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reports, "TEST-*.xml")) {
            for (Path file : stream) {
                if (cases.size() >= MAX_CASES) {
                    break;
                }
                parseFile(file, cases);
            }
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
        if (cases.size() > MAX_CASES) {
            return List.copyOf(cases.subList(0, MAX_CASES));
        }
        return List.copyOf(cases);
    }

    private static void parseFile(Path file, List<CaseResult> sink) throws IOException {
        Document document;
        try {
            document = newSafeDocumentBuilder().parse(file.toFile());
        } catch (Exception exception) {
            return;
        }
        NodeList testCases = document.getElementsByTagName("testcase");
        for (int i = 0; i < testCases.getLength(); i++) {
            Node node = testCases.item(i);
            if (!(node instanceof Element testCase)) {
                continue;
            }
            sink.add(toCaseResult(testCase));
        }
    }

    private static CaseResult toCaseResult(Element testCase) {
        String name = testCase.getAttribute("name");
        if (name == null || name.isBlank()) {
            name = "(unnamed case)";
        }
        String className = nullIfBlank(testCase.getAttribute("classname"));
        Long durationMillis = durationMillis(testCase.getAttribute("time"));

        Element failure = firstChild(testCase, "failure");
        Element error = firstChild(testCase, "error");
        Element skipped = firstChild(testCase, "skipped");

        if (skipped != null) {
            return new CaseResult(name, className, CaseStatus.SKIPPED, durationMillis,
                    cap(nullIfBlank(skipped.getAttribute("message"))), null, null);
        }
        Element broken = failure != null ? failure : error;
        if (broken == null) {
            return CaseResult.passed(name, className, durationMillis);
        }
        String message = cap(nullIfBlank(broken.getAttribute("message")));
        if (message == null) {
            message = cap(nullIfBlank(broken.getTextContent()));
        }
        String expected = null;
        String actual = null;
        if (message != null) {
            Matcher matcher = EXPECTED_ACTUAL.matcher(message);
            if (matcher.find()) {
                expected = cap(matcher.group(1));
                actual = cap(matcher.group(2));
            }
        }
        return new CaseResult(name, className,
                failure != null ? CaseStatus.FAILED : CaseStatus.ERROR,
                durationMillis, message, expected, actual);
    }

    private static Long durationMillis(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(time.trim());
            if (seconds < 0) {
                return null;
            }
            return Math.round(seconds * 1000.0);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getParentNode() == parent) {
                return element;
            }
        }
        return null;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cap(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= MESSAGE_CAP
                ? collapsed
                : collapsed.substring(0, MESSAGE_CAP) + " …(已截断)";
    }

    private static DocumentBuilder newSafeDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }
}
