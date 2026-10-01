package com.tradevision.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Review item #10: a bounded pool for @Async work (specifically AutoTradeService.evaluateSignal),
 * not the JDK default SimpleAsyncTaskExecutor Spring falls back to with plain @EnableAsync —
 * that creates one new unbounded thread per task with no queue and no ceiling, which under load
 * is its own way to degrade the application.
 */
@Configuration
public class AsyncConfig {

    @Bean("autoTradeExecutor")
    public Executor autoTradeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("auto-trade-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Review finding (P1 — "API metrics are not actually zero-impact"): MetricsFilter's own
     * comment claimed "save async — don't block the response", but the write was a plain
     * synchronous metricRepo.save() on the request-handling thread itself — at scale, that's a
     * Mongo write competing for the same thread pool as actual request handling. A dedicated,
     * separate (not the auto-trade) pool: metrics are naturally high-volume but low-priority —
     * losing an occasional metric under heavy load is fine, unlike a trading signal, so this
     * uses DiscardPolicy: if the queue is ever full, extra metrics are silently dropped rather
     * than blocking the caller or throwing, which would be worse for a side-channel that exists
     * purely for observability.
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
     * Review finding ("P&L / commission limitation remains" -- external review, thirteenth pass,
     * full context in FillLedgerService.backfillHistoricalCommissionConversion's own javadoc):
     * this method makes a real network call (a historical klines fetch) purely for accounting
     * accuracy, not trading correctness -- it must never share autoTradeExecutor's own pool
     * (which would mean competing with actual order-placement work) or delay OCO placement on
     * the calling thread (the position is genuinely unprotected until that OCO exists, and this
     * backfill has no bearing on whether it should). A small, dedicated pool, same disposable-
     * side-channel reasoning as metricsExecutor above but under its own name since this is a
     * different concern with its own retry/failure semantics.
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
