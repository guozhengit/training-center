package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.web.config.TrainingProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceLocatorTest {
    @Test
    void locatesCurrentRepositoryWorkspaceFromNestedModuleDirectory() {
        Path nested = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        WorkspaceLocator locator = new WorkspaceLocator(new TrainingProperties("", null, null, null, null));
        Path workspace = locator.locateFrom(nested);

        assertThat(workspace.resolve("training-center/config")).isDirectory();
        assertThat(workspace.resolve("output/coding-ai-exam/catalog/questions.json")).isRegularFile();
    }
}
