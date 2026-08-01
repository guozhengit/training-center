package com.guoyongzheng.training.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImportedCatalogTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mergesImportedQuestionsIntoTheCatalog(@TempDir Path temporaryDirectory) throws IOException {
        Path root = workspaceRoot();
        JsonNode realCoding = read(root.resolve("output/coding-ai-exam/catalog/questions.json"));
        JsonNode realOd = read(root.resolve("training-center/config/od-questions.json"));
        JsonNode realOralArray = read(root.resolve("training-center/config/oral-questions.json")).path("questions");
        JsonNode realProjectArray = read(root.resolve("training-center/config/project-cases.json")).path("cases");
        JsonNode realOral = realOralArray.get(0);
        JsonNode realProject = realProjectArray.get(0);

        ObjectNode coding = JSON.createObjectNode();
        coding.put("type", "coding");
        coding.put("id", "X001");
        coding.put("group", "basic");
        coding.put("title", "导入的编码题");
        coding.put("topic", "算法");
        coding.put("difficulty", "medium");
        coding.put("language", "java");
        coding.put("content_path", "content/basic/B001.md");
        coding.put("source_path", "java/src/main/java/com/guoyongzheng/exam/basic/B001TwoSum.java");
        coding.put("test_path", "java/src/test/java/com/guoyongzheng/exam/basic/B001TwoSumTest.java");

        ObjectNode oral = JSON.createObjectNode();
        oral.put("type", "oral");
        oral.put("id", "X002");
        oral.put("title", realOral.path("title").asText());
        oral.put("topic", realOral.path("topic").asText());
        oral.put("source_path", realOral.path("source_path").asText());
        oral.put("question_heading", realOral.path("question_heading").asText());

        ObjectNode project = JSON.createObjectNode();
        project.put("type", "project");
        project.put("id", "X003");
        project.put("project_name", realProject.path("project_name").asText());
        project.put("project_nature", realProject.path("project_nature").asText());
        project.put("title", realProject.path("title").asText());
        project.put("source_path", realProject.path("source_path").asText());
        project.put("section_heading", realProject.path("section_heading").asText());

        Path importedIndex = temporaryDirectory.resolve("imported-questions.json");
        Files.writeString(importedIndex, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                array(coding, oral, project)), StandardCharsets.UTF_8);

        TrainingCatalog catalog = new CatalogLoader().load(root,
                root.resolve("output/coding-ai-exam/catalog/questions.json"),
                root.resolve("training-center/config/oral-questions.json"),
                root.resolve("training-center/config/project-cases.json"),
                importedIndex);

        QuestionDescriptor x001 = catalog.require("X001");
        assertThat(x001.track()).isEqualTo(Track.CODING);
        assertThat(x001.groupName()).isEqualTo("basic");
        assertThat(x001.starterRef()).isEqualTo(
                "training-center/starters/java/src/main/java/com/guoyongzheng/exam/basic/B001TwoSum.java");

        QuestionDescriptor x002 = catalog.require("X002");
        assertThat(x002.track()).isEqualTo(Track.ORAL);
        assertThat(x002.sourceRef()).isEqualTo(
                realOral.path("source_path").asText() + "#" + realOral.path("question_heading").asText());
        assertThat(x002.starterRef()).isNull();

        QuestionDescriptor x003 = catalog.require("X003");
        assertThat(x003.track()).isEqualTo(Track.PROJECT);
        assertThat(x003.sourceRef()).isEqualTo(
                realProject.path("source_path").asText() + "#" + realProject.path("section_heading").asText());

        int expected = realCoding.size() + realOd.size() + realOralArray.size() + realProjectArray.size() + 3;
        assertThat(catalog.find(null)).hasSize(expected);
    }

    private static ObjectNode[] array(ObjectNode... entries) {
        return entries;
    }

    private static JsonNode read(Path path) throws IOException {
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("output/coding-ai-exam"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate workspace root");
        }
        return current;
    }
}
