package com.guoyongzheng.training.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Externalized environment configuration for the training judge runtime.
 * All paths default to the local development machine but can be overridden
 * via environment variables (TRAINING_JDK_HOME, TRAINING_MAVEN_HOME, TRAINING_PYTHON).
 */
@ConfigurationProperties(prefix = "training")
public record TrainingProperties(
        String workspace,
        Path jdkHome,
        Path mavenHome,
        String pythonExecutable) {

    public TrainingProperties {
        if (workspace == null) {
            workspace = "";
        }
        if (jdkHome == null) {
            jdkHome = Path.of("D:/jdk/jdk-17.0.12");
        }
        if (mavenHome == null) {
            mavenHome = Path.of("D:/Program Files (x86)/apache-maven-3.9.9");
        }
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            pythonExecutable = "python";
        }
        jdkHome = jdkHome.toAbsolutePath().normalize();
        mavenHome = mavenHome.toAbsolutePath().normalize();
    }

    /** Returns the JDK bin directory, e.g. D:/jdk/jdk-17.0.12/bin */
    public Path jdkBin() {
        return jdkHome.resolve("bin");
    }

    /** Returns the Maven bin directory, e.g. D:/Program Files (x86)/apache-maven-3.9.9/bin */
    public Path mavenBin() {
        return mavenHome.resolve("bin");
    }
}
