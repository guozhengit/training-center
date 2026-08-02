package com.guoyongzheng.training.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once after application startup: recovers judgements left dangling in
 * RUNNING by a previous crash, then sweeps sandboxes that outlived their
 * retention window.
 */
@Component
public class JudgeStartupRecoveryRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(JudgeStartupRecoveryRunner.class);

    private final JudgeService judgeService;

    public JudgeStartupRecoveryRunner(JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[Startup] recovering stale judgements and sweeping stale sandboxes");
        judgeService.recoverStaleJudgements();
        judgeService.sweepStaleSandboxes();
    }
}
