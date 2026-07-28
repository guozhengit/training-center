package com.guoyongzheng.training.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewIndexValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesTheStableOralAndProjectIndexesAgainstTheirMarkdownSources() {
        Path workspaceRoot = workspaceRoot();

        InterviewIndexValidator.ValidationReport report = new InterviewIndexValidator().validate(workspaceRoot);

        assertThat(report.valid()).withFailMessage("%s", report.violations()).isTrue();
        assertThat(report.oralCount()).isEqualTo(80);
        assertThat(report.projectCount()).isEqualTo(8);
        assertThat(report.oralIds()).containsExactlyElementsOf(ids("O", 80));
        assertThat(report.projectIds()).containsExactlyElementsOf(ids("P", 8));
        assertThat(report.violations()).isEmpty();
    }

    @Test
    void generatedProjectIndexesKeepEvidenceAndTitlesIntactForSupplementaryCases() throws IOException {
        JsonNode cases = readProjectCases();
        JsonNode p001 = caseById(cases, "P001");
        JsonNode p007 = caseById(cases, "P007");
        JsonNode p008 = caseById(cases, "P008");

        assertThat(p001.path("title").asText()).isEqualTo("四、核心案例一：AnySign SaaS 合同全生命周期");
        assertThat(p008.path("title").asText()).isEqualTo("十一、补充项目二：`zkt-hotel-cloud` AI 辅助个人练习");
        assertThat(p007.path("evidence_entry").asText()).isEqualTo("### 2. 证据、正常流与故障");
        assertThat(p008.path("evidence_entry").asText()).isEqualTo("### 2. 系统边界、证据与正常流");
    }

    @Test
    void rejectsMissingOralAnswerAndFollowUpLocators(@TempDir Path temporaryDirectory) throws IOException {
        Path fixtureRoot = fixtureWorkspace(temporaryDirectory);
        Path oralIndexPath = fixtureRoot.resolve("training-center/config/oral-questions.json");
        ObjectNode oralIndex = (ObjectNode) JSON.readTree(Files.readString(oralIndexPath, StandardCharsets.UTF_8));
        ObjectNode firstQuestion = (ObjectNode) caseById(oralIndex.path("questions"), "O001");
        firstQuestion.put("answer_locator", "");
        firstQuestion.put("follow_up_locator", "");
        writeJson(oralIndexPath, oralIndex);

        InterviewIndexValidator.ValidationReport report = new InterviewIndexValidator().validate(fixtureRoot);

        assertThat(report.valid()).isFalse();
        assertThat(report.violations()).contains("Missing answer_locator for O001", "Missing follow_up_locator for O001");
    }

    @Test
    void rejectsDuplicateScopedOralAndProjectLocators(@TempDir Path temporaryDirectory) throws IOException {
        Path fixtureRoot = fixtureWorkspace(temporaryDirectory);
        ObjectNode oralIndex = readObject(fixtureRoot.resolve("training-center/config/oral-questions.json"));
        ObjectNode projectIndex = readObject(fixtureRoot.resolve("training-center/config/project-cases.json"));
        JsonNode o001 = caseById(oralIndex.path("questions"), "O001");
        JsonNode p007 = caseById(projectIndex.path("cases"), "P007");

        duplicateInsideOralQuestion(
                fixtureRoot.resolve(oralIndex.path("source_path").asText()),
                o001.path("question_heading").asText(),
                o001.path("answer_locator").asText());
        duplicateInsideProjectSection(
                fixtureRoot.resolve(projectIndex.path("source_path").asText()),
                p007.path("section_heading").asText(),
                p007.path("fact_boundary").asText());

        InterviewIndexValidator.ValidationReport report = new InterviewIndexValidator().validate(fixtureRoot);

        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .contains("Unresolvable answer_locator for O001: " + o001.path("answer_locator").asText())
                .contains("Unresolvable fact_boundary for P007: " + p007.path("fact_boundary").asText());
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !java.nio.file.Files.isDirectory(current.resolve("output"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate workspace root");
        }
        return current;
    }

    private static java.util.List<String> ids(String prefix, int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(number -> "%s%03d".formatted(prefix, number))
                .toList();
    }

    private static JsonNode readProjectCases() throws IOException {
        return JSON.readTree(Files.readString(
                workspaceRoot().resolve("training-center/config/project-cases.json"), StandardCharsets.UTF_8)).path("cases");
    }

    private static JsonNode caseById(JsonNode cases, String id) {
        for (JsonNode projectCase : cases) {
            if (id.equals(projectCase.path("id").asText())) {
                return projectCase;
            }
        }
        throw new IllegalArgumentException("Missing project case " + id);
    }

    private static Path fixtureWorkspace(Path temporaryDirectory) throws IOException {
        Path fixtureRoot = temporaryDirectory.resolve("workspace");
        Path oralIndex = workspaceRoot().resolve("training-center/config/oral-questions.json");
        Path projectIndex = workspaceRoot().resolve("training-center/config/project-cases.json");
        Path fixtureConfig = fixtureRoot.resolve("training-center/config");
        Files.createDirectories(fixtureConfig);
        Files.copy(oralIndex, fixtureConfig.resolve("oral-questions.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(projectIndex, fixtureConfig.resolve("project-cases.json"), StandardCopyOption.REPLACE_EXISTING);
        copySource(fixtureRoot, readObject(oralIndex).path("source_path").asText());
        copySource(fixtureRoot, readObject(projectIndex).path("source_path").asText());
        return fixtureRoot;
    }

    private static void copySource(Path fixtureRoot, String sourcePath) throws IOException {
        Path target = fixtureRoot.resolve(sourcePath);
        Files.createDirectories(target.getParent());
        Files.copy(workspaceRoot().resolve(sourcePath), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static ObjectNode readObject(Path path) throws IOException {
        return (ObjectNode) JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void writeJson(Path path, ObjectNode node) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
    }

    private static void duplicateInsideOralQuestion(Path source, String questionHeading, String locator) throws IOException {
        String markdown = Files.readString(source, StandardCharsets.UTF_8);
        int start = markdown.indexOf(questionHeading);
        int end = markdown.indexOf("\n### ", start + questionHeading.length());
        if (start < 0 || end < 0) {
            throw new IllegalStateException("Unable to locate oral question scope");
        }
        Files.writeString(source, markdown.substring(0, end) + "\n" + locator + markdown.substring(end), StandardCharsets.UTF_8);
    }

    private static void duplicateInsideProjectSection(Path source, String sectionHeading, String locator) throws IOException {
        String markdown = Files.readString(source, StandardCharsets.UTF_8);
        int start = markdown.indexOf(sectionHeading);
        int end = markdown.indexOf("\n## ", start + sectionHeading.length());
        int locatorIndex = markdown.indexOf(locator, start);
        if (start < 0 || end < 0 || locatorIndex < 0 || locatorIndex >= end) {
            throw new IllegalStateException("Unable to locate project section scope");
        }
        Files.writeString(source, markdown.substring(0, locatorIndex) + locator + "\n" + markdown.substring(locatorIndex), StandardCharsets.UTF_8);
    }
}
