package com.guoyongzheng.training.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 共享诊断线程池（Spring 托管 → executor_* 指标自动暴露到 /actuator/prometheus）。
 *
 * 教学点：Executors.newFixedThreadPool 自建池是监控盲区——不出现在 executor_* 指标里，
 * 生产环境所有业务线程池都应交给 Spring 管理或手动绑定 Micrometer，
 * 否则"线程池耗尽/队列堆积"故障在监控上看不到。
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(name = "diagThreadPool")
    public ThreadPoolTaskExecutor diagThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        // 无界队列：复现 17 无界队列堆积（任务产生速度 > 消费速度时队列无限涨）
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("diag-pool-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(0);
        executor.initialize();
        return executor;
    }
}
