package com.guoyongzheng.training.web.api;

import com.guoyongzheng.training.web.service.JudgeProgressEvent;
import com.guoyongzheng.training.web.service.JudgeService;
import com.guoyongzheng.training.web.service.SessionQueryService;
import com.guoyongzheng.training.web.service.StarterHintService;
import com.guoyongzheng.training.web.service.TrainingSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "Training", description = "Session lifecycle, attempt submission, and judging")
@RestController
@RequestMapping("/api/training")
public class TrainingController {
    private final TrainingSessionService trainingSessionService;
    private final SessionQueryService sessionQueryService;
    private final JudgeService judgeService;
    private final StarterHintService starterHintService;
    private final ExecutorService judgeExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "judge-sse");
        thread.setDaemon(true);
        return thread;
    });

    public TrainingController(TrainingSessionService trainingSessionService,
                              SessionQueryService sessionQueryService,
                              JudgeService judgeService,
                              StarterHintService starterHintService) {
        this.trainingSessionService = trainingSessionService;
        this.sessionQueryService = sessionQueryService;
        this.judgeService = judgeService;
        this.starterHintService = starterHintService;
    }

    @Operation(summary = "Training statistics", description = "Aggregated attempt counts and pass rates.")
    @GetMapping("/stats")
    public TrainingSessionService.TrainingStatsResponse stats() {
        return sessionQueryService.stats();
    }

    @Operation(summary = "Session history", description = "Recent training sessions with attempt summaries.")
    @GetMapping("/history")
    public TrainingSessionService.TrainingHistoryResponse history(
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        return sessionQueryService.history(limit);
    }

    @Operation(summary = "Create session", description = "Starts a new training session with selected questions.")
    @PostMapping("/sessions")
    public TrainingSessionService.TrainingSessionResponse createSession(
            @RequestBody TrainingSessionService.CreateSessionRequest request) {
        return trainingSessionService.createSession(request);
    }

    @Operation(summary = "Get session", description = "Retrieves a session with all its attempts.")
    @GetMapping("/sessions/{sessionId}")
    public TrainingSessionService.TrainingSessionResponse session(@PathVariable("sessionId") String sessionId) {
        return sessionQueryService.session(sessionId);
    }

    @Operation(summary = "Submit attempt", description = "Records the user's answer for an attempt.")
    @PostMapping("/attempts/{attemptId}/submit")
    public TrainingSessionService.SubmitAttemptResponse submitAttempt(
            @PathVariable("attemptId") String attemptId,
            @RequestBody TrainingSessionService.SubmitAttemptRequest request) {
        return trainingSessionService.submitAttempt(attemptId, request);
    }

    @Operation(summary = "Judge attempt", description = "Runs the sandbox judge on a submitted attempt.")
    @PostMapping("/attempts/{attemptId}/judge")
    public TrainingSessionService.JudgeAttemptResponse judgeAttempt(@PathVariable("attemptId") String attemptId) {
        return judgeService.judgeAttempt(attemptId);
    }

    @Operation(summary = "Judge attempt (SSE stream)", description = "Streams judge progress stages via Server-Sent Events, ending with the full result.")
    @GetMapping(value = "/attempts/{attemptId}/judge-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter judgeAttemptStream(@PathVariable("attemptId") String attemptId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        judgeExecutor.execute(() -> {
            try {
                TrainingSessionService.JudgeAttemptResponse result =
                        judgeService.judgeAttemptWithProgress(attemptId, event -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("progress")
                                        .data(event));
                            } catch (IOException ignored) {
                                // client disconnected
                            }
                        });
                emitter.send(SseEmitter.event().name("result").data(result));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(new JudgeProgressEvent(
                                    JudgeProgressEvent.Stage.FAILED,
                                    exception.getMessage() != null ? exception.getMessage() : "判题异常")));
                } catch (IOException ignored) {
                    // client disconnected
                }
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    @Operation(summary = "Open sandbox", description = "Provisions and returns the sandbox path for an attempt.")
    @PostMapping("/attempts/{attemptId}/open-sandbox")
    public TrainingSessionService.OpenSandboxResponse openSandbox(@PathVariable("attemptId") String attemptId) {
        return judgeService.openSandbox(attemptId);
    }

    @Operation(summary = "Starter hint", description = "Returns starter source at a progressive hint level (0=signatures, 1=signatures+hints, 2=full).")
    @PostMapping("/attempts/{attemptId}/hint")
    public StarterHintService.HintResponse hint(
            @PathVariable("attemptId") String attemptId,
            @RequestBody HintRequest request) {
        return starterHintService.reveal(request.source(), request.level());
    }

    public record HintRequest(String source, int level) {
    }
}
