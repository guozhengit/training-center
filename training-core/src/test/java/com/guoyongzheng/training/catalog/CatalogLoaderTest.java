package com.guoyongzheng.training.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CatalogLoaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void loadsAllTracksInStableOrderAndExposesImmutableResults() {
        TrainingCatalog catalog = new CatalogLoader().load(workspaceRoot());

        List<QuestionDescriptor> all = catalog.find(new QuestionFilter(null, null, null, null, null));

        assertThat(all).hasSize(208);
        assertThat(all.subList(0, 120).stream().map(QuestionDescriptor::id))
                .containsExactlyElementsOf(indexIds(workspaceRoot().resolve("output/coding-ai-exam/catalog/questions.json"), null));
        assertThat(all.subList(120, 200).stream().map(QuestionDescriptor::id))
                .containsExactlyElementsOf(indexIds(workspaceRoot().resolve("training-center/config/oral-questions.json"), "questions"));
        assertThat(all.subList(200, 208).stream().map(QuestionDescriptor::id))
                .containsExactlyElementsOf(indexIds(workspaceRoot().resolve("training-center/config/project-cases.json"), "cases"));
        assertThat(all.stream().filter(question -> question.track() == Track.CODING)).hasSize(120);
        assertThat(catalog.find(new QuestionFilter(Track.ORAL, null, null, null, null))).hasSize(80);
        assertThat(catalog.find(new QuestionFilter(Track.PROJECT, null, null, null, null))).hasSize(8);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> all.add(all.get(0)));

        assertDescriptorLocatorSemantics(all);
    }

    @Test
    void rejectsDuplicateIdsAcrossTracks(@TempDir Path temporaryDirectory) throws IOException {
        IndexPaths indexes = copiedIndexes(temporaryDirectory);
        ObjectNode oral = readObject(indexes.oral());
        ((ObjectNode) oral.withArray("questions").get(0)).put("id", "B001");
        writeJson(indexes.oral(), oral);

        assertThatIllegalArgumentException().isThrownBy(() -> load(indexes))
                .withMessageContaining("Duplicate question ID: B001");
    }

    @Test
    void rejectsBadEscapingAndAbsoluteCodingPaths(@TempDir Path temporaryDirectory) throws IOException {
        IndexPaths missingContentIndexes = codingPathIndexes(
                temporaryDirectory, "missing-content", "content_path", "content/basic/does-not-exist.md");

        assertThatIllegalArgumentException().isThrownBy(() -> load(missingContentIndexes))
                .withMessageContaining("Missing source path");

        IndexPaths escapingContentIndexes = codingPathIndexes(
                temporaryDirectory, "escaping-content", "content_path", "../outside.md");

        assertThatIllegalArgumentException().isThrownBy(() -> load(escapingContentIndexes))
                .withMessageContaining("Unsafe path");

        IndexPaths absoluteContentIndexes = codingPathIndexes(
                temporaryDirectory, "absolute-content", "content_path",
                workspaceRoot().resolve("output/coding-ai-exam/content/basic/B001.md").toString());

        assertThatIllegalArgumentException().isThrownBy(() -> load(absoluteContentIndexes))
                .withMessageContaining("Unsafe path");

        IndexPaths missingSourceIndexes = codingPathIndexes(
                temporaryDirectory, "missing-source", "source_path", "java/src/main/java/missing.java");

        assertThatIllegalArgumentException().isThrownBy(() -> load(missingSourceIndexes))
                .withMessageContaining("Missing source path");

        IndexPaths escapingSourceIndexes = codingPathIndexes(
                temporaryDirectory, "escaping-source", "source_path", "../outside.java");

        assertThatIllegalArgumentException().isThrownBy(() -> load(escapingSourceIndexes))
                .withMessageContaining("Unsafe path");

        IndexPaths absoluteSourceIndexes = codingPathIndexes(
                temporaryDirectory, "absolute-source", "source_path", absoluteCodingPath("source_path"));

        assertThatIllegalArgumentException().isThrownBy(() -> load(absoluteSourceIndexes))
                .withMessageContaining("Unsafe path");

        IndexPaths missingTestIndexes = codingPathIndexes(
                temporaryDirectory, "missing-test", "test_path", "java/src/test/java/missingTest.java");

        assertThatIllegalArgumentException().isThrownBy(() -> load(missingTestIndexes))
                .withMessageContaining("Missing source path");

        IndexPaths escapingTestIndexes = codingPathIndexes(
                temporaryDirectory, "escaping-test", "test_path", "../outsideTest.java");

        assertThatIllegalArgumentException().isThrownBy(() -> load(escapingTestIndexes))
                .withMessageContaining("Unsafe path");

        IndexPaths absoluteTestIndexes = codingPathIndexes(
                temporaryDirectory, "absolute-test", "test_path", absoluteCodingPath("test_path"));

        assertThatIllegalArgumentException().isThrownBy(() -> load(absoluteTestIndexes))
                .withMessageContaining("Unsafe path");
    }

    @Test
    void rejectsMissingMarkdownHeadingsAndUnknownCodingLanguages(@TempDir Path temporaryDirectory) throws IOException {
        IndexPaths missingHeadingIndexes = copiedIndexes(temporaryDirectory);
        ObjectNode oral = readObject(missingHeadingIndexes.oral());
        ((ObjectNode) oral.withArray("questions").get(0)).put("question_heading", "### missing");
        writeJson(missingHeadingIndexes.oral(), oral);

        assertThatIllegalArgumentException().isThrownBy(() -> load(missingHeadingIndexes))
                .withMessageContaining("Missing heading");

        IndexPaths missingProjectHeadingIndexes = copiedIndexes(temporaryDirectory.resolve("project-heading"));
        ObjectNode projects = readObject(missingProjectHeadingIndexes.project());
        ((ObjectNode) projects.withArray("cases").get(0)).put("section_heading", "## missing");
        writeJson(missingProjectHeadingIndexes.project(), projects);

        assertThatIllegalArgumentException().isThrownBy(() -> load(missingProjectHeadingIndexes))
                .withMessageContaining("Missing heading");

        IndexPaths unknownLanguageIndexes = copiedIndexes(temporaryDirectory.resolve("language"));
        ArrayNode coding = (ArrayNode) JSON.readTree(Files.readString(unknownLanguageIndexes.coding(), StandardCharsets.UTF_8));
        ((ObjectNode) coding.get(0)).put("language", "ruby");
        writeJson(unknownLanguageIndexes.coding(), coding);

        assertThatIllegalArgumentException().isThrownBy(() -> load(unknownLanguageIndexes))
                .withMessageContaining("Unknown coding language: ruby");
    }

    private static TrainingCatalog load(IndexPaths indexes) {
        return new CatalogLoader().load(workspaceRoot(), indexes.coding(), indexes.oral(), indexes.project());
    }

    private static IndexPaths codingPathIndexes(Path temporaryDirectory, String fixtureName, String field, String value)
            throws IOException {
        IndexPaths indexes = copiedIndexes(temporaryDirectory.resolve(fixtureName));
        ArrayNode coding = (ArrayNode) JSON.readTree(Files.readString(indexes.coding(), StandardCharsets.UTF_8));
        ((ObjectNode) coding.get(0)).put(field, value);
        writeJson(indexes.coding(), coding);
        return indexes;
    }

    private static IndexPaths copiedIndexes(Path temporaryDirectory) throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("indexes"));
        Path root = workspaceRoot();
        Path coding = directory.resolve("questions.json");
        Path oral = directory.resolve("oral-questions.json");
        Path project = directory.resolve("project-cases.json");
        Files.copy(root.resolve("output/coding-ai-exam/catalog/questions.json"), coding);
        Files.copy(root.resolve("training-center/config/oral-questions.json"), oral);
        Files.copy(root.resolve("training-center/config/project-cases.json"), project);
        return new IndexPaths(coding, oral, project);
    }

    private static ObjectNode readObject(Path path) throws IOException {
        return (ObjectNode) JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void writeJson(Path path, JsonNode node) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
    }

    private static List<String> indexIds(Path indexPath, String member) {
        try {
            JsonNode root = JSON.readTree(Files.readString(indexPath, StandardCharsets.UTF_8));
            JsonNode entries = member == null ? root : root.path(member);
            return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                    .map(entry -> entry.path("id").asText())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read test index: " + indexPath, exception);
        }
    }

    private static void assertDescriptorLocatorSemantics(List<QuestionDescriptor> all) {
        JsonNode coding = readIndex(workspaceRoot().resolve("output/coding-ai-exam/catalog/questions.json"), null);
        for (int index = 0; index < coding.size(); index++) {
            JsonNode source = coding.get(index);
            QuestionDescriptor question = all.get(index);
            assertThat(question.sourceRef()).isEqualTo(source.path("content_path").asText());
            assertThat(question.starterRef()).isEqualTo("training-center/starters/" + source.path("source_path").asText());
        }

        JsonNode oral = readIndex(workspaceRoot().resolve("training-center/config/oral-questions.json"), "questions");
        for (int index = 0; index < oral.size(); index++) {
            JsonNode source = oral.get(index);
            QuestionDescriptor question = all.get(120 + index);
            assertThat(question.sourceRef()).isEqualTo(source.path("source_path").asText() + "#" + source.path("question_heading").asText());
            assertThat(question.starterRef()).isNull();
        }

        JsonNode projects = readIndex(workspaceRoot().resolve("training-center/config/project-cases.json"), "cases");
        for (int index = 0; index < projects.size(); index++) {
            JsonNode source = projects.get(index);
            QuestionDescriptor question = all.get(200 + index);
            assertThat(question.sourceRef()).isEqualTo(source.path("source_path").asText() + "#" + source.path("section_heading").asText());
            assertThat(question.starterRef()).isNull();
        }
    }

    private static JsonNode readIndex(Path indexPath, String member) {
        try {
            JsonNode root = JSON.readTree(Files.readString(indexPath, StandardCharsets.UTF_8));
            return member == null ? root : root.path(member);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read test index: " + indexPath, exception);
        }
    }

    private static String absoluteCodingPath(String field) {
        JsonNode coding = readIndex(workspaceRoot().resolve("output/coding-ai-exam/catalog/questions.json"), null);
        return workspaceRoot().resolve("output/coding-ai-exam").resolve(coding.get(0).path(field).asText()).toString();
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

    private record IndexPaths(Path coding, Path oral, Path project) {
    }
}
