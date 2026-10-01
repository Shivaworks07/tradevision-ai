package com.tradevision.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * P0-7 fix ("Dedicated scheduler pool" -- external review, confirmed real by direct inspection:
 * this application's only scheduling wiring was a bare @EnableScheduling on
 * TradeVisionApplication, with no TaskScheduler bean of its own anywhere in the codebase.
 * Spring Boot's own TaskSchedulingAutoConfiguration then supplies the default -- a
 * ThreadPoolTaskScheduler with pool size 1 (spring.task.scheduling.pool.size defaults to 1,
 * confirmed against Spring Boot's own TaskSchedulingProperties) -- meaning every single
 * @Scheduled method in this application (reconciliation, autonomous scanning, kline/user-data
 * stream polling, stuck-order/signal recovery, incident retry, ML call-result updates) shared
 * ONE thread. A slow autonomous scan (a real network-bound cycle touching every enabled symbol
 * across every credential) or a slow kline poll could -- and in production, eventually would --
 * delay PositionMonitorService's own reconciliation loop, the one @Scheduled job in this
 * codebase actually responsible for detecting and fixing unprotected/naked positions. A blocked
 * reconciliation cycle is not a performance nuisance here, it's a safety gap: every fix this
 * session made to reconciliation (P0-1 through P0-5) is worthless if reconciliation itself can
 * be starved of CPU time by an unrelated, lower-priority scheduled job sharing its only thread.
 *
 * The actual fix: three separate, dedicated TaskScheduler beans, each with its own bounded pool
 * and its own thread-name prefix (so a thread dump immediately shows which class of scheduled
 * work is running/stuck) -- referenced explicitly via @Scheduled(scheduler = "...") at every
 * call site (see each @Scheduled method's own updated annotation), rather than a single larger
 * shared pool. A single bigger pool would already fix the specific starvation scenario above (a
 * large enough pool means every job gets its own thread in practice), but a genuinely runaway
 * scan or stream-poll task could still, in principle, exhaust a shared pool's queue and delay
 * reconciliation behind it. Separate pools make that structurally impossible: reconciliation's
 * own pool can never be blocked by anything running in the scan or maintenance pool, because
 * they are different threads entirely, not just different queue positions in the same pool.
 *
 *  - reconciliationScheduler: PositionMonitorService's own reconciliation loop ONLY -- the one
 *    scheduled job this application treats as safety-critical. A pool size of 2 (not 1) so a
 *    still-running reconciliation cycle for one credential can never delay this application's
 *    own periodic health/shutdown-related scheduled work from this same pool in the future,
 *    though today it is the sole occupant.
 *  - scanScheduler: the market-data-heavy, naturally bursty jobs -- autonomous signal scanning,
 *    the 1-minute kline stream's own polling fallback, and the Binance user-data-stream
 *    keepalive/reconnect checks. Sized for the current three occupants plus real headroom.
 *  - maintenanceScheduler: the lower-frequency, non-time-critical recovery/bookkeeping jobs --
 *    stuck-order/stuck-signal recovery, incident retry, and ML call-result updates. These can
 *    tolerate being queued behind each other far more readily than reconciliation or live
 *    scanning can, so they share the smallest dedicated pool.
 *
 * Every pool is named explicitly (setThreadNamePrefix) for the same reason AsyncConfig's own
 * executors already are -- a thread dump or profiler should never require guessing which
 * @Scheduled method a given thread belongs to.
 */
@Configuration
public class SchedulingConfig {

    @Bean("reconciliationScheduler")
    public TaskScheduler reconciliationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("reconcile-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Bean("scanScheduler")
    public TaskScheduler scanScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("scan-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Bean("maintenanceScheduler")
    public TaskScheduler maintenanceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("maint-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * P1-18 fix ("WebSocket listener does blocking reconciliation on the socket thread" --
     * confirmed real, full context in BinanceUserDataStreamService.Listener.onText's own
     * updated comment): a dedicated pool for the debounced reconciliation BinanceUserDataStreamService
     * now dispatches to instead of calling PositionMonitorService.reconcileCredential()
     * synchronously on the WebSocket's own receive thread. Deliberately separate from every
     * other pool above -- reusing reconciliationScheduler specifically would reintroduce a
     * milder version of the exact problem this fix closes (a burst of WS-triggered dispatches
     * competing with, and potentially delaying, the periodic 60s reconciliation sweep that pool
     * exists to protect). Small (3 threads): this pool's own job is a cheap `schedule()` call
     * onto a short-delay debounce timer, not the reconciliation work itself, which still runs
     * on one of these threads but briefly and one credential at a time per burst (the debounce
     * in Listener.scheduleDebouncedReconcile collapses a burst into a single dispatch).
     */
    @Bean("wsReconcileDispatchScheduler")
    public TaskScheduler wsReconcileDispatchScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("ws-reconcile-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * A named "taskScheduler" bean is still supplied as a safety net -- Spring Boot's own
     * scheduling infrastructure looks for a bean of this exact name (or type TaskScheduler) when
     * a @Scheduled method does NOT specify scheduler=..., and without ANY TaskScheduler bean
     * present at all, adding these three dedicated ones would actually make Spring Boot's own
     * auto-configuration back off entirely (it only supplies its own default when no
     * user-defined TaskScheduler exists), leaving any @Scheduled method that forgets to specify
     * a scheduler with nothing to run on. A small pool (not size 1) so even this fallback path
     * doesn't reintroduce the single-thread bottleneck this whole fix exists to close.
     */
    @Bean("taskScheduler")
    public TaskScheduler defaultTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("default-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
