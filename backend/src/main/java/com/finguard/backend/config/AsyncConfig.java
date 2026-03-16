package com.finguard.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/*
 * AsyncConfig
 * -----------
 * Enables @Async support and sets up a dedicated thread pool
 * specifically for CSV processing jobs.
 *
 * Why not just use @Async without this?
 * --------------------------------------
 * By default, Spring's @Async uses a SimpleAsyncTaskExecutor which
 * creates a new thread for EVERY single call — no thread reuse,
 * no limits. Under load (many users uploading at once), this would
 * spin up hundreds of threads and crash the server.
 *
 * Our ThreadPoolTaskExecutor is controlled and efficient:
 *   - Max 5 threads for CSV work
 *   - Queue up to 10 jobs if all threads are busy
 *   - Named threads → easy to find in logs ("csv-async-1", etc.)
 *
 * @EnableAsync → tells Spring to look for @Async annotations
 *               and execute those methods on a separate thread.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "csvTaskExecutor")
    public Executor csvTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        /*
         * corePoolSize = 2
         * Always keep 2 threads alive, even when idle.
         * Good default for a small project — won't waste server resources.
         */
        executor.setCorePoolSize(2);

        /*
         * maxPoolSize = 5
         * Spin up extra threads (up to 5 total) if many users
         * upload simultaneously.
         */
        executor.setMaxPoolSize(5);

        /*
         * queueCapacity = 10
         * If all 5 threads are busy, queue up to 10 more jobs.
         * Jobs beyond 10 will be rejected with an error.
         */
        executor.setQueueCapacity(10);

        /*
         * threadNamePrefix = "csv-async-"
         * Threads are named "csv-async-1", "csv-async-2", etc.
         * Makes them easy to identify in logs and thread dumps.
         */
        executor.setThreadNamePrefix("csv-async-");

        executor.initialize();
        return executor;
    }
}