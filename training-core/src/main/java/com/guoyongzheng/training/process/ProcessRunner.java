package com.guoyongzheng.training.process;

/** Runs one argv-only process request without converting test failures to exceptions. */
@FunctionalInterface
public interface ProcessRunner {

    ProcessResult run(ProcessRequest request);
}
