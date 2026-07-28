package com.guoyongzheng.training.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Executes local child processes with bounded output and bounded tree cleanup. */
public final class LocalProcessRunner implements ProcessRunner {

    private static final int HARD_OUTPUT_CAP = 64 * 1024;
    private static final long MAX_TIMEOUT_NANOS = Duration.ofDays(1).toNanos();
    private static final long OBSERVATION_SLICE_NANOS =
            TimeUnit.MILLISECONDS.toNanos(10);
    private static final Duration TREE_GRACE = Duration.ofMillis(250);
    private static final Duration TREE_FORCE = Duration.ofSeconds(3);
    private static final Duration READER_JOIN = Duration.ofSeconds(2);
    private static final AtomicLong READER_SEQUENCE = new AtomicLong();

    @Override
    public ProcessResult run(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        long timeoutNanos;
        try {
            timeoutNanos = request.timeout().toNanos();
        } catch (ArithmeticException exception) {
            return environmentError(started, "timeout is too large");
        }
        if (timeoutNanos <= 0 || timeoutNanos > MAX_TIMEOUT_NANOS) {
            return environmentError(started, "timeout exceeds the supported one-day limit");
        }
        if (!Files.isDirectory(request.workingDirectory())) {
            return environmentError(started, "working directory is not a directory");
        }

        Process process = null;
        ObservedTree observedTree = null;
        BoundedCapture stdout = null;
        BoundedCapture stderr = null;
        Thread stdoutReader = null;
        Thread stderrReader = null;
        boolean interrupted = false;
        boolean cleanupComplete = true;
        int exitCode = -1;
        boolean timedOut = false;
        FailureKind failureKind = FailureKind.ENVIRONMENT_ERROR;
        String environmentMessage = "process execution failed";
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(request.workingDirectory().toFile());
            configureEnvironment(builder, request.environment());
            process = builder.start();
            observedTree = new ObservedTree(process.toHandle());
            observedTree.observe();
            process.getOutputStream().close();

            stdout = new BoundedCapture(
                    process.getInputStream(),
                    Math.min(request.maxStdoutBytes(), HARD_OUTPUT_CAP));
            stderr = new BoundedCapture(
                    process.getErrorStream(),
                    Math.min(request.maxStderrBytes(), HARD_OUTPUT_CAP));
            stdoutReader = readerThread(stdout, "stdout");
            stderrReader = readerThread(stderr, "stderr");
            stdoutReader.start();
            stderrReader.start();

            boolean completed = awaitCompletion(process, observedTree, timeoutNanos);
            if (completed) {
                exitCode = process.exitValue();
                failureKind = exitCode == 0
                        ? FailureKind.NONE
                        : FailureKind.TEST_FAILURE;
            } else {
                timedOut = true;
                failureKind = FailureKind.TIMED_OUT;
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            environmentMessage = "process execution was interrupted";
            failureKind = FailureKind.ENVIRONMENT_ERROR;
        } catch (IOException | RuntimeException exception) {
            environmentMessage = safeMessage(exception);
            failureKind = FailureKind.ENVIRONMENT_ERROR;
        } finally {
            if (process != null) {
                if (observedTree == null) {
                    observedTree = new ObservedTree(process.toHandle());
                }
                cleanupComplete = terminateTree(observedTree);
                closeQuietly(process.getOutputStream());
                if (stdoutReader != null) {
                    interrupted |= joinReader(stdoutReader);
                    if (stdoutReader.isAlive()) {
                        closeQuietly(process.getInputStream());
                        interrupted |= joinReader(stdoutReader);
                    }
                } else {
                    closeQuietly(process.getInputStream());
                }
                if (stderrReader != null) {
                    interrupted |= joinReader(stderrReader);
                    if (stderrReader.isAlive()) {
                        closeQuietly(process.getErrorStream());
                        interrupted |= joinReader(stderrReader);
                    }
                } else {
                    closeQuietly(process.getErrorStream());
                }
            }
        }

        boolean readerFailure = stdout != null && stdout.failure() != null
                || stderr != null && stderr.failure() != null
                || stdoutReader != null && stdoutReader.isAlive()
                || stderrReader != null && stderrReader.isAlive();
        if ((!cleanupComplete || readerFailure)
                && failureKind != FailureKind.TIMED_OUT) {
            exitCode = -1;
            timedOut = false;
            failureKind = FailureKind.ENVIRONMENT_ERROR;
            environmentMessage = !cleanupComplete
                    ? "process tree cleanup did not complete"
                    : "process output capture did not complete";
        }

        String stdoutText = "";
        String stderrText = failureKind == FailureKind.ENVIRONMENT_ERROR
                ? environmentMessage
                : "";
        try {
            if (stdout != null) {
                stdoutText = stdout.text();
            }
            if (stderr != null) {
                stderrText = stderr.text();
            }
        } catch (RuntimeException exception) {
            exitCode = -1;
            timedOut = false;
            failureKind = FailureKind.ENVIRONMENT_ERROR;
            stdoutText = "";
            stderrText = safeMessage(exception);
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new ProcessResult(
                exitCode,
                timedOut,
                stdoutText,
                stderrText,
                elapsed(started),
                failureKind);
    }

    private static boolean awaitCompletion(
            Process process,
            ObservedTree observedTree,
            long timeoutNanos) throws InterruptedException {
        long waitStarted = System.nanoTime();
        while (true) {
            observedTree.observe();
            if (!process.isAlive()) {
                observedTree.observe();
                return true;
            }
            long elapsed = System.nanoTime() - waitStarted;
            if (elapsed >= timeoutNanos) {
                return false;
            }
            long remaining = timeoutNanos - elapsed;
            process.waitFor(
                    Math.min(remaining, OBSERVATION_SLICE_NANOS),
                    TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Starts from a clean environment, preserving only platform variables
     * needed for executable lookup, temporary files, home/runtime lookup, and
     * locale. JAVA_HOME is required so Maven uses the intended JDK instead of
     * whichever Java happens to be first on PATH. Explicit request entries are
     * then applied as overrides.
     */
    private static void configureEnvironment(
            ProcessBuilder builder,
            Map<String, String> overrides) {
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = new LinkedHashMap<>(environment);
        environment.clear();
        environment.putAll(minimalEnvironment(inherited, overrides, isWindows()));
    }

    static Map<String, String> minimalEnvironment(
            Map<String, String> inherited,
            Map<String, String> overrides,
            boolean windows) {
        Objects.requireNonNull(inherited, "inherited");
        Objects.requireNonNull(overrides, "overrides");
        List<String> allowlist = windows
                ? List.of(
                        "PATH",
                        "SystemRoot",
                        "ComSpec",
                        "PATHEXT",
                        "TEMP",
                        "TMP",
                        "JAVA_HOME")
                : List.of(
                        "PATH",
                        "HOME",
                        "TMPDIR",
                        "LANG",
                        "LC_ALL",
                        "LC_CTYPE",
                        "JAVA_HOME");
        Map<String, String> selected = new LinkedHashMap<>();
        for (String allowed : allowlist) {
            if (windows) {
                inherited.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(allowed))
                        .findFirst()
                        .ifPresent(entry -> selected.put(allowed, entry.getValue()));
            } else if (inherited.containsKey(allowed)) {
                selected.put(allowed, inherited.get(allowed));
            }
        }
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            String key = override.getKey();
            if (windows) {
                key = allowlist.stream()
                        .filter(allowed -> allowed.equalsIgnoreCase(override.getKey()))
                        .findFirst()
                        .orElse(key);
            }
            selected.put(key, override.getValue());
        }
        return selected;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static ProcessResult environmentError(long started, String message) {
        return new ProcessResult(
                -1,
                false,
                "",
                message,
                elapsed(started),
                FailureKind.ENVIRONMENT_ERROR);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }

    private static Thread readerThread(BoundedCapture capture, String stream) {
        Thread thread = new Thread(
                capture,
                "training-process-reader-"
                        + stream
                        + "-"
                        + READER_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    private static boolean joinReader(Thread reader) {
        long deadline = System.nanoTime() + READER_JOIN.toNanos();
        boolean interrupted = false;
        while (reader.isAlive() && System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            try {
                TimeUnit.NANOSECONDS.timedJoin(reader, Math.max(1L, remaining));
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static boolean terminateTree(ObservedTree observedTree) {
        if (destroyUntil(observedTree, TREE_GRACE, false)) {
            return true;
        }
        return destroyUntil(observedTree, TREE_FORCE, true);
    }

    private static boolean destroyUntil(
            ObservedTree observedTree,
            Duration duration,
            boolean forcibly) {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            observedTree.observe();
            List<ObservedProcess> leafFirst = observedTree.processes().stream()
                    .filter(process -> process.handle().isAlive())
                    .sorted(Comparator
                            .comparingInt(ObservedProcess::depth)
                            .reversed()
                            .thenComparingLong(process -> process.handle().pid()))
                    .toList();
            for (ObservedProcess observed : leafFirst) {
                ProcessHandle handle = observed.handle();
                if (!handle.isAlive()) {
                    continue;
                }
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            }
            if (observedTree.processes().stream()
                    .noneMatch(process -> process.handle().isAlive())) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        } while (System.nanoTime() < deadline);
        observedTree.observe();
        return observedTree.processes().stream()
                .noneMatch(process -> process.handle().isAlive());
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing is only used to bound reader shutdown after process completion.
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private record ObservedProcess(ProcessHandle handle, int depth) {
    }

    private static final class ObservedTree {

        private final Map<Long, ObservedProcess> observed = new LinkedHashMap<>();

        private ObservedTree(ProcessHandle root) {
            observed.put(root.pid(), new ObservedProcess(root, 0));
        }

        private void observe() {
            List<ObservedProcess> snapshot = new ArrayList<>(observed.values());
            for (ObservedProcess process : snapshot) {
                observeChildren(process, new java.util.HashSet<>());
            }
        }

        private void observeChildren(
                ObservedProcess parent,
                Set<Long> visiting) {
            if (!visiting.add(parent.handle().pid())) {
                return;
            }
            try {
                parent.handle().children().forEach(child -> {
                    int depth = parent.depth() + 1;
                    ObservedProcess current = observed.get(child.pid());
                    if (current == null || current.depth() < depth) {
                        current = new ObservedProcess(child, depth);
                        observed.put(child.pid(), current);
                    }
                    observeChildren(current, visiting);
                });
            } catch (RuntimeException ignored) {
                // A process can exit between obtaining and traversing its handle.
            }
        }

        private List<ObservedProcess> processes() {
            return List.copyOf(observed.values());
        }
    }

    private static final class BoundedCapture implements Runnable {

        private final InputStream stream;
        private final int limit;
        private final ByteArrayOutputStream captured;
        private volatile boolean truncated;
        private volatile IOException failure;

        private BoundedCapture(InputStream stream, int limit) {
            this.stream = stream;
            this.limit = limit;
            this.captured = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = limit - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private IOException failure() {
            return failure;
        }

        private String text() {
            byte[] bytes = captured.toByteArray();
            if (!truncated) {
                return decode(copyPrefix(bytes, validUtf8Prefix(bytes, bytes.length)));
            }
            int contentLimit = limit - ProcessResult.TRUNCATION_MARKER_BYTES;
            int validLength = validUtf8Prefix(bytes, contentLimit);
            String prefix = decode(copyPrefix(bytes, validLength));
            int padding = contentLimit
                    - prefix.getBytes(StandardCharsets.UTF_8).length;
            return prefix + " ".repeat(padding) + ProcessResult.TRUNCATION_MARKER;
        }

        private static byte[] copyPrefix(byte[] bytes, int length) {
            return java.util.Arrays.copyOf(bytes, length);
        }

        private static int validUtf8Prefix(byte[] bytes, int maximum) {
            int length = Math.min(bytes.length, maximum);
            ByteBuffer input = ByteBuffer.wrap(bytes, 0, length);
            CharBuffer output = CharBuffer.allocate(length);
            CoderResult result = decoder().decode(input, output, true);
            return result.isUnderflow() ? length : input.position();
        }

        private static String decode(byte[] bytes) {
            try {
                return decoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException exception) {
                throw new IllegalStateException("validated UTF-8 prefix did not decode", exception);
            }
        }

        private static java.nio.charset.CharsetDecoder decoder() {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
        }
    }
}
