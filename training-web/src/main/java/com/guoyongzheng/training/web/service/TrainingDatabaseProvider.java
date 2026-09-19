package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.web.config.TrainingProperties;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.persistence.PostgresSettings;
import com.guoyongzheng.training.persistence.TrainingDatabase;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages database readiness: migration, question catalog sync, and connection provisioning.
 * Shared across all training services to avoid duplicating lifecycle logic.
 *
 * <p>Supports two backends, selected via {@code training.database-type}:
 * <ul>
 *   <li>{@code sqlite} (default): local file database under the workspace.</li>
 *   <li>{@code postgres}: server-backed database from {@code training.database-url}.</li>
 * </ul>
 */
@Component
public class TrainingDatabaseProvider {
    private final WorkspaceLocator workspaceLocator;
    private final CatalogCache catalogCache;
    private final TrainingProperties properties;

    private Path migratedDatabasePath;
    private TrainingDatabase cachedDatabase;

    public TrainingDatabaseProvider(WorkspaceLocator workspaceLocator,
                                    CatalogCache catalogCache,
                                    TrainingProperties properties) {
        this.workspaceLocator = workspaceLocator;
        this.catalogCache = catalogCache;
        this.properties = properties;
    }

    public Path workspace() {
        return workspaceLocator.locate();
    }

    public synchronized TrainingDatabase readyDatabase() {
        if (properties.postgresEnabled()) {
            return readyPostgresDatabase();
        }
        return readySqliteDatabase();
    }

    private TrainingDatabase readyPostgresDatabase() {
        if (cachedDatabase == null) {
            TrainingDatabase database = new TrainingDatabase(new PostgresSettings(
                    properties.databaseUrl(),
                    properties.databaseUser(),
                    properties.databasePassword()));
            database.migrate();
            try (Connection connection = database.openConnection()) {
                syncQuestions(connection, catalogCache.get().find(null));
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot sync question catalog to PostgreSQL", exception);
            }
            cachedDatabase = database;
        }
        return cachedDatabase;
    }

    private TrainingDatabase readySqliteDatabase() {
        Path workspace = workspaceLocator.locate();
        Path databasePath = workspace.resolve("output/training-runtime/database/training.db")
                .toAbsolutePath()
                .normalize();
        if (!databasePath.equals(migratedDatabasePath)) {
            TrainingDatabase database = new TrainingDatabase(databasePath);
            database.migrate();
            try (Connection connection = database.openConnection()) {
                syncQuestions(connection, catalogCache.get().find(null));
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot sync question catalog to database", exception);
            }
            migratedDatabasePath = databasePath;
            cachedDatabase = database;
        }
        return cachedDatabase;
    }

    public List<QuestionDescriptor> loadCatalog() {
        return catalogCache.get().find(null);
    }

    static void syncQuestions(Connection connection, List<QuestionDescriptor> questions) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO questions
                    (id, track, group_name, title, topic, difficulty, language, source_ref, starter_ref, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(id) DO UPDATE SET
                    track = excluded.track,
                    group_name = excluded.group_name,
                    title = excluded.title,
                    topic = excluded.topic,
                    difficulty = excluded.difficulty,
                    language = excluded.language,
                    source_ref = excluded.source_ref,
                    starter_ref = excluded.starter_ref,
                    active = 1
                """)) {
            for (QuestionDescriptor question : questions) {
                statement.setString(1, question.id());
                statement.setString(2, question.track().name());
                statement.setString(3, question.groupName());
                statement.setString(4, question.title());
                statement.setString(5, question.topic());
                statement.setString(6, question.difficulty());
                statement.setString(7, question.language());
                statement.setString(8, question.sourceRef());
                statement.setString(9, question.starterRef());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
