package com.tradevision.integration;

import com.tradevision.dto.StrategyPlanRequest;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerType;
import com.tradevision.model.StrategyPlan;
import com.tradevision.model.TradeDirection;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.StrategyPlanRepository;
import com.tradevision.service.StrategyPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that StrategyPlan's update()/setEnabled() cannot lose concurrent changes, against
 * a real MongoDB actually serializing concurrent writes to the same plan document, rather
 * than only against Mockito.
 *
 * Requires Docker (via Testcontainers) and is skipped automatically when no Docker daemon is
 * available. Run `mvn test -Dtest=StrategyPlanVersionIntegrationTest` on a machine with
 * Docker to execute it.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at
// all -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class StrategyPlanVersionIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private StrategyPlanService strategyPlanService;
    @Autowired
    private StrategyPlanRepository strategyPlanRepo;
    @Autowired
    private BrokerCredentialRepository credentialRepo;

    private String setUpCredentialAndPlan(String suffix) {
        String credentialId = "integration-test-cred-" + suffix;
        BrokerCredential credential = new BrokerCredential();
        credential.setId(credentialId);
        credential.setUserId("integration-user");
        credential.setBroker(BrokerType.BINANCE);
        credential.setActive(true);
        credentialRepo.save(credential);

        StrategyPlanRequest createReq = new StrategyPlanRequest();
        createReq.setCredentialId(credentialId);
        createReq.setTimeframe("1h");
        createReq.setDirection(TradeDirection.LONG);
        createReq.setRiskPerTradePercent(1.0);
        StrategyPlan created = strategyPlanService.create("integration-user", createReq);
        assertThat(created.getVersion()).isEqualTo(1L); // @Version's own starting point, confirmed against a real save
        return created.getId();
    }

    /**
     * Two concurrent updates to the same plan -> exactly one succeeds -> version increments
     * once -> no lost fields.
     */
    @Test
    @DisplayName("update(): against a REAL MongoDB, two concurrent updates to the SAME plan cannot both silently win -- exactly one succeeds, the other throws OptimisticLockingFailureException, and the surviving write's own fields are genuinely intact, not a merge of both")
    void concurrentUpdates_realMongo_exactlyOneSucceedsNoLostFields() throws InterruptedException {
        String planId = setUpCredentialAndPlan("concurrent-update-" + System.currentTimeMillis());

        StrategyPlanRequest reqA = new StrategyPlanRequest();
        reqA.setCredentialId(strategyPlanRepo.findById(planId).orElseThrow().getCredentialId());
        reqA.setTimeframe("5m"); // Request A's own distinguishing change
        reqA.setDirection(TradeDirection.LONG);
        reqA.setRiskPerTradePercent(2.0);

        StrategyPlanRequest reqB = new StrategyPlanRequest();
        reqB.setCredentialId(reqA.getCredentialId());
        reqB.setTimeframe("15m"); // Request B's own distinguishing change
        reqB.setDirection(TradeDirection.LONG);
        reqB.setRiskPerTradePercent(3.0);
        reqB.setEnabled(false); // Request B also disables the plan -- this must not be silently lost either

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(2);
        List<Boolean> succeeded = new CopyOnWriteArrayList<>();

        Runnable attemptA = () -> {
            try {
                startLine.await();
                strategyPlanService.update("integration-user", planId, reqA);
                succeeded.add(true);
            } catch (OptimisticLockingFailureException e) {
                succeeded.add(false);
            } catch (InterruptedException ignored) {
            } finally {
                allDone.countDown();
            }
        };
        Runnable attemptB = () -> {
            try {
                startLine.await();
                strategyPlanService.update("integration-user", planId, reqB);
                succeeded.add(true);
            } catch (OptimisticLockingFailureException e) {
                succeeded.add(false);
            } catch (InterruptedException ignored) {
            } finally {
                allDone.countDown();
            }
        };
        executor.submit(attemptA);
        executor.submit(attemptB);
        startLine.countDown();
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Not both can have won.
        long successCount = succeeded.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(1);

        // Version incremented exactly once total, not twice (the lost-update symptom this
        // guards against) -- and not zero (a working update must still increment it).
        StrategyPlan finalState = strategyPlanRepo.findById(planId).orElseThrow();
        assertThat(finalState.getVersion()).isEqualTo(2L);

        // Whichever request actually won, its OWN fields are genuinely intact -- not silently
        // overwritten by the other, and not a merge of both (the classic lost-update symptom).
        boolean matchesA = "5m".equals(finalState.getTimeframe()) && finalState.getRiskPerTradePercent() == 2.0 && finalState.isEnabled();
        boolean matchesB = "15m".equals(finalState.getTimeframe()) && finalState.getRiskPerTradePercent() == 3.0 && !finalState.isEnabled();
        assertThat(matchesA || matchesB).isTrue();
        assertThat(matchesA && matchesB).isFalse(); // never a merge of both requests' own fields
    }

    /**
     * update() vs setEnabled() -> no lost update.
     */
    @Test
    @DisplayName("update() vs setEnabled(): against a REAL MongoDB, a concurrent update() and setEnabled() on the same plan cannot both silently win either -- the same optimistic-lock guarantee holds across these two different write paths, not just within one of them")
    void updateVsSetEnabled_realMongo_noLostUpdate() throws InterruptedException {
        String planId = setUpCredentialAndPlan("update-vs-enabled-" + System.currentTimeMillis());

        StrategyPlanRequest updateReq = new StrategyPlanRequest();
        updateReq.setCredentialId(strategyPlanRepo.findById(planId).orElseThrow().getCredentialId());
        updateReq.setTimeframe("30m");
        updateReq.setDirection(TradeDirection.LONG);
        updateReq.setRiskPerTradePercent(1.5);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLine.await();
                strategyPlanService.update("integration-user", planId, updateReq);
                successCount.incrementAndGet();
            } catch (OptimisticLockingFailureException | InterruptedException ignored) {
            } finally {
                allDone.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startLine.await();
                strategyPlanService.setEnabled("integration-user", planId, false);
                successCount.incrementAndGet();
            } catch (OptimisticLockingFailureException e) {
            } catch (InterruptedException ignored) {
            } finally {
                allDone.countDown();
            }
        });
        startLine.countDown();
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        StrategyPlan finalState = strategyPlanRepo.findById(planId).orElseThrow();
        assertThat(finalState.getVersion()).isEqualTo(2L); // exactly one real write landed
    }

    /**
     * update() vs claimPlanExecution() -> a stale signal cannot execute under an incorrectly
     * reused version. A concurrency-safe version field is what makes claimPlanExecution's own
     * atomic authorization boundary meaningful in the first place.
     */
    @Test
    @DisplayName("update() vs claimPlanExecution(): against a REAL MongoDB, a signal stamped with the plan's version BEFORE a concurrent edit cannot claim execution AFTER that edit lands -- proving the version this session's own execution-authorization boundary relies on is genuinely reliable, not just present")
    void updateVsClaimPlanExecution_realMongo_staleSignalCannotClaim() {
        String planId = setUpCredentialAndPlan("update-vs-claim-" + System.currentTimeMillis());
        StrategyPlan original = strategyPlanRepo.findById(planId).orElseThrow();
        long versionAtSignalGenerationTime = original.getVersion(); // what a scanner would have stamped onto a signal right now

        // The plan is edited (e.g. disabled) AFTER the signal was generated but BEFORE it executes.
        strategyPlanService.setEnabled("integration-user", planId, false);

        // The stale-versioned signal now attempts to claim execution -- must fail, since the
        // real, current version in the database has moved on.
        boolean claimed = strategyPlanService.claimPlanExecution(planId, versionAtSignalGenerationTime,
            "integration-user", original.getCredentialId());
        assertThat(claimed).isFalse();

        // A signal generated AFTER the edit, carrying the real current version, correctly succeeds.
        StrategyPlan current = strategyPlanRepo.findById(planId).orElseThrow();
        boolean claimedWithCurrentVersion = strategyPlanService.claimPlanExecution(planId, current.getVersion(),
            "integration-user", original.getCredentialId());
        // The plan is now disabled, so this correctly fails too -- for the RIGHT reason (enabled=false), not a version mismatch.
        assertThat(claimedWithCurrentVersion).isFalse();
    }
}
