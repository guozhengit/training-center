package com.guoyongzheng.training.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.catalog.QuestionFilter;
import com.guoyongzheng.training.catalog.TrainingCatalog;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DashboardService {
    private static final int MAX_QUESTION_LIMIT = 300;

    private final WorkspaceLocator workspaceLocator;
    private final CatalogCache catalogCache;
    private final ObjectMapper objectMapper;
    private volatile Map<String, QuestionContentResponse> contentCache;

    public DashboardService(WorkspaceLocator workspaceLocator, CatalogCache catalogCache, ObjectMapper objectMapper) {
        this.workspaceLocator = workspaceLocator;
        this.catalogCache = catalogCache;
        this.objectMapper = objectMapper;
    }

    public HealthResponse health() {
        Path workspace = workspaceLocator.locate();
        Path report = matrixReportPath(workspace);
        return new HealthResponse(
                "UP",
                workspace.toString(),
                Files.isDirectory(workspace.resolve("training-center/config")),
                Files.isRegularFile(workspace.resolve("output/coding-ai-exam/catalog/questions.json")),
                Files.isRegularFile(report),
                Instant.now());
    }

    public CatalogSummaryResponse catalogSummary() {
        Path workspace = workspaceLocator.locate();
        List<QuestionDescriptor> questions = catalogCache.get().find(null);
        return new CatalogSummaryResponse(
                workspace.toString(),
                questions.size(),
                countByTrack(questions),
                countBy(questions, question -> question.groupName()),
                countBy(questions, question -> question.topic()),
                countBy(questions, question -> question.language()));
    }

    public QuestionListResponse questions(String trackValue, int requestedLimit) {
        return questions(trackValue, null, null, null, requestedLimit);
    }

    public QuestionListResponse questions(String trackValue, String group, String topic, String difficulty, int requestedLimit) {
        Path workspace = workspaceLocator.locate();
        Track track = parseTrack(trackValue);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_QUESTION_LIMIT));
        String groupFilter = (group == null || group.isBlank()) ? null : group;
        String topicFilter = (topic == null || topic.isBlank()) ? null : topic;
        String difficultyFilter = (difficulty == null || difficulty.isBlank()) ? null : difficulty;
        List<QuestionCard> cards = catalogCache.get()
                .find(new QuestionFilter(track, groupFilter, topicFilter, difficultyFilter, null))
                .stream()
                .limit(limit)
                .map(QuestionCard::from)
                .toList();
        return new QuestionListResponse(track == null ? "ALL" : track.name(), limit, cards.size(), cards);
    }

    public FiltersResponse availableFilters(String trackValue) {
        Track track = parseTrack(trackValue);
        List<QuestionDescriptor> all = catalogCache.get()
                .find(new QuestionFilter(track, null, null, null, null));
        List<String> groups = all.stream().map(QuestionDescriptor::groupName)
                .distinct().sorted().toList();
        List<String> topics = all.stream().map(QuestionDescriptor::topic)
                .distinct().sorted().toList();
        List<String> difficulties = all.stream().map(QuestionDescriptor::difficulty)
                .distinct().sorted().toList();
        return new FiltersResponse(groups, topics, difficulties);
    }

    public MatrixReportResponse matrixReport() {
        Path workspace = workspaceLocator.locate();
        Path reportPath = matrixReportPath(workspace);
        if (!Files.isRegularFile(reportPath)) {
            return MatrixReportResponse.missing(reportPath.toString());
        }

        try {
            JsonNode root = objectMapper.readTree(Files.readString(reportPath, StandardCharsets.UTF_8));
            JsonNode results = root.path("results");
            int totalResults = results.isArray() ? results.size() : 0;
            int nonPass = 0;
            if (results.isArray()) {
                for (JsonNode result : results) {
                    if (!"PASS".equalsIgnoreCase(result.path("status").asText())) {
                        nonPass++;
                    }
                }
            }
            return new MatrixReportResponse(
                    true,
                    reportPath.toString(),
                    sha256(reportPath),
                    root.path("schemaVersion").asText("unknown"),
                    totalResults,
                    nonPass,
                    matrixMode(root, "contract"),
                    matrixMode(root, "starterExpectedFailures"),
                    matrixMode(root, "referencePasses"),
                    root.path("officialTree").path("afterSha256").asText(""),
                    root.path("officialTree").path("afterFileCount").asInt(0));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read matrix report: " + reportPath, exception);
        }
    }

    public QuestionContentResponse questionContent(String questionId) {
        Map<String, QuestionContentResponse> cache = contentCache;
        if (cache == null) {
            synchronized (this) {
                cache = contentCache;
                if (cache == null) {
                    cache = preloadContent();
                    contentCache = cache;
                }
            }
        }
        QuestionContentResponse response = cache.get(questionId);
        if (response == null) {
            throw new IllegalArgumentException("Question not found: " + questionId);
        }
        return response;
    }

    public synchronized void invalidateContentCache() {
        contentCache = null;
    }

    private Map<String, QuestionContentResponse> preloadContent() {
        Path workspace = workspaceLocator.locate();
        TrainingCatalog catalog = catalogCache.get();
        List<QuestionDescriptor> all = catalog.find(null);
        Map<String, QuestionContentResponse> map = new HashMap<>(all.size());
        for (QuestionDescriptor question : all) {
            String description = readContentFile(workspace, question.sourceRef());
            String starterCode = readContentFile(workspace, question.starterRef());
            map.put(question.id(),
                    new QuestionContentResponse(question.id(), question.title(), description, starterCode));
        }
        return Map.copyOf(map);
    }

    private static String readContentFile(Path workspace, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        // sourceRef is relative to output/coding-ai-exam/, starterRef is relative to workspace root
        Path candidate = workspace.resolve(relativePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(candidate)) {
            // try under output/coding-ai-exam/ for sourceRef-style paths
            candidate = workspace.resolve("output/coding-ai-exam").resolve(relativePath).toAbsolutePath().normalize();
        }
        if (!Files.isRegularFile(candidate)) {
            return "";
        }
        try {
            return Files.readString(candidate, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private static Path matrixReportPath(Path workspace) {
        return workspace.resolve("output/training-runtime/logs/starter-matrix-report.json");
    }

    private static Track parseTrack(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Track.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown track: " + value);
        }
    }

    private static MatrixModeSummary matrixMode(JsonNode root, String field) {
        JsonNode node = root.path("summary").path(field);
        return new MatrixModeSummary(node.path("passed").asInt(0), node.path("total").asInt(0));
    }

    private static Map<String, Long> countByTrack(List<QuestionDescriptor> questions) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(Track.CODING.name(), 0L);
        counts.put(Track.ORAL.name(), 0L);
        counts.put(Track.PROJECT.name(), 0L);
        for (QuestionDescriptor question : questions) {
            counts.compute(question.track().name(), (key, count) -> count == null ? 1L : count + 1L);
        }
        return counts;
    }

    private static Map<String, Long> countBy(List<QuestionDescriptor> questions, ValueExtractor extractor) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (QuestionDescriptor question : questions) {
            String value = extractor.extract(question);
            counts.compute(value, (key, count) -> count == null ? 1L : count + 1L);
        }
        return counts;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append("%02X".formatted(value));
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot hash file: " + path, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private interface ValueExtractor {
        String extract(QuestionDescriptor question);
    }

    public record HealthResponse(
            String status,
            String workspaceRoot,
            boolean configAvailable,
            boolean catalogAvailable,
            boolean matrixReportAvailable,
            Instant checkedAt) {
    }

    public record FiltersResponse(List<String> groups, List<String> topics, List<String> difficulties) {
    }

    public record CatalogSummaryResponse(
            String workspaceRoot,
            int totalQuestions,
            Map<String, Long> trackCounts,
            Map<String, Long> groupCounts,
            Map<String, Long> topicCounts,
            Map<String, Long> languageCounts) {
    }

    public record QuestionListResponse(String track, int limit, int returned, List<QuestionCard> questions) {
    }

    public record QuestionCard(
            String id,
            String track,
            String groupName,
            String title,
            String topic,
            String difficulty,
            String language,
            String sourceRef,
            String starterRef) {
        static QuestionCard from(QuestionDescriptor question) {
            return new QuestionCard(
                    question.id(),
                    question.track().name(),
                    question.groupName(),
                    question.title(),
                    question.topic(),
                    question.difficulty(),
                    question.language(),
                    question.sourceRef(),
                    question.starterRef());
        }
    }

    public record QuestionContentResponse(
            String questionId,
            String title,
            String description,
            String starterCode) {
    }

    public record MatrixReportResponse(
            boolean available,
            String reportPath,
            String reportSha256,
            String schemaVersion,
            int totalResults,
            int nonPassResults,
            MatrixModeSummary contract,
            MatrixModeSummary starterExpectedFailures,
            MatrixModeSummary referencePasses,
            String officialTreeAfterSha256,
            int officialTreeAfterFileCount) {
        static MatrixReportResponse missing(String reportPath) {
            return new MatrixReportResponse(false, reportPath, "", "", 0, 0,
                    new MatrixModeSummary(0, 0),
                    new MatrixModeSummary(0, 0),
                    new MatrixModeSummary(0, 0),
                    "",
                    0);
        }
    }

    public record MatrixModeSummary(int passed, int total) {
    }
}
