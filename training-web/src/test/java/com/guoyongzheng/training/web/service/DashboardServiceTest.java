package com.guoyongzheng.training.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoyongzheng.training.web.config.TrainingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardServiceTest {
    private final WorkspaceLocator workspaceLocator =
            new WorkspaceLocator(new TrainingProperties("", null, null, null));
    private final DashboardService service = new DashboardService(
            workspaceLocator, new CatalogCache(workspaceLocator), new ObjectMapper());

    @Test
    void summarizesUnifiedTrainingCatalog() {
        DashboardService.CatalogSummaryResponse summary = service.catalogSummary();

        assertThat(summary.totalQuestions()).isEqualTo(300);
        assertThat(summary.trackCounts()).containsEntry("CODING", 212L);
        assertThat(summary.trackCounts()).containsEntry("ORAL", 80L);
        assertThat(summary.trackCounts()).containsEntry("PROJECT", 8L);
    }

    @Test
    void exposesTask21MatrixReportSummaryWhenPresent() {
        DashboardService.MatrixReportResponse report = service.matrixReport();

        assertThat(report.available()).isTrue();
        assertThat(report.schemaVersion()).isEqualTo("starter-matrix-report/v1");
        assertThat(report.totalResults()).isEqualTo(360);
        assertThat(report.nonPassResults()).isZero();
        assertThat(report.contract().passed()).isEqualTo(120);
        assertThat(report.starterExpectedFailures().passed()).isEqualTo(120);
        assertThat(report.referencePasses().passed()).isEqualTo(120);
    }

    @Test
    void oralContentExposesAnswerFollowUpsAndRecommendedSeconds() {
        DashboardService.QuestionContentResponse content = service.questionContent("O001");

        assertThat(content.recommendedSeconds()).isEqualTo(90);
        assertThat(content.answer()).isNotBlank();
        assertThat(content.answer()).contains("HashMap");
        assertThat(content.followUps()).isNotBlank();
        assertThat(content.sections()).isNotEmpty();
        assertThat(content.sections().get(0).heading()).isEqualTo("完整口述稿");
    }

    @Test
    void odContentExposesSolutionApproachAsAnswer() {
        DashboardService.QuestionContentResponse content = service.questionContent("OD001");

        assertThat(content.answer()).isNotBlank();
        assertThat(content.answer()).contains("大小写");
        assertThat(content.answer()).doesNotContain("## 解题思路");
    }

    @Test
    void codingContentWithoutSolutionApproachHasNullAnswer() {
        DashboardService.QuestionContentResponse content = service.questionContent("B001");

        assertThat(content.answer()).isNull();
        assertThat(content.starterCode()).isNotBlank();
    }

    @Test
    void projectContentExposesStructuredSectionsAndEvidenceBoundary() {
        DashboardService.QuestionContentResponse content = service.questionContent("P001");

        assertThat(content.recommendedSeconds()).isEqualTo(120);
        assertThat(content.sections()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(content.evidenceEntry()).isNotBlank();
        assertThat(content.evidenceEntry()).contains("ContractServiceImpl");
        assertThat(content.factBoundary()).isNotBlank();
    }
}
