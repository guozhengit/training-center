package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.sandbox.SandboxManifest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** A request to judge one completed Task 6 sandbox attempt. */
public record JudgeRequest(
        Path attemptDirectory,
        SandboxManifest manifest,
        Duration timeout,
        Map<String, String> environment) {

    public JudgeRequest {
        attemptDirectory = Objects.requireNonNull(attemptDirectory, "attemptDirectory")
                .toAbsolutePath()
                .normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
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
