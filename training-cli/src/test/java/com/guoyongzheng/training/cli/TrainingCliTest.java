package com.guoyongzheng.training.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
