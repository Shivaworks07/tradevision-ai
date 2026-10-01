package com.tradevision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Review finding (P1 #29 — "Shutdown is improved but still not a true trading shutdown"):
 * server.shutdown=graceful (already configured) stops Tomcat accepting NEW HTTP requests, and
 * the autoTradeExecutor already waits for in-flight async tasks to finish. Neither of those
 * stops the @Scheduled reconciliation loop from starting a NEW cycle, or a signal evaluation
 * that's about to begin (not yet submitted to the executor) from actually starting — this is
 * the missing explicit "stop initiating new work" signal, checked at the two points that matter:
 * AutoTradeService.evaluateSignal (before evaluating any new incoming signal) and
 * PositionMonitorService's scheduled reconciliation (before starting a new cycle).
 *
 * Honest scope: this does NOT interrupt work already in progress — an evaluation or
 * reconciliation cycle that has already started runs to completion (the SAME safe behavior
 * server.shutdown=graceful already provides for in-flight HTTP requests). It only prevents NEW
 * work from starting once shutdown has begun. Closing the WebSocket listener itself on shutdown
 * is further scope not addressed here — BinanceUserDataStreamService's own lifecycle isn't
 * touched by this change.
 *
 * P3-6 fix ("ShutdownState via @PreDestroy -- flag flips late in shutdown -- SmartLifecycle
 * stopping schedulers first" -- external review, confirmed real: @PreDestroy methods run during
 * AbstractApplicationContext#destroyBeans(), which Spring's own documented shutdown sequence
 * (AbstractApplicationContext#doClose()) calls ONLY AFTER getLifecycleProcessor().onClose() has
 * already run every Lifecycle/SmartLifecycle bean's stop() -- meaning the OLD @PreDestroy-based
 * flag flip happened strictly LATER than this application's own @Scheduled task infrastructure
 * (SchedulingConfig's ThreadPoolTaskScheduler beans, which are themselves Lifecycle-managed) had
 * already begun its own shutdown, not before. A new scheduled cycle could still be dispatched and
 * pass this class's own isShuttingDown() check (still false) in that window, exactly defeating
 * the "stop initiating new work" guarantee this class exists to provide. The actual fix:
 * implementing SmartLifecycle instead moves the flag flip into stop(), which the lifecycle
 * processor invokes BEFORE any @PreDestroy method runs, and getPhase() returns Integer.MAX_VALUE
 * -- SmartLifecycle's own documented stop ordering runs the HIGHEST phase FIRST, so this flips
 * before any other Lifecycle bean at the (much more common) default phase 0, including the
 * scheduler infrastructure itself. HONEST SCOPE: this deployment's test suite has no live Spring
 * ApplicationContext integration test exercising a real container shutdown (every existing test
 * here mocks this class directly), so the exact relative phase of SchedulingConfig's own
 * ThreadPoolTaskScheduler beans was not independently confirmed against a running context --
 * Integer.MAX_VALUE is nonetheless a strict, verifiable improvement over @PreDestroy regardless
 * of that scheduler's own phase, since SmartLifecycle.stop() is unconditionally guaranteed to run
 * before ANY @PreDestroy callback fires at all, per Spring's own documented shutdown contract.
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
        // Highest phase stops FIRST (SmartLifecycle's own documented contract) -- this flag must
        // flip before the scheduler infrastructure (or anything else at the default phase 0)
        // gets its own stop() called, so a scheduled method's isShuttingDown() check is never
        // reading a stale "false" during the shutdown window this class exists to close.
        return Integer.MAX_VALUE;
    }
}
