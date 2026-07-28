package com.guoyongzheng.training.core;

import java.util.List;

public record EnvironmentReport(boolean ready, List<EnvironmentIssue> issues) {
    public EnvironmentReport {
        issues = List.copyOf(issues);
    }
}
