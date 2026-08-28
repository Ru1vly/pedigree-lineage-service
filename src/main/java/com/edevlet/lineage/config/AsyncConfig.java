package com.edevlet.lineage.config;

import com.edevlet.lineage.infrastructure.tracing.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-lineage-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Bounded, container-managed scheduler shared by every open SSE progress stream.
     *
     * <p>The streaming endpoint previously called {@code Executors.newSingleThreadExecutor()} per
     * request and never shut it down, so each connection leaked a platform thread and its pool for
     * the lifetime of the JVM - unbounded, and driven directly by client request volume. One
     * shared pool with {@code removeOnCancelPolicy} means a cancelled poll is discarded straight
     * away rather than lingering in the queue, and Spring shuts the pool down with the context.
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler sseProgressScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sse-progress-");
        // No MdcTaskDecorator here: ThreadPoolTaskScheduler only gained setTaskDecorator in
        // Spring Framework 6.2 and this build is on 6.1.x (Boot 3.3.2). Poll logging therefore
        // carries no inherited traceId; the transactionId is on the log line itself.
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
