package com.guoyongzheng.training.starter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.process.FailureKind;
import com.guoyongzheng.training.process.LocalProcessRunner;
import com.guoyongzheng.training.process.ProcessRequest;
import com.guoyongzheng.training.process.ProcessResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@Tag("integration")
class StarterMappingTest {

    @Test
    void mappingOnlyContractMatchesTheOfficialCatalog() throws IOException {
        List<StarterTestSupport.MappingRow> mappings = StarterTestSupport.loadMappings();
        List<StarterTestSupport.CatalogRow> catalog = StarterTestSupport.loadCatalog();

        assertThat(mappings).hasSize(120);
        assertThat(catalog).hasSize(120);
        assertThat(mappings).extracting(StarterTestSupport.MappingRow::questionId)
                .containsExactlyElementsOf(catalog.stream()
                        .map(StarterTestSupport.CatalogRow::questionId)
                        .toList());
        assertThat(new HashSet<>(mappings.stream()
                .map(StarterTestSupport.MappingRow::questionId)
                .toList())).hasSize(120);

        for (int index = 0; index < catalog.size(); index++) {
            StarterTestSupport.CatalogRow source = catalog.get(index);
            StarterTestSupport.MappingRow mapping = mappings.get(index);
            assertThat(mapping)
                    .as(source.questionId())
                    .isEqualTo(StarterTestSupport.derive(source));
            StarterTestSupport.assertSafeExisting(
                    StarterTestSupport.examRoot(), source.sourcePath(), "formal source");
            StarterTestSupport.assertSafeExisting(
                    StarterTestSupport.examRoot(), source.testPath(), "formal selected test");
        }
    }

    @Test
    void portablePathValidatorRejectsUnsafeAliases() {
        List<String> unsafe = List.of(
                "/rooted/file.java",
                "\\rooted\\file.java",
                "\\\\server\\share\\file.java",
                "C:/absolute/file.java",
                "C:drive-relative.java",
                "../escape.java",
                "safe/../escape.java",
                "./alias.java",
                "safe/./alias.java",
                "safe//alias.java",
                "safe\\alias.java",
                "safe/\u00a0/alias.java",
                "safe/\t/alias.java",
                "safe/name.",
                "safe/CON.java",
                "safe/aux",
                "safe/COM1.txt",
                "safe/lpt9.py");

        for (String candidate : unsafe) {
            assertThatIllegalArgumentException()
                    .as(candidate)
                    .isThrownBy(() -> StarterTestSupport.requirePortableRelative(
                            candidate, "candidate"));
        }
    }

    @Test
    void starterIdSelectionIsExactUniqueKnownAndCatalogOrdered() {
        assertThat(StarterTestSupport.selectMappings(null)).hasSize(120);
        assertThat(StarterTestSupport.selectMappings(" \t")).hasSize(120);
        assertThat(StarterTestSupport.selectMappings("B002,B001"))
                .extracting(StarterTestSupport.MappingRow::questionId)
                .containsExactly("B001", "B002");

        for (String invalid : List.of(
                "B001,B001",
                "B001,",
                ",B001",
                "B001,,B002",
                "B999",
                "b001",
                "B01",
                "B001 B002",
                "B001,\tB002",
                "B001,\u00a0B002")) {
            assertThatIllegalArgumentException()
                    .as(invalid)
                    .isThrownBy(() -> StarterTestSupport.selectMappings(invalid));
        }
    }

