package com.guoyongzheng.training.judge;

import java.io.IOException;
import java.nio.file.Path;

/** Checks whether a sandbox attempt directory is complete and ready for judging. */
@FunctionalInterface
public interface SandboxCompletionCheck {

    boolean isComplete(Path attemptDirectory) throws IOException;
}
