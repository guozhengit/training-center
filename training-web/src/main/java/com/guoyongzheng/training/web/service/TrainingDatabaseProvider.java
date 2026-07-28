package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.catalog.CatalogLoader;
import com.guoyongzheng.training.domain.QuestionDescriptor;
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
 */
@Component
public class TrainingDatabaseProvider {
    private final WorkspaceLocator workspaceLocator;
    private final CatalogLoader catalogLoader = new CatalogLoader();

    private Path migratedDatabasePath;

    public TrainingDatabaseProvider(WorkspaceLocator workspaceLocator) {
        this.workspaceLocator = workspaceLocator;
    }

    public Path workspace() {
        return workspaceLocator.locate();
    }

    public synchronized TrainingDatabase readyDatabase() {
        Path workspace = workspaceLocator.locate();
        Path databasePath = workspace.resolve("output/training-runtime/database/training.db")
                .toAbsolutePath()
                .normalize();
        if (!databasePath.equals(migratedDatabasePath)) {
            TrainingDatabase database = new TrainingDatabase(databasePath);
            database.migrate();
            migratedDatabasePath = databasePath;
            try (Connection connection = database.openConnection()) {
                syncQuestions(connection, catalogLoader.load(workspace).find(null));
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot sync question catalog to database", exception);
            }
        }
        return new TrainingDatabase(databasePath);
    }

    public List<QuestionDescriptor> loadCatalog() {
        return catalogLoader.load(workspaceLocator.locate()).find(null);
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