    @Test
    void generatorIsByteDeterministicAndFailsClosed(
            @TempDir Path temporaryWorkspace) throws IOException {
        JsonNode pristine = prepareGeneratorWorkspace(temporaryWorkspace);
        Path temporaryCatalog = temporaryWorkspace.resolve(
                "output/coding-ai-exam/catalog/questions.json");

        ProcessResult first = runGenerator(temporaryWorkspace);
        assertThat(first.failureKind()).withFailMessage(first.stderr())
                .isEqualTo(FailureKind.NONE);
        Path generated = temporaryWorkspace.resolve(
                "training-center/config/starter-mapping.json");
        byte[] firstBytes = Files.readAllBytes(generated);
        ProcessResult second = runGenerator(temporaryWorkspace);
        assertThat(second.failureKind()).withFailMessage(second.stderr())
                .isEqualTo(FailureKind.NONE);
        assertThat(Files.readAllBytes(generated)).containsExactly(firstBytes);

        JsonNode duplicate = pristine.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.get(1))
                .put("id", duplicate.get(0).path("id").textValue());
        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, duplicate.toPrettyString(),
                firstBytes, "Duplicate catalog id");

        String duplicateMember = pristine.toPrettyString().replaceFirst(
                "\"id\"\\s*:\\s*\"B001\"\\s*,",
                "\"id\":\"B001\",\"id\":\"B001\",");
        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, duplicateMember,
                firstBytes, "Duplicate JSON member");
        String escapedDuplicateMember = pristine.toPrettyString().replaceFirst(
                "\"id\"\\s*:\\s*\"B001\"\\s*,",
                "\"id\":\"B001\",\"\\\\u0069d\":\"B001\",");
        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, escapedDuplicateMember,
                firstBytes, "Duplicate JSON member");

        JsonNode unsafe = pristine.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsafe.get(0))
                .put("source_path", "../escape.java");
        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, unsafe.toPrettyString(),
                firstBytes, "unsafe path segment");

        JsonNode reserved = pristine.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) reserved.get(0))
                .put("source_path", "java/src/main/java/safe/CON.java");
        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, reserved.toPrettyString(),
                firstBytes, "unsafe path segment");

        JsonNode trailingDot = pristine.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) trailingDot.get(0))
                .put("source_path", "java/src/main/java/safe/Name.");
        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, trailingDot.toPrettyString(),
                firstBytes, "unsafe path segment");

        assertRejectedWithoutOverwriting(
                temporaryWorkspace, temporaryCatalog, "[",
                firstBytes, "malformed JSON");
    }

    @Test
    void generatorRejectsJunctionedOutputDirectory(@TempDir Path temporaryRoot)
            throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
        Path workspace = temporaryRoot.resolve("workspace");
        prepareGeneratorWorkspace(workspace);
        ProcessResult initial = runGenerator(workspace);
        assertThat(initial.failureKind()).withFailMessage(initial.stderr())
                .isEqualTo(FailureKind.NONE);

        Path config = workspace.resolve("training-center/config");
        Files.delete(config.resolve("starter-mapping.json"));
        Files.delete(config);
        Path outside = temporaryRoot.resolve("outside-config");
        Files.createDirectory(outside);
        ProcessResult junction = new LocalProcessRunner().run(new ProcessRequest(
                List.of(
                        "cmd.exe", "/d", "/c", "mklink", "/J",
                        config.toString(), outside.toString()),
                temporaryRoot,
                Duration.ofSeconds(10),
                64 * 1024,
                64 * 1024,
                Map.of()));
        assertThat(junction.failureKind()).withFailMessage(junction.stderr())
                .isEqualTo(FailureKind.NONE);
        try {
            ProcessResult rejected = runGenerator(workspace);
            assertThat(rejected.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
            assertThat(rejected.stderr() + rejected.stdout())
                    .containsIgnoringCase("reparse");
            assertThat(outside.resolve("starter-mapping.json")).doesNotExist();
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void generatorPublicationFaultPreservesPriorBytesAndLeavesNoResidue(
            @TempDir Path temporaryWorkspace) throws IOException {
        prepareGeneratorWorkspace(temporaryWorkspace);
        ProcessResult initial = runGenerator(temporaryWorkspace);
        assertThat(initial.failureKind()).withFailMessage(initial.stderr())
                .isEqualTo(FailureKind.NONE);
        Path config = temporaryWorkspace.resolve("training-center/config");
        Path mapping = config.resolve("starter-mapping.json");
        byte[] prior = Files.readAllBytes(mapping);

        ProcessResult failed = runGenerator(temporaryWorkspace, true);

        assertThat(failed.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(failed.stderr() + failed.stdout())
                .contains("Injected publication failure");
        assertThat(Files.readAllBytes(mapping)).containsExactly(prior);
        try (Stream<Path> entries = Files.list(config)) {
            assertThat(entries.toList()).containsExactly(mapping);
        }
        assertThat(temporaryWorkspace.resolve("outside-starter-mapping.json"))
                .doesNotExist();
    }

    @Test
    void generatorRejectsTamperedPublishedOutputAndPreservesOwnedBackup(
            @TempDir Path temporaryWorkspace) throws IOException {
        prepareGeneratorWorkspace(temporaryWorkspace);
        ProcessResult initial = runGenerator(temporaryWorkspace);
        assertThat(initial.failureKind()).withFailMessage(initial.stderr())
                .isEqualTo(FailureKind.NONE);
        Path config = temporaryWorkspace.resolve("training-center/config");
        Path mapping = config.resolve("starter-mapping.json");
        byte[] prior = Files.readAllBytes(mapping);

        ProcessResult failed = runGenerator(
                temporaryWorkspace, "-TestTamperPublishedAfterReplace");

        assertThat(failed.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(failed.stderr() + failed.stdout())
                .contains("Published output does not match")
                .doesNotContain("Wrote 120 deterministic Starter mappings");
        assertThat(Files.readAllBytes(mapping)).isNotEqualTo(prior);
        try (Stream<Path> entries = Files.list(config)) {
            List<Path> residue = entries.toList();
            assertThat(residue).hasSize(2).contains(mapping);
            Path backup = residue.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".bak"))
                    .findFirst()
                    .orElseThrow();
            assertThat(Files.readAllBytes(backup)).containsExactly(prior);
            assertThat(residue).noneMatch(path ->
                    path.getFileName().toString().endsWith(".tmp"));
        }
        assertThat(temporaryWorkspace.resolve("outside-starter-mapping.json"))
                .doesNotExist();
    }

    @Test
    void generatorRejectsUnrelatedBackupWithoutDeletingIt(
            @TempDir Path temporaryWorkspace) throws IOException {
        prepareGeneratorWorkspace(temporaryWorkspace);
        ProcessResult initial = runGenerator(temporaryWorkspace);
        assertThat(initial.failureKind()).withFailMessage(initial.stderr())
                .isEqualTo(FailureKind.NONE);
        Path config = temporaryWorkspace.resolve("training-center/config");
        Path mapping = config.resolve("starter-mapping.json");
        byte[] validMapping = Files.readAllBytes(mapping);

        ProcessResult failed = runGenerator(
                temporaryWorkspace, "-TestReplaceBackupAfterReplace");

        assertThat(failed.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(failed.stderr() + failed.stdout())
                .contains("Backup does not match the prior destination")
                .doesNotContain("Wrote 120 deterministic Starter mappings");
        assertThat(Files.readAllBytes(mapping)).containsExactly(validMapping);
        try (Stream<Path> entries = Files.list(config)) {
            List<Path> residue = entries.toList();
            assertThat(residue).hasSize(2).contains(mapping);
            Path backup = residue.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".bak"))
                    .findFirst()
                    .orElseThrow();
            assertThat(Files.readString(backup, StandardCharsets.UTF_8))
                    .isEqualTo("UNRELATED TEST BACKUP\n");
            assertThat(residue).noneMatch(path ->
                    path.getFileName().toString().endsWith(".tmp"));
        }
        assertThat(temporaryWorkspace.resolve("outside-starter-mapping.json"))
                .doesNotExist();
    }

    @Test
    void everyMappedStarterFileExists() {
        List<String> missing = StarterTestSupport.loadMappings().stream()
                .filter(mapping -> !Files.isRegularFile(
                        StarterTestSupport.resolvePortable(
                                StarterTestSupport.workspaceRoot(), mapping.starterPath()),
                        LinkOption.NOFOLLOW_LINKS))
                .map(mapping -> mapping.questionId() + "=" + mapping.starterPath())
                .toList();

        assertThat(missing)
                .withFailMessage("Missing Starter files (%d/120):%n%s",
                        missing.size(), String.join(System.lineSeparator(), missing))
                .isEmpty();
        for (StarterTestSupport.MappingRow mapping : StarterTestSupport.loadMappings()) {
            try {
                StarterTestSupport.assertSafeExisting(
                        StarterTestSupport.starterRoot(),
                        mapping.sandboxSourcePath(),
                        "Starter " + mapping.questionId());
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Cannot validate Starter " + mapping.questionId(), exception);
            }
        }
    }

    private static void createEmptyFixture(Path examRoot, String portable) throws IOException {
        Path file = StarterTestSupport.resolvePortable(examRoot, portable);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "", StandardCharsets.UTF_8);
    }

    private static JsonNode prepareGeneratorWorkspace(Path workspace) throws IOException {
        Path temporaryExam = workspace.resolve("output/coding-ai-exam");
        Path temporaryCatalog = temporaryExam.resolve("catalog/questions.json");
        Files.createDirectories(temporaryCatalog.getParent());
        JsonNode pristine = new ObjectMapper().readTree(Files.readString(
                StarterTestSupport.examRoot().resolve("catalog/questions.json"),
                StandardCharsets.UTF_8));
        Files.writeString(
                temporaryCatalog,
                pristine.toPrettyString(),
                StandardCharsets.UTF_8);
        for (JsonNode record : pristine) {
            createEmptyFixture(temporaryExam, record.path("source_path").textValue());
            createEmptyFixture(temporaryExam, record.path("test_path").textValue());
        }
        return pristine;
    }

    private static ProcessResult runGenerator(Path temporaryWorkspace) {
        return runGenerator(temporaryWorkspace, false);
    }

    private static ProcessResult runGenerator(
            Path temporaryWorkspace, boolean failBeforePublish) {
        return runGenerator(
                temporaryWorkspace,
                failBeforePublish ? List.of("-TestFailBeforePublish") : List.of());
    }

    private static ProcessResult runGenerator(
            Path temporaryWorkspace, String testSwitch) {
        return runGenerator(temporaryWorkspace, List.of(testSwitch));
    }

    private static ProcessResult runGenerator(
            Path temporaryWorkspace, List<String> testSwitches) {
        Path script = StarterTestSupport.workspaceRoot()
                .resolve("training-center/tools/build_starter_mapping.ps1");
        List<String> command = new ArrayList<>(List.of(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                "-WorkspaceRoot",
                temporaryWorkspace.toString()));
        command.addAll(testSwitches);
        return new LocalProcessRunner().run(new ProcessRequest(
                command,
                temporaryWorkspace,
                Duration.ofSeconds(30),
                64 * 1024,
                64 * 1024,
                Map.of()));
    }

    private static void assertRejectedWithoutOverwriting(
            Path workspace,
            Path catalog,
            String invalidJson,
            byte[] expectedMapping,
            String diagnosticFragment) throws IOException {
        Files.writeString(catalog, invalidJson, StandardCharsets.UTF_8);
        ProcessResult result = runGenerator(workspace);
        assertThat(result.failureKind()).isEqualTo(FailureKind.TEST_FAILURE);
        assertThat(result.stderr() + result.stdout())
                .containsIgnoringCase(diagnosticFragment);
        assertThat(Files.readAllBytes(workspace.resolve(
                "training-center/config/starter-mapping.json")))
                .containsExactly(expectedMapping);
    }
}

