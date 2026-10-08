package com.tradevision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync backs AutoTradeService's @Async evaluateSignal() method, moving Binance calls
// (which can take seconds, especially under retry/backoff) off the HTTP request thread handling
// POST /api/calls/save.
//
// @EnableScheduling itself lives on SchedulingConfig's @ConditionalOnProperty-gated
// SchedulingEnablerConfig rather than directly on this class, so that app.scheduling.enabled
// (default true everywhere, set false only in the test JVM via tradevision-backend/pom.xml's
// Surefire configuration) can turn off real @Scheduled timer firing for Spring-context-backed
// tests specifically, without touching scheduling for any real deployment profile. This matters
// because each Testcontainers-backed integration test in this codebase points at its own
// short-lived Mongo container, and Spring's test ApplicationContext cache does not necessarily
// close a test class's context the moment its container is torn down — so with scheduling always
// on, an earlier test's still-live context could keep firing reconciliation/scanning/maintenance
// jobs against an already-stopped Mongo container for the rest of the suite. None of the
// integration tests rely on a @Scheduled method actually firing on its own timer; each one calls
// the production method under test directly, so disabling the real timer in tests changes
// nothing about what any test exercises or asserts.
@SpringBootApplication
@EnableAsync
public class TradeVisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeVisionApplication.class, args);
    }
}
