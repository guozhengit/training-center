package com.guoyongzheng.training.sandbox;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Static utilities for sandbox path resolution and containment validation.
 * Extracted from SandboxService to isolate path-safety concerns. Also exposes
 * the well-known attempt-directory layout so callers (e.g. the web judge)
 * share a single source of truth instead of hardcoding path literals.
 */
public final class SandboxPaths {

    /** Directory inside an attempt that holds the copied exam project. */
    public static final String WORK_DIR = "work";
    /** JSON manifest describing a completed attempt. */
    public static final String MANIFEST_FILE = "manifest.json";
    /** Terminal-state marker file for an attempt. */
    public static final String STATE_FILE = ".state";
    /** Ownership marker file for an attempt. */
    public static final String OWNER_FILE = ".owner";

    private SandboxPaths() {
    }

    /** Resolves the work directory inside an attempt directory. */
    public static Path workPath(Path attemptDirectory) {
        return attemptDirectory.resolve(WORK_DIR);
    }

    /** Resolves the manifest file inside an attempt directory. */
    public static Path manifestPath(Path attemptDirectory) {
        return attemptDirectory.resolve(MANIFEST_FILE);
    }

    /** Resolves the terminal-state marker inside an attempt directory. */
    public static Path statePath(Path attemptDirectory) {
        return attemptDirectory.resolve(STATE_FILE);
    }

    /** Resolves the ownership marker inside an attempt directory. */
    public static Path ownerPath(Path attemptDirectory) {
        return attemptDirectory.resolve(OWNER_FILE);
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
