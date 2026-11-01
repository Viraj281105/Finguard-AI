package com.finguard.backend.common.config;

import com.finguard.backend.common.observability.TraceContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
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
        executor.setTaskDecorator(mdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }

    static TaskDecorator mdcPropagatingTaskDecorator() {
        return runnable -> {
            Map<String, String> parentContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (parentContext != null) {
                        MDC.setContextMap(parentContext);
                        // Mint a child span ID for the async worker while preserving the parent trace_id
                        MDC.put(TraceContext.MDC_SPAN_ID, TraceContext.newSpanId());
                    } else {
                        MDC.clear();
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
