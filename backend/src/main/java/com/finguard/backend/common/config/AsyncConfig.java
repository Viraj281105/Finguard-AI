package com.finguard.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Bounded thread pool for statement imports. Without it, {@code @Async} would spawn an
 * unbounded thread per call. When the pool and queue are full, submission is rejected
 * (see {@code ImportService}) rather than silently piling up work.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String IMPORT_EXECUTOR = "importTaskExecutor";

    @Bean(name = IMPORT_EXECUTOR)
    public Executor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("import-");
        executor.initialize();
        return executor;
    }
}
