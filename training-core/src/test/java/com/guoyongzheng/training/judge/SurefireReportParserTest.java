package com.guoyongzheng.training.judge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireReportParserTest {

    @TempDir
    Path project;

    private void writeReport(String xml) throws Exception {
        Path reports = project.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-com.example.DemoTest.xml"), xml);
    }

    @Test
    void parsesPassedFailedAndSkippedCases() throws Exception {
        writeReport("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.DemoTest" tests="3" failures="1" errors="0" skipped="1" time="0.42">
                  <testcase name="findsTheRequiredPairInOnePassOrder" classname="com.example.DemoTest" time="0.012"/>
                  <testcase name="usesTwoDifferentIndexesForDuplicateValues" classname="com.example.DemoTest" time="0.025">
                    <failure message="array contents differ at index [0], expected: &lt;0&gt; but was: &lt;1&gt;" type="org.opentest4j.AssertionFailedError">stack</failure>
                  </testcase>
                  <testcase name="edgeCaseIsIgnoredForNow" classname="com.example.DemoTest" time="0.001">
                    <skipped/>
                  </testcase>
                </testsuite>
                """);

        List<CaseResult> cases = SurefireReportParser.parse(project);

        assertEquals(3, cases.size());

        CaseResult passed = cases.get(0);
        assertEquals("findsTheRequiredPairInOnePassOrder", passed.name());
        assertEquals(CaseStatus.PASSED, passed.status());
        assertEquals("com.example.DemoTest", passed.className());
        assertEquals(12L, passed.durationMillis());
        assertNull(passed.message());

        CaseResult failed = cases.get(1);
        assertEquals(CaseStatus.FAILED, failed.status());
        assertEquals(25L, failed.durationMillis());
        assertEquals("0", failed.expected());
        assertEquals("1", failed.actual());
        assertTrue(failed.message().contains("array contents differ"));

        CaseResult skipped = cases.get(2);
        assertEquals(CaseStatus.SKIPPED, skipped.status());
    }

    @Test
    void treatsErrorChildAsErrorStatus() throws Exception {
        writeReport("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.DemoTest" tests="1" failures="0" errors="1" skipped="0" time="0.1">
                  <testcase name="boom" classname="com.example.DemoTest" time="0.005">
                    <error message="java.lang.NullPointerException: nope" type="java.lang.NullPointerException">trace</error>
                  </testcase>
                </testsuite>
                """);

        List<CaseResult> cases = SurefireReportParser.parse(project);

        assertEquals(1, cases.size());
        assertEquals(CaseStatus.ERROR, cases.get(0).status());
        assertEquals("java.lang.NullPointerException: nope", cases.get(0).message());
        assertNull(cases.get(0).expected());
    }

    @Test
    void fallsBackToTextContentWhenMessageAttributeIsMissing() throws Exception {
        writeReport("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.DemoTest" tests="1" failures="1" errors="0" skipped="0" time="0.1">
                  <testcase name="noMessageAttr" classname="com.example.DemoTest" time="0.002">
                    <failure type="org.opentest4j.AssertionFailedError">expected: &lt;3&gt; but was: &lt;4&gt;</failure>
                  </testcase>
                </testsuite>
                """);

        List<CaseResult> cases = SurefireReportParser.parse(project);

        assertEquals(1, cases.size());
        assertEquals("3", cases.get(0).expected());
        assertEquals("4", cases.get(0).actual());
    }

    @Test
    void returnsEmptyListWhenNoReportsExist() {
        assertTrue(SurefireReportParser.parse(project).isEmpty());
    }

    @Test
    void returnsEmptyListOnMalformedXml() throws Exception {
        writeReport("<testsuite><testcase name=\"broken\"");

        assertTrue(SurefireReportParser.parse(project).isEmpty());
    }

    @Test
    void ignoresFilesThatAreNotSurefireReports() throws Exception {
        Path reports = project.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("other.txt"), "not xml");

        assertTrue(SurefireReportParser.parse(project).isEmpty());
    }

    @Test
    void readsEveryReportFileInTheDirectory() throws Exception {
        Path reports = project.resolve("target").resolve("surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-a.xml"),
                "<testsuite name=\"a\"><testcase name=\"one\" time=\"0.01\"/></testsuite>");
        Files.writeString(reports.resolve("TEST-b.xml"),
                "<testsuite name=\"b\"><testcase name=\"two\" time=\"0.01\"/></testsuite>");

        List<CaseResult> cases = SurefireReportParser.parse(project);

        assertEquals(2, cases.size());
        assertNotNull(cases.get(0).name());
    }
}
