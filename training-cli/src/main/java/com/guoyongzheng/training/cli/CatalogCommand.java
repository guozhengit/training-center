package com.guoyongzheng.training.cli;

import com.guoyongzheng.training.catalog.CatalogLoader;
import com.guoyongzheng.training.catalog.QuestionFilter;
import com.guoyongzheng.training.catalog.TrainingCatalog;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** Lists training questions from the catalog with optional filtering. */
@Command(
        name = "catalog",
        mixinStandardHelpOptions = true,
        description = "List and filter training questions."
)
public final class CatalogCommand implements Callable<Integer> {

    @Option(names = {"-w", "--workspace"}, description = "Workspace root directory.", required = true)
    private Path workspace;

    @Option(names = {"-t", "--track"}, description = "Filter by track: CODING, ORAL, PROJECT.")
    private Track track;

    @Option(names = {"-d", "--difficulty"}, description = "Filter by difficulty.")
    private String difficulty;

    @Option(names = {"-l", "--language"}, description = "Filter by language.")
    private String language;

    @Option(names = {"--topic"}, description = "Filter by topic.")
    private String topic;

    @Option(names = {"--count-only"}, description = "Print only the matching count.")
    private boolean countOnly;

    @Override
    public Integer call() {
        try {
            TrainingCatalog catalog = new CatalogLoader().load(workspace.toAbsolutePath().normalize());
            QuestionFilter filter = new QuestionFilter(track, null, topic, difficulty, language);
            List<QuestionDescriptor> results = catalog.find(filter);

            if (countOnly) {
                System.out.println(results.size());
                return 0;
            }

            System.out.printf("%-6s %-8s %-12s %-40s %s%n", "ID", "TRACK", "DIFFICULTY", "TITLE", "TOPIC");
            System.out.println("-".repeat(100));
            for (QuestionDescriptor question : results) {
                System.out.printf("%-6s %-8s %-12s %-40s %s%n",
                        question.id(),
                        question.track(),
                        question.difficulty(),
                        truncate(question.title(), 40),
                        question.topic());
            }            System.out.printf("%nTotal: %d questions%n", results.size());
            return 0;
        } catch (Exception exception) {
            System.err.println("Catalog load failed: " + exception.getMessage());
            return 1;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
