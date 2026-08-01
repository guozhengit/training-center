package com.guoyongzheng.training.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Loads the read-only question indexes plus the optional imported index into one validated catalog. */
public final class CatalogLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CODING_COUNT = 120;
    private static final int ORAL_COUNT = 80;
    private static final int PROJECT_COUNT = 8;

    public TrainingCatalog load(Path workspaceRoot) {
        Path root = approvedWorkspaceRoot(workspaceRoot);
        return load(root,
                root.resolve("output/coding-ai-exam/catalog/questions.json"),
                root.resolve("training-center/config/oral-questions.json"),
                root.resolve("training-center/config/project-cases.json"),
                root.resolve("training-center/config/imported-questions.json"));
    }

    /**
     * Loads explicit index files while keeping every referenced source constrained to the supplied workspace.
     * This overload makes corruption fixtures possible without granting those fixtures broader filesystem access.
     */
    public TrainingCatalog load(Path workspaceRoot, Path codingIndex, Path oralIndex, Path projectIndex) {
        Path root = approvedWorkspaceRoot(workspaceRoot);
        return load(root, codingIndex, oralIndex, projectIndex,
                root.resolve("training-center/config/imported-questions.json"));
    }

    /** Loads explicit indexes including a caller-supplied imported index (used by tests). */
    public TrainingCatalog load(Path workspaceRoot, Path codingIndex, Path oralIndex, Path projectIndex,
                                Path importedIndex) {
        Path root = approvedWorkspaceRoot(workspaceRoot);
        Path codingRoot = requireDirectory(root.resolve("output/coding-ai-exam"), "coding catalog root");
        Path interviewRoot = requireDirectory(root.resolve("output/interview"), "interview source root");

        List<QuestionDescriptor> questions = new ArrayList<>(CODING_COUNT + ORAL_COUNT + PROJECT_COUNT);
        Map<String, QuestionDescriptor> byId = new LinkedHashMap<>();
        loadCoding(readArray(codingIndex, "coding catalog"), codingRoot, questions, byId);
        loadOral(readArrayMember(oralIndex, "questions"), root, interviewRoot, questions, byId);
        loadProjects(readArrayMember(projectIndex, "cases"), root, interviewRoot, questions, byId);
        loadOdQuestionsIfPresent(root, codingRoot, questions, byId);
        loadImportedIfPresent(importedIndex, root, codingRoot, interviewRoot, questions, byId);
        return new ImmutableTrainingCatalog(questions, byId);
    }

    private static void loadCoding(JsonNode entries, Path codingRoot, List<QuestionDescriptor> questions,
                                   Map<String, QuestionDescriptor> byId) {
        requireCount(entries, CODING_COUNT, "coding");
        for (JsonNode entry : entries) {
            addCoding(entry, codingRoot, questions, byId, "coding question");
        }
    }

    private static void loadOdQuestionsIfPresent(Path workspaceRoot, Path codingRoot,
                                                  List<QuestionDescriptor> questions,
                                                  Map<String, QuestionDescriptor> byId) {
        Path odIndex = workspaceRoot.resolve("training-center/config/od-questions.json");
        if (!Files.isRegularFile(odIndex)) {
            return; // OD catalog is optional
        }
        for (JsonNode entry : readArray(odIndex, "OD catalog")) {
            addCoding(entry, codingRoot, questions, byId, "OD question");
        }
    }

    private static void addCoding(JsonNode entry, Path codingRoot, List<QuestionDescriptor> questions,
                                  Map<String, QuestionDescriptor> byId, String recordName) {
        String id = requiredText(entry, "id", recordName);
        String language = requiredText(entry, "language", id).toLowerCase(Locale.ROOT);
        if (!language.equals("java") && !language.equals("python")) {
            throw new IllegalArgumentException("Unknown coding language: " + language + " for " + id);
        }
        String contentPath = requiredText(entry, "content_path", id);
        String sourcePath = requiredText(entry, "source_path", id);
        String testPath = requiredText(entry, "test_path", id);
        requireRegularFile(codingRoot, contentPath, id);
        requireRegularFile(codingRoot, sourcePath, id);
        requireRegularFile(codingRoot, testPath, id);
        String priority = entry.has("priority") && !entry.path("priority").asText().isBlank()
                ? entry.get("priority").asText() : null;
        add(questions, byId, new QuestionDescriptor(
                id,
                Track.CODING,
                requiredText(entry, "group", id),
                requiredText(entry, "title", id),
                requiredText(entry, "topic", id),
                requiredText(entry, "difficulty", id),
                language,
                contentPath,
                "training-center/starters/" + sourcePath,
                priority));
    }

    private static void loadOral(JsonNode entries, Path workspaceRoot, Path interviewRoot,
                                 List<QuestionDescriptor> questions, Map<String, QuestionDescriptor> byId) {
        requireCount(entries, ORAL_COUNT, "oral");
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            String id = requiredText(entry, "id", "oral question");
            requireUniqueId(byId, id);
            requireExpectedId(id, "O", index + 1, "oral");
            addOral(entry, workspaceRoot, interviewRoot, questions, byId, "oral question");
        }
    }

    private static void addOral(JsonNode entry, Path workspaceRoot, Path interviewRoot,
                                List<QuestionDescriptor> questions, Map<String, QuestionDescriptor> byId,
                                String recordName) {
        String id = requiredText(entry, "id", recordName);
        requireUniqueId(byId, id);
        String sourcePath = requiredText(entry, "source_path", id);
        Path source = requireRegularWorkspaceFile(workspaceRoot, interviewRoot, sourcePath, id);
        String heading = requiredText(entry, "question_heading", id);
        requireSingleHeading(source, heading, id);
        add(questions, byId, new QuestionDescriptor(
                id,
                Track.ORAL,
                "oral",
                requiredText(entry, "title", id),
                requiredText(entry, "topic", id),
                "oral",
                "markdown",
                sourcePath + "#" + heading,
                null,
                null,
                optionalText(entry, "answer_locator", id),
                optionalText(entry, "follow_up_locator", id),
                null,
                null,
                optionalSeconds(entry, "recommended_seconds", id)));
    }

    private static void loadProjects(JsonNode entries, Path workspaceRoot, Path interviewRoot,
                                     List<QuestionDescriptor> questions, Map<String, QuestionDescriptor> byId) {
        requireCount(entries, PROJECT_COUNT, "project");
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            String id = requiredText(entry, "id", "project case");
            requireUniqueId(byId, id);
            requireExpectedId(id, "P", index + 1, "project");
            addProject(entry, workspaceRoot, interviewRoot, questions, byId, "project case");
        }
    }

    private static void addProject(JsonNode entry, Path workspaceRoot, Path interviewRoot,
                                   List<QuestionDescriptor> questions, Map<String, QuestionDescriptor> byId,
                                   String recordName) {
        String id = requiredText(entry, "id", recordName);
        requireUniqueId(byId, id);
        String sourcePath = requiredText(entry, "source_path", id);
        Path source = requireRegularWorkspaceFile(workspaceRoot, interviewRoot, sourcePath, id);
        String heading = requiredText(entry, "section_heading", id);
        requireSingleHeading(source, heading, id);
        add(questions, byId, new QuestionDescriptor(
                id,
                Track.PROJECT,
                requiredText(entry, "project_name", id),
                requiredText(entry, "title", id),
                requiredText(entry, "project_nature", id),
                "project",
                "markdown",
                sourcePath + "#" + heading,
                null,
                null,
                null,
                null,
                optionalText(entry, "evidence_entry", id),
                optionalText(entry, "fact_boundary", id),
                optionalSeconds(entry, "recommended_seconds", id)));
    }

    /**
     * Loads user-imported questions from {@code imported-questions.json} when present.
     * Entries are discriminated by {@code type} (coding/oral/project) and, unlike the built-in
     * indexes, are not subject to fixed record counts or sequential ID expectations.
     */
    private static void loadImportedIfPresent(Path importedIndex, Path workspaceRoot, Path codingRoot,
                                              Path interviewRoot, List<QuestionDescriptor> questions,
                                              Map<String, QuestionDescriptor> byId) {
        if (!Files.isRegularFile(importedIndex)) {
            return; // imported catalog is optional
        }
        for (JsonNode entry : readArray(importedIndex, "imported catalog")) {
            String type = requiredText(entry, "type", "imported question").toLowerCase(Locale.ROOT);
            switch (type) {
                case "coding" -> addCoding(entry, codingRoot, questions, byId, "imported question");
                case "oral" -> addOral(entry, workspaceRoot, interviewRoot, questions, byId, "imported question");
                case "project" -> addProject(entry, workspaceRoot, interviewRoot, questions, byId, "imported question");
                default -> throw new IllegalArgumentException("Unknown imported type: " + type);
            }
        }
    }

    private static Path approvedWorkspaceRoot(Path workspaceRoot) {
        return requireDirectory(Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize(),
                "workspace root");
    }

    private static JsonNode readArray(Path indexPath, String name) {
        JsonNode node = readTree(indexPath, name);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Expected array in " + name + ": " + indexPath);
        }
        return node;
    }

    private static JsonNode readArrayMember(Path indexPath, String member) {
        JsonNode node = readTree(indexPath, member + " index").path(member);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Missing array " + member + " in " + indexPath);
        }
        return node;
    }

    private static JsonNode readTree(Path indexPath, String name) {
        Path path = Objects.requireNonNull(indexPath, "indexPath").toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Missing " + name + ": " + path);
        }
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read " + name + ": " + path, exception);
        }
    }

    private static void requireCount(JsonNode entries, int expected, String name) {
        if (entries.size() != expected) {
            throw new IllegalArgumentException("Expected " + expected + " " + name + " records but found " + entries.size());
        }
    }

    private static String requiredText(JsonNode entry, String field, String record) {
        String value = entry.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + " for " + record);
        }
        return value;
    }

    private static String optionalText(JsonNode entry, String field, String record) {
        String value = entry.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static Integer optionalSeconds(JsonNode entry, String field, String record) {
        JsonNode node = entry.path(field);
        if (!node.isNumber() || !node.canConvertToInt() || node.asInt() < 1) {
            return null;
        }
        return node.asInt();
    }

    private static void requireExpectedId(String actual, String prefix, int number, String kind) {
        String expected = "%s%03d".formatted(prefix, number);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Expected " + kind + " ID " + expected + " but found " + actual);
        }
    }

    private static void add(List<QuestionDescriptor> questions, Map<String, QuestionDescriptor> byId,
                            QuestionDescriptor question) {
        requireUniqueId(byId, question.id());
        byId.put(question.id(), question);
        questions.add(question);
    }

    private static void requireUniqueId(Map<String, QuestionDescriptor> byId, String id) {
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate question ID: " + id);
        }
    }

    private static Path requireRegularFile(Path approvedRoot, String reference, String id) {
        if (Path.of(reference).isAbsolute()) {
            throw new IllegalArgumentException("Unsafe path for " + id + ": " + reference);
        }
        Path resolved = approvedRoot.resolve(reference).normalize();
        if (!resolved.startsWith(approvedRoot)) {
            throw new IllegalArgumentException("Unsafe path for " + id + ": " + reference);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Missing source path for " + id + ": " + reference);
        }
        return resolved;
    }

    private static Path requireRegularWorkspaceFile(Path workspaceRoot, Path approvedRoot, String reference, String id) {
        if (Path.of(reference).isAbsolute()) {
            throw new IllegalArgumentException("Unsafe path for " + id + ": " + reference);
        }
        Path resolved = workspaceRoot.resolve(reference).normalize();
        if (!resolved.startsWith(approvedRoot)) {
            throw new IllegalArgumentException("Unsafe path for " + id + ": " + reference);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Missing source path for " + id + ": " + reference);
        }
        return resolved;
    }

    private static Path requireDirectory(Path path, String name) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Missing " + name + ": " + path);
        }
        return path;
    }

    private static void requireSingleHeading(Path source, String heading, String id) {
        try (var lines = Files.lines(source, StandardCharsets.UTF_8)) {
            long count = lines.filter(heading::equals).count();
            if (count != 1) {
                throw new IllegalArgumentException("Missing heading for " + id + ": " + heading);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read source for " + id + ": " + source, exception);
        }
    }

    private static final class ImmutableTrainingCatalog implements TrainingCatalog {
        private final List<QuestionDescriptor> questions;
        private final Map<String, QuestionDescriptor> byId;

        private ImmutableTrainingCatalog(List<QuestionDescriptor> questions, Map<String, QuestionDescriptor> byId) {
            this.questions = List.copyOf(questions);
            this.byId = Map.copyOf(byId);
        }

        @Override
        public QuestionDescriptor require(String id) {
            QuestionDescriptor question = byId.get(id);
            if (question == null) {
                throw new IllegalArgumentException("Unknown question ID: " + id);
            }
            return question;
        }

        @Override
        public List<QuestionDescriptor> find(QuestionFilter filter) {
            if (filter == null) {
                return questions;
            }
            return questions.stream().filter(filter::matches).toList();
        }
    }
}
