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
// a Surefire systemPropertyVariable (set once in tradevision-backend/pom.xml, see its own
// comment there) sets app.scheduling.enabled=false, so every Spring-context-backed test in this
// codebase -- unit or integration, whatever profile it activates -- now boots with scheduling
// off by default. A src/test/resources/application.properties file was tried first and reverted
// after it broke every single integration test's ApplicationContext in a real CI run
// (Testcontainers was never available in the sandbox this was first written in, so the
// regression could not be caught before that real run surfaced it): Spring Boot does not merge
// a test-classpath application.properties with the main one, it loads whichever one the
// classloader resolves first, and target/test-classes precedes target/classes on Maven's test
// classpath -- so that file silently replaced the ENTIRE main application.properties for every
// test, wiping out unrelated properties such as app.jwt.expiration and breaking JwtUtil's
// construction. The pom.xml systemPropertyVariable sidesteps that failure mode entirely: a JVM
// system property never touches classpath resource loading, and Spring's property resolution
// already places it above any properties file. None of the integration tests this review is about rely on a
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
