package com.guoyongzheng.training.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An argv-only request for a bounded local child process. */
public record ProcessRequest(
        List<String> command,
        Path workingDirectory,
        Duration timeout,
        int maxStdoutBytes,
        int maxStderrBytes,
        Map<String, String> environment) {

    public ProcessRequest {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()
                || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command must contain nonblank argv entries");
        }
        command = List.copyOf(command);
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxStdoutBytes < ProcessResult.TRUNCATION_MARKER_BYTES
                || maxStderrBytes < ProcessResult.TRUNCATION_MARKER_BYTES) {
            throw new IllegalArgumentException(
                    "output limits must fit the truncation marker");
        }
        Objects.requireNonNull(environment, "environment");
        if (environment.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null)) {
            throw new IllegalArgumentException(
                    "environment overrides require nonblank keys and nonnull values");
        }
        environment = Map.copyOf(environment);
    }
}
