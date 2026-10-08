package com.tradevision.integration;

import com.tradevision.service.DistributedLockService;
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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies lock-generation fencing (LockGenerationCounter, the generation-aware renew()
 * overload, per-mutation fencing throughout PositionMonitorService and PositionSafetyService)
 * against a real MongoDB actually serializing concurrent acquisition attempts, following the
 * same pattern as PositionSlotReservationIntegrationTest.
 *
 * Requires Docker (Testcontainers); run
 * `mvn test -Dtest=DistributedLockServiceIntegrationTest` on a machine with Docker available.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at all
// -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class DistributedLockServiceIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private DistributedLockService lockService;

    @Test
    @DisplayName("tryAcquire: against a REAL MongoDB, exactly one of many concurrent threads racing for the same credential's reconciliation lock succeeds -- not just that the insert-based acquisition logic was called correctly, but that MongoDB's own atomicity actually serializes it")
    void concurrentTryAcquire_realMongo_exactlyOneWins() throws InterruptedException {
        String credentialId = "integration-test-lock-" + System.currentTimeMillis();
        int concurrentAttempts = 20;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(concurrentAttempts);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentAttempts; i++) {
            String instanceId = "instance-" + i;
            executor.submit(() -> {
                try {
                    startLine.await(); // all threads fire as close to simultaneously as possible
                    if (lockService.tryAcquire(credentialId, instanceId, Duration.ofSeconds(90))) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    allDone.countDown();
                }
            });
        }

        startLine.countDown();
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // No matter how many instances race for this credential's reconciliation lock
        // simultaneously, MongoDB's atomicity (insert-based acquisition, not a read-then-write
        // check) means exactly one can ever win.
        assertThat(successCount.get()).isEqualTo(1);
    }

    /**
     * Verifies instance-id fencing: a renewal using the plain 3-arg renew() overload is refused
     * once a different instance legitimately owns the lock document. The generation-fencing
     * guarantee (the 4-arg overload) is covered separately below.
     */
    @Test
    @DisplayName("renew: an instance that no longer holds the lock (a different instance has since acquired it) fails to renew against a REAL MongoDB -- instance-id fencing, proven against a real document, not a mock")
    void renewAfterLostLock_realMongo_fails() throws InterruptedException {
        String credentialId = "integration-test-lock-renew-" + System.currentTimeMillis();
        assertThat(lockService.tryAcquire(credentialId, "instance-A", Duration.ofMillis(200))).isTrue();

        // Let instance-A's short lease genuinely expire, then let instance-B acquire the same
        // credential's lock for real -- no mocking of "time has passed", an actual sleep against
        // an actual document with an actual expiresAt.
        Thread.sleep(400);
        assertThat(lockService.tryAcquire(credentialId, "instance-B", Duration.ofSeconds(90))).isTrue();

        // instance-A, unaware its lease is long gone, attempts to renew -- against a real
        // MongoDB, this must fail: the document instance-B now legitimately owns does not match
        // instance-A's own instanceId in the renewal's own query condition.
        boolean instanceAStillRenews = lockService.renew(credentialId, "instance-A", Duration.ofSeconds(90));
        assertThat(instanceAStillRenews).isFalse();
    }

    /**
     * Verifies generation fencing: capture the generation from tryAcquireWithDiagnosis's
     * LockLease, let it expire and be re-acquired by the same instanceId (so instance-id fencing
     * alone could never catch this -- only the generation check can), then confirm the old
     * generation is correctly refused by a real MongoDB.
     */
    @Test
    @DisplayName("renew: the SAME instanceId re-acquiring after its own lease expired gets a NEW generation -- a renewal carrying the OLD (stale) generation is refused by a real MongoDB, even though the instanceId itself matches. This is the actual generation-fencing guarantee, isolated from instance-id fencing (which alone could not catch this case, since the instanceId is identical both times)")
    void renewWithStaleGeneration_sameInstanceId_realMongo_fails() throws InterruptedException {
        String credentialId = "integration-test-lock-generation-" + System.currentTimeMillis();
        var firstLease = lockService.tryAcquireWithDiagnosis(credentialId, "instance-A", Duration.ofMillis(200));
        assertThat(firstLease.acquired()).isTrue();
        long staleGeneration = firstLease.generation();

        // Let the lease genuinely expire, then the SAME instanceId re-acquires -- a real,
        // separate acquisition cycle against real MongoDB, which the production code's own
        // nextGeneration() counter advances regardless of which instanceId wins it.
        Thread.sleep(400);
        var secondLease = lockService.tryAcquireWithDiagnosis(credentialId, "instance-A", Duration.ofSeconds(90));
        assertThat(secondLease.acquired()).isTrue();
        assertThat(secondLease.generation()).isGreaterThan(staleGeneration); // confirms the generation genuinely advanced

        // A renewal carrying the stale generation must be refused, even though "instance-A" is
        // the correct, current instanceId both times -- a genuine generation check, not
        // something instance-id fencing alone could catch.
        boolean staleGenerationRenews = lockService.renew(credentialId, "instance-A", staleGeneration, Duration.ofSeconds(90));
        assertThat(staleGenerationRenews).isFalse();

        // And the real, CURRENT generation from the second lease renews successfully -- confirming
        // the failure above is specific to the stale generation, not a broken renew() path entirely.
        boolean currentGenerationRenews = lockService.renew(credentialId, "instance-A", secondLease.generation(), Duration.ofSeconds(90));
        assertThat(currentGenerationRenews).isTrue();
    }
}
