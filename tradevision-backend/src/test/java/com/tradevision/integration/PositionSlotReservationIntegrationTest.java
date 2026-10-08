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
 * Verifies, against a real MongoDB container (via Testcontainers) rather than a mock, that two
 * actual concurrent Mongo operations behave correctly: real concurrent threads, real atomic
 * $inc, checking the database's own guarantee.
 *
 * When many threads race for a limited number of reservation slots against a real
 * (non-standalone) MongoDB replica set, concurrent transactions against the same reservation
 * document can hit a WriteConflict (MongoDB error code 112, carrying the
 * "TransientTransactionError" label). reserveTransactionally retries the whole transaction body
 * (same ClientSession, fresh startTransaction()) up to MAX_TRANSACTION_RETRIES=10 times when the
 * caught exception carries that label or code, so that exactly maxAllowed threads succeed
 * rather than only the first to win a single attempt; a genuine standalone-deployment or other
 * fatal error still falls through to the existing handling.
 *
 * Requires Docker with real container-registry access; run
 * `mvn test -Dtest=PositionSlotReservationIntegrationTest` on a machine with that access.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at all
// -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class PositionSlotReservationIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
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
                    // reserve() returns a SlotReserveResult carrying the reservation's ownership id.
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

        // No matter how many threads race for it simultaneously, MongoDB's atomicity means
        // exactly maxAllowed can ever succeed.
        assertThat(successCount.get()).isEqualTo(maxAllowed);
    }
}
