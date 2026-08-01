package com.guoyongzheng.training.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class QuestionImporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void importsJavaCodingQuestionAndWritesAllArtifacts(@TempDir Path workspace, @TempDir Path files) throws IOException {
        Path importFile = files.resolve("import.json");
        ObjectNode question = codingNode()
                .put("id", "X001")
                .put("group", "custom")
                .put("language", "java")
                .put("solution", """
                        package com.guoyongzheng.exam.custom;
                        public class X001Add {
                            public static int add(int a, int b) { return a + b; }
                        }
                        """)
                .put("test", """
                        package com.guoyongzheng.exam.custom;
                        public class X001AddTest {
                        }
                        """)
                .put("starter", """
                        package com.guoyongzheng.exam.custom;
                        public class X001Add {
                            public static int add(int a, int b) { throw new UnsupportedOperationException(); }
                        }
                        """);
        write(importFile, batch(question));

        QuestionImporter.ImportResult result = new QuestionImporter().importBatch(workspace, importFile);

        assertThat(result.importedIds()).containsExactly("X001");
        assertThat(result.writtenFiles()).isEqualTo(4);
        String content = Files.readString(workspace.resolve("output/coding-ai-exam/content/custom/X001.md"),
                StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("## X001\n\n题目内容");
        assertThat(workspace.resolve("output/coding-ai-exam/java/src/main/java/com/guoyongzheng/exam/custom/X001Add.java")).isRegularFile();
        assertThat(workspace.resolve("output/coding-ai-exam/java/src/test/java/com/guoyongzheng/exam/custom/X001AddTest.java")).isRegularFile();
        assertThat(workspace.resolve("training-center/starters/java/src/main/java/com/guoyongzheng/exam/custom/X001Add.java")).isRegularFile();

        JsonNode index = read(workspace.resolve("training-center/config/imported-questions.json"));
        JsonNode entry = index.get(0);
        assertThat(entry.path("id").asText()).isEqualTo("X001");
        assertThat(entry.path("type").asText()).isEqualTo("coding");
        assertThat(entry.path("content_path").asText()).isEqualTo("content/custom/X001.md");
        assertThat(entry.path("source_path").asText())
                .isEqualTo("java/src/main/java/com/guoyongzheng/exam/custom/X001Add.java");
        assertThat(entry.path("test_path").asText())
                .isEqualTo("java/src/test/java/com/guoyongzheng/exam/custom/X001AddTest.java");

        JsonNode mapping = read(workspace.resolve("training-center/config/imported-starter-mapping.json"));
        assertThat(mapping.path(0).path("question_id").asText()).isEqualTo("X001");
        assertThat(mapping.path(0).path("runner_kind").asText()).isEqualTo("MAVEN");
        assertThat(mapping.path(0).path("test_selector").asText()).isEqualTo("X001AddTest");
    }

    @Test
    void importsPythonCodingQuestion(@TempDir Path workspace, @TempDir Path files) throws IOException {
        Path importFile = files.resolve("import.json");
        ObjectNode question = codingNode()
                .put("id", "X010")
                .put("group", "custom")
                .put("language", "python")
                .put("solution", "def add(a, b):\n    return a + b\n")
                .put("test", "def test_add():\n    assert add(1, 2) == 3\n")
                .put("starter", "def add(a, b):\n    raise NotImplementedError\n");
        write(importFile, batch(question));

        new QuestionImporter().importBatch(workspace, importFile);

        assertThat(workspace.resolve("output/coding-ai-exam/python/src/ai_exam/X010.py")).isRegularFile();
        assertThat(workspace.resolve("output/coding-ai-exam/python/tests/test_X010.py")).isRegularFile();
        assertThat(workspace.resolve("training-center/starters/python/src/ai_exam/X010.py")).isRegularFile();
        JsonNode mapping = read(workspace.resolve("training-center/config/imported-starter-mapping.json"));
        assertThat(mapping.path(0).path("runner_kind").asText()).isEqualTo("PYTEST");
        assertThat(mapping.path(0).path("test_selector").asText()).isEqualTo("python/tests/test_X010.py");
    }

    @Test
    void importsOralAndProjectQuestionsWithDerivedHeadings(@TempDir Path workspace, @TempDir Path files)
            throws IOException {
        Path importFile = files.resolve("import.json");
        ObjectNode oral = JSON.createObjectNode();
        oral.put("type", "oral");
        oral.put("id", "X020");
        oral.put("title", "Spring Bean 生命周期");
        oral.put("topic", "Spring");
        oral.put("content_md", "**完整口述**\n\n1. 实例化\n\n**继续追问**\n\n作用域有几种?");
        ObjectNode project = JSON.createObjectNode();
        project.put("type", "project");
        project.put("id", "X021");
        project.put("project_name", "any-sign");
        project.put("project_nature", "production");
        project.put("title", "合同全生命周期");
        project.put("content_md", "### 证据\n\n演示了签署链路");
        write(importFile, batch(oral, project));

        new QuestionImporter().importBatch(workspace, importFile);

        String oralMd = Files.readString(workspace.resolve("output/interview/imported/X020.md"), StandardCharsets.UTF_8);
        assertThat(oralMd).startsWith("### Spring Bean 生命周期\n\n");
        JsonNode oralEntry = index(workspace, "X020");
        assertThat(oralEntry.path("question_heading").asText()).isEqualTo("### Spring Bean 生命周期");
        assertThat(oralEntry.path("answer_locator").asText()).isEqualTo("**完整口述**");
        assertThat(oralEntry.path("follow_up_locator").asText()).isEqualTo("**继续追问**");

        JsonNode projectEntry = index(workspace, "X021");
        assertThat(projectEntry.path("section_heading").asText()).isEqualTo("### 证据");
        assertThat(projectEntry.path("project_name").asText()).isEqualTo("any-sign");
        assertThat(workspace.resolve("output/interview/imported/X021.md")).isRegularFile();
    }

    @Test
    void dryRunValidatesAndWritesNothing(@TempDir Path workspace, @TempDir Path files) throws IOException {
        Path importFile = files.resolve("import.json");
        write(importFile, batch(codingNode().put("id", "X999")));

        QuestionImporter.ImportResult result = new QuestionImporter()
                .importBatch(workspace, importFile, true, false);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.writtenFiles()).isZero();
        assertThat(workspace.resolve("training-center/config/imported-questions.json")).doesNotExist();
    }

    @Test
    void rejectsCollidingIdWithoutForceAndOverwritesWithForce(@TempDir Path workspace, @TempDir Path files)
            throws IOException {
        Path questionsIndex = workspace.resolve("output/coding-ai-exam/catalog/questions.json");
        Files.createDirectories(questionsIndex.getParent());
        write(questionsIndex, array(object().put("id", "B001")));
        Path importFile = files.resolve("import.json");
        write(importFile, batch(codingNode().put("id", "B001")));

        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionImporter().importBatch(workspace, importFile))
                .withMessageContaining("B001");

        new QuestionImporter().importBatch(workspace, importFile, false, true);
        assertThat(workspace.resolve("output/coding-ai-exam/content/custom/B001.md")).isRegularFile();
    }

    @Test
    void rejectsInvalidDefinitions(@TempDir Path workspace, @TempDir Path files) throws IOException {
        assertRejected(workspace, files, object().put("type", "quiz").put("id", "X001"), "未知题目类型");
        assertRejected(workspace, files, codingNode().put("id", "X001").put("title", ""), "缺少字段 title");

        ObjectNode duplicate = codingNode().put("id", "X001");
        write(files.resolve("dup.json"), batch(duplicate, duplicate));
        assertThatIllegalArgumentException().isThrownBy(
                        () -> new QuestionImporter().importBatch(workspace, files.resolve("dup.json")))
                .withMessageContaining("重复");

        ObjectNode badPackage = codingNode().put("id", "X001")
                .put("solution", "package com.other;\npublic class X001Add {}");
        assertRejected(workspace, files, badPackage, "应声明包");

        ObjectNode noClass = codingNode().put("id", "X001")
                .put("solution", "package com.guoyongzheng.exam.custom;\nint helper = 1;");
        assertRejected(workspace, files, noClass, "类名");

        ObjectNode badTestClass = codingNode().put("id", "X001")
                .put("test", "package com.guoyongzheng.exam.custom;\npublic class WrongNameTest {}");
        assertRejected(workspace, files, badTestClass, "测试类名");

        ObjectNode badHeading = JSON.createObjectNode();
        badHeading.put("type", "oral");
        badHeading.put("id", "X020");
        badHeading.put("title", "t");
        badHeading.put("topic", "t");
        badHeading.put("content_md", "### 重复\n\n### 重复\n");
        assertRejected(workspace, files, badHeading, "标题");
    }

    private static void assertRejected(Path workspace, Path files, ObjectNode question, String messagePart)
            throws IOException {
        Path importFile = files.resolve("reject-" + question.path("id").asText("q") + ".json");
        write(importFile, batch(question));
        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionImporter().importBatch(workspace, importFile))
                .withMessageContaining(messagePart);
    }

    private static JsonNode index(Path workspace, String id) throws IOException {
        JsonNode root = read(workspace.resolve("training-center/config/imported-questions.json"));
        for (JsonNode entry : root) {
            if (id.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Missing imported entry: " + id);
    }

    private static ObjectNode codingNode() {
        ObjectNode node = object();
        node.put("type", "coding");
        node.put("id", "X001");
        node.put("group", "custom");
        node.put("title", "X001 题");
        node.put("topic", "算法");
        node.put("difficulty", "medium");
        node.put("language", "java");
        node.put("content_md", "## X001\n\n题目内容");
        node.put("solution", "package com.guoyongzheng.exam.custom;\npublic class X001Add {\n}\n");
        node.put("test", "package com.guoyongzheng.exam.custom;\npublic class X001AddTest {\n}\n");
        node.put("starter", "package com.guoyongzheng.exam.custom;\npublic class X001Add {\n}\n");
        return node;
    }

    private static ObjectNode batch(ObjectNode... questions) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode question : questions) {
            array.add(question);
        }
        ObjectNode document = object();
        document.set("questions", array);
        return document;
    }

    private static ObjectNode object() {
        return JSON.createObjectNode();
    }

    private static void write(Path path, JsonNode node) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private static JsonNode read(Path path) throws IOException {
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static ArrayNode array(ObjectNode entry) {
        ArrayNode array = JSON.createArrayNode();
        array.add(entry);
        return array;
    }
}
