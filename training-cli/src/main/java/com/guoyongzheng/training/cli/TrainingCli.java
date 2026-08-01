package com.guoyongzheng.training.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Entry point for the training-center CLI.
 *
 * <p>Provides offline access to environment diagnostics, catalog inspection,
 * database migration, and a minimal local training workflow without starting
 * the web server.</p>
 */
@Command(
        name = "training",
        mixinStandardHelpOptions = true,
        version = "training-cli 1.0.0",
        description = "Interview Training Center command-line tool.",
        subcommands = {
                DoctorCommand.class,
                CatalogCommand.class,
                MigrateCommand.class,
                StartCommand.class,
                SubmitCommand.class,
                JudgeCommand.class,
                HistoryCommand.class,
                ExportCommand.class,
                ImportCommand.class
        }
)
public final class TrainingCli implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TrainingCli()).execute(args);
        System.exit(exitCode);
    }
}
