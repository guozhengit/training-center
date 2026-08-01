package com.guoyongzheng.training.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "history", mixinStandardHelpOptions = true, description = "Show recent training attempts.")
public final class HistoryCommand implements Callable<Integer> {
    @Option(names = {"-w", "--workspace"}, required = true, description = "Workspace root directory.")
    private Path workspace;

    @Option(names = {"--database"}, description = "SQLite database path.")
    private Path database;

    @Option(names = {"--limit"}, defaultValue = "20", description = "Maximum rows.")
    private int limit;

    @Override
    public Integer call() {
        try {
            CliDatabaseSupport.ReadyContext context = CliDatabaseSupport.ready(workspace, database);
            try (Connection connection = context.database().openConnection()) {
                List<CliDatabaseSupport.HistoryRow> rows = CliDatabaseSupport.history(connection, limit);
                System.out.printf("%-38s %-8s %-8s %-10s %-8s %s%n",
                        "ATTEMPT", "QUESTION", "TRACK", "STATUS", "VERDICT", "TITLE");
                for (CliDatabaseSupport.HistoryRow row : rows) {
                    System.out.printf("%-38s %-8s %-8s %-10s %-8s %s%n",
                            row.attemptId(),
                            row.questionId(),
                            row.track(),
                            row.status(),
                            row.verdict() == null ? "-" : row.verdict(),
                            truncate(row.title(), 48));
                }
                System.out.println("Total: " + rows.size());
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("History failed: " + exception.getMessage());
            return 1;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
