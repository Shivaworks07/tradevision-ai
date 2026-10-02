package com.tradevision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// Review item #10: @EnableAsync backs AutoTradeService's @Async evaluateSignal() method —
// moves Binance calls (which can take seconds, especially under retry/backoff) off the HTTP
// request thread handling POST /api/calls/save.
//
// CI-review fix ("MongoDB connection-refused errors from scheduled background reconciliation
// tasks" -- external review, GitHub Actions integration-test failures, "Additional issue"):
// @EnableScheduling used to live directly on this class, meaning EVERY Spring context this
// application ever boots -- including each of the ~13 separate Testcontainers-backed
// @SpringBootTest integration-test classes in this codebase, each pointed at its own short-lived
// Mongo container -- started the real reconciliation/scanning/maintenance @Scheduled jobs
// (PositionMonitorService's 30s-initial-delay reconciliation loop chief among them). Spring's
// test ApplicationContext cache does not necessarily close a test class's context the moment
// that test class's own @Container Mongo instance is torn down (the Testcontainers JUnit
// extension stops the container when the test class finishes, independently of whether Spring
// decides to evict/close the matching context yet) -- so a still-live context's scheduled jobs
// kept firing against an already-stopped Mongo container for the remainder of the suite,
// producing exactly the repeated MongoDB connection-refused log noise this review flagged.
// Real-world confirmation this is the actual mechanism, not a guess: PositionMonitorService's
// own reconciliation loop (initialDelay=30_000, fixedDelay=60_000) and CallResultUpdater's
// (fixedDelay=300_000) are both well within how long a serial 13-class integration-test run
// takes end to end, so later test classes' contexts booting and running are exactly the window
// in which an earlier class's now-headless scheduled jobs would still be mid-cycle.
//
// The fix is scoped to test lifecycle ONLY, never to production scheduling cadence or
// conditions: @EnableScheduling moved off this always-active class and onto a new
// @ConditionalOnProperty-gated SchedulingEnablerConfig (see that class's own javadoc) that
// defaults to enabled (matchIfMissing = true) everywhere -- a real deployment, `java -jar`, or a
// developer's own "local" profile run behaves identically to before, scheduling included. Only
// src/test/resources/application.properties (packaged solely on the TEST classpath, never into
// the production jar) sets app.scheduling.enabled=false, so every Spring-context-backed test in
// this codebase -- unit or integration, whatever profile it activates -- now boots with
// scheduling off by default. None of the integration tests this review is about rely on a
// @Scheduled method actually firing on its own timer; each one calls the production method
// under test directly (e.g. positionMonitorService.createPositionForLateDiscoveredFill(...),
// positionMonitorService.recoverStuckProtectionAttempts(...)) rather than waiting for the
// scheduler to invoke it, so disabling the real timer changes nothing about what any existing
// test actually exercises or asserts.
@SpringBootApplication
@EnableAsync
public class TradeVisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeVisionApplication.class, args);
    }
}
