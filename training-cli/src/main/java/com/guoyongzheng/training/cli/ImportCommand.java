package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.imports.QuestionImporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Imports questions from a batch JSON file to extend the catalog. */
@Command(
        name = "import",
        mixinStandardHelpOptions = true,
        description = "Import questions from a batch JSON file (coding/oral/project)."
)
public final class ImportCommand implements Callable<Integer> {

    @Option(names = {"-w", "--workspace"}, description = "Workspace root directory.", required = true)
    private Path workspace;

    @Parameters(index = "0", paramLabel = "IMPORT_FILE",
            description = "Batch JSON import file with a 'questions' array.")
    private Path importFile;

    @Option(names = "--dry-run", description = "Validate only, write nothing.")
    private boolean dryRun;

    @Option(names = "--force", description = "Overwrite existing questions with the same id.")
    private boolean force;

    @Override
    public Integer call() {
        try {
            QuestionImporter.ImportResult result = new QuestionImporter()
                    .importBatch(workspace.toAbsolutePath().normalize(), importFile.toAbsolutePath().normalize(),
                            dryRun, force);
            if (dryRun) {
                System.out.printf("校验通过 (%d 道题), --dry-run 未写入任何文件%n", result.importedIds().size());
                for (String id : result.importedIds()) {
                    System.out.println("  " + id);
                }
                return 0;
            }
            System.out.printf("导入完成: %d 道题, 写入 %d 个文件%n",
                    result.importedIds().size(), result.writtenFiles());
            for (String id : result.importedIds()) {
                System.out.println("  " + id);
            }
            System.out.println("索引: " + result.questionsIndex());
            System.out.println("映射: " + result.mappingIndex());
            System.out.println("提示: 提交 config/ 变更并同步 output/ 内容到部署环境后, 用 update.sh 生效。");
            return 0;
        } catch (Exception exception) {
            System.err.println("Import failed: " + exception.getMessage());
            return 1;
        }
    }
}
