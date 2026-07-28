package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.persistence.TrainingDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Runs Flyway migrations on the training database. */
@Command(
        name = "migrate",
        mixinStandardHelpOptions = true,
        description = "Initialize or upgrade the training database schema."
)
public final class MigrateCommand implements Callable<Integer> {

    @Option(names = {"-d", "--database"}, description = "Path to the SQLite database file.", required = true)
    private Path databasePath;

    @Override
    public Integer call() {
        Path resolved = databasePath.toAbsolutePath().normalize();
        System.out.println("Migrating database: " + resolved);
        try {
            TrainingDatabase database = new TrainingDatabase(resolved);
            database.migrate();
            database.lastBackup().ifPresent(backup ->
                    System.out.println("Backup created: " + backup));
            System.out.println("Migration complete. Schema version: "
                    + TrainingDatabase.SUPPORTED_SCHEMA_VERSION);
            return 0;
        } catch (Exception exception) {
            System.err.println("Migration failed: " + exception.getMessage());
            return 1;
        }
    }
}
