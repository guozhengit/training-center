package com.guoyongzheng.training.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.catalog.StarterMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SandboxServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-26T12:34:56Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsJavaSandboxByReplacingOnlyTheSelectedSource() throws IOException {
        Fixture fixture = fixture();
        StarterMapping mapping = javaMapping();
        Map<String, byte[]> original = snapshot(fixture.examRoot.resolve("java"));

        SandboxManifest manifest = fixture.service.create("session-1", "attempt-1", mapping);

        Path attempt = fixture.sandboxRoot.resolve("session-1/attempt-1");
        Path work = attempt.resolve("work");
        assertThat(Files.readString(work.resolve(mapping.sandboxSourcePath())))
                .isEqualTo("package exam; public class Selected { }\n");
        assertThat(Files.readString(work.resolve("java/src/main/java/exam/Shared.java")))
                .isEqualTo("package exam; public record Shared(String value) { }\n");
        assertThat(Files.readString(work.resolve("java/src/test/java/exam/SelectedTest.java")))
                .contains("class SelectedTest");
        assertThat(work.resolve("java/pom.xml")).isRegularFile();
        assertThat(work.resolve("java/target")).doesNotExist();
        assertOnlySelectedSourceChanged(
                withoutGeneratedFiles(original),
                snapshot(work.resolve("java")),
                "src/main/java/exam/Selected.java");
        assertThat(snapshot(fixture.examRoot.resolve("java"))).containsExactlyEntriesOf(original);

        assertThat(manifest.questionId()).isEqualTo("B001");
        assertThat(manifest.runnerKind()).isEqualTo(RunnerKind.MAVEN);
        assertThat(manifest.selectedTest()).isEqualTo("exam.SelectedTest");
        assertThat(manifest.createdAt()).isEqualTo(CREATED_AT);
        assertThat(manifest.originalSourceSha256()).isNotEqualTo(manifest.starterSha256());
        assertThat(manifest.sandboxSourceSha256()).isEqualTo(manifest.starterSha256());
    }

    @Test
    void createsPythonSandboxWithCompleteProjectAndNoCaches() throws IOException {
        Fixture fixture = fixture();
        StarterMapping mapping = pythonMapping();
        Map<String, byte[]> original = snapshot(fixture.examRoot.resolve("python"));

        SandboxManifest manifest = fixture.service.create("session-2", "attempt-2", mapping);

        Path work = fixture.sandboxRoot.resolve("session-2/attempt-2/work");
        assertThat(Files.readString(work.resolve(mapping.sandboxSourcePath())))
                .isEqualTo("def selected():\n    raise NotImplementedError\n");
        assertThat(work.resolve("python/pyproject.toml")).isRegularFile();
        assertThat(work.resolve("python/tests/test_selected.py")).isRegularFile();
        assertThat(work.resolve("python/src/exam/shared.py")).isRegularFile();
        assertThat(work.resolve("python/.pytest_cache")).doesNotExist();
        assertThat(work.resolve("python/src/exam/__pycache__")).doesNotExist();
        assertOnlySelectedSourceChanged(
                withoutGeneratedFiles(original),
                snapshot(work.resolve("python")),
                "src/exam/selected.py");
        assertThat(snapshot(fixture.examRoot.resolve("python"))).containsExactlyEntriesOf(original);
        assertThat(manifest.runnerKind()).isEqualTo(RunnerKind.PYTEST);
        assertThat(manifest.selectedTest()).isEqualTo("python/tests/test_selected.py");
    }

    @Test
    void writesCompleteManifestWithHashesAndRelativePathsOnly() throws IOException {
        Fixture fixture = fixture();

        SandboxManifest manifest = fixture.service.create("session-3", "attempt-3", javaMapping());

        Path manifestPath = fixture.sandboxRoot.resolve("session-3/attempt-3/manifest.json");
        JsonNode json = new ObjectMapper().readTree(manifestPath.toFile());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "questionId",
                "runnerKind",
                "originalProjectSha256",
                "originalSourceSha256",
                "starterSha256",
                "sandboxSourceSha256",
                "workTreeSha256",
                "selectedTest",
                "ownershipToken",
                "createdAt",
                "workPath",
                "sandboxSourcePath",
                "starterPath");
        assertThat(json.properties()).allSatisfy(entry -> assertThat(entry.getValue().isNull()).isFalse());
        assertThat(json.get("questionId").asText()).isEqualTo("B001");
        assertThat(json.get("selectedTest").asText()).isEqualTo("exam.SelectedTest");
        assertThat(json.get("ownershipToken").asText())
                .matches("[0-9a-f]{8}-[0-9a-f-]{27,}");
        assertThat(json.get("createdAt").asText()).isEqualTo(CREATED_AT.toString());
        assertThat(json.get("originalProjectSha256").asText()).isEqualTo(manifest.originalProjectSha256());
        assertThat(json.get("originalSourceSha256").asText()).hasSize(64);
        assertThat(json.get("starterSha256").asText()).hasSize(64);
        assertThat(json.get("sandboxSourceSha256").asText()).hasSize(64);
        assertThat(json.get("workTreeSha256").asText())
                .isEqualTo(manifest.workTreeSha256())
                .hasSize(64);
        assertRelative(json.get("workPath").asText());
        assertRelative(json.get("sandboxSourcePath").asText());
        assertRelative(json.get("starterPath").asText());
    }

    @Test
    void rejectsDotDotAndAbsoluteInputPathsWithoutCreatingAnAttempt() throws IOException {
        Fixture fixture = fixture();
        Path absoluteSource = fixture.examRoot.resolve("java/pom.xml").toAbsolutePath();

        assertThatThrownBy(() -> fixture.service.create(
                "session-4",
                "attempt-dotdot",
                new StarterMapping(
                        "B001",
                        Path.of("../starter.java"),
                        Path.of("java/src/main/java/exam/Selected.java"),
                        RunnerKind.MAVEN,
                        "exam.SelectedTest")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.create(
                "session-4",
                "attempt-absolute",
                new StarterMapping(
                        "B001",
                        Path.of("java/Selected.java"),
                        absoluteSource,
                        RunnerKind.MAVEN,
                        "exam.SelectedTest")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.create("../outside", "attempt", javaMapping()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.sandboxRoot.resolve("session-4")).doesNotExist();
    }

    @Test
    void rejectsDriveRelativeRootsUnsafeIdsAndCancellingSegmentsBeforeNormalization()
            throws IOException {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> new StarterMapping(
                "B001",
                Path.of("D:starter.java"),
                Path.of("java/src/main/java/exam/Selected.java"),
                RunnerKind.MAVEN,
                "exam.SelectedTest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StarterMapping(
                "B001",
                Path.of("java/Selected.java"),
                Path.of("D:src\\Selected.java"),
                RunnerKind.MAVEN,
                "exam.SelectedTest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StarterMapping(
                "B001",
                Path.of("java/tmp/../Selected.java"),
                Path.of("java/src/main/java/exam/Selected.java"),
                RunnerKind.MAVEN,
                "exam.SelectedTest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.create("D:session", "attempt", javaMapping()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.create("bad session", "attempt", javaMapping()))
                .isInstanceOf(IllegalArgumentException.class);
        Path cancellingCleanup = fixture.sandboxRoot.resolve(
                "session/../session/attempt");
        assertThatThrownBy(() -> fixture.service.cleanup(cancellingCleanup))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxManifest(
                "B001",
                RunnerKind.MAVEN,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "exam.SelectedTest",
                "00000000-0000-0000-0000-000000000001",
                CREATED_AT,
                "D:work",
                "java/src/main/java/exam/Selected.java",
                "java/Selected.java"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesRunnerSpecificSourceAndTestLayouts() throws IOException {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(
                "layout",
                "java-source-in-tests",
                new StarterMapping(
                        "B001",
                        Path.of("java/Selected.java"),
                        Path.of("java/src/test/java/exam/SelectedTest.java"),
                        RunnerKind.MAVEN,
                        "exam.SelectedTest")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java/src/main/java");
        assertThatThrownBy(() -> fixture.service.create(
                "layout",
                "java-missing-test",
                new StarterMapping(
                        "B001",
                        Path.of("java/Selected.java"),
                        Path.of("java/src/main/java/exam/Selected.java"),
                        RunnerKind.MAVEN,
                        "exam.MissingTest")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("selected test");
        assertThatThrownBy(() -> fixture.service.create(
                "layout",
                "python-source-in-tests",
                new StarterMapping(
                        "I001",
                        Path.of("python/selected.py"),
                        Path.of("python/tests/test_selected.py"),
                        RunnerKind.PYTEST,
                        "python/tests/test_selected.py")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("python/src");
        assertThatThrownBy(() -> fixture.service.create(
                "layout",
                "python-test-in-src",
                new StarterMapping(
                        "I001",
                        Path.of("python/selected.py"),
                        Path.of("python/src/exam/selected.py"),
                        RunnerKind.PYTEST,
                        "python/src/exam/selected.py")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("python/tests");
        assertThatThrownBy(() -> fixture.service.create(
                "layout",
                "java-method-node",
                new StarterMapping(
                        "B001",
                        Path.of("java/Selected.java"),
                        Path.of("java/src/main/java/exam/Selected.java"),
                        RunnerKind.MAVEN,
                        "exam.SelectedTest#specificMethod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("class selector");
        assertThatThrownBy(() -> fixture.service.create(
                "layout",
                "python-node",
                new StarterMapping(
                        "I001",
                        Path.of("python/selected.py"),
                        Path.of("python/src/exam/selected.py"),
                        RunnerKind.PYTEST,
                        "python/tests/test_selected.py::test_selected")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test file");
    }

    @Test
    void rejectsSourceStarterMismatchAndMissingSelectedSource() throws IOException {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(
                "session-5",
                "attempt-mismatch",
                new StarterMapping(
                        "B001",
                        Path.of("python/selected.py"),
                        Path.of("java/src/main/java/exam/Selected.java"),
                        RunnerKind.MAVEN,
                        "exam.SelectedTest")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mismatch");
        assertThatThrownBy(() -> fixture.service.create(
                "session-5",
                "attempt-missing",
                new StarterMapping(
                        "B001",
                        Path.of("java/Selected.java"),
                        Path.of("java/src/main/java/exam/Missing.java"),
                        RunnerKind.MAVEN,
                        "exam.SelectedTest")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("source");
        assertThat(fixture.sandboxRoot.resolve("session-5")).doesNotExist();
    }

    @Test
    void rejectsSymlinkEscapeInCopiedProject() throws IOException {
        Fixture fixture = fixture();
        Path outside = Files.writeString(temporaryDirectory.resolve("outside-secret.txt"), "secret\n");
        Path link = fixture.examRoot.resolve("java/src/main/java/exam/Escape.java");
        boolean linkCreated = createSymbolicLink(link, outside);
        assumeTrue(linkCreated, "symbolic links are unavailable on this Windows host");

        assertThatThrownBy(() -> fixture.service.create("session-6", "attempt-6", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link");
        assertThat(fixture.sandboxRoot.resolve("session-6")).doesNotExist();
        assertThat(Files.readString(outside)).isEqualTo("secret\n");
    }

    @Test
    void rejectsWindowsJunctionInCopiedProjectWhenHostPermitsIt() throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        Fixture fixture = fixture();
        Path outside = Files.createDirectories(temporaryDirectory.resolve("junction-outside"));
        Path marker = Files.writeString(outside.resolve("keep.txt"), "keep\n");
        Path junction = fixture.examRoot.resolve("java/src/main/java/exam/junction");
        assumeTrue(createJunction(junction, outside), "junction creation is unavailable");

        try {
            assertThatThrownBy(() ->
                    fixture.service.create("session-junction", "attempt-junction", javaMapping()))
                    .isInstanceOf(IOException.class)
                    .satisfies(exception -> assertThat(exception.getMessage())
                            .matches(".*(junction|reparse|link).*"));
            assertThat(Files.readString(marker)).isEqualTo("keep\n");
            assertThat(fixture.sandboxRoot.resolve("session-junction")).doesNotExist();
        } finally {
            if (Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(junction);
            }
        }
    }

    @Test
    void rejectsUnownedIncompleteFinalAndDoesNotOverwriteIt() throws IOException {
        Fixture fixture = fixture();
        Path interrupted = Files.createDirectories(
                fixture.sandboxRoot.resolve("session-7/attempt-7/work"));
        Path marker = Files.writeString(interrupted.resolve("marker.txt"), "partial\n");

        assertThatThrownBy(() -> fixture.service.create("session-7", "attempt-7", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cleanup");
        assertThat(Files.readString(marker)).isEqualTo("partial\n");
        assertThat(fixture.sandboxRoot.resolve("session-7/attempt-7/.state")).doesNotExist();
    }

    @Test
    void rejectsExistingCompleteOrIncompleteFinalAttemptWithoutMerging() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-final", "attempt-complete", javaMapping());
        Path completeMarker = Files.writeString(
                fixture.sandboxRoot.resolve("session-final/attempt-complete/work/user.txt"),
                "user answer\n");
        Path incompleteMarker = writeAndReturn(
                fixture.sandboxRoot.resolve("session-final/attempt-incomplete/work/partial.txt"),
                "partial\n");

        assertThatThrownBy(() ->
                fixture.service.create("session-final", "attempt-complete", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cleanup");
        assertThatThrownBy(() ->
                fixture.service.create("session-final", "attempt-incomplete", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cleanup");
        assertThat(Files.readString(completeMarker)).isEqualTo("user answer\n");
        assertThat(Files.readString(incompleteMarker)).isEqualTo("partial\n");
    }

    @Test
    void concurrentCreatorsUseCreateNewReservationAndNeverClobber() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SandboxService reservingService = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                new SandboxService.PublicationHook() {
                    @Override
                    public void afterReservation(Path reservation) throws IOException {
                        reserved.countDown();
                        await(release);
                    }
                },
                SandboxService.CleanupHook.none());
        SandboxService competingService = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(
                    () -> reservingService.create("session-race", "attempt-race", javaMapping()));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() ->
                    competingService.create("session-race", "attempt-race", javaMapping()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cleanup");
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).questionId()).isEqualTo("B001");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(fixture.sandboxRoot.resolve("session-race/attempt-race/manifest.json"))
                .isRegularFile();
        assertCompleteState(
                fixture.sandboxRoot.resolve("session-race/attempt-race"));
        try (var sessionEntries = Files.list(
                fixture.sandboxRoot.resolve("session-race"))) {
            assertThat(sessionEntries)
                    .noneMatch(path -> path.getFileName().toString().contains(".reserve"));
        }
    }

    @Test
    void identityUnavailableFailsClosedBeforeCreatingAttemptContent() throws IOException {
        Fixture fixture = fixture();
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                path -> {
                    throw new IOException("stable identity unavailable");
                },
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none());

        assertThatThrownBy(() ->
                service.create("session-identity", "attempt-identity", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stable identity");
        assertThat(fixture.sandboxRoot.resolve("session-identity/attempt-identity"))
                .doesNotExist();
    }

    @Test
    void midPublishFailureRequiresExplicitCleanupBeforeRetry()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                new SandboxService.PublicationHook() {
                    @Override
                    public void beforeCompletion(Path attempt) throws IOException {
                        throw new IOException("injected before completion");
                    }
                },
                SandboxService.CleanupHook.none());

        assertThatThrownBy(() ->
                service.create("session-stage", "attempt-stage", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("before completion");

        Path attempt = fixture.sandboxRoot.resolve("session-stage/attempt-stage");
        assertThat(attempt).isDirectory();
        assertFailedState(attempt);
        assertThat(fixture.service.isComplete(attempt)).isFalse();

        assertThatThrownBy(() -> fixture.service.create(
                "session-stage",
                "attempt-stage",
                javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cleanup");
        assertFailedState(attempt);

        fixture.service.cleanup("session-stage", "attempt-stage");
        assertThat(attempt).doesNotExist();

        SandboxManifest recovered =
                fixture.service.create("session-stage", "attempt-stage", javaMapping());

        assertThat(recovered.questionId()).isEqualTo("B001");
        assertCompleteState(attempt);
        assertThat(fixture.service.isComplete(attempt)).isTrue();
    }

    @Test
    void forgedOwnedMarkersNeverAuthorizeCreateToDeleteExistingAttempt()
            throws IOException {
        Fixture fixture = fixture();
        Path attempt = Files.createDirectories(
                fixture.sandboxRoot.resolve("session-forged/attempt-forged"));
        String token = "00000000-0000-0000-0000-000000000123";
        Files.writeString(attempt.resolve(".owner"), token);
        Files.writeString(attempt.resolve(".state"), "FAILED:" + token);
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put("questionId", "B001");
        request.put("runnerKind", "MAVEN");
        request.put("sandboxSourcePath", "java/src/main/java/exam/Selected.java");
        request.put("starterPath", "java/Selected.java");
        request.put("selectedTest", "exam.SelectedTest");
        request.put("ownershipToken", token);
        new ObjectMapper().writeValue(attempt.resolve(".request.json").toFile(), request);
        Path marker = Files.writeString(attempt.resolve("do-not-delete.txt"), "external\n");

        assertThatThrownBy(() -> fixture.service.create(
                "session-forged",
                "attempt-forged",
                javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cleanup");
        assertThat(Files.readString(marker)).isEqualTo("external\n");
    }

    @Test
    void identityFailureAfterFinalReservationLeavesIncompleteDirectoryForExplicitCleanup()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService.FileIdentityProvider system =
                SandboxService.FileIdentityProvider.system();
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                path -> {
                    if ("attempt-orphan".equals(path.getFileName().toString())) {
                        throw new IOException("attempt identity unavailable");
                    }
                    return system.identity(path);
                },
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none());

        assertThatThrownBy(() ->
                service.create("session-orphan", "attempt-orphan", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identity")
                .hasMessageContaining("cleanup");
        Path attempt = fixture.sandboxRoot.resolve("session-orphan/attempt-orphan");
        assertThat(attempt).isDirectory();

        fixture.service.cleanup(attempt);
        assertThat(attempt).doesNotExist();
    }

    @Test
    void identityCaptureFailureNeverDeletesThroughASwappedPlainAncestor()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService.FileIdentityProvider system =
                SandboxService.FileIdentityProvider.system();
        Path session = fixture.sandboxRoot.resolve("session-identity-swap");
        Path heldSession = fixture.sandboxRoot.resolve("held-identity-swap");
        Path[] externalEmptyAttempt = new Path[1];
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                path -> {
                    if ("attempt-identity-swap".equals(path.getFileName().toString())) {
                        Files.move(session, heldSession);
                        externalEmptyAttempt[0] = Files.createDirectories(
                                session.resolve("attempt-identity-swap"));
                        throw new IOException("attempt identity unavailable");
                    }
                    return system.identity(path);
                },
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none());

        try {
            assertThatThrownBy(() -> service.create(
                    "session-identity-swap",
                    "attempt-identity-swap",
                    javaMapping()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cleanup");
            assertThat(externalEmptyAttempt[0]).isDirectory();
            assertThat(heldSession.resolve("attempt-identity-swap")).isDirectory();
        } finally {
            if (externalEmptyAttempt[0] != null) {
                Files.deleteIfExists(externalEmptyAttempt[0]);
            }
            Files.deleteIfExists(session);
            if (Files.exists(heldSession)) {
                Files.move(heldSession, session);
            }
        }
        fixture.service.cleanup("session-identity-swap", "attempt-identity-swap");
    }

    @Test
    void nonSourceWorkMutationBeforeCompletionPreventsPublication() throws IOException {
        Fixture fixture = fixture();
        SandboxService service = serviceWithPublicationHook(
                fixture,
                attempt -> Files.writeString(
                        attempt.resolve("work/java/src/main/java/exam/Shared.java"),
                        "package exam; public record Shared(String changed) { }\n"));

        assertThatThrownBy(() ->
                service.create("session-work-hash", "attempt-work-hash", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");

        Path attempt =
                fixture.sandboxRoot.resolve("session-work-hash/attempt-work-hash");
        assertFailedState(attempt);
    }

    @Test
    void manifestMutationBeforeCompletionPreventsPublication() throws IOException {
        Fixture fixture = fixture();
        SandboxService service = serviceWithPublicationHook(fixture, attempt -> {
            Path manifest = attempt.resolve("manifest.json");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode json = (ObjectNode) mapper.readTree(manifest.toFile());
            json.put("sandboxSourceSha256", "0".repeat(64));
            mapper.writeValue(manifest.toFile(), json);
        });

        assertThatThrownBy(() ->
                service.create("session-manifest", "attempt-manifest", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");
        Path attempt = fixture.sandboxRoot.resolve("session-manifest/attempt-manifest");
        assertFailedState(attempt);
    }

    @Test
    void coherentManifestAndWorkRewriteBeforeCompletionCannotSelfAuthenticate()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService service = serviceWithPublicationHook(fixture, attempt -> {
            Path work = attempt.resolve("work");
            Path source = work.resolve("java/src/main/java/exam/Selected.java");
            Files.writeString(
                    source,
                    "package exam; public class Selected { public int answer() { return -1; } }\n");
            Files.writeString(
                    work.resolve("java/src/main/java/exam/Shared.java"),
                    "package exam; public record Shared(String attackerValue) { }\n");

            String sourceHash = testHashFile(source);
            Path manifest = attempt.resolve("manifest.json");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode json = (ObjectNode) mapper.readTree(manifest.toFile());
            json.put("questionId", "ATTACKER");
            json.put("originalProjectSha256", "a".repeat(64));
            json.put("originalSourceSha256", "b".repeat(64));
            json.put("starterSha256", sourceHash);
            json.put("sandboxSourceSha256", sourceHash);
            json.put("workTreeSha256", oldFramedWorkHash(work));
            json.put("createdAt", "2030-01-01T00:00:00Z");
            json.put("starterPath", "java/attacker.java");
            mapper.writeValue(manifest.toFile(), json);
        });

        assertThatThrownBy(() ->
                service.create("session-coherent", "attempt-coherent", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void coherentRewriteAfterValidationBeforeStateCannotSelfAuthenticate()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                new SandboxService.PublicationHook() {
                    @Override
                    public void afterValidation(Path attempt) throws IOException {
                        rewritePublishedAttemptCoherently(attempt);
                    }
                },
                SandboxService.CleanupHook.none());

        assertThatThrownBy(() ->
                service.create("session-late-rewrite", "attempt-late-rewrite", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");

        Path attempt = fixture.sandboxRoot.resolve(
                "session-late-rewrite/attempt-late-rewrite");
        assertFailedState(attempt);
        assertThat(service.isComplete(attempt)).isFalse();
    }

    @Test
    void coherentMutationAfterCreateInvalidatesCompleteState() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create(
                "session-post-create", "attempt-post-create", javaMapping());
        Path attempt = fixture.sandboxRoot.resolve(
                "session-post-create/attempt-post-create");
        assertThat(fixture.service.isComplete(attempt)).isTrue();

        rewriteUnselectedWorkCoherently(attempt);

        assertThat(fixture.service.isComplete(attempt)).isFalse();
    }

    @Test
    void workHashLengthPrefixesDistinguishAnOldFramingAmbiguity()
            throws IOException {
        Fixture fixture = fixture();
        String firstRelative = "java/src/zz-a";
        String secondRelative = "java/src/zz-b";
        Path first = fixture.examRoot.resolve(firstRelative);
        Path second = fixture.examRoot.resolve(secondRelative);
        ByteArrayOutputStream ambiguousContent = new ByteArrayOutputStream();
        ambiguousContent.write('x');
        ambiguousContent.write(0xff);
        ambiguousContent.writeBytes(secondRelative.getBytes(StandardCharsets.UTF_8));
        ambiguousContent.write(0);
        ambiguousContent.write('y');
        Files.write(first, ambiguousContent.toByteArray());

        SandboxManifest oneFile =
                fixture.service.create("session-frame", "attempt-one", javaMapping());

        Files.write(first, new byte[] {'x'});
        Files.write(second, new byte[] {'y'});
        SandboxManifest twoFiles =
                fixture.service.create("session-frame", "attempt-two", javaMapping());

        Path oneWork = fixture.sandboxRoot.resolve("session-frame/attempt-one/work");
        Path twoWork = fixture.sandboxRoot.resolve("session-frame/attempt-two/work");
        assertThat(oldFramedWorkHash(oneWork)).isEqualTo(oldFramedWorkHash(twoWork));
        assertThat(oneFile.workTreeSha256()).isNotEqualTo(twoFiles.workTreeSha256());
    }

    @Test
    void failedStateAppearingBeforeCompletionPreventsCompleteState()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService service = serviceWithPublicationHook(fixture, attempt -> {
            String token = Files.readString(attempt.resolve(".owner"));
            Files.writeString(attempt.resolve(".state"), "FAILED:" + token);
        });

        assertThatThrownBy(() ->
                service.create("session-marker-race", "attempt-marker-race", javaMapping()))
                .isInstanceOf(IOException.class);
        Path attempt = fixture.sandboxRoot.resolve(
                "session-marker-race/attempt-marker-race");
        assertFailedState(attempt);
    }

    @Test
    void completeStateIsTheFinalFallibleOperationAndUsesOneExactStateFile()
            throws IOException {
        Fixture fixture = fixture();
        Path attempt = fixture.sandboxRoot.resolve("session-final-op/attempt-final-op");
        SandboxService.FileIdentityProvider system =
                SandboxService.FileIdentityProvider.system();
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                path -> {
                    if (Files.exists(attempt.resolve(".state"))
                            || Files.exists(attempt.resolve(".complete"))) {
                        throw new IOException("identity queried after terminal state");
                    }
                    return system.identity(path);
                },
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none());

        SandboxManifest manifest =
                service.create("session-final-op", "attempt-final-op", javaMapping());

        assertThat(manifest.questionId()).isEqualTo("B001");
        assertThat(Files.readString(attempt.resolve(".state")))
                .isEqualTo(expectedCompleteState(attempt));
        assertThat(attempt.resolve(".complete")).doesNotExist();
        assertThat(attempt.resolve(".failed")).doesNotExist();
    }

    @Test
    void failureHandlingNeverOverwritesAnExistingMalformedTerminalState() throws IOException {
        Fixture fixture = fixture();
        SandboxService service = serviceWithPublicationHook(fixture, attempt -> {
            String token = Files.readString(attempt.resolve(".owner"));
            Files.writeString(attempt.resolve(".state"), "COMPLETE:" + token);
            throw new IOException("injected after terminal state");
        });

        assertThatThrownBy(() ->
                service.create("session-state-race", "attempt-state-race", javaMapping()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("after terminal state");

        Path attempt = fixture.sandboxRoot.resolve(
                "session-state-race/attempt-state-race");
        String token = Files.readString(attempt.resolve(".owner"));
        assertThat(Files.readString(attempt.resolve(".state")))
                .isEqualTo("COMPLETE:" + token);
        assertThat(service.isComplete(attempt)).isFalse();
        assertThat(attempt.resolve(".complete")).doesNotExist();
        assertThat(attempt.resolve(".failed")).doesNotExist();
    }

    @Test
    void exactCompleteStatePersistedBeforeWriterFailureReturnsSuccess()
            throws IOException {
        Fixture fixture = fixture();
        SandboxService.StateWriter writer = (path, state) -> {
            Files.writeString(
                    path,
                    state,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            throw new IOException("injected after exact state write");
        };
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none(),
                writer);

        SandboxManifest manifest = service.create(
                "session-writer", "attempt-writer", javaMapping());

        Path attempt = fixture.sandboxRoot.resolve(
                "session-writer/attempt-writer");
        assertThat(manifest.questionId()).isEqualTo("B001");
        assertCompleteState(attempt);
        assertThat(service.isComplete(attempt)).isTrue();
    }

    @Test
    void wrongExternalCompleteStateFailsWithoutAddingFailedState()
            throws IOException {
        Fixture fixture = fixture();
        String[] injectedState = new String[1];
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                new SandboxService.PublicationHook() {
                    @Override
                    public void afterValidation(Path attempt) throws IOException {
                        String token = Files.readString(attempt.resolve(".owner"));
                        injectedState[0] = "COMPLETE:v1:"
                                + token
                                + ":"
                                + "0".repeat(64)
                                + ":"
                                + "1".repeat(64);
                        Files.writeString(
                                attempt.resolve(".state"),
                                injectedState[0],
                                StandardCharsets.UTF_8);
                    }
                },
                SandboxService.CleanupHook.none());

        assertThatThrownBy(() ->
                service.create("session-external", "attempt-external", javaMapping()))
                .isInstanceOf(IOException.class);

        Path attempt = fixture.sandboxRoot.resolve(
                "session-external/attempt-external");
        assertThat(Files.readString(attempt.resolve(".state")))
                .isEqualTo(injectedState[0]);
        assertThat(service.isComplete(attempt)).isFalse();
    }

    @Test
    void isCompleteRequiresExactCompleteStateContent() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-state-exact", "attempt-state-exact", javaMapping());
        Path attempt = fixture.sandboxRoot.resolve(
                "session-state-exact/attempt-state-exact");
        String token = Files.readString(attempt.resolve(".owner"));

        assertThat(fixture.service.isComplete(attempt)).isTrue();

        Files.writeString(attempt.resolve(".state"), "COMPLETE:" + token + "\n");

        assertThat(fixture.service.isComplete(attempt)).isFalse();
    }

    @Test
    void cleanupRejectsOutsideRootAndBroadTargets() throws IOException {
        Fixture fixture = fixture();
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside/attempt"));
        Path marker = Files.writeString(outside.resolve("keep.txt"), "keep\n");

        assertThatThrownBy(() -> fixture.service.cleanup(outside))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.cleanup(fixture.sandboxRoot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.cleanup(fixture.sandboxRoot.resolve("session-only")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(marker)).isEqualTo("keep\n");
    }

    @Test
    void cleanupDeletesOnlyTheValidatedAttemptDescendant() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-8", "attempt-delete", javaMapping());
        fixture.service.create("session-8", "attempt-keep", javaMapping());

        fixture.service.cleanup("session-8", "attempt-delete");

        assertThat(fixture.sandboxRoot.resolve("session-8/attempt-delete")).doesNotExist();
        assertThat(fixture.sandboxRoot.resolve("session-8/attempt-keep/manifest.json")).isRegularFile();
        assertThat(fixture.examRoot.resolve("java/pom.xml")).isRegularFile();
        try (var rootChildren = Files.list(fixture.sandboxRoot)) {
            assertThat(rootChildren)
                    .noneMatch(path -> path.getFileName().toString().startsWith(".trash-"));
        }
    }

    @Test
    void explicitCleanupCanRemoveIncompleteAttemptAfterCompletionMarkerLoss()
            throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-complete", "attempt-complete", javaMapping());
        Path attempt = fixture.sandboxRoot.resolve("session-complete/attempt-complete");
        assertThat(fixture.service.isComplete(attempt)).isTrue();

        Files.delete(attempt.resolve(".state"));

        assertThat(fixture.service.isComplete(attempt)).isFalse();
        fixture.service.cleanup(attempt);
        assertThat(attempt).doesNotExist();
    }

    @Test
    void failedStateIsNeverAcceptedAsComplete() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-markers", "attempt-markers", javaMapping());
        Path attempt = fixture.sandboxRoot.resolve("session-markers/attempt-markers");
        String token = Files.readString(attempt.resolve(".owner"));
        assertCompleteState(attempt);

        Files.writeString(attempt.resolve(".state"), "FAILED:" + token);

        assertThat(fixture.service.isComplete(attempt)).isFalse();
    }

    @Test
    void isCompleteRejectsSessionJunctionToExternalMarkerShapedAttempt()
            throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        Fixture fixture = fixture();
        fixture.service.create("session-shaped", "attempt-shaped", javaMapping());
        Path session = fixture.sandboxRoot.resolve("session-shaped");
        Path externalSession = temporaryDirectory.resolve("external-shaped");
        Files.move(session, externalSession);
        assumeTrue(createJunction(session, externalSession), "junction creation is unavailable");

        try {
            assertThat(fixture.service.isComplete(session.resolve("attempt-shaped")))
                    .isFalse();
        } finally {
            if (Files.exists(session, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(session);
            }
            Files.move(externalSession, session);
        }
    }

    @Test
    void isCompleteRejectsChangedPublishedWork() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-changed", "attempt-changed", javaMapping());
        Path attempt = fixture.sandboxRoot.resolve("session-changed/attempt-changed");
        Files.writeString(
                attempt.resolve("work/java/src/main/java/exam/Shared.java"),
                "package exam; public record Shared(String changed) { }\n");

        assertThat(fixture.service.isComplete(attempt)).isFalse();
    }

    @Test
    void realWindowsIdentitySurvivesRenameAndChangesOnReplacement() throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        SandboxService.FileIdentityProvider provider =
                SandboxService.FileIdentityProvider.system();
        Path original = Files.createDirectory(temporaryDirectory.resolve("identity-original"));
        String before = provider.identity(original);
        Path renamed = temporaryDirectory.resolve("identity-renamed");

        Files.move(original, renamed);
        String afterRename = provider.identity(renamed);
        Files.createDirectory(original);
        String replacement = provider.identity(original);

        assertThat(afterRename).isEqualTo(before);
        assertThat(replacement).isNotEqualTo(before);
    }

    @Test
    void fsutilFallbackTimesOutAndDoesNotHoldSynchronizedServiceForever()
            throws Exception {
        Fixture fixture = fixture();
        AtomicReference<Path> stdout = new AtomicReference<>();
        AtomicReference<Path> stderr = new AtomicReference<>();
        CountDownLatch processStarted = new CountDownLatch(1);
        SandboxService.FileIdentityProvider provider =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> {
                            Process process = startRedirected(
                                    builder,
                                    stdout,
                                    stderr,
                                    "powershell.exe",
                                    "-NoProfile",
                                    "-Command",
                                    "Start-Sleep -Seconds 30");
                            processStarted.countDown();
                            return process;
                        },
                        Duration.ofMillis(300),
                        128);
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                provider,
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var creation = executor.submit(
                    () -> service.create("session-timeout", "attempt-timeout", javaMapping()));
            assertThat(processStarted.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(thread -> thread.getName().startsWith(
                            "sandbox-fsutil-output"));
            assertThatThrownBy(() -> creation.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage("stable identity query timed out");
            assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(thread -> thread.getName().startsWith(
                            "sandbox-fsutil-output"));
        } finally {
            executor.shutdownNow();
        }
        assertThat(stdout.get()).doesNotExist();
        assertThat(stderr.get()).doesNotExist();
    }

    @Test
    void fsutilFallbackRejectsOversizedOutputAndDeletesRedirectFiles()
            throws IOException {
        Path identityTarget = Files.createDirectory(
                temporaryDirectory.resolve("process-output-target"));
        AtomicReference<Path> stdout = new AtomicReference<>();
        AtomicReference<Path> stderr = new AtomicReference<>();
        SandboxService.FileIdentityProvider oversized =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> startRedirected(
                                builder,
                                stdout,
                                stderr,
                                "powershell.exe",
                                "-NoProfile",
                                "-Command",
                                "Write-Output ('x' * 4096)"),
                        Duration.ofSeconds(3),
                        64);

        assertThatThrownBy(() -> oversized.identity(identityTarget))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("output limit");
        assertThat(stdout.get()).doesNotExist();
        assertThat(stderr.get()).doesNotExist();
    }

    @Test
    void fsutilFallbackRejectsMalformedAndNonUtf8Output() throws IOException {
        Path identityTarget = Files.createDirectory(
                temporaryDirectory.resolve("process-malformed-target"));
        SandboxService.FileIdentityProvider malformed =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> startRedirected(
                                builder,
                                new AtomicReference<>(),
                                new AtomicReference<>(),
                                "cmd.exe",
                                "/c",
                                "echo malformed"),
                        Duration.ofSeconds(3),
                        128);
        SandboxService.FileIdentityProvider nonUtf8 =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> startRedirected(
                                builder,
                                new AtomicReference<>(),
                                new AtomicReference<>(),
                                "powershell.exe",
                                "-NoProfile",
                                "-Command",
                                "$stream=[Console]::OpenStandardOutput();"
                                        + "$bytes=[byte[]](0xC3,0x28);"
                                        + "$stream.Write($bytes,0,$bytes.Length)"),
                        Duration.ofSeconds(3),
                        128);
        SandboxService.FileIdentityProvider multipleIds =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> startRedirected(
                                builder,
                                new AtomicReference<>(),
                                new AtomicReference<>(),
                                "cmd.exe",
                                "/c",
                                "echo File IDs are "
                                        + "0x11111111111111111111111111111111 "
                                        + "0x22222222222222222222222222222222"),
                        Duration.ofSeconds(3),
                        256);

        assertThatThrownBy(() -> malformed.identity(identityTarget))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> nonUtf8.identity(identityTarget))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> multipleIds.identity(identityTarget))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void fsutilFallbackRejectsNonzeroExit() throws IOException {
        Path identityTarget = Files.createDirectory(
                temporaryDirectory.resolve("process-exit-target"));
        SandboxService.FileIdentityProvider provider =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> startRedirected(
                                builder,
                                new AtomicReference<>(),
                                new AtomicReference<>(),
                                "cmd.exe",
                                "/c",
                                "exit",
                                "7"),
                        Duration.ofSeconds(3),
                        128);

        assertThatThrownBy(() -> provider.identity(identityTarget))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit code 7");
    }

    @Test
    void fsutilFallbackRestoresInterruptionAndTerminatesTheProcess()
            throws Exception {
        Path identityTarget = Files.createDirectory(
                temporaryDirectory.resolve("process-interrupt-target"));
        AtomicReference<Thread> caller = new AtomicReference<>();
        AtomicReference<Process> process = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        SandboxService.FileIdentityProvider provider =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> {
                            Process child = startRedirected(
                                    builder,
                                    new AtomicReference<>(),
                                    new AtomicReference<>(),
                                    "powershell.exe",
                                    "-NoProfile",
                                    "-Command",
                                    "Start-Sleep -Seconds 30");
                            process.set(child);
                            started.countDown();
                            return child;
                        },
                        Duration.ofSeconds(10),
                        128);
        Thread thread = new Thread(() -> {
            caller.set(Thread.currentThread());
            try {
                provider.identity(identityTarget);
            } catch (IOException exception) {
                failure.set(exception);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        }, "fsutil-interruption-test");

        thread.start();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        caller.get().interrupt();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(failure.get())
                .hasMessageContaining("interrupted");
        assertThat(interruptRestored).isTrue();
        assertThat(process.get().isAlive()).isFalse();
    }

    @Test
    void fsutilFallbackTerminatesObservedChildAndGrandchildAfterParentExit()
            throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path identityTarget = Files.createDirectory(
                temporaryDirectory.resolve("process-descendant-target"));
        Path childPidFile = temporaryDirectory.resolve("fsutil-child.pid");
        Path grandchildPidFile = temporaryDirectory.resolve("fsutil-grandchild.pid");
        Path spawnGate = temporaryDirectory.resolve("fsutil-spawn-grandchild.flag");
        String escapedPidFile = childPidFile.toString().replace("'", "''");
        String escapedGrandchildPidFile =
                grandchildPidFile.toString().replace("'", "''");
        String escapedSpawnGate = spawnGate.toString().replace("'", "''");
        String childCommand = "while (!(Test-Path -LiteralPath '"
                + escapedSpawnGate
                + "')) { Start-Sleep -Milliseconds 5 };"
                + "Start-Sleep -Milliseconds 75;"
                + "$grandchild=Start-Process -FilePath 'powershell.exe' "
                + "-ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 30') "
                + "-PassThru;"
                + "[IO.File]::WriteAllText('"
                + escapedGrandchildPidFile
                + "',$grandchild.Id);"
                + "Start-Sleep -Seconds 30";
        String encodedChildCommand = java.util.Base64.getEncoder().encodeToString(
                childCommand.getBytes(StandardCharsets.UTF_16LE));
        String command = "$child = Start-Process -FilePath 'powershell.exe' "
                + "-ArgumentList @('-NoProfile','-EncodedCommand','"
                + encodedChildCommand
                + "') "
                + "-PassThru; "
                + "Set-Content -LiteralPath '" + escapedPidFile
                + "' -Value $child.Id -NoNewline; "
                + "Set-Content -LiteralPath '"
                + escapedSpawnGate
                + "' -Value 'spawn' -NoNewline; "
                + "Write-Output 'File ID is 0x11111111111111111111111111111111'";
        SandboxService.FileIdentityProvider provider =
                SandboxService.FileIdentityProvider.windowsFileId(
                        builder -> startRedirected(
                                builder,
                                new AtomicReference<>(),
                                new AtomicReference<>(),
                                "powershell.exe",
                                "-NoProfile",
                                "-Command",
                                command),
                        Duration.ofSeconds(3),
                        128);

        long childPid = -1;
        long grandchildPid = -1;
        try {
            long started = System.nanoTime();
            assertThat(provider.identity(identityTarget))
                    .isEqualTo("windows-file-id:11111111111111111111111111111111");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(5));
            childPid = readPidEventually(childPidFile, Duration.ofSeconds(2));
            grandchildPid = readPidEventually(
                    grandchildPidFile,
                    Duration.ofSeconds(2));
            assertThat(ProcessHandle.of(childPid)
                            .map(ProcessHandle::isAlive)
                            .orElse(false))
                    .as("child process is terminated before identity() returns")
                    .isFalse();
            assertThat(ProcessHandle.of(grandchildPid)
                            .map(ProcessHandle::isAlive)
                            .orElse(false))
                    .as("grandchild process is terminated before identity() returns")
                    .isFalse();
        } finally {
            if (childPid < 0 && Files.exists(childPidFile)) {
                childPid = Long.parseLong(Files.readString(childPidFile));
            }
            if (grandchildPid < 0 && Files.exists(grandchildPidFile)) {
                grandchildPid = Long.parseLong(Files.readString(grandchildPidFile));
            }
            if (childPid >= 0) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            if (grandchildPid >= 0) {
                ProcessHandle.of(grandchildPid)
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void cleanupRefusesAncestorSwapBeforeQuarantineAndPreservesExternalData() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-swap", "attempt-swap", javaMapping());
        Path session = fixture.sandboxRoot.resolve("session-swap");
        Path heldSession = fixture.sandboxRoot.resolve("held-session");
        Path externalSession = Files.createDirectories(
                temporaryDirectory.resolve("external-swap"));
        Path externalAttempt = Files.createDirectories(externalSession.resolve("attempt-swap"));
        Path marker = Files.writeString(externalAttempt.resolve("keep.txt"), "keep\n");

        SandboxService racingService = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                SandboxService.PublicationHook.none(),
                (root, capturedSession, attempt) -> {
                    Files.move(capturedSession, heldSession);
                    Files.createSymbolicLink(capturedSession, externalSession);
                });
        try {
            assertThatThrownBy(() -> racingService.cleanup("session-swap", "attempt-swap"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("changed");
            assertThat(Files.readString(marker)).isEqualTo("keep\n");
            assertThat(heldSession.resolve("attempt-swap/manifest.json")).isRegularFile();
        } finally {
            if (Files.isSymbolicLink(session)) {
                Files.delete(session);
            }
            if (Files.exists(heldSession)) {
                Files.move(heldSession, session);
            }
        }
        fixture.service.cleanup("session-swap", "attempt-swap");
    }

    @Test
    void cleanupRejectsPlainSamePathAncestorReplacementWithClonedMetadata()
            throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-plain", "attempt-plain", javaMapping());
        Path session = fixture.sandboxRoot.resolve("session-plain");
        Path attempt = session.resolve("attempt-plain");
        Path heldSession = fixture.sandboxRoot.resolve("held-plain");
        FileTime sessionTime = Files.getLastModifiedTime(session);
        FileTime attemptTime = Files.getLastModifiedTime(attempt);
        SandboxService.FileIdentityProvider system =
                SandboxService.FileIdentityProvider.system();
        SandboxService.FileIdentityProvider provider = path -> {
            if (Files.exists(path.resolve("replacement.marker"))) {
                return "injected-replacement-identity";
            }
            return system.identity(path);
        };
        SandboxService racingService = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                provider,
                SandboxService.PublicationHook.none(),
                (root, capturedSession, capturedAttempt) -> {
                    Files.move(capturedSession, heldSession);
                    Path replacementAttempt =
                            Files.createDirectories(capturedSession.resolve("attempt-plain"));
                    Files.writeString(capturedSession.resolve("replacement.marker"), "external\n");
                    Files.setLastModifiedTime(replacementAttempt, attemptTime);
                    Files.setLastModifiedTime(capturedSession, sessionTime);
                });

        try {
            assertThatThrownBy(() ->
                    racingService.cleanup("session-plain", "attempt-plain"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("identity");
            assertThat(Files.readString(session.resolve("replacement.marker")))
                    .isEqualTo("external\n");
            assertCompleteState(heldSession.resolve("attempt-plain"));
        } finally {
            if (Files.exists(session)) {
                Files.delete(session.resolve("replacement.marker"));
                Files.delete(session.resolve("attempt-plain"));
                Files.delete(session);
            }
            if (Files.exists(heldSession)) {
                Files.move(heldSession, session);
            }
        }
        fixture.service.cleanup("session-plain", "attempt-plain");
    }

    @Test
    void cleanupLeavesQuarantineWhenPostMoveIdentityMismatches() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-quarantine", "attempt-quarantine", javaMapping());
        SandboxService.FileIdentityProvider system =
                SandboxService.FileIdentityProvider.system();
        SandboxService.FileIdentityProvider provider = path ->
                path.getFileName().toString().startsWith(".trash-")
                        ? "injected-wrong-quarantine-id"
                        : system.identity(path);
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                provider,
                SandboxService.PublicationHook.none(),
                SandboxService.CleanupHook.none());

        assertThatThrownBy(() ->
                service.cleanup("session-quarantine", "attempt-quarantine"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("quarantine identity");

        assertThat(fixture.sandboxRoot.resolve(
                "session-quarantine/attempt-quarantine")).doesNotExist();
        try (var children = Files.list(fixture.sandboxRoot)) {
            Path quarantine = children
                    .filter(path -> path.getFileName().toString().startsWith(".trash-"))
                    .findFirst()
                    .orElseThrow();
            assertCompleteState(quarantine);
            assertThat(quarantine.resolve("work/java/pom.xml")).isRegularFile();
        }
    }

    @Test
    void cleanupRevalidatesQuarantineIdentityImmediatelyBeforeDeletion()
            throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-delete-race", "attempt-delete-race", javaMapping());
        Path[] held = new Path[1];
        Path[] replacementMarker = new Path[1];
        SandboxService service = new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                SandboxService.FileIdentityProvider.system(),
                SandboxService.PublicationHook.none(),
                new SandboxService.CleanupHook() {
                    @Override
                    public void beforeQuarantine(Path root, Path session, Path attempt) {
                    }

                    @Override
                    public void beforeDelete(Path root, Path quarantine) throws IOException {
                        held[0] = root.resolve(".held-delete-race");
                        Files.move(quarantine, held[0]);
                        Files.createDirectory(quarantine);
                        replacementMarker[0] =
                                Files.writeString(quarantine.resolve("keep.txt"), "external\n");
                    }
                });

        assertThatThrownBy(() ->
                service.cleanup("session-delete-race", "attempt-delete-race"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identity");
        assertThat(Files.readString(replacementMarker[0])).isEqualTo("external\n");
        assertCompleteState(held[0]);

        Files.delete(replacementMarker[0]);
        Files.delete(replacementMarker[0].getParent());
        Path originalAttempt = fixture.sandboxRoot.resolve(
                "session-delete-race/attempt-delete-race");
        Files.move(held[0], originalAttempt);
        fixture.service.cleanup(originalAttempt);
    }

    @Test
    void cleanupRejectsSymlinkWithoutFollowingIt() throws IOException {
        Fixture fixture = fixture();
        fixture.service.create("session-9", "attempt-9", javaMapping());
        Path work = fixture.sandboxRoot.resolve("session-9/attempt-9/work");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("cleanup-outside"));
        Path marker = Files.writeString(outside.resolve("keep.txt"), "keep\n");
        boolean linkCreated = createSymbolicLink(work.resolve("escape"), outside);
        assumeTrue(linkCreated, "symbolic links are unavailable on this Windows host");

        assertThatThrownBy(() -> fixture.service.cleanup(work))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.cleanup(work.getParent()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link");
        assertThat(Files.readString(marker)).isEqualTo("keep\n");
    }

    @Test
    void cleanupRejectsSandboxRootThatWasReplacedBySymlink() throws IOException {
        Fixture fixture = fixture();
        Path externalRoot = Files.createDirectories(
                temporaryDirectory.resolve("external-sandboxes/session-root/attempt-root"));
        Path marker = Files.writeString(externalRoot.resolve("keep.txt"), "keep\n");
        Files.createDirectories(fixture.sandboxRoot.getParent());
        boolean linkCreated = createSymbolicLink(
                fixture.sandboxRoot,
                temporaryDirectory.resolve("external-sandboxes"));
        assumeTrue(linkCreated, "symbolic links are unavailable on this Windows host");

        assertThatThrownBy(() -> fixture.service.cleanup("session-root", "attempt-root"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link");
        assertThat(Files.readString(marker)).isEqualTo("keep\n");
    }

    @Test
    void cleanupRejectsSessionDirectorySymlink() throws IOException {
        Fixture fixture = fixture();
        Path externalAttempt = Files.createDirectories(
                temporaryDirectory.resolve("external-session/attempt-session"));
        Path marker = Files.writeString(externalAttempt.resolve("keep.txt"), "keep\n");
        Files.createDirectories(fixture.sandboxRoot);
        boolean linkCreated = createSymbolicLink(
                fixture.sandboxRoot.resolve("session-link"),
                temporaryDirectory.resolve("external-session"));
        assumeTrue(linkCreated, "symbolic links are unavailable on this Windows host");

        assertThatThrownBy(() -> fixture.service.cleanup("session-link", "attempt-session"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link");
        assertThat(Files.readString(marker)).isEqualTo("keep\n");
    }

    private Fixture fixture() throws IOException {
        Path examRoot = Files.createDirectories(temporaryDirectory.resolve("exam"));
        Path starterRoot = Files.createDirectories(temporaryDirectory.resolve("starters"));
        Path sandboxRoot = temporaryDirectory.resolve("runtime/sandboxes");

        write(examRoot.resolve("java/pom.xml"), "<project />\n");
        write(examRoot.resolve("java/src/main/java/exam/Selected.java"),
                "package exam; public class Selected { public int answer() { return 42; } }\n");
        write(examRoot.resolve("java/src/main/java/exam/Shared.java"),
                "package exam; public record Shared(String value) { }\n");
        write(examRoot.resolve("java/src/test/java/exam/SelectedTest.java"),
                "package exam; class SelectedTest { }\n");
        write(examRoot.resolve("java/target/generated.txt"), "generated\n");
        write(starterRoot.resolve("java/Selected.java"), "package exam; public class Selected { }\n");

        write(examRoot.resolve("python/pyproject.toml"), "[project]\nname='exam'\nversion='1'\n");
        write(examRoot.resolve("python/src/exam/selected.py"), "def selected():\n    return 42\n");
        write(examRoot.resolve("python/src/exam/shared.py"), "VALUE = 1\n");
        write(examRoot.resolve("python/tests/test_selected.py"), "def test_selected():\n    pass\n");
        write(examRoot.resolve("python/.pytest_cache/state"), "generated\n");
        write(examRoot.resolve("python/src/exam/__pycache__/selected.pyc"), "generated\n");
        write(starterRoot.resolve("python/selected.py"),
                "def selected():\n    raise NotImplementedError\n");

        Clock clock = Clock.fixed(CREATED_AT, ZoneOffset.UTC);
        SandboxPolicy policy = new SandboxPolicy(examRoot, starterRoot, sandboxRoot);
        return new Fixture(
                examRoot,
                starterRoot,
                sandboxRoot,
                policy,
                new SandboxService(
                        policy,
                        clock,
                        fastTestIdentity(),
                        SandboxService.PublicationHook.none(),
                        SandboxService.CleanupHook.none()));
    }

    private static SandboxService serviceWithPublicationHook(
            Fixture fixture,
            AttemptMutation mutation) {
        return new SandboxService(
                fixture.policy,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                fastTestIdentity(),
                new SandboxService.PublicationHook() {
                    @Override
                    public void beforeCompletion(Path attempt) throws IOException {
                        mutation.apply(attempt);
                    }
                },
                SandboxService.CleanupHook.none());
    }

    private static SandboxService.FileIdentityProvider fastTestIdentity() {
        return path -> {
            var attributes = Files.readAttributes(
                    path,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(path)
                    || attributes.isSymbolicLink()
                    || attributes.isOther()) {
                throw new IOException("test identity rejects links and special files");
            }
            return "test:"
                    + attributes.creationTime().toInstant()
                    + ":"
                    + attributes.isDirectory()
                    + ":"
                    + attributes.isRegularFile();
        };
    }

    private static StarterMapping javaMapping() {
        return new StarterMapping(
                "B001",
                Path.of("java/Selected.java"),
                Path.of("java/src/main/java/exam/Selected.java"),
                RunnerKind.MAVEN,
                "exam.SelectedTest");
    }

    private static StarterMapping pythonMapping() {
        return new StarterMapping(
                "I001",
                Path.of("python/selected.py"),
                Path.of("python/src/exam/selected.py"),
                RunnerKind.PYTEST,
                "python/tests/test_selected.py");
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static Path writeAndReturn(Path path, String value) throws IOException {
        write(path, value);
        return path;
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return files;
    }

    private static String testHashFile(Path path) throws IOException {
        MessageDigest digest = testSha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String oldFramedWorkHash(Path work) throws IOException {
        MessageDigest digest = testSha256();
        for (Map.Entry<String, byte[]> entry : snapshot(work).entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue());
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String framedWorkHash(Path work) throws IOException {
        MessageDigest digest = testSha256();
        for (Map.Entry<String, byte[]> entry : snapshot(work).entrySet()) {
            byte[] relativePath = entry.getKey().getBytes(StandardCharsets.UTF_8);
            updateTestLong(digest, relativePath.length);
            digest.update(relativePath);
            updateTestLong(digest, entry.getValue().length);
            digest.update(entry.getValue());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateTestLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static void rewritePublishedAttemptCoherently(Path attempt)
            throws IOException {
        Path work = attempt.resolve("work");
        Path source = work.resolve("java/src/main/java/exam/Selected.java");
        Files.writeString(
                source,
                "package exam; public class Selected { public int answer() { return -1; } }\n");
        Files.writeString(
                work.resolve("java/src/main/java/exam/Shared.java"),
                "package exam; public record Shared(String attackerValue) { }\n");
        Files.writeString(
                work.resolve("java/src/test/java/exam/SelectedTest.java"),
                "package exam; class SelectedTest { int attackerValue() { return -1; } }\n");

        String sourceHash = testHashFile(source);
        Path manifest = attempt.resolve("manifest.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = (ObjectNode) mapper.readTree(manifest.toFile());
        json.put("questionId", "ATTACKER");
        json.put("starterSha256", sourceHash);
        json.put("sandboxSourceSha256", sourceHash);
        json.put("workTreeSha256", framedWorkHash(work));
        json.put("createdAt", "2030-01-01T00:00:00Z");
        json.put("starterPath", "java/attacker.java");
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), json);
    }

    private static void rewriteUnselectedWorkCoherently(Path attempt)
            throws IOException {
        Path work = attempt.resolve("work");
        Files.writeString(
                work.resolve("java/src/main/java/exam/Shared.java"),
                "package exam; public record Shared(String attackerValue) { }\n");
        Path manifest = attempt.resolve("manifest.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = (ObjectNode) mapper.readTree(manifest.toFile());
        json.put("workTreeSha256", framedWorkHash(work));
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), json);
    }

    private static MessageDigest testSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, byte[]> withoutGeneratedFiles(Map<String, byte[]> original) {
        Map<String, byte[]> expected = new TreeMap<>(original);
        expected.keySet().removeIf(path -> path.startsWith("target/")
                || path.startsWith(".pytest_cache/")
                || path.contains("/__pycache__/")
                || path.endsWith(".pyc"));
        return expected;
    }

    private static void assertOnlySelectedSourceChanged(
            Map<String, byte[]> original,
            Map<String, byte[]> sandbox,
            String selectedSource) {
        assertThat(sandbox.keySet()).containsExactlyInAnyOrderElementsOf(original.keySet());
        for (Map.Entry<String, byte[]> entry : original.entrySet()) {
            if (!entry.getKey().equals(selectedSource)) {
                assertThat(sandbox.get(entry.getKey()))
                        .as("unchanged sandbox file %s", entry.getKey())
                        .containsExactly(entry.getValue());
            }
        }
        assertThat(sandbox.get(selectedSource)).isNotEqualTo(original.get(selectedSource));
    }

    private static void assertCompleteState(Path attempt) throws IOException {
        assertThat(Files.readString(attempt.resolve(".state")))
                .isEqualTo(expectedCompleteState(attempt));
        assertThat(attempt.resolve(".complete")).doesNotExist();
        assertThat(attempt.resolve(".failed")).doesNotExist();
    }

    private static void assertFailedState(Path attempt) throws IOException {
        assertTerminalState(attempt, "FAILED:");
    }

    private static String expectedCompleteState(Path attempt) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(attempt.resolve("manifest.json").toFile());
        ObjectNode canonical = mapper.createObjectNode();
        canonical.put("questionId", manifest.path("questionId").asText());
        canonical.put("runnerKind", manifest.path("runnerKind").asText());
        canonical.put(
                "originalProjectSha256",
                manifest.path("originalProjectSha256").asText());
        canonical.put(
                "originalSourceSha256",
                manifest.path("originalSourceSha256").asText());
        canonical.put("starterSha256", manifest.path("starterSha256").asText());
        canonical.put(
                "sandboxSourceSha256",
                manifest.path("sandboxSourceSha256").asText());
        canonical.put("workTreeSha256", manifest.path("workTreeSha256").asText());
        canonical.put("selectedTest", manifest.path("selectedTest").asText());
        canonical.put("ownershipToken", manifest.path("ownershipToken").asText());
        canonical.put("createdAt", manifest.path("createdAt").asText());
        canonical.put("workPath", manifest.path("workPath").asText());
        canonical.put(
                "sandboxSourcePath",
                manifest.path("sandboxSourcePath").asText());
        canonical.put("starterPath", manifest.path("starterPath").asText());
        String manifestDigest = HexFormat.of().formatHex(
                testSha256().digest(mapper.writeValueAsBytes(canonical)));
        return "COMPLETE:v1:"
                + Files.readString(attempt.resolve(".owner"))
                + ":"
                + manifestDigest
                + ":"
                + manifest.path("workTreeSha256").asText();
    }

    private static void assertTerminalState(Path attempt, String prefix)
            throws IOException {
        String token = Files.readString(attempt.resolve(".owner"));
        assertThat(Files.readString(attempt.resolve(".state")))
                .isEqualTo(prefix + token);
        assertThat(attempt.resolve(".complete")).doesNotExist();
        assertThat(attempt.resolve(".failed")).doesNotExist();
    }

    private static void assertRelative(String pathText) {
        Path path = Path.of(pathText);
        assertThat(path.isAbsolute()).isFalse();
        assertThat(path.normalize().startsWith("..")).isFalse();
    }

    private static Process startRedirected(
            ProcessBuilder builder,
            AtomicReference<Path> stdout,
            AtomicReference<Path> stderr,
            String... command) throws IOException {
        assertThat(builder.redirectErrorStream()).isFalse();
        assertThat(builder.redirectOutput().type())
                .isEqualTo(ProcessBuilder.Redirect.Type.WRITE);
        assertThat(builder.redirectError().type())
                .isEqualTo(ProcessBuilder.Redirect.Type.WRITE);
        Path outputPath = builder.redirectOutput()
                .file()
                .toPath()
                .toAbsolutePath()
                .normalize();
        Path errorPath = builder.redirectError()
                .file()
                .toPath()
                .toAbsolutePath()
                .normalize();
        assertThat(outputPath).isNotEqualTo(errorPath);
        stdout.set(outputPath);
        stderr.set(errorPath);
        builder.command(command);
        return builder.start();
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | FileSystemException exception) {
            return false;
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean createJunction(Path junction, Path target) {
        try {
            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/c",
                    "mklink",
                    "/J",
                    junction.toString(),
                    target.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test interrupted", exception);
        }
    }

    private static long readPidEventually(Path pidFile, Duration timeout)
            throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for PID file", exception);
            }
        }
        return Long.parseLong(Files.readString(pidFile));
    }

    private record Fixture(
            Path examRoot,
            Path starterRoot,
            Path sandboxRoot,
            SandboxPolicy policy,
            SandboxService service) {
    }

    @FunctionalInterface
    private interface AttemptMutation {
        void apply(Path attempt) throws IOException;
    }
}
