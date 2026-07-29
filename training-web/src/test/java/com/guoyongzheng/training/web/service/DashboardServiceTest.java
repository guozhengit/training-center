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
}
