package com.guoyongzheng.training.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows-specific stable file identity via {@code fsutil.exe file queryFileID}.
 * Extracted from SandboxService to isolate platform-specific process management.
 */
final class WindowsFileIdentity {

    private static final Pattern FSUTIL_FILE_ID = Pattern.compile(
            "\\A(?![^\\r\\n]*0[xX][^\\r\\n]*0[xX])"
                    + "[^\\r\\n]*0[xX]([0-9A-Fa-f]{32})[\\t ]*\\z");

    private WindowsFileIdentity() {
    }

    static String systemIdentity(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                absolute,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(absolute)
                || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw new IOException("stable identity is forbidden for a link or reparse point");
        }
        if (attributes.fileKey() != null) {
            return "nio:" + attributes.fileKey();
        }
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows")) {
            throw new IOException("stable identity unavailable: fileKey is null");
        }

        return query(
                absolute,
                SandboxService.ProcessStarter.system(),
                Duration.ofSeconds(5),
                4096);
    }

    static String query(
            Path path,
            SandboxService.ProcessStarter processStarter,
            Duration timeout,
            int outputLimit) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path captureDirectory = Files.createTempDirectory("sandbox-fsutil-")
                .toAbsolutePath()
                .normalize();
        Path stdout = captureDirectory.resolve("stdout.txt");
        Path stderr = captureDirectory.resolve("stderr.txt");
        Set<ProcessHandle> descendants = new HashSet<>();
        boolean restoreInterrupt = false;

        try {
            Files.createFile(stdout);
            Files.createFile(stderr);
            ProcessBuilder builder = new ProcessBuilder(
                            "cmd.exe",
                            "/d",
                            "/v:off",
                            "/c",
                            "chcp 65001>nul & fsutil.exe file queryFileID "
                                    + "\"%SANDBOX_FSUTIL_TARGET%\"")
                    .redirectErrorStream(false)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            builder.environment().put(
                    "SANDBOX_FSUTIL_TARGET",
                    absolute.toString());
            Process process = processStarter.start(builder);
            int exitCode;
            try {
                closeQuietly(process.getOutputStream());
                waitForParent(process, descendants, timeout);
                exitCode = process.exitValue();
            } catch (InterruptedException exception) {
                restoreInterrupt = true;
                throw new IOException("stable identity query was interrupted", exception);
            } finally {
                restoreInterrupt |= terminateProcessTree(process, descendants);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }

            byte[] output = readBoundedFile(stdout, outputLimit, "output");
            byte[] error = readBoundedFile(stderr, outputLimit, "error output");
            String text = decodeUtf8(output, "output");
            String errorText = decodeUtf8(error, "error output");
            if (exitCode != 0) {
                throw new IOException(
                        "stable identity query failed with exit code " + exitCode);
            }
            if (!errorText.isEmpty()) {
                throw new IOException("stable identity error output from fsutil was invalid");
            }
            text = stripSingleLineEnding(text);
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
                throw new IOException("stable identity output from fsutil was invalid");
            }
            Matcher matcher = FSUTIL_FILE_ID.matcher(text);
            if (!matcher.matches()) {
                throw new IOException("stable identity output from fsutil was invalid");
            }
            return "windows-file-id:" + matcher.group(1).toLowerCase(Locale.ROOT);
        } finally {
            try {
                deleteCaptureFiles(captureDirectory, stdout, stderr);
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // --- process tree management ---

    private static void waitForParent(
            Process process,
            Set<ProcessHandle> descendants,
            Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            discoverProcessTree(process.toHandle(), descendants);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("stable identity query timed out");
            }
            long waitMillis = Math.max(
                    1,
                    Math.min(25, TimeUnit.NANOSECONDS.toMillis(remaining)));
            process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        }
        discoverProcessTree(process.toHandle(), descendants);
    }

    private static boolean terminateProcessTree(
            Process process,
            Set<ProcessHandle> descendants) throws IOException {
        boolean interrupted = Thread.interrupted();
        ProcessHandle root = process.toHandle();
        discoverProcessTree(root, descendants);
        if (!root.isAlive()
                && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
            return interrupted;
        }
        if (!root.isAlive()
                && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            interrupted |= observeLateDescendants(
                    root,
                    descendants,
                    TimeUnit.SECONDS.toNanos(2),
                    TimeUnit.MILLISECONDS.toNanos(200));
        }
        interrupted |= sweepProcessTree(
                root,
                descendants,
                false,
                TimeUnit.MILLISECONDS.toNanos(300),
                TimeUnit.MILLISECONDS.toNanos(75));
        if (root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            interrupted |= sweepProcessTree(
                    root,
                    descendants,
                    true,
                    TimeUnit.MILLISECONDS.toNanos(1500),
                    TimeUnit.MILLISECONDS.toNanos(75));
        }
        discoverProcessTree(root, descendants);
        terminateLeafToRoot(root, descendants, true);
        if (root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            throw new IOException("stable identity process tree could not be terminated");
        }
        return interrupted;
    }

    private static boolean observeLateDescendants(
            ProcessHandle root,
            Set<ProcessHandle> descendants,
            long durationNanos,
            long stablePeriodNanos) {
        boolean interrupted = false;
        long started = System.nanoTime();
        long deadline = started + durationNanos;
        long lastDiscovery = started;
        int observedCount = descendants.size();
        boolean discoveredNew = false;
        while (System.nanoTime() < deadline
                && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            discoverProcessTree(root, descendants);
            long now = System.nanoTime();
            if (descendants.size() != observedCount) {
                observedCount = descendants.size();
                lastDiscovery = now;
                discoveredNew = true;
            } else if (discoveredNew && now - lastDiscovery >= stablePeriodNanos) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        discoverProcessTree(root, descendants);
        return interrupted;
    }

    private static boolean sweepProcessTree(
            ProcessHandle root,
            Set<ProcessHandle> descendants,
            boolean force,
            long durationNanos,
            long quietPeriodNanos) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + durationNanos;
        long quietSince = -1;
        while (System.nanoTime() < deadline) {
            int observedBefore = descendants.size();
            discoverProcessTree(root, descendants);
            terminateLeafToRoot(root, descendants, force);
            discoverProcessTree(root, descendants);
            boolean alive = root.isAlive()
                    || descendants.stream().anyMatch(ProcessHandle::isAlive);
            boolean discovered = descendants.size() != observedBefore;
            long now = System.nanoTime();
            if (!alive && !discovered) {
                if (quietSince < 0) {
                    quietSince = now;
                } else if (now - quietSince >= quietPeriodNanos) {
                    break;
                }
            } else {
                quietSince = -1;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static void terminateLeafToRoot(
            ProcessHandle root,
            Set<ProcessHandle> descendants,
            boolean force) {
        List<ProcessHandle> leafFirst = new ArrayList<>(descendants);
        leafFirst.sort(Comparator.comparingLong(WindowsFileIdentity::descendantCount));
        leafFirst.forEach(handle -> terminate(handle, force));
        terminate(root, force);
    }

    private static long descendantCount(ProcessHandle handle) {
        try (var descendants = handle.descendants()) {
            return descendants.count();
        }
    }

    private static void discoverProcessTree(
            ProcessHandle root,
            Set<ProcessHandle> descendants) {
        List<ProcessHandle> observed = new ArrayList<>(descendants.size() + 1);
        observed.add(root);
        observed.addAll(descendants);
        for (ProcessHandle handle : observed) {
            try (var handles = handle.descendants()) {
                handles.forEach(descendants::add);
            }
        }
    }

    private static void terminate(ProcessHandle handle, boolean force) {
        if (!handle.isAlive()) {
            return;
        }
        try {
            if (force) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (SecurityException ignored) {
        }
    }

    // --- I/O helpers ---

    private static byte[] readBoundedFile(
            Path path,
            int outputLimit,
            String label) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || !attributes.isRegularFile()) {
            throw new IOException("stable identity " + label + " file was invalid");
        }
        if (attributes.size() > outputLimit) {
            throw new IOException("stable identity " + label + " exceeded output limit");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > outputLimit) {
            throw new IOException("stable identity " + label + " exceeded output limit");
        }
        return bytes;
    }

    private static String decodeUtf8(byte[] bytes, String label) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException(
                    "stable identity " + label + " was not valid UTF-8",
                    exception);
        }
    }

    private static String stripSingleLineEnding(String text) {
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static void deleteCaptureFiles(
            Path captureDirectory,
            Path stdout,
            Path stderr) throws IOException {
        Path normalizedDirectory = captureDirectory.toAbsolutePath().normalize();
        Path normalizedStdout = stdout.toAbsolutePath().normalize();
        Path normalizedStderr = stderr.toAbsolutePath().normalize();
        if (!normalizedStdout.getParent().equals(normalizedDirectory)
                || !normalizedStderr.getParent().equals(normalizedDirectory)
                || normalizedStdout.equals(normalizedStderr)) {
            throw new IOException("stable identity capture paths were invalid");
        }
        Files.deleteIfExists(normalizedStdout);
        Files.deleteIfExists(normalizedStderr);
        Files.deleteIfExists(normalizedDirectory);
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
