package com.guoyongzheng.training.persistence;

import java.util.Objects;

/**
 * Connection settings for the PostgreSQL backend.
 *
 * @param jdbcUrl  JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/training}
 * @param user     database user
 * @param password database password
 */
public record PostgresSettings(String jdbcUrl, String user, String password) {

    public PostgresSettings {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
    }
}
