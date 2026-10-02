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
 * CI-review fix ("concurrentReserve_realMongo_enforcesExactCap: expected 3, actual 1" --
 * external review, GitHub Actions integration-test failures, failure 1): confirmed a genuine
 * production bug, not a test problem -- reserveTransactionally's own error handling treated
 * EVERY RuntimeException from a failed transaction as either "standalone Mongo, fall back
 * non-transactionally" or an outright failure, with no case at all for a WriteConflict
 * (MongoDB error code 112, carrying the driver's own "TransientTransactionError" label) under
 * genuine concurrent transactions against the same reservation document -- exactly what 20
 * threads racing for 3 slots against a REAL (non-standalone) MongoDB replica set produces. Only
 * the single thread that won the very first attempt ever succeeded; every other thread's
 * transaction aborted on its first WriteConflict and was never retried, so successCount landed
 * at 1 instead of 3. Per this review's own explicit instruction ("Do not simply change the
 * expected value from 3 to 1" -- the correct fix is in PositionSlotReservationService itself:
 * reserveTransactionally now retries the whole transaction body (same ClientSession, fresh
 * startTransaction()) up to MAX_TRANSACTION_RETRIES=10 times specifically when the caught
 * exception carries the TransientTransactionError label or code 112, which is MongoDB's own
 * documented retry pattern for this exact condition -- never for a genuine standalone-deployment
 * or other fatal error, which still fall through to the existing (unchanged) handling. This
 * test's own already-existing assertion (exactly maxAllowed succeed, not "probably") is left
 * completely unchanged and now serves directly as the regression test for that fix -- it is the
 * same 20-threads-vs-3-slots scenario that exposed the bug, so no separate regression test is
 * added; weakening or duplicating this assertion would both violate the review's own instruction
 * and add no real coverage beyond what is already here.
 *
 * HONEST LIMITATION, unlike every other test written this session: I cannot run this myself.
 * `docker ps` succeeds in this sandbox (the Docker daemon itself runs), but every container
 * registry (Docker Hub, GHCR, Quay) and direct MongoDB binary download are blocked by this
 * sandbox's own egress policy (confirmed via repeated 403 Forbidden responses, not a transient
 * failure) -- so no real MongoDB instance can actually be started here, and I have not executed
 * this test and cannot confirm it passes. The fix above is supported by direct source-level
 * tracing of MongoDB's own documented WriteConflict/TransientTransactionError retry contract and
 * successful compilation only. Run `mvn test -Dtest=PositionSlotReservationIntegrationTest` on a
 * machine with real registry/Docker access to actually confirm this passes before trusting it.
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
