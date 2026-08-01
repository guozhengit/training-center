package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.persistence.TrainingDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TrainingCliTest {

    @Test
    void printsUsageWhenNoSubcommandGiven() {
        StringWriter output = new StringWriter();
        CommandLine commandLine = new CommandLine(new TrainingCli());
        commandLine.setOut(new PrintWriter(output));

        int exitCode = commandLine.execute();

        assertThat(exitCode).isZero();
        assertThat(output.toString()).contains("training");
        assertThat(output.toString()).contains("doctor");
        assertThat(output.toString()).contains("catalog");
        assertThat(output.toString()).contains("migrate");
        assertThat(output.toString()).contains("start");
        assertThat(output.toString()).contains("submit");
        assertThat(output.toString()).contains("judge");
        assertThat(output.toString()).contains("history");
        assertThat(output.toString()).contains("export");
    }

    @Test
    void doctorFailsGracefullyWhenWorkspaceIsMissing(@TempDir Path directory) {
        Path missing = directory.resolve("nonexistent");
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(new DoctorCommand());
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("--workspace", missing.toString());

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void migrateCreatesDatabaseInTempDirectory(@TempDir Path directory) {
        Path dbPath = directory.resolve("test.db");
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(new MigrateCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--database", dbPath.toString());

        assertThat(exitCode).isZero();
        assertThat(dbPath).exists();
    }

    @Test
    void versionOptionPrintsVersion() {
        StringWriter output = new StringWriter();
        CommandLine commandLine = new CommandLine(new TrainingCli());
        commandLine.setOut(new PrintWriter(output));

        int exitCode = commandLine.execute("--version");

        assertThat(exitCode).isZero();
        assertThat(output.toString()).contains("training-cli 1.0.0");
    }

    @Test
    void cliTrainingWorkflowCanStartSubmitJudgeHistoryAndExport(@TempDir Path directory) throws Exception {
        Path workspace = Path.of("D:/AI-SOURCE/jiupainews");
        assumeTrue(Files.isRegularFile(workspace.resolve("output/coding-ai-exam/catalog/questions.json")),
                "local workspace catalog is required");
        Path database = directory.resolve("workflow.db");
        Path export = directory.resolve("history.md");
        CommandLine commandLine = new CommandLine(new TrainingCli());

        StringWriter startOut = new StringWriter();
        commandLine.setOut(new PrintWriter(startOut));
        int startCode = commandLine.execute(
                "start",
                "--workspace", workspace.toString(),
                "--database", database.toString(),
                "--track", "ORAL",
                "--question-ids", "O001",
                "--seed", "42");
        assertThat(startCode).isZero();
        String attemptId = readOnlyAttemptId(database);

        int submitCode = new CommandLine(new TrainingCli()).execute(
                "submit",
                "--workspace", workspace.toString(),
                "--database", database.toString(),
                "--attempt", attemptId,
                "--verdict", "PASSED",
                "--duration-seconds", "90",
                "--score", "2,2,2,1,2",
                "--notes", "clear answer");
        assertThat(submitCode).isZero();

        int judgeCode = new CommandLine(new TrainingCli()).execute(
                "judge",
                "--workspace", workspace.toString(),
                "--database", database.toString(),
                "--attempt", attemptId,
                "--status", "PASSED",
                "--passed-count", "1",
                "--failed-count", "0");
        assertThat(judgeCode).isZero();

        int historyCode = new CommandLine(new TrainingCli()).execute(
                "history",
                "--workspace", workspace.toString(),
                "--database", database.toString(),
                "--limit", "5");
        assertThat(historyCode).isZero();

        int exportCode = new CommandLine(new TrainingCli()).execute(
                "export",
                "--workspace", workspace.toString(),
                "--database", database.toString(),
                "--format", "md",
                "--output", export.toString());
        assertThat(exportCode).isZero();
        assertThat(export).exists();
        assertThat(Files.readString(export)).contains(attemptId).contains("O001");
    }

    private static String readOnlyAttemptId(Path database) throws Exception {
        try (Connection connection = new TrainingDatabase(database).openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id FROM attempts LIMIT 1")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