final class StarterTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> MAPPING_FIELDS = Set.of(
            "question_id",
            "starter_path",
            "sandbox_source_path",
            "runner_kind",
            "test_selector");
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "id", "language", "source_path", "test_path");

    private StarterTestSupport() {
    }

    static Path workspaceRoot() {
        String injected = System.getProperty("training.workspace");
        if (injected != null && !injected.isBlank()) {
            return Path.of(injected).toAbsolutePath().normalize();
        }
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(
                    "output/coding-ai-exam/catalog/questions.json"))
                    && Files.isDirectory(candidate.resolve("training-center"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Workspace root not found; set -Dtraining.workspace=<absolute-path>");
    }

    static Path examRoot() {
        return workspaceRoot().resolve("output/coding-ai-exam");
    }

    static Path starterRoot() {
        return workspaceRoot().resolve("training-center/starters");
    }

    static List<MappingRow> loadMappings() {
        Path path = workspaceRoot().resolve("training-center/config/starter-mapping.json");
        JsonNode root = readJson(path, "Starter mapping");
        if (!root.isArray()) {
            throw new IllegalArgumentException("Starter mapping root must be a JSON array");
        }
        List<MappingRow> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode node = root.get(index);
            requireObjectWithExactFields(node, MAPPING_FIELDS, "mapping[" + index + "]");
            MappingRow row = new MappingRow(
                    requiredText(node, "question_id", index),
                    requirePortableRelative(
                            requiredText(node, "starter_path", index), "starter_path"),
                    requirePortableRelative(
                            requiredText(node, "sandbox_source_path", index),
                            "sandbox_source_path"),
                    requiredText(node, "runner_kind", index),
                    requiredText(node, "test_selector", index));
            if (!ids.add(row.questionId())) {
                throw new IllegalArgumentException(
                        "Duplicate Starter mapping question_id: " + row.questionId());
            }
            validateMappingShape(row);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    static List<CatalogRow> loadCatalog() {
        Path path = examRoot().resolve("catalog/questions.json");
        JsonNode root = readJson(path, "Official catalog");
        if (!root.isArray()) {
            throw new IllegalArgumentException("Official catalog root must be a JSON array");
        }
        List<CatalogRow> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode node = root.get(index);
            if (!node.isObject() || !containsAll(node.fieldNames(), CATALOG_FIELDS)) {
                throw new IllegalArgumentException(
                        "catalog[" + index + "] lacks a required field");
            }
            CatalogRow row = new CatalogRow(
                    requiredText(node, "id", index),
                    requiredText(node, "language", index).toLowerCase(Locale.ROOT),
                    requirePortableRelative(
                            requiredText(node, "source_path", index), "source_path"),
                    requirePortableRelative(
                            requiredText(node, "test_path", index), "test_path"));
            if (!ids.add(row.questionId())) {
                throw new IllegalArgumentException(
                        "Duplicate catalog question id: " + row.questionId());
            }
            validateCatalogShape(row);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    static MappingRow derive(CatalogRow row) {
        String runner;
        String selector;
        if ("java".equals(row.language())) {
            runner = "MAVEN";
            String filename = row.testPath().substring(row.testPath().lastIndexOf('/') + 1);
            selector = filename.substring(0, filename.length() - ".java".length());
        } else if ("python".equals(row.language())) {
            runner = "PYTEST";
            selector = row.testPath();
        } else {
            throw new IllegalArgumentException(
                    "Unsupported catalog language for " + row.questionId() + ": "
                            + row.language());
        }
        return new MappingRow(
                row.questionId(),
                "training-center/starters/" + row.sourcePath(),
                row.sourcePath(),
                runner,
                selector);
    }

    static List<MappingRow> selectedMappings() {
        return selectMappings(System.getProperty("starter.ids"));
    }

    static List<MappingRow> selectMappings(String selection) {
        List<MappingRow> all = loadMappings();
        if (selection == null || selection.isBlank()) {
            return all;
        }
        if (!selection.equals(selection.trim())
                || selection.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "starter.ids must not contain whitespace; use comma-separated IDs");
        }
        String[] tokens = selection.split(",", -1);
        Set<String> requested = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.isEmpty()) {
                throw new IllegalArgumentException("starter.ids contains an empty token");
            }
            if (!token.matches("(?:B|A|I)\\d{3}")) {
                throw new IllegalArgumentException("Malformed starter id: " + token);
            }
            if (!requested.add(token)) {
                throw new IllegalArgumentException("Duplicate starter id: " + token);
            }
        }
        Set<String> known = all.stream()
                .map(MappingRow::questionId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> unknown = new LinkedHashSet<>(requested);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown starter ids: " + unknown);
        }
        return all.stream().filter(row -> requested.contains(row.questionId())).toList();
    }

    static Stream<MappingRow> selectedMappingStream() {
        return selectedMappings().stream();
    }

    static String requirePortableRelative(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!value.equals(value.trim())
                || value.indexOf('\\') >= 0
                || value.startsWith("/")
                || value.startsWith("//")
                || value.matches("^[A-Za-z]:.*")
                || value.codePoints().anyMatch(codePoint ->
                        Character.isISOControl(codePoint)
                                || Character.isWhitespace(codePoint)
                                || Character.isSpaceChar(codePoint))) {
            throw new IllegalArgumentException(field + " is not a normalized portable path: " + value);
        }
        String[] segments = value.split("/", -1);
        if (segments.length == 0) {
            throw new IllegalArgumentException(field + " has no path segments");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.indexOf(':') >= 0
                    || segment.endsWith(".")
                    || segment.matches(
                            "(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]|CONIN\\$|CONOUT\\$)"
                                    + "(?:\\..*)?")) {
                throw new IllegalArgumentException(
                        field + " contains an unsafe path segment: " + value);
            }
        }
        return String.join("/", segments);
    }

    static Path resolvePortable(Path root, String portable) {
        Path current = root.toAbsolutePath().normalize();
        for (String segment : requirePortableRelative(portable, "portable path").split("/")) {
            current = current.resolve(segment);
        }
        Path resolved = current.normalize();
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Portable path escapes root: " + portable);
        }
        return resolved;
    }

    static void assertSafeExisting(Path root, String portable, String kind) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = resolvePortable(normalizedRoot, portable);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(kind + " is not a regular file: " + portable);
        }
        Path realRoot = normalizedRoot.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new IllegalArgumentException(kind + " escapes through a link: " + portable);
        }
        Path current = normalizedRoot;
        for (String segment : portable.split("/")) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(kind + " contains a symbolic link: " + portable);
            }
        }
    }

    private static JsonNode readJson(Path path, String description) {
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    description + " cannot be read as strict UTF-8 JSON: " + path, exception);
        }
    }

    private static void requireObjectWithExactFields(
            JsonNode node, Set<String> expected, String description) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    description + " fields must be exactly " + expected + " but were " + actual);
        }
    }

    private static boolean containsAll(Iterator<String> names, Set<String> required) {
        Set<String> actual = new HashSet<>();
        names.forEachRemaining(actual::add);
        return actual.containsAll(required);
    }

    private static String requiredText(JsonNode node, String field, int index) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "record[" + index + "]." + field + " must be nonblank text");
        }
        return value.textValue();
    }

    private static void validateMappingShape(MappingRow row) {
        if (!row.questionId().matches("(?:B|A|I|OD)\\d{3}")) {
            throw new IllegalArgumentException("Malformed mapping id: " + row.questionId());
        }
        String expectedPrefix = "training-center/starters/";
        if (!row.starterPath().equals(expectedPrefix + row.sandboxSourcePath())) {
            throw new IllegalArgumentException(
                    row.questionId() + " Starter path does not mirror sandbox source path");
        }
        if ("MAVEN".equals(row.runnerKind())) {
            if (!row.sandboxSourcePath().startsWith("java/src/main/java/")
                    || !row.sandboxSourcePath().endsWith(".java")
                    || !row.testSelector().matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException(
                        row.questionId() + " has an invalid Maven mapping");
            }
        } else if ("PYTEST".equals(row.runnerKind())) {
            requirePortableRelative(row.testSelector(), "test_selector");
            if (!row.sandboxSourcePath().startsWith("python/src/")
                    || !row.sandboxSourcePath().endsWith(".py")
                    || !row.testSelector().startsWith("python/tests/")
                    || !row.testSelector().endsWith(".py")) {
                throw new IllegalArgumentException(
                        row.questionId() + " has an invalid pytest mapping");
            }
        } else {
            throw new IllegalArgumentException(
                    row.questionId() + " has unsupported runner_kind " + row.runnerKind());
        }
    }

    private static void validateCatalogShape(CatalogRow row) {
        if (!row.questionId().matches("(?:B|A|I|OD)\\d{3}")) {
            throw new IllegalArgumentException("Malformed catalog id: " + row.questionId());
        }
        if ("java".equals(row.language())) {
            if (!row.sourcePath().startsWith("java/src/main/java/")
                    || !row.sourcePath().endsWith(".java")
                    || !row.testPath().startsWith("java/src/test/java/")
                    || !row.testPath().endsWith("Test.java")) {
                throw new IllegalArgumentException(
                        row.questionId() + " has an invalid Java catalog shape");
            }
        } else if ("python".equals(row.language())) {
            if (!row.sourcePath().startsWith("python/src/")
                    || !row.sourcePath().endsWith(".py")
                    || !row.testPath().startsWith("python/tests/")
                    || !row.testPath().endsWith(".py")) {
                throw new IllegalArgumentException(
                        row.questionId() + " has an invalid Python catalog shape");
            }
        } else {
            throw new IllegalArgumentException(
                    row.questionId() + " has unsupported language " + row.language());
        }
    }

    record MappingRow(
            String questionId,
            String starterPath,
            String sandboxSourcePath,
            String runnerKind,
            String testSelector) {
    }

    record CatalogRow(
            String questionId,
            String language,
            String sourcePath,
            String testPath) {
    }
}
