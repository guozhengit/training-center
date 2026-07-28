package com.guoyongzheng.training.web.api;

import com.guoyongzheng.training.web.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard", description = "Health, catalog, and matrix report endpoints")
@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "Health check", description = "Returns service status and matrix report availability.")
    @GetMapping("/health")
    public DashboardService.HealthResponse health() {
        return dashboardService.health();
    }

    @Operation(summary = "Catalog summary", description = "Returns question counts grouped by track.")
    @GetMapping("/catalog/summary")
    public DashboardService.CatalogSummaryResponse catalogSummary() {
        return dashboardService.catalogSummary();
    }

    @Operation(summary = "List questions", description = "Returns questions filtered by track with a configurable limit.")
    @GetMapping("/questions")
    public DashboardService.QuestionListResponse questions(
            @RequestParam(name = "track", required = false) String track,
            @RequestParam(name = "limit", defaultValue = "40") int limit) {
        return dashboardService.questions(track, limit);
    }

    @Operation(summary = "Matrix report", description = "Returns the starter verification matrix report.")
    @GetMapping("/matrix-report")
    public DashboardService.MatrixReportResponse matrixReport() {
        return dashboardService.matrixReport();
    }

    @Operation(summary = "Question content", description = "Returns the markdown description and starter code for a question.")
    @GetMapping("/questions/{questionId}/content")
    public DashboardService.QuestionContentResponse questionContent(
            @org.springframework.web.bind.annotation.PathVariable("questionId") String questionId) {
        return dashboardService.questionContent(questionId);
    }
}
