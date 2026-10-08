package com.tradevision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Signals application shutdown so that scheduled and asynchronous trading work stops starting
 * new cycles while letting work already in progress finish. server.shutdown=graceful stops
 * Tomcat from accepting new HTTP requests, and autoTradeExecutor waits for in-flight async tasks
 * to finish, but neither of those prevents the @Scheduled reconciliation loop from starting a new
 * cycle, or a signal evaluation not yet submitted to the executor from beginning. This class is
 * the explicit "stop initiating new work" signal, checked at AutoTradeService.evaluateSignal
 * before evaluating any new incoming signal, and at PositionMonitorService's scheduled
 * reconciliation before starting a new cycle.
 *
 * This does not interrupt work already in progress — an evaluation or reconciliation cycle
 * already running finishes normally, mirroring the behavior server.shutdown=graceful provides
 * for in-flight HTTP requests. It only prevents new work from starting once shutdown has begun.
 * Closing the WebSocket listener itself on shutdown is a separate concern, owned by
 * BinanceUserDataStreamService's own lifecycle.
 *
 * Implemented as a SmartLifecycle rather than a @PreDestroy method, and deliberately: @PreDestroy
 * callbacks run during AbstractApplicationContext#destroyBeans(), which Spring's shutdown
 * sequence invokes only after every Lifecycle/SmartLifecycle bean's stop() has already run. A
 * @PreDestroy-based flag flip would therefore happen strictly later than the scheduler
 * infrastructure's own shutdown (SchedulingConfig's ThreadPoolTaskScheduler beans, themselves
 * Lifecycle-managed), leaving a window where a new scheduled cycle could still be dispatched and
 * see a stale "not shutting down" flag. Implementing SmartLifecycle moves the flag flip into
 * stop(), which the lifecycle processor invokes before any @PreDestroy method runs, and
 * getPhase() returns Integer.MAX_VALUE so this flips before any other Lifecycle bean at the
 * default phase 0 — SmartLifecycle's documented stop ordering runs the highest phase first.
 */
@Component
public class ShutdownState implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShutdownState.class);
    private volatile boolean shuttingDown = false;
    private volatile boolean running = false;

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        shuttingDown = true;
        running = false;
        log.warn("Shutdown initiated — no new signal evaluations or reconciliation cycles will start. "
            + "Work already in progress will still run to completion.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Highest phase stops first under SmartLifecycle's contract, so this flag flips before
        // the scheduler infrastructure (or anything else at the default phase 0) has its own
        // stop() called — a scheduled method's isShuttingDown() check never reads a stale
        // "false" during shutdown.
        return Integer.MAX_VALUE;
    }
}
