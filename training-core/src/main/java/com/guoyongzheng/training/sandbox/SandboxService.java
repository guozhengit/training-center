package com.guoyongzheng.training.sandbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guoyongzheng.training.catalog.RunnerKind;
import com.guoyongzheng.training.catalog.StarterMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates isolated coding projects and removes only explicitly validated attempts. */
public final class SandboxService {

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final String COMPLETE_STATE_PREFIX = "COMPLETE:v1:";
    private static final String FAILED_STATE = "FAILED:";
    private static final Pattern COMPLETE_STATE_PATTERN = Pattern.compile(
            "\\ACOMPLETE:v1:"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):"
                    + "([0-9a-f]{64}):"
                    + "([0-9a-f]{64})\\z");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
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

    private final SandboxPolicy policy;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final FileIdentityProvider identityProvider;
    private final PublicationHook publicationHook;
    private final CleanupHook cleanupHook;
    private final StateWriter stateWriter;

    public SandboxService(SandboxPolicy policy, Clock clock) {
        this(
                policy,
                clock,
                FileIdentityProvider.system(),
                PublicationHook.none(),
                CleanupHook.none(),
                StateWriter.system());
    }

    SandboxService(
            SandboxPolicy policy,
            Clock clock,
            FileIdentityProvider identityProvider,
            PublicationHook publicationHook,
            CleanupHook cleanupHook) {
        this(
                policy,
                clock,
                identityProvider,
                publicationHook,
                cleanupHook,
                StateWriter.system());
    }

    SandboxService(
            SandboxPolicy policy,
            Clock clock,
            FileIdentityProvider identityProvider,
            PublicationHook publicationHook,
            CleanupHook cleanupHook,
            StateWriter stateWriter) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
        this.cleanupHook = Objects.requireNonNull(cleanupHook, "cleanupHook");
        this.stateWriter = Objects.requireNonNull(stateWriter, "stateWriter");
    }

    public synchronized SandboxManifest create(
            String sessionId,
            String attemptId,
            StarterMapping mapping) throws IOException {
        requireSegment(sessionId, "sessionId");
        requireSegment(attemptId, "attemptId");
        PreparedInput input = prepare(mapping);

        Path sandboxRoot = ensureSandboxRoot();
        FileIdentity rootIdentity = captureDirectoryIdentity(sandboxRoot);
        Path sessionDirectory = contained(sandboxRoot, Path.of(sessionId), "session directory");
        if (Files.exists(sessionDirectory, NO_FOLLOW)) {
            requirePlainDirectory(sessionDirectory);
        } else {
            try {
                Files.createDirectory(sessionDirectory);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                requirePlainDirectory(sessionDirectory);
            }
        }
        revalidateIdentity(rootIdentity, "sandbox root identity");
        FileIdentity sessionIdentity = captureDirectoryIdentity(sessionDirectory);

        Path attemptDirectory = contained(
                sandboxRoot,
                Path.of(sessionId, attemptId),
                "attempt directory");
        if (Files.exists(attemptDirectory, NO_FOLLOW)) {
            rejectExistingAttempt(attemptDirectory);
        }
        try {
            Files.createDirectory(attemptDirectory);
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            throw existingAttemptException(exception);
        }
        FileIdentity attemptIdentity;
        try {
            attemptIdentity = captureDirectoryIdentity(attemptDirectory);
        } catch (IOException exception) {
            throw new IOException(
                    "attempt identity unavailable; reserved attempt retained; call "
                            + "cleanup(sessionId, attemptId) explicitly before retry",
                    exception);
        }
        revalidateIdentity(rootIdentity, "sandbox root identity");
        revalidateIdentity(sessionIdentity, "session directory identity");

        String ownershipToken = UUID.randomUUID().toString();
        Path stagingDirectory = attemptDirectory.resolve(".staging-" + ownershipToken);
        try {
            writeOwnedText(attemptDirectory.resolve(".owner"), ownershipToken);
            publicationHook.afterReservation(attemptDirectory);

            Files.createDirectory(stagingDirectory);
            Path work = stagingDirectory.resolve("work");
            Files.createDirectory(work);
            Path copiedProject = work.resolve(projectDirectory(mapping.runnerKind()));
            Files.createDirectory(copiedProject);
            copyMinimumProject(input.projectRoot(), copiedProject, mapping.runnerKind());

            String copiedHash = hashMinimumProject(copiedProject, mapping.runnerKind());
            if (!copiedHash.equals(input.projectHash())) {
                throw new IOException("incomplete or changed project copy");
            }

            Path targetSource = resolveRelativeFile(work, mapping.sandboxSourcePath(), "sandbox source");
            String copiedSourceHash = hashFile(targetSource);
            if (!copiedSourceHash.equals(input.originalSourceHash())) {
                throw new IOException("sandbox source does not match the original source");
            }
            Files.copy(input.starter(), targetSource, StandardCopyOption.REPLACE_EXISTING);
            String sandboxSourceHash = hashFile(targetSource);
            if (!sandboxSourceHash.equals(input.starterHash())) {
                throw new IOException("sandbox source does not match the selected starter");
            }

            if (!hashMinimumProject(input.projectRoot(), mapping.runnerKind())
                    .equals(input.projectHash())
                    || !hashFile(input.originalSource()).equals(input.originalSourceHash())) {
                throw new IOException("original exam project changed during sandbox creation");
            }

            SandboxManifest manifest = new SandboxManifest(
                    mapping.questionId(),
                    mapping.runnerKind(),
                    input.projectHash(),
                    input.originalSourceHash(),
                    input.starterHash(),
                    sandboxSourceHash,
                    hashWorkTree(work),
                    mapping.testSelector(),
                    ownershipToken,
                    clock.instant(),
                    "work",
                    portable(mapping.sandboxSourcePath()),
                    portable(mapping.starterPath()));
            String expectedCompleteState = completeState(manifest);
            writeManifest(stagingDirectory.resolve("manifest.json"), manifest);
            publicationHook.afterStaging(stagingDirectory);

            Files.move(stagingDirectory.resolve("work"), attemptDirectory.resolve("work"));
            Files.move(
                    stagingDirectory.resolve("manifest.json"),
                    attemptDirectory.resolve("manifest.json"));
            Files.delete(stagingDirectory);
            publicationHook.beforeCompletion(attemptDirectory);

            revalidateIdentity(rootIdentity, "sandbox root identity");
            revalidateIdentity(sessionIdentity, "session directory identity");
            revalidateIdentity(attemptIdentity, "attempt directory identity");
            if (!validateExpectedCompletion(
                    input,
                    mapping.runnerKind(),
                    attemptDirectory,
                    manifest)) {
                throw new IOException("sandbox work integrity changed before completion");
            }
            publicationHook.afterValidation(attemptDirectory);
            revalidateIdentity(rootIdentity, "sandbox root identity");
            revalidateIdentity(sessionIdentity, "session directory identity");
            revalidateIdentity(attemptIdentity, "attempt directory identity");
            if (!validateExpectedCompletion(
                    input,
                    mapping.runnerKind(),
                    attemptDirectory,
                    manifest)) {
                throw new IOException("sandbox work integrity changed before state write");
            }
            Path statePath = attemptDirectory.resolve(".state");
            try {
                stateWriter.write(statePath, expectedCompleteState);
            } catch (IOException | RuntimeException stateFailure) {
                try {
                    revalidateIdentity(rootIdentity, "sandbox root identity");
                    revalidateIdentity(sessionIdentity, "session directory identity");
                    revalidateIdentity(attemptIdentity, "attempt directory identity");
                    if (readExactState(statePath).equals(expectedCompleteState)
                            && validateExpectedCompletion(
                                    input,
                                    mapping.runnerKind(),
                                    attemptDirectory,
                                    manifest)) {
                        return manifest;
                    }
                } catch (IOException | RuntimeException recoveryFailure) {
                    stateFailure.addSuppressed(recoveryFailure);
                }
                throw stateFailure;
            }
            return manifest;
        } catch (IOException | RuntimeException exception) {
            try {
                if (Files.exists(attemptDirectory, NO_FOLLOW)
                        && identityStillMatches(attemptIdentity)) {
                    writeFailureStateIfAbsent(
                            attemptDirectory.resolve(".state"),
                            ownershipToken);
                }
            } catch (IOException markerFailure) {
                exception.addSuppressed(markerFailure);
            }
            throw exception;
        }
    }

    public synchronized void cleanup(String sessionId, String attemptId) throws IOException {
        requireSegment(sessionId, "sessionId");
        requireSegment(attemptId, "attemptId");
        cleanup(policy.sandboxRoot().resolve(sessionId).resolve(attemptId));
    }

    public synchronized void cleanup(Path attemptDirectory) throws IOException {
        Objects.requireNonNull(attemptDirectory, "attemptDirectory");
        if (SandboxPaths.hasParentSegment(attemptDirectory)) {
            throw new IllegalArgumentException("cleanup target must not contain raw '..' segments");
        }
        Path sandboxRoot = policy.sandboxRoot();
        rejectReparseInExistingAncestors(sandboxRoot);
        Path target = attemptDirectory.toAbsolutePath().normalize();
        Path relative;
        try {
            relative = sandboxRoot.relativize(target);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cleanup target is outside sandboxRoot", exception);
        }
        if (target.equals(sandboxRoot)
                || !target.startsWith(sandboxRoot)
                || relative.getNameCount() != 2
                || SandboxPaths.hasParentSegment(relative)) {
            throw new IllegalArgumentException(
                    "cleanup target must be an explicit session/attempt descendant");
        }
        requireSegment(relative.getName(0).toString(), "sessionId");
        requireSegment(relative.getName(1).toString(), "attemptId");
        if (!Files.exists(sandboxRoot, NO_FOLLOW)) {
            return;
        }
        requirePlainDirectory(sandboxRoot);
        rejectReparseInExistingAncestors(target);
        if (!Files.exists(target, NO_FOLLOW)) {
            return;
        }
        Path session = target.getParent();
        requirePlainDirectory(session);
        requirePlainDirectory(target);
        scanForLinks(target);
        quarantineAndDelete(sandboxRoot, session, target, cleanupHook);
    }

    private PreparedInput prepare(StarterMapping mapping) throws IOException {
        Objects.requireNonNull(mapping, "mapping");
        Path projectRoot = policy.projectRoot(mapping.runnerKind());
        rejectReparseInExistingAncestors(projectRoot);
        rejectReparseInExistingAncestors(policy.starterRoot());
        requirePlainDirectory(projectRoot);
        scanForLinks(projectRoot);
        requirePlainDirectory(policy.starterRoot());

        validateSourceKind(mapping);
        Path originalSource = resolveRelativeFile(
                policy.examRoot(),
                mapping.sandboxSourcePath(),
                "source");
        Path starter = resolveRelativeFile(
                policy.starterRoot(),
                mapping.starterPath(),
                "starter");
        validateSelectedTest(projectRoot, mapping);

        return new PreparedInput(
                projectRoot,
                originalSource,
                starter,
                hashMinimumProject(projectRoot, mapping.runnerKind()),
                hashFile(originalSource),
                hashFile(starter));
    }

    private void validateSourceKind(StarterMapping mapping) {
        Path sourcePath = mapping.sandboxSourcePath();
        String source = portable(sourcePath).toLowerCase();
        String starter = portable(mapping.starterPath()).toLowerCase();
        String requiredSuffix = mapping.runnerKind() == RunnerKind.MAVEN ? ".java" : ".py";
        if (!source.endsWith(requiredSuffix) || !starter.endsWith(requiredSuffix)) {
            throw new IllegalArgumentException("source/starter mismatch for " + mapping.runnerKind());
        }
        Path requiredRoot = mapping.runnerKind() == RunnerKind.MAVEN
                ? Path.of("java/src/main/java")
                : Path.of("python/src");
        if (!sourcePath.startsWith(requiredRoot) || sourcePath.equals(requiredRoot)) {
            throw new IllegalArgumentException(
                    "sandbox source must be under " + portable(requiredRoot));
        }
    }

    private void validateSelectedTest(Path projectRoot, StarterMapping mapping) throws IOException {
        String selector = mapping.testSelector();
        if (mapping.runnerKind() == RunnerKind.MAVEN) {
            if (selector.contains("#")
                    || !selector.matches(
                            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                throw new IllegalArgumentException(
                        "Maven class selector must name one Java test class");
            }
            Path relativeTest = Path.of(
                    "src/test/java/" + selector.replace('.', '/') + ".java");
            Path selectedTest = resolveRelativeFile(projectRoot, relativeTest, "selected test");
            if (selectedTest.equals(policy.examRoot().resolve(mapping.sandboxSourcePath()))) {
                throw new IllegalArgumentException("source and selected test must be distinct");
            }
            return;
        }
        if (selector.contains("::")) {
            throw new IllegalArgumentException(
                    "pytest test file selector must not contain a node");
        }
        Path testPath = Path.of(selector);
        validateRelative(testPath, "selected test");
        if (!portable(testPath).endsWith(".py")
                || !testPath.startsWith(Path.of("python/tests"))
                || testPath.equals(Path.of("python/tests"))) {
            throw new IllegalArgumentException(
                    "pytest selector must name a file under python/tests");
        }
        Path selectedTest = resolveRelativeFile(policy.examRoot(), testPath, "selected test");
        if (selectedTest.equals(policy.examRoot().resolve(mapping.sandboxSourcePath()))) {
            throw new IllegalArgumentException("source and selected test must be distinct");
        }
    }

    private Path ensureSandboxRoot() throws IOException {
        Path root = policy.sandboxRoot();
        rejectReparseInExistingAncestors(root);
        if (Files.exists(root, NO_FOLLOW)) {
            requirePlainDirectory(root);
        } else {
            Files.createDirectories(root);
            rejectReparseInExistingAncestors(root);
            requirePlainDirectory(root);
        }
        return root;
    }

    public boolean isComplete(Path attemptDirectory) throws IOException {
        Objects.requireNonNull(attemptDirectory, "attemptDirectory");
        if (SandboxPaths.hasParentSegment(attemptDirectory)) {
            return false;
        }
        try {
            Path root = policy.sandboxRoot();
            rejectReparseInExistingAncestors(root);
            requirePlainDirectory(root);
            Path attempt = attemptDirectory.toAbsolutePath().normalize();
            if (!attempt.startsWith(root) || attempt.equals(root)) {
                return false;
            }
            Path relative = root.relativize(attempt);
            if (relative.getNameCount() != 2 || SandboxPaths.hasParentSegment(relative)) {
                return false;
            }
            requireSegment(relative.getName(0).toString(), "sessionId");
            requireSegment(relative.getName(1).toString(), "attemptId");
            Path session = root.resolve(relative.getName(0));
            rejectReparseInExistingAncestors(attempt);
            requirePlainDirectory(session);
            requirePlainDirectory(attempt);
            FileIdentity rootIdentity = captureDirectoryIdentity(root);
            FileIdentity sessionIdentity = captureDirectoryIdentity(session);
            FileIdentity attemptIdentity = captureDirectoryIdentity(attempt);
            scanForLinks(attempt);
            String owner = readOwnedToken(attempt.resolve(".owner"));
            CompleteState state = parseCompleteState(
                    readExactState(attempt.resolve(".state")));
            if (state == null
                    || !owner.equals(state.ownershipToken())
                    || !validatePublishedAttemptForCompletionCheck(
                            attempt,
                            owner,
                            state)) {
                return false;
            }
            revalidateIdentity(rootIdentity, "sandbox root identity");
            revalidateIdentity(sessionIdentity, "session directory identity");
            revalidateIdentity(attemptIdentity, "attempt directory identity");
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void rejectExistingAttempt(Path attempt) throws IOException {
        requirePlainDirectory(attempt);
        throw existingAttemptException(null);
    }

    private IOException existingAttemptException(IOException cause) {
        return new IOException(
                "attempt already exists; call cleanup(sessionId, attemptId) "
                        + "explicitly before retry",
                cause);
    }

    private void copyMinimumProject(Path source, Path target, RunnerKind runnerKind) throws IOException {
        if (runnerKind == RunnerKind.MAVEN) {
            copyRegularFile(source.resolve("pom.xml"), target.resolve("pom.xml"));
            copyTree(source.resolve("src"), target.resolve("src"), runnerKind);
        } else {
            copyRegularFile(source.resolve("pyproject.toml"), target.resolve("pyproject.toml"));
            copyTree(source.resolve("src"), target.resolve("src"), runnerKind);
            copyTree(source.resolve("tests"), target.resolve("tests"), runnerKind);
        }
    }

    private void copyTree(Path source, Path target, RunnerKind runnerKind) throws IOException {
        requirePlainDirectory(source);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectLink(directory, attributes);
                if (!directory.equals(source) && excluded(directory.getFileName().toString(), runnerKind)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = source.relativize(directory);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectLink(file, attributes);
                if (!excluded(file.getFileName().toString(), runnerKind)) {
                    copyRegularFile(file, target.resolve(source.relativize(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean excluded(String name, RunnerKind runnerKind) {
        if (".git".equals(name) || ".idea".equals(name)) {
            return true;
        }
        return runnerKind == RunnerKind.MAVEN
                ? "target".equals(name)
                : ".pytest_cache".equals(name)
                        || "__pycache__".equals(name)
                        || ".venv".equals(name)
                        || name.endsWith(".pyc");
    }

    private void copyRegularFile(Path source, Path target) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                source,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(source, attributes);
        if (!attributes.isRegularFile()) {
            throw new IOException("required project file is not regular: " + source.getFileName());
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
    }

    private String hashMinimumProject(Path projectRoot, RunnerKind runnerKind) throws IOException {
        List<Path> files = new ArrayList<>();
        if (runnerKind == RunnerKind.MAVEN) {
            collectHashFiles(projectRoot, projectRoot.resolve("pom.xml"), files, runnerKind);
            collectHashFiles(projectRoot, projectRoot.resolve("src"), files, runnerKind);
        } else {
            collectHashFiles(projectRoot, projectRoot.resolve("pyproject.toml"), files, runnerKind);
            collectHashFiles(projectRoot, projectRoot.resolve("src"), files, runnerKind);
            collectHashFiles(projectRoot, projectRoot.resolve("tests"), files, runnerKind);
        }
        files.sort(Comparator.comparing(path -> portable(projectRoot.relativize(path))));
        MessageDigest digest = sha256();
        for (Path file : files) {
            digest.update(portable(projectRoot.relativize(file)).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void collectHashFiles(
            Path projectRoot,
            Path start,
            List<Path> files,
            RunnerKind runnerKind) throws IOException {
        if (!Files.exists(start, NO_FOLLOW)) {
            throw new IOException("required project path is missing: " + projectRoot.relativize(start));
        }
        BasicFileAttributes rootAttributes = Files.readAttributes(
                start,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(start, rootAttributes);
        if (rootAttributes.isRegularFile()) {
            files.add(start);
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectLink(directory, attributes);
                if (!directory.equals(start) && excluded(directory.getFileName().toString(), runnerKind)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectLink(file, attributes);
                if (!excluded(file.getFileName().toString(), runnerKind)) {
                    if (!attributes.isRegularFile()) {
                        throw new IOException("project contains unsupported special file: " + file);
                    }
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String hashFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(path, attributes);
        if (!attributes.isRegularFile()) {
            throw new IOException("expected regular file: " + path.getFileName());
        }
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String hashWorkTree(Path work) throws IOException {
        requirePlainDirectory(work);
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(work, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) throws IOException {
                rejectLink(directory, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) throws IOException {
                rejectLink(file, attributes);
                if (!attributes.isRegularFile()) {
                    throw new IOException("work contains unsupported special file: " + file);
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> portable(work.relativize(path))));
        MessageDigest digest = sha256();
        for (Path file : files) {
            byte[] relativePath =
                    portable(work.relativize(file)).getBytes(StandardCharsets.UTF_8);
            updateLong(digest, relativePath.length);
            digest.update(relativePath);
            BasicFileAttributes attributes = Files.readAttributes(
                    file,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            rejectLink(file, attributes);
            if (!attributes.isRegularFile()) {
                throw new IOException("work contains unsupported special file: " + file);
            }
            updateLong(digest, attributes.size());
            long bytesRead = 0;
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                    bytesRead += count;
                }
            }
            if (bytesRead != attributes.size()) {
                throw new IOException("work file changed while hashing: " + file.getFileName());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private void writeManifest(Path path, SandboxManifest manifest) throws IOException {
        ObjectNode json = manifestJson(manifest);
        Files.write(
                path,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private ObjectNode manifestJson(SandboxManifest manifest) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("questionId", manifest.questionId());
        json.put("runnerKind", manifest.runnerKind().name());
        json.put("originalProjectSha256", manifest.originalProjectSha256());
        json.put("originalSourceSha256", manifest.originalSourceSha256());
        json.put("starterSha256", manifest.starterSha256());
        json.put("sandboxSourceSha256", manifest.sandboxSourceSha256());
        json.put("workTreeSha256", manifest.workTreeSha256());
        json.put("selectedTest", manifest.selectedTest());
        json.put("ownershipToken", manifest.ownershipToken());
        json.put("createdAt", manifest.createdAt().toString());
        json.put("workPath", manifest.workPath());
        json.put("sandboxSourcePath", manifest.sandboxSourcePath());
        json.put("starterPath", manifest.starterPath());
        return json;
    }

    private String completeState(SandboxManifest manifest) throws IOException {
        return COMPLETE_STATE_PREFIX
                + manifest.ownershipToken()
                + ":"
                + hashBytes(objectMapper.writeValueAsBytes(manifestJson(manifest)))
                + ":"
                + manifest.workTreeSha256();
    }

    private static String hashBytes(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private boolean validateExpectedCompletion(
            PreparedInput input,
            RunnerKind runnerKind,
            Path attempt,
            SandboxManifest manifest) throws IOException {
        return hashMinimumProject(input.projectRoot(), runnerKind)
                        .equals(input.projectHash())
                && hashFile(input.originalSource()).equals(input.originalSourceHash())
                && hashFile(input.starter()).equals(input.starterHash())
                && validatePublishedAttempt(attempt, manifest);
    }

    private boolean validatePublishedAttempt(Path attempt, SandboxManifest expected)
            throws IOException {
        Path manifest = attempt.resolve("manifest.json");
        if (!Files.isRegularFile(manifest, NO_FOLLOW)
                || Files.isSymbolicLink(manifest)) {
            return false;
        }
        SandboxManifest published = readManifest(manifest);
        if (!expected.equals(published)) {
            return false;
        }
        return validatePublishedIntegrity(attempt, published);
    }

    private boolean validatePublishedAttemptForCompletionCheck(
            Path attempt,
            String ownershipToken,
            CompleteState state) throws IOException {
        Path manifest = attempt.resolve("manifest.json");
        if (!Files.isRegularFile(manifest, NO_FOLLOW)
                || Files.isSymbolicLink(manifest)) {
            return false;
        }
        SandboxManifest published = readManifest(manifest);
        if (!ownershipToken.equals(published.ownershipToken())
                || !state.manifestSha256().equals(
                        hashBytes(objectMapper.writeValueAsBytes(manifestJson(published))))
                || !state.workTreeSha256().equals(published.workTreeSha256())) {
            return false;
        }
        return validatePublishedIntegrity(attempt, published);
    }

    private boolean validatePublishedIntegrity(Path attempt, SandboxManifest published)
            throws IOException {
        if (!"work".equals(published.workPath())
                || !published.starterSha256().equals(published.sandboxSourceSha256())) {
            return false;
        }
        Path officialProject = policy.projectRoot(published.runnerKind());
        if (!published.originalProjectSha256().equals(
                hashMinimumProject(officialProject, published.runnerKind()))) {
            return false;
        }
        Path originalSource = resolveRelativeFile(
                policy.examRoot(),
                Path.of(published.sandboxSourcePath()),
                "published original source");
        if (!published.originalSourceSha256().equals(hashFile(originalSource))) {
            return false;
        }
        Path starter = resolveRelativeFile(
                policy.starterRoot(),
                Path.of(published.starterPath()),
                "published starter");
        if (!published.starterSha256().equals(hashFile(starter))) {
            return false;
        }
        Path work = attempt.resolve(published.workPath());
        requirePlainDirectory(work);
        scanForLinks(work);
        if (!published.workTreeSha256().equals(hashWorkTree(work))) {
            return false;
        }
        Path source = resolveRelativeFile(
                work,
                Path.of(published.sandboxSourcePath()),
                "published sandbox source");
        if (!published.sandboxSourceSha256().equals(hashFile(source))) {
            return false;
        }
        Path selectedTest = selectedPublishedTest(published);
        Path publishedTest = resolveRelativeFile(
                work,
                selectedTest,
                "published selected test");
        Path originalTest = resolveRelativeFile(
                policy.examRoot(),
                selectedTest,
                "published original test");
        if (!hashFile(publishedTest).equals(hashFile(originalTest))) {
            return false;
        }
        return published.workTreeSha256().equals(hashWorkTree(work));
    }

    private SandboxManifest readManifest(Path manifest) throws IOException {
        JsonNode json = objectMapper.readTree(manifest.toFile());
        if (json == null || !json.isObject()) {
            throw new IOException("manifest schema is invalid");
        }
        HashSet<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(MANIFEST_FIELDS)
                || fields.stream().anyMatch(field -> !json.path(field).isTextual())) {
            throw new IOException("manifest schema is invalid");
        }
        try {
            return new SandboxManifest(
                    json.path("questionId").asText(),
                    RunnerKind.valueOf(json.path("runnerKind").asText()),
                    json.path("originalProjectSha256").asText(),
                    json.path("originalSourceSha256").asText(),
                    json.path("starterSha256").asText(),
                    json.path("sandboxSourceSha256").asText(),
                    json.path("workTreeSha256").asText(),
                    json.path("selectedTest").asText(),
                    json.path("ownershipToken").asText(),
                    Instant.parse(json.path("createdAt").asText()),
                    json.path("workPath").asText(),
                    json.path("sandboxSourcePath").asText(),
                    json.path("starterPath").asText());
        } catch (IllegalArgumentException exception) {
            throw new IOException("manifest content is invalid", exception);
        }
    }

    private Path selectedPublishedTest(SandboxManifest manifest) {
        String selector = manifest.selectedTest();
        if (manifest.runnerKind() == RunnerKind.MAVEN) {
            if (selector.contains("#")
                    || !selector.matches(
                            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                throw new IllegalArgumentException("manifest Maven selector is invalid");
            }
            if (!manifest.sandboxSourcePath().startsWith("java/src/main/java/")
                    || !manifest.sandboxSourcePath().endsWith(".java")) {
                throw new IllegalArgumentException("manifest Maven source is invalid");
            }
            return Path.of(
                    "java/src/test/java/" + selector.replace('.', '/') + ".java");
        }
        if (selector.contains("::")) {
            throw new IllegalArgumentException("manifest pytest selector is invalid");
        }
        Path test = Path.of(selector);
        if (!test.startsWith(Path.of("python/tests"))
                || !portable(test).endsWith(".py")
                || !manifest.sandboxSourcePath().startsWith("python/src/")
                || !manifest.sandboxSourcePath().endsWith(".py")) {
            throw new IllegalArgumentException("manifest pytest paths are invalid");
        }
        return test;
    }

    private String readOwnedToken(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(path, attributes);
        if (!attributes.isRegularFile()) {
            throw new IOException("ownership marker must be a regular file");
        }
        return requireOwnershipToken(Files.readString(path, StandardCharsets.UTF_8));
    }

    private String requireOwnershipToken(String token) throws IOException {
        try {
            return UUID.fromString(token).toString();
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid ownership token", exception);
        }
    }

    private void writeOwnedText(Path path, String ownershipToken) throws IOException {
        Files.writeString(
                path,
                ownershipToken,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private void writeState(Path path, String prefix, String ownershipToken)
            throws IOException {
        Files.writeString(
                path,
                prefix + ownershipToken,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private void writeFailureStateIfAbsent(Path path, String ownershipToken)
            throws IOException {
        if (Files.exists(path, NO_FOLLOW)) {
            return;
        }
        try {
            writeState(path, FAILED_STATE, ownershipToken);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // A terminal state is immutable; failure handling never overwrites it.
        }
    }

    private String readExactState(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(path, attributes);
        if (!attributes.isRegularFile()) {
            throw new IOException("terminal state must be a regular file");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private CompleteState parseCompleteState(String text) {
        Matcher matcher = COMPLETE_STATE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        return new CompleteState(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private void quarantineAndDelete(
            Path root,
            Path session,
            Path attempt,
            CleanupHook hook) throws IOException {
        requirePlainDirectory(root);
        requirePlainDirectory(session);
        requirePlainDirectory(attempt);
        scanForLinks(attempt);
        FileIdentity rootIdentity = captureDirectoryIdentity(root);
        FileIdentity sessionIdentity = captureDirectoryIdentity(session);
        FileIdentity attemptIdentity = captureDirectoryIdentity(attempt);

        hook.beforeQuarantine(root, session, attempt);
        revalidateIdentity(rootIdentity, "sandbox root identity");
        revalidateIdentity(sessionIdentity, "session directory identity");
        revalidateIdentity(attemptIdentity, "attempt directory identity");

        String quarantineName = ".trash-"
                + session.getFileName()
                + "-"
                + attempt.getFileName()
                + "-"
                + UUID.randomUUID();
        Path quarantine = contained(root, Path.of(quarantineName), "cleanup quarantine");
        Files.move(attempt, quarantine, StandardCopyOption.ATOMIC_MOVE);

        revalidateIdentity(rootIdentity, "sandbox root identity after quarantine");
        FileIdentity quarantineIdentity = captureDirectoryIdentity(quarantine);
        if (!attemptIdentity.stableId().equals(quarantineIdentity.stableId())) {
            throw new IOException("quarantine identity does not match captured attempt identity");
        }
        hook.beforeDelete(root, quarantine);
        deleteValidatedTree(
                quarantine,
                root,
                1,
                quarantineName,
                quarantineIdentity);
    }

    private void deleteValidatedTree(
            Path target,
            Path root,
            int requiredDepth,
            String expectedLeaf,
            FileIdentity expectedIdentity) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)
                || normalizedTarget.equals(normalizedRoot)
                || normalizedRoot.relativize(normalizedTarget).getNameCount() != requiredDepth
                || !normalizedTarget.getFileName().toString().equals(expectedLeaf)) {
            throw new IllegalArgumentException("refusing broad or escaped recursive deletion");
        }
        revalidateIdentity(expectedIdentity, "quarantine identity");
        scanForLinks(normalizedTarget);
        try (var paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                revalidateIdentity(expectedIdentity, "quarantine identity");
                Files.delete(path);
            }
        }
    }

    private void scanForLinks(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectLink(directory, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectLink(file, attributes);
                if (!attributes.isRegularFile()) {
                    throw new IOException("unsupported special file: " + file.getFileName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void requirePlainDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(directory, attributes);
        if (!attributes.isDirectory()) {
            throw new IOException("expected directory: " + directory.getFileName());
        }
    }

    private FileIdentity captureDirectoryIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        rejectLink(path, attributes);
        if (!attributes.isDirectory()) {
            throw new IOException("expected directory identity: " + path.getFileName());
        }
        return new FileIdentity(
                path.toAbsolutePath().normalize(),
                path.toRealPath(LinkOption.NOFOLLOW_LINKS),
                identityProvider.identity(path.toAbsolutePath().normalize()));
    }

    private void revalidateIdentity(FileIdentity expected, String label) throws IOException {
        try {
            FileIdentity actual = captureDirectoryIdentity(expected.path());
            if (!sameIdentity(expected, actual)) {
                throw new IOException(label + " changed before mutation");
            }
        } catch (IOException exception) {
            throw new IOException(label + " changed before mutation", exception);
        }
    }

    private boolean identityStillMatches(FileIdentity expected) {
        try {
            return sameIdentity(expected, captureDirectoryIdentity(expected.path()));
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean sameIdentity(FileIdentity expected, FileIdentity actual) {
        return expected.realPath().equals(actual.realPath())
                && expected.stableId().equals(actual.stableId());
    }

    private void rejectReparseInExistingAncestors(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, NO_FOLLOW)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        current,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                rejectLink(current, attributes);
            }
            current = current.getParent();
        }
    }

    private void rejectLink(Path path, BasicFileAttributes attributes) throws IOException {
        if (Files.isSymbolicLink(path) || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("symbolic link, junction, or reparse point is forbidden: "
                    + path.getFileName());
        }
    }

    private Path resolveRelativeFile(Path root, Path relative, String label) throws IOException {
        validateRelative(relative, label);
        Path target = contained(root, relative, label);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    target,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new IOException(label + " is missing: " + portable(relative), exception);
        }
        rejectLink(target, attributes);
        if (!attributes.isRegularFile()) {
            throw new IOException(label + " must be a regular file: " + portable(relative));
        }
        Path current = root;
        for (Path segment : relative.normalize()) {
            current = current.resolve(segment);
            BasicFileAttributes part = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            rejectLink(current, part);
        }
        return target;
    }

    private Path contained(Path root, Path relative, String label) {
        return SandboxPaths.contained(root, relative, label);
    }

    private void validateRelative(Path path, String label) {
        SandboxPaths.validateRelative(path, label);
    }

    private static void requireSegment(String value, String name) {
        SandboxPaths.requireSegment(value, name);
    }

    private static String projectDirectory(RunnerKind runnerKind) {
        return runnerKind == RunnerKind.MAVEN ? "java" : "python";
    }

    private static String portable(Path path) {
        return SandboxPaths.portable(path);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record PreparedInput(
            Path projectRoot,
            Path originalSource,
            Path starter,
            String projectHash,
            String originalSourceHash,
            String starterHash) {
    }

    private record CompleteState(
            String ownershipToken,
            String manifestSha256,
            String workTreeSha256) {
    }

    private record FileIdentity(
            Path path,
            Path realPath,
            String stableId) {
    }

    @FunctionalInterface
    interface FileIdentityProvider {
        String identity(Path path) throws IOException;

        static FileIdentityProvider system() {
            return WindowsFileIdentity::systemIdentity;
        }

        static FileIdentityProvider windowsFileId(
                ProcessStarter processStarter,
                Duration timeout,
                int outputLimit) {
            Objects.requireNonNull(processStarter, "processStarter");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (outputLimit < 32) {
                throw new IllegalArgumentException("outputLimit must be at least 32 bytes");
            }
            return path -> WindowsFileIdentity.query(
                    path,
                    processStarter,
                    timeout,
                    outputLimit);
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;

        static ProcessStarter system() {
            return ProcessBuilder::start;
        }
    }

    @FunctionalInterface
    interface StateWriter {
        void write(Path path, String state) throws IOException;

        static StateWriter system() {
            return (path, state) -> Files.writeString(
                    path,
                    state,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }
    }

    interface PublicationHook {
        default void afterReservation(Path reservation) throws IOException {
        }

        default void afterStaging(Path staging) throws IOException {
        }

        default void beforeCompletion(Path attempt) throws IOException {
        }

        default void afterValidation(Path attempt) throws IOException {
        }

        static PublicationHook none() {
            return new PublicationHook() {
            };
        }
    }

    @FunctionalInterface
    interface CleanupHook {
        void beforeQuarantine(Path root, Path session, Path attempt) throws IOException;

        default void beforeDelete(Path root, Path quarantine) throws IOException {
        }

        static CleanupHook none() {
            return (root, session, attempt) -> {
            };
        }
    }

}
