package com.tradevision.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Supplies separate, dedicated TaskScheduler pools for this application's @Scheduled work,
 * instead of relying on Spring Boot's default single-thread ThreadPoolTaskScheduler (pool size
 * 1). With everything sharing one thread, a slow network-bound job — an autonomous scan touching
 * every enabled symbol across every credential, or a kline poll — could delay
 * PositionMonitorService's reconciliation loop, the job responsible for detecting and fixing
 * unprotected/naked positions. A blocked reconciliation cycle is a safety gap, not just a
 * performance issue, so reconciliation gets its own pool that cannot be starved by unrelated
 * scheduled work.
 *
 * Each @Scheduled method references one of these pools explicitly via
 * @Scheduled(scheduler = "..."), and each pool has its own thread-name prefix so a thread dump
 * immediately shows which class of scheduled work is running or stuck. Separate pools (rather
 * than one larger shared pool) make cross-job starvation structurally impossible: reconciliation
 * runs on threads that scan or maintenance work can never occupy.
 *
 *  - reconciliationScheduler: PositionMonitorService's reconciliation loop only — the one
 *    scheduled job this application treats as safety-critical. Pool size 2, leaving headroom for
 *    future periodic health/shutdown-related work on the same pool.
 *  - scanScheduler: the market-data-heavy, naturally bursty jobs — autonomous signal scanning,
 *    the 1-minute kline stream's polling fallback, and Binance user-data-stream
 *    keepalive/reconnect checks. Sized for its three occupants plus headroom.
 *  - maintenanceScheduler: lower-frequency, non-time-critical recovery and bookkeeping jobs —
 *    stuck-order/stuck-signal recovery, incident retry, and ML call-result updates. These
 *    tolerate queuing behind each other, so they share the smallest dedicated pool.
 *
 * Every pool is named explicitly (setThreadNamePrefix), matching AsyncConfig's executors, so a
 * thread dump or profiler never requires guessing which @Scheduled method a thread belongs to.
 *
 * app.scheduling.enabled gates whether @Scheduled timers actually fire (via
 * SchedulingEnablerConfig below): it is set false only in the test JVM by
 * tradevision-backend/pom.xml's Surefire configuration, and defaults to true everywhere else.
 * The TaskScheduler bean definitions in this outer class are deliberately NOT gated by that same
 * property, because BinanceUserDataStreamService constructor-injects wsReconcileDispatchScheduler
 * directly, by type/name, to dispatch debounced reconciliation work from its WebSocket listener
 * thread — an unconditional dependency on the bean existing, independent of whether @Scheduled
 * annotations are being processed. Gating the bean definitions would break that dependency
 * whenever app.scheduling.enabled=false. Idle pools in a test context are a minor resource cost
 * compared to an ApplicationContext that fails to start.
 */
@Configuration
public class SchedulingConfig {

    /**
     * Gates @EnableScheduling behind app.scheduling.enabled (default true) so Surefire can turn
     * off real @Scheduled timer firing specifically for Spring-context-backed tests, without
     * touching scheduling for any real deployment profile or a developer's manual local run.
     * This only controls whether @Scheduled timers fire, not production scheduling cadence or
     * behavior.
     */
    @Configuration
    @ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    public static class SchedulingEnablerConfig {
    }

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
     * Dedicated pool for the debounced reconciliation that BinanceUserDataStreamService
     * dispatches here instead of calling PositionMonitorService.reconcileCredential()
     * synchronously on the WebSocket's own receive thread. Kept separate from
     * reconciliationScheduler so a burst of WS-triggered dispatches can never compete with, or
     * delay, the periodic 60s reconciliation sweep that pool protects. Small (3 threads): this
     * pool mainly schedules a short-delay debounce timer; the reconciliation work itself runs
     * briefly and one credential at a time per burst, since the debounce in
     * Listener.scheduleDebouncedReconcile collapses a burst into a single dispatch.
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
     * Fast-cadence pool for PositionMonitorService.watchExitProtection, which re-runs the
     * stuck-triggered-stop-leg detection and flatten logic every 10 seconds instead of every 60.
     * A fast price move through a resting STOP_LOSS_LIMIT leg's buffer can otherwise sit
     * undetected for up to a minute on the main reconciliation cadence. Kept separate from
     * reconciliationScheduler: this work acquires the same per-credential lock reconciliation
     * uses, so the two never run concurrently for one credential, and giving it its own thread
     * means a slow watchdog pass for one credential never delays the 60s pass's broader
     * reconciliation work for a different credential.
     */
    @Bean("watchdogScheduler")
    public TaskScheduler watchdogScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("watchdog-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Fallback bean named "taskScheduler" for any @Scheduled method that omits scheduler=....
     * Spring Boot's scheduling infrastructure looks for a bean of this exact name (or type
     * TaskScheduler); since defining the dedicated pools above makes Spring Boot's
     * auto-configuration back off from supplying its own default, this bean must exist so such a
     * method still has something to run on. Sized above 1 so this fallback path does not become
     * a single-thread bottleneck on its own.
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
