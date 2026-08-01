package com.guoyongzheng.training.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "export", mixinStandardHelpOptions = true, description = "Export recent history to Markdown or CSV.")
public final class ExportCommand implements Callable<Integer> {
    @Option(names = {"-w", "--workspace"}, required = true, description = "Workspace root directory.")
    private Path workspace;

    @Option(names = {"--database"}, description = "SQLite database path.")
    private Path database;

    @Option(names = {"--format"}, defaultValue = "md", description = "md or csv.")
    private String format;

    @Option(names = {"--limit"}, defaultValue = "100", description = "Maximum rows.")
    private int limit;

    @Option(names = {"-o", "--output"}, required = true, description = "Output file.")
    private Path output;

    @Override
    public Integer call() {
        try {
            CliDatabaseSupport.ReadyContext context = CliDatabaseSupport.ready(workspace, database);
            try (Connection connection = context.database().openConnection()) {
                List<CliDatabaseSupport.HistoryRow> rows = CliDatabaseSupport.history(connection, limit);
                CliDatabaseSupport.writeExport(output, format, rows);
                System.out.println("Exported " + rows.size() + " rows to " + output.toAbsolutePath().normalize());
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("Export failed: " + exception.getMessage());
            return 1;
        }
    }
}
