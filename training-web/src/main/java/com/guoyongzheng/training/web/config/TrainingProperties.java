package com.guoyongzheng.training.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Externalized environment configuration for the training judge runtime and
 * database/Redis connectivity. Paths default to the local development machine
 * but can be overridden via environment variables
 * ({@code TRAINING_JDK_HOME}, {@code TRAINING_MAVEN_HOME}, {@code TRAINING_PYTHON},
 * {@code TRAINING_DATABASE_TYPE}, {@code TRAINING_DB_URL}, {@code TRAINING_DB_USER},
 * {@code TRAINING_DB_PASSWORD}, {@code TRAINING_REDIS_HOST}, {@code TRAINING_REDIS_PORT},
 * {@code TRAINING_REDIS_PASSWORD}).
 */
@ConfigurationProperties(prefix = "training")
public record TrainingProperties(
        String workspace,
        Path jdkHome,
        Path mavenHome,
        String pythonExecutable,
        Integer sandboxRetentionHours,
        String databaseType,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        String redisHost,
        Integer redisPort,
        String redisPassword) {

    /** SQLite (default, local file database) or PostgreSQL (server deployment). */
    public static final String DATABASE_TYPE_SQLITE = "sqlite";
    public static final String DATABASE_TYPE_POSTGRES = "postgres";

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
        if (sandboxRetentionHours == null || sandboxRetentionHours <= 0) {
            sandboxRetentionHours = 72;
        }
        if (databaseType == null || databaseType.isBlank()) {
            databaseType = DATABASE_TYPE_SQLITE;
        }
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = "jdbc:postgresql://localhost:5432/training";
        }
        if (databaseUser == null || databaseUser.isBlank()) {
            databaseUser = "postgres";
        }
        if (databasePassword == null) {
            databasePassword = "";
        }
        if (redisHost == null) {
            redisHost = "";
        }
        if (redisPort == null || redisPort <= 0) {
            redisPort = 6379;
        }
        if (redisPassword == null) {
            redisPassword = "";
        }
        jdkHome = jdkHome.toAbsolutePath().normalize();
        mavenHome = mavenHome.toAbsolutePath().normalize();
    }

    /** True when the PostgreSQL backend is selected. */
    public boolean postgresEnabled() {
        return DATABASE_TYPE_POSTGRES.equalsIgnoreCase(databaseType.trim());
    }

    /** True when Redis connectivity is configured. */
    public boolean redisEnabled() {
        return redisHost != null && !redisHost.isBlank();
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
