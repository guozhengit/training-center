package com.guoyongzheng.training.sandbox;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Static utilities for sandbox path resolution and containment validation.
 * Extracted from SandboxService to isolate path-safety concerns.
 */
final class SandboxPaths {

    private SandboxPaths() {
    }

    static Path contained(Path root, Path relative, String label) {
        validateRelative(relative, label);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        if (target.equals(normalizedRoot) || !target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(label + " escapes its configured root");
        }
        return target;
    }

    static void validateRelative(Path path, String label) {
        Objects.requireNonNull(path, label);
        if (path.getRoot() != null
                || path.isAbsolute()
                || path.getNameCount() == 0
                || hasParentSegment(path)) {
            throw new IllegalArgumentException(label + " must be a contained relative path");
        }
    }

    static boolean hasParentSegment(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    static void requireSegment(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || ".".equals(value)
                || "..".equals(value)) {
            throw new IllegalArgumentException(name + " must be one safe path segment");
        }
    }

    static String portable(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
