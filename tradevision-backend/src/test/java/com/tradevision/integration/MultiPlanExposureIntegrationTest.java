package com.tradevision.integration;

import com.tradevision.service.ExposureReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Multiple plans can duplicate exposure" -- external review, ninth pass,
 * required test: "3 plans, same symbol, same direction, different timeframe -> risk engine ->
 * actual combined exposure should never exceed account limits"): this is that exact test.
 *
 * Confirmed by direct inspection before writing this test, not assumed: AutoTradeService calls
 * ExposureReservationService.reserve(credentialId, symbol, ...) -- keyed by credentialId and
 * symbol ALONE, with no planId anywhere in that key. This means the architecture already gives
 * the right answer for the review's own named scenario: three different strategy plans (however
 * many, whatever their own individual timeframes) all attempting to buy the same symbol under
 * the same credential all atomically share the exact same exposure counter. There is no
 * per-plan exposure bucket a plan could exploit to bypass the account-level total -- the plan
 * identity is architecturally irrelevant to this specific cap, by construction, not by a check
 * that could be forgotten.
 *
 * This is exactly the kind of atomic-concurrency claim that needs a real database to prove --
 * Mockito can only confirm the code calls the right method with the right arguments, not that
 * MongoDB's own atomic findAndModify genuinely serializes three concurrent reservations against
 * the same document correctly.
 *
 * HONEST LIMITATION, same as every other integration test in this package: `docker ps` fails
 * outright in this sandbox -- no Docker daemon is available here, so I have not executed this
 * test and cannot confirm it passes. Run
 * `mvn test -Dtest=MultiPlanExposureIntegrationTest` on a machine with Docker available to
 * actually confirm this before trusting it.
 */
@Testcontainers(disabledWithoutDocker = true)
// P1-16 fix: spring.profiles.active now defaults to "prod" (fail-closed), which has no default
// secrets at all -- without this, this Testcontainers-backed context would fail to start
// outside a real deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, the
// same secrets this test always implicitly relied on before that default changed.
@ActiveProfiles("local")
@SpringBootTest
class MultiPlanExposureIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private ExposureReservationService exposureReservationService;

    @Test
    @DisplayName("reserve: against a REAL MongoDB, three concurrent reservations for the SAME symbol under the SAME credential (simulating Plan A/B/C -- 1m/5m/15m -- all independently deciding to buy SOL at once) never let combined exposure exceed the account-level total cap, even though nothing in the reservation call itself knows or cares which plan initiated each one")
    void threeConcurrentPlansSameSymbol_combinedExposureNeverExceedsAccountCap() throws InterruptedException {
        String credentialId = "integration-test-exposure-" + System.currentTimeMillis();
        String symbol = "SOLUSDT";
        BigDecimal maxTotal = BigDecimal.valueOf(1000); // account-level total exposure cap
        BigDecimal maxSymbol = BigDecimal.valueOf(1000); // no separate per-symbol cap tighter than total, for this test
        BigDecimal perAttemptValue = BigDecimal.valueOf(400); // three attempts of 400 = 1200, which must NOT all succeed against a 1000 cap

        int attempts = 3; // Plan A (1m), Plan B (5m), Plan C (15m) -- the review's own exact scenario
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(attempts);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    startLine.await(); // all three "plans" fire as close to simultaneously as possible
                    var result = exposureReservationService.reserve(credentialId, symbol, perAttemptValue, maxTotal, maxSymbol);
                    if (result.allowed()) successCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    allDone.countDown();
                }
            });
        }

        startLine.countDown();
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // The actual claim under test: with a 1000 cap and three concurrent 400-unit attempts
        // (1200 combined if all succeeded), at most 2 can ever be accepted (800 total) -- the
        // third MUST be rejected, no matter which "plan" it came from, because the reservation
        // itself has no concept of plan identity at all, only credentialId+symbol.
        assertThat(successCount.get()).isEqualTo(2);

        // Confirm the actual persisted total never exceeds the cap, directly against the real
        // database -- not inferred from the success count alone.
        var afterAllAttempts = exposureReservationService.reserve(credentialId, symbol, BigDecimal.valueOf(201), maxTotal, maxSymbol);
        assertThat(afterAllAttempts.allowed()).isFalse(); // 800 + 201 = 1001 > 1000 -- must still be rejected
        var exactRemainder = exposureReservationService.reserve(credentialId, symbol, BigDecimal.valueOf(200), maxTotal, maxSymbol);
        assertThat(exactRemainder.allowed()).isTrue(); // 800 + 200 = 1000, exactly at the cap -- must be allowed
    }
}
