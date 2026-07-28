package com.guoyongzheng.training.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates stable interview indexes without reading or exposing answer bodies. */
public final class InterviewIndexValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    public ValidationReport validate(Path workspaceRoot) {
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        List<String> oralIds = validateOral(normalizedRoot, violations);
        List<String> projectIds = validateProjects(normalizedRoot, violations);
        return new ValidationReport(violations.isEmpty(), oralIds.size(), projectIds.size(),
                List.copyOf(oralIds), List.copyOf(projectIds), List.copyOf(violations));
    }

    private List<String> validateOral(Path root, List<String> violations) {
        JsonNode entries = readEntries(root.resolve("training-center/config/oral-questions.json"), "questions", violations);
        List<String> ids = ids(entries, "oral", violations);
        validateExactSequence(ids, "O", 80, "oral", violations);
        validateUniqueQuestionHeadings(root, entries, "oral", violations);
        return ids;
    }

    private List<String> validateProjects(Path root, List<String> violations) {
        JsonNode entries = readEntries(root.resolve("training-center/config/project-cases.json"), "cases", violations);
        List<String> ids = ids(entries, "project", violations);
        validateExactSequence(ids, "P", 8, "project", violations);
        validateProjectLocators(root, entries, violations);
        return ids;
    }

    private JsonNode readEntries(Path indexPath, String arrayName, List<String> violations) {
        try {
            if (!Files.isRegularFile(indexPath)) {
                violations.add("Missing index: " + indexPath);
                return JSON.createArrayNode();
            }
            JsonNode entries = JSON.readTree(Files.readString(indexPath, StandardCharsets.UTF_8)).path(arrayName);
            if (!entries.isArray()) {
                violations.add("Index array is missing: " + arrayName);
                return JSON.createArrayNode();
            }
            return entries;
        } catch (IOException exception) {
            violations.add("Cannot read index: " + indexPath);
            return JSON.createArrayNode();
        }
    }

    private List<String> ids(JsonNode entries, String kind, List<String> violations) {
        List<String> ids = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode entry : entries) {
            String id = entry.path("id").asText();
            if (id.isBlank()) {
                violations.add("Blank " + kind + " ID");
            } else if (!unique.add(id)) {
                violations.add("Duplicate " + kind + " ID: " + id);
            }
            ids.add(id);
        }
        return ids;
    }

    private void validateExactSequence(List<String> ids, String prefix, int count, String kind, List<String> violations) {
        if (ids.size() != count) {
            violations.add("Expected " + count + " " + kind + " records but found " + ids.size());
        }
        for (int index = 0; index < Math.min(ids.size(), count); index++) {
            String expected = "%s%03d".formatted(prefix, index + 1);
            if (!expected.equals(ids.get(index))) {
                violations.add("Expected " + kind + " ID " + expected + " at position " + (index + 1));
            }
        }
    }

    private void validateUniqueQuestionHeadings(Path root, JsonNode entries, String kind, List<String> violations) {
        Set<String> locators = new HashSet<>();
        for (JsonNode entry : entries) {
            String id = entry.path("id").asText("<unknown>");
            String heading = entry.path("question_heading").asText();
            Path source = sourcePath(root, entry.path("source_path").asText(), id, violations);
            if (heading.isBlank()) {
                violations.add("Missing question heading for " + id);
                continue;
            }
            String locator = source + "#" + heading;
            if (!locators.add(locator)) {
                violations.add("Duplicate " + kind + " heading locator: " + heading);
            }
            if (source != null) {
                if (countLines(source, heading) != 1) {
                    violations.add("Unresolvable " + kind + " heading for " + id + ": " + heading);
                } else {
                    validateOralLocator(source, heading, entry, "answer_locator", id, violations);
                    validateOralLocator(source, heading, entry, "follow_up_locator", id, violations);
                }
            }
        }
    }

    private void validateOralLocator(Path source, String questionHeading, JsonNode entry, String property,
                                     String id, List<String> violations) {
        String locator = entry.path(property).asText();
        if (locator.isBlank()) {
            violations.add("Missing " + property + " for " + id);
        } else if (countWithinOralQuestion(source, questionHeading, locator) != 1) {
            violations.add("Unresolvable " + property + " for " + id + ": " + locator);
        }
    }

    private void validateProjectLocators(Path root, JsonNode entries, List<String> violations) {
        Set<String> sections = new HashSet<>();
        for (JsonNode entry : entries) {
            String id = entry.path("id").asText("<unknown>");
            Path source = sourcePath(root, entry.path("source_path").asText(), id, violations);
            String section = entry.path("section_heading").asText();
            if (section.isBlank()) {
                violations.add("Missing section_heading for " + id);
            } else if (source != null && countLines(source, section) != 1) {
                violations.add("Unresolvable section_heading for " + id + ": " + section);
            }
            if (!section.isBlank() && !sections.add(section)) {
                violations.add("Duplicate project section locator: " + section);
            }
            for (String property : List.of("evidence_entry", "fact_boundary")) {
                String heading = entry.path(property).asText();
                if (heading.isBlank()) {
                    violations.add("Missing " + property + " for " + id);
                } else if (source != null && countWithinProjectSection(source, section, heading) != 1) {
                    violations.add("Unresolvable " + property + " for " + id + ": " + heading);
                }
            }
        }
    }

    private Path sourcePath(Path root, String sourceReference, String id, List<String> violations) {
        if (sourceReference.isBlank()) {
            violations.add("Missing source path for " + id);
            return null;
        }
        Path source = root.resolve(sourceReference).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            violations.add("Missing or unsafe source for " + id + ": " + sourceReference);
            return null;
        }
        return source;
    }

    private int countLines(Path source, String expected) {
        try (var lines = Files.lines(source, StandardCharsets.UTF_8)) {
            return (int) lines.filter(expected::equals).count();
        } catch (IOException exception) {
            return 0;
        }
    }

    private int countWithinOralQuestion(Path source, String questionHeading, String expectedLocator) {
        try {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            int start = lines.indexOf(questionHeading);
            if (start < 0) {
                return 0;
            }
            int count = 0;
            for (int index = start + 1; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.matches("^### \\d+\\. .+")) {
                    return count;
                }
                if (line.equals(expectedLocator)) {
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            return 0;
        }
    }

    private int countWithinProjectSection(Path source, String sectionHeading, String expectedHeading) {
        try {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            int start = lines.indexOf(sectionHeading);
            if (start < 0) {
                return 0;
            }
            int count = 0;
            for (int index = start + 1; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.startsWith("## ")) {
                    return count;
                }
                if (line.equals(expectedHeading)) {
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            return 0;
        }
    }

    public record ValidationReport(boolean valid, int oralCount, int projectCount,
                                   List<String> oralIds, List<String> projectIds, List<String> violations) {
    }
}
