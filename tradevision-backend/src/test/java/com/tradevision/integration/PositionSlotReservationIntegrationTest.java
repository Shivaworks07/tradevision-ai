package com.tradevision.integration;

import com.tradevision.service.PositionSlotReservationService;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding (P1/🟠 #11 — "Testing is still the biggest weakness"): the review's exact
 * example was PositionSlotReservationServiceTest/ExposureReservationServiceTest only proving
 * findAndModify() was CALLED with the right arguments — never that two ACTUAL concurrent Mongo
 * operations behave correctly against a REAL MongoDB. This is that proof: a real MongoDB
 * container (via Testcontainers), real concurrent threads, real atomic $inc, checking the
 * database's own actual guarantee rather than a mocked stand-in for it.
 *
 * HONEST LIMITATION, unlike every other test written this session: I cannot run this myself.
 * `docker ps` fails outright in this sandbox — no Docker daemon is available, so I have not
 * executed this test and cannot confirm it passes. Every other test this session was verified by
 * hand-tracing logic against Mockito, which doesn't need a real runtime to reason about; this one
 * genuinely does. Run `mvn test -Dtest=PositionSlotReservationIntegrationTest` on a machine with
 * Docker available to actually confirm this passes before trusting it.
 */
@Testcontainers(disabledWithoutDocker = true)
// P1-16 fix: spring.profiles.active now defaults to "prod" (fail-closed), which has no default
// secrets at all -- without this, this Testcontainers-backed context would fail to start
// outside a real deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, the
// same secrets this test always implicitly relied on before that default changed.
@ActiveProfiles("local")
@SpringBootTest
class PositionSlotReservationIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private PositionSlotReservationService slotReservationService;

    @Test
    @DisplayName("reserve: against a REAL MongoDB, exactly maxAllowed concurrent threads succeed when many more race for the same credential's slots — not just that findAndModify was called")
    void concurrentReserve_realMongo_enforcesExactCap() throws InterruptedException {
        String credentialId = "integration-test-cred-" + System.currentTimeMillis();
        int maxAllowed = 3;
        int concurrentAttempts = 20; // deliberately far more than maxAllowed

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(concurrentAttempts);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentAttempts; i++) {
            executor.submit(() -> {
                try {
                    startLine.await(); // all threads fire as close to simultaneously as possible
                    // Review finding ("Position slot reservations still don't have ownership
                    // IDs" -- external review, twenty-eighth pass, P0, full context in
                    // PositionSlotReservationRecord's own class javadoc): reserve() now returns
                    // SlotReserveResult, not a bare boolean.
                    if (slotReservationService.reserve(credentialId, maxAllowed).reserved()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    allDone.countDown();
                }
            });
        }

        startLine.countDown(); // release all 20 threads at once
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // The actual claim under test: no matter how many threads race for it simultaneously,
        // MongoDB's own atomicity means EXACTLY maxAllowed can ever succeed — not "probably", not
        // "usually", exactly. A mocked test can assert the code CALLS findAndModify correctly;
        // only a real database can prove findAndModify itself actually serializes these threads.
        assertThat(successCount.get()).isEqualTo(maxAllowed);
    }
}
