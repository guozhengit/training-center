package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.core.EnvironmentDoctor;
import com.guoyongzheng.training.core.TrainingPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Checks that the local environment satisfies all training-center prerequisites. */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Verify JDK, Maven, Python, catalog, and runtime directories."
)
public final class DoctorCommand implements Callable<Integer> {

    @Option(names = {"-w", "--workspace"}, description = "Workspace root directory.", required = true)
    private Path workspace;

    @Option(names = "--jdk-home", description = "JDK home directory.", defaultValue = "D:/jdk/jdk-17.0.12")
    private Path jdkHome;

    @Option(names = "--maven-executable", description = "Maven executable path.",
            defaultValue = "D:/Program Files (x86)/apache-maven-3.9.9/bin/mvn.cmd")
    private Path mavenExecutable;

    @Option(names = "--python", description = "Python executable path.", defaultValue = "python")
    private Path pythonExecutable;

    @Override
    public Integer call() {
        Path root = workspace.toAbsolutePath().normalize();
        TrainingPaths paths = new TrainingPaths(
                root,
                root.resolve("training-center/config"),
                root.resolve("training-center/runtime"),
                root.resolve("training-center/runtime/sandboxes"),
                root.resolve("training-center/runtime/exports"),
                root.resolve("training-center/runtime/logs"),
                root.resolve("training-center/runtime/db"),
                jdkHome.toAbsolutePath().normalize(),
                mavenExecutable.toAbsolutePath().normalize(),
                pythonExecutable.toAbsolutePath().normalize());

        var report = EnvironmentDoctor.create().inspect(paths);

        if (report.ready()) {
            System.out.println("Environment OK - all checks passed.");
            return 0;
        }

        System.err.println("Environment check FAILED:");
        for (var issue : report.issues()) {
            System.err.printf("  [%s] %s%n", issue.code(), issue.message());
        }
        return 1;
    }
}
