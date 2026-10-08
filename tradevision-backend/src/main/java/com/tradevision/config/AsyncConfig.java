package com.tradevision.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Supplies bounded thread pools for the application's @Async work, in place of the JDK default
 * SimpleAsyncTaskExecutor that plain @EnableAsync falls back to — that executor creates one new
 * unbounded thread per task with no queue and no ceiling, which degrades the application under
 * load.
 */
@Configuration
public class AsyncConfig {

    /**
     * Dedicated pool for evaluating incoming trading signals (AutoTradeService.evaluateSignal).
     * Uses AbortPolicy on saturation, explicitly set rather than left to the executor's default:
     * a trading signal is not disposable, so silently discarding one under load would be strictly
     * worse than a loud, immediate RejectedExecutionException. Callers that submit to this pool
     * (TradeCallService.saveCall, AutoTradeRecoveryService.recoverStuckSignals) catch that
     * exception explicitly and rely on the existing 2-minute recovery sweep to retry the signal
     * once the pool has capacity again, rather than losing it.
     */
    @Bean("autoTradeExecutor")
    public Executor autoTradeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("auto-trade-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for persisting API metrics off the request-handling thread, separate from
     * autoTradeExecutor so metrics writes never compete with trading work. Metrics are
     * high-volume but low-priority, so this uses DiscardPolicy: if the queue is ever full, extra
     * metrics are silently dropped rather than blocking the caller or throwing, which would be
     * disproportionate for a side-channel that exists purely for observability.
     */
    @Bean("metricsExecutor")
    public Executor metricsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("api-metrics-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false); // metrics are disposable — don't delay shutdown for them
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for the historical-klines fetch used to backfill commission conversion
     * rates for accounting accuracy. This work must never share autoTradeExecutor's pool, which
     * would mean competing with order-placement work, nor run on the calling thread, which would
     * delay OCO placement while a position sits unprotected. Same disposable-side-channel
     * reasoning as metricsExecutor, but under its own pool since this has its own retry/failure
     * semantics.
     */
    @Bean("commissionBackfillExecutor")
    public Executor commissionBackfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("commission-backfill-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false); // accounting backfill is disposable — don't delay shutdown for it
        executor.initialize();
        return executor;
    }
}
