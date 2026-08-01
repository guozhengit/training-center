package com.guoyongzheng.training.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量导入题目以拓展题库。
 *
 * <p>导入文件为一个 JSON 文档, 包含 {@code questions} 数组, 每项是 {@code type} 为
 * {@code coding}/{@code oral}/{@code project} 的题目定义。导入分为三个阶段:
 * 先整体校验(含与已有索引的 id 冲突), 再写内容文件, 最后把元数据追加到导入索引
 * {@code training-center/config/imported-questions.json} 与 starter 映射
 * {@code training-center/config/imported-starter-mapping.json}。</p>
 *
 * <p>内容文件按现有目录约定写入: 编码题落在 {@code output/coding-ai-exam} 与
 * {@code training-center/starters}, 口述/项目题落在 {@code output/interview/imported},
 * 从而复用 CatalogLoader / JudgeService / SandboxService 的既有校验与判题链路。</p>
 */
public final class QuestionImporter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path QUESTIONS_INDEX = Path.of("training-center/config/imported-questions.json");
    private static final Path MAPPING_INDEX = Path.of("training-center/config/imported-starter-mapping.json");

    private static final Pattern JAVA_CLASS =
            Pattern.compile("\\b(?:public\\s+|abstract\\s+|final\\s+|strictfp\\s+)*class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern JAVA_PACKAGE_SEGMENT = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern PYTHON_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_GROUP = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String EXAM_PACKAGE = "com.guoyongzheng.exam";

    /** 一次导入的结果摘要。 */
    public record ImportResult(boolean dryRun, List<String> importedIds, int writtenFiles,
                               Path questionsIndex, Path mappingIndex) {
        public ImportResult {
            importedIds = List.copyOf(importedIds);
        }
    }

    public ImportResult importBatch(Path workspaceRoot, Path importFile) throws IOException {
        return importBatch(workspaceRoot, importFile, false, false);
    }

    public ImportResult importBatch(Path workspaceRoot, Path importFile, boolean dryRun, boolean force)
            throws IOException {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("工作区根目录不存在: " + root);
        }

        JsonNode document = JSON.readTree(importFile.toFile());
        JsonNode questions = document.path("questions");
        if (!questions.isArray() || questions.isEmpty()) {
            throw new IllegalArgumentException("导入文件必须包含非空的 questions 数组");
        }

        // 1. 解析并校验每条题目 (自身结构 + 与已有索引的 id 冲突)
        List<PendingQuestion> pending = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : questions) {
            PendingQuestion question = PendingQuestion.parse(node);
            if (!ids.add(question.id())) {
                throw new IllegalArgumentException("导入文件内重复的题目 id: " + question.id());
            }
            pending.add(question);
        }
        Set<String> reserved = reservedIds(root);
        for (PendingQuestion question : pending) {
            if (!force && reserved.contains(question.id())) {
                throw new IllegalArgumentException("题目 id 已存在: " + question.id()
                        + " (需要覆盖时使用 --force)");
            }
        }

        // 2. 写入内容文件 (校验全部通过后才动手)
        int written = 0;
        if (!dryRun) {
            for (PendingQuestion question : pending) {
                written += question.writeFiles(root);
            }
        }

        // 3. 更新导入索引与 starter 映射 (force 时替换同 id 条目)
        if (!dryRun) {
            ArrayNode questionsIndex = readArray(root.resolve(QUESTIONS_INDEX));
            ArrayNode mappingIndex = readArray(root.resolve(MAPPING_INDEX));
            for (PendingQuestion question : pending) {
                replaceEntry(questionsIndex, question.indexEntry());
                ObjectNode mapping = question.mappingEntry();
                if (mapping != null) {
                    replaceEntry(mappingIndex, mapping);
                }
            }
            writeArray(root.resolve(QUESTIONS_INDEX), questionsIndex);
            writeArray(root.resolve(MAPPING_INDEX), mappingIndex);
        }

        return new ImportResult(dryRun, List.copyOf(ids), written,
                root.resolve(QUESTIONS_INDEX), root.resolve(MAPPING_INDEX));
    }

    private interface PendingQuestion {
        String id();

        int writeFiles(Path workspaceRoot) throws IOException;

        ObjectNode indexEntry();

        ObjectNode mappingEntry();

        static PendingQuestion parse(JsonNode node) {
            String type = requiredText(node, "type", "question").toLowerCase(Locale.ROOT);
            return switch (type) {
                case "coding" -> CodingQuestion.parse(node);
                case "oral" -> OralQuestion.parse(node);
                case "project" -> ProjectQuestion.parse(node);
                default -> throw new IllegalArgumentException("未知题目类型: " + type
                        + " (仅支持 coding/oral/project)");
            };
        }
    }

    private record CodingQuestion(String id, String group, String title, String topic, String difficulty,
                                  String language, String priority, String contentMd,
                                  String solution, String test, String starter, String className)
            implements PendingQuestion {

        static CodingQuestion parse(JsonNode node) {
            String id = requiredText(node, "id", "coding");
            String language = requiredText(node, "language", id).toLowerCase(Locale.ROOT);
            if (!language.equals("java") && !language.equals("python")) {
                throw new IllegalArgumentException("未知语言: " + language + " (" + id
                        + "), 仅支持 java/python");
            }
            String group = requiredText(node, "group", id);
            if (!SAFE_GROUP.matcher(group).matches()) {
                throw new IllegalArgumentException("group 含非法字符: " + group + " (" + id + ")");
            }
            String solution = requiredText(node, "solution", id);
            String test = requiredText(node, "test", id);
            String starter = requiredText(node, "starter", id);
            String className = null;
            if (language.equals("java")) {
                if (!JAVA_PACKAGE_SEGMENT.matcher(group).matches()) {
                    throw new IllegalArgumentException("java 题目的 group 必须是合法包名段 (小写字母开头): "
                            + group + " (" + id + ")");
                }
                className = javaClassName(solution, id);
                String expectedPackage = EXAM_PACKAGE + "." + group;
                requirePackage(solution, expectedPackage, id);
                requirePackage(starter, expectedPackage, id);
                requirePackage(test, expectedPackage, id);
                if (!declaresClass(test, className + "Test")) {
                    throw new IllegalArgumentException("测试类名应为 " + className + "Test (" + id + ")");
                }
            } else {
                if (!PYTHON_IDENTIFIER.matcher(id).matches()) {
                    throw new IllegalArgumentException("python 题目的 id 必须是合法 Python 标识符: " + id);
                }
            }
            return new CodingQuestion(id, group, requiredText(node, "title", id),
                    requiredText(node, "topic", id), requiredText(node, "difficulty", id), language,
                    optionalText(node, "priority"), requiredText(node, "content_md", id),
                    solution, test, starter, className);
        }

        @Override
        public int writeFiles(Path root) throws IOException {
            int written = writeText(root.resolve("output/coding-ai-exam/content")
                    .resolve(group).resolve(id + ".md"), contentMd);
            if (language.equals("java")) {
                Path javaMain = root.resolve("output/coding-ai-exam/java/src/main/java/com/guoyongzheng/exam")
                        .resolve(group);
                Path javaTest = root.resolve("output/coding-ai-exam/java/src/test/java/com/guoyongzheng/exam")
                        .resolve(group);
                Path starterDir = root.resolve("training-center/starters/java/src/main/java/com/guoyongzheng/exam")
                        .resolve(group);
                written += writeText(javaMain.resolve(className + ".java"), solution);
                written += writeText(javaTest.resolve(className + "Test.java"), test);
                written += writeText(starterDir.resolve(className + ".java"), starter);
            } else {
                written += writeText(root.resolve("output/coding-ai-exam/python/src/ai_exam")
                        .resolve(id + ".py"), solution);
                written += writeText(root.resolve("output/coding-ai-exam/python/tests")
                        .resolve("test_" + id + ".py"), test);
                written += writeText(root.resolve("training-center/starters/python/src/ai_exam")
                        .resolve(id + ".py"), starter);
            }
            return written;
        }

        @Override
        public ObjectNode indexEntry() {
            ObjectNode node = baseEntry("coding");
            node.put("group", group);
            node.put("title", title);
            node.put("topic", topic);
            node.put("difficulty", difficulty);
            node.put("language", language);
            node.put("content_path", "content/" + group + "/" + id + ".md");
            if (language.equals("java")) {
                node.put("source_path", "java/src/main/java/com/guoyongzheng/exam/" + group + "/" + className + ".java");
                node.put("test_path", "java/src/test/java/com/guoyongzheng/exam/" + group + "/" + className + "Test.java");
            } else {
                node.put("source_path", "python/src/ai_exam/" + id + ".py");
                node.put("test_path", "python/tests/test_" + id + ".py");
            }
            return node;
        }

        @Override
        public ObjectNode mappingEntry() {
            ObjectNode node = JSON.createObjectNode();
            node.put("question_id", id);
            if (language.equals("java")) {
                String main = "java/src/main/java/com/guoyongzheng/exam/" + group + "/" + className + ".java";
                node.put("starter_path", "training-center/starters/" + main);
                node.put("sandbox_source_path", main);
                node.put("runner_kind", "MAVEN");
                node.put("test_selector", className + "Test");
            } else {
                node.put("starter_path", "training-center/starters/python/src/ai_exam/" + id + ".py");
                node.put("sandbox_source_path", "python/src/ai_exam/" + id + ".py");
                node.put("runner_kind", "PYTEST");
                node.put("test_selector", "python/tests/test_" + id + ".py");
            }
            return node;
        }

        private ObjectNode baseEntry(String type) {
            ObjectNode node = JSON.createObjectNode();
            node.put("type", type);
            node.put("id", id);
            if (priority != null) {
                node.put("priority", priority);
            }
            return node;
        }
    }

    private record OralQuestion(String id, String title, String topic, String priority, String heading,
                                String contentMd, String answerLocator, String followUpLocator,
                                int recommendedSeconds) implements PendingQuestion {

        static OralQuestion parse(JsonNode node) {
            String id = requiredText(node, "id", "oral");
            String title = requiredText(node, "title", id);
            String content = requiredText(node, "content_md", id);
            String topic = requiredText(node, "topic", id);
            String[] plan = headingPlan(content, "### " + title, id);
            return new OralQuestion(id, title, topic, optionalText(node, "priority"), plan[0], plan[1],
                    node.path("answer_locator").asText("**完整口述**"),
                    node.path("follow_up_locator").asText("**继续追问**"),
                    node.path("recommended_seconds").asInt(90));
        }

        @Override
        public int writeFiles(Path root) throws IOException {
            return writeText(root.resolve("output/interview/imported").resolve(id + ".md"), contentMd);
        }

        @Override
        public ObjectNode indexEntry() {
            ObjectNode node = JSON.createObjectNode();
            node.put("type", "oral");
            node.put("id", id);
            node.put("title", title);
            node.put("topic", topic);
            if (priority != null) {
                node.put("priority", priority);
            }
            node.put("source_path", "output/interview/imported/" + id + ".md");
            node.put("question_heading", heading);
            node.put("answer_locator", answerLocator);
            node.put("follow_up_locator", followUpLocator);
            node.put("recommended_seconds", recommendedSeconds);
            return node;
        }

        @Override
        public ObjectNode mappingEntry() {
            return null;
        }
    }

    private record ProjectQuestion(String id, String projectName, String projectNature, String title,
                                   String priority, String heading, String contentMd, String evidenceEntry,
                                   String factBoundary, int recommendedSeconds) implements PendingQuestion {

        static ProjectQuestion parse(JsonNode node) {
            String id = requiredText(node, "id", "project");
            String title = requiredText(node, "title", id);
            String content = requiredText(node, "content_md", id);
            String[] plan = headingPlan(content, "## " + title, id);
            return new ProjectQuestion(id, requiredText(node, "project_name", id),
                    requiredText(node, "project_nature", id), title, optionalText(node, "priority"),
                    plan[0], plan[1], node.path("evidence_entry").asText("### 可验证依据"),
                    node.path("fact_boundary").asText("### 不支持声称警铃"),
                    node.path("recommended_seconds").asInt(120));
        }

        @Override
        public int writeFiles(Path root) throws IOException {
            return writeText(root.resolve("output/interview/imported").resolve(id + ".md"), contentMd);
        }

        @Override
        public ObjectNode indexEntry() {
            ObjectNode node = JSON.createObjectNode();
            node.put("type", "project");
            node.put("id", id);
            node.put("project_name", projectName);
            node.put("project_nature", projectNature);
            node.put("title", title);
            if (priority != null) {
                node.put("priority", priority);
            }
            node.put("source_path", "output/interview/imported/" + id + ".md");
            node.put("section_heading", heading);
            node.put("evidence_entry", evidenceEntry);
            node.put("fact_boundary", factBoundary);
            node.put("recommended_seconds", recommendedSeconds);
            return node;
        }

        @Override
        public ObjectNode mappingEntry() {
            return null;
        }
    }

    private static String[] headingPlan(String content, String defaultHeading, String id) {
        String found = firstHeading(content);
        String heading = found != null ? found : defaultHeading;
        String finalContent = found != null ? content : heading + "\n\n" + content.strip();
        long count = finalContent.lines().filter(line -> line.strip().equals(heading)).count();
        if (count != 1) {
            throw new IllegalArgumentException("标题 \"" + heading + "\" 在内容中出现 " + count
                    + " 次, 需要恰好 1 次 (" + id + ")");
        }
        return new String[]{heading, finalContent};
    }

    private static String firstHeading(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                return trimmed;
            }
        }
        return null;
    }

    private static String javaClassName(String code, String id) {
        Matcher matcher = JAVA_CLASS.matcher(code);
        if (!matcher.find()) {
            throw new IllegalArgumentException("无法从参考答案中解析类名 (" + id + ")");
        }
        return matcher.group(1);
    }

    private static void requirePackage(String code, String expectedPackage, String id) {
        for (String line : code.split("\\R")) {
            if (line.strip().equals("package " + expectedPackage + ";")) {
                return;
            }
        }
        throw new IllegalArgumentException("代码应声明包 " + expectedPackage + " (" + id + ")");
    }

    private static boolean declaresClass(String code, String className) {
        Matcher matcher = JAVA_CLASS.matcher(code);
        while (matcher.find()) {
            if (matcher.group(1).equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static int writeText(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return 1;
    }

    private static Set<String> reservedIds(Path root) throws IOException {
        Set<String> reserved = new LinkedHashSet<>();
        collectIds(root.resolve("output/coding-ai-exam/catalog/questions.json"), null, reserved);
        collectIds(root.resolve("training-center/config/oral-questions.json"), "questions", reserved);
        collectIds(root.resolve("training-center/config/project-cases.json"), "cases", reserved);
        collectIds(root.resolve("training-center/config/od-questions.json"), null, reserved);
        collectIds(root.resolve(QUESTIONS_INDEX), null, reserved);
        return reserved;
    }

    private static void collectIds(Path path, String member, Set<String> ids) throws IOException {
        if (!Files.isRegularFile(path)) {
            return;
        }
        JsonNode node = JSON.readTree(path.toFile());
        JsonNode entries = member == null ? node : node.path(member);
        if (!entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            String id = entry.path("id").asText();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
    }

    private static ArrayNode readArray(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return JSON.createArrayNode();
        }
        JsonNode node = JSON.readTree(path.toFile());
        if (!node.isArray()) {
            throw new IllegalArgumentException("索引必须是 JSON 数组: " + path);
        }
        return (ArrayNode) node;
    }

    private static void writeArray(Path path, ArrayNode array) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(array) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void replaceEntry(ArrayNode array, ObjectNode entry) {
        String key = entry.has("id") ? "id" : "question_id";
        String value = entry.path(key).asText();
        for (int index = array.size() - 1; index >= 0; index--) {
            if (value.equals(array.get(index).path(key).asText())) {
                array.remove(index);
            }
        }
        array.add(entry);
    }

    private static String requiredText(JsonNode node, String field, String record) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少字段 " + field + " (" + record + ")");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }
}
