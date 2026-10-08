package com.tradevision.integration;

import com.tradevision.model.ScannedCandle;
import com.tradevision.repository.ScannedCandleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
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
 * Verifies the scanner's cross-instance candle-dedup claim against a real MongoDB enforcing the
 * unique index, not a mock: many concurrent "instances" (simulated as concurrent threads)
 * racing to claim the identical candle, against a real Mongo unique index.
 *
 * Requires Docker (Testcontainers); run
 * `mvn test -Dtest=ScannedCandleClaimIntegrationTest` on a machine with Docker available.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at all
// -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class ScannedCandleClaimIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private ScannedCandleRepository scannedCandleRepo;

    @Test
    @DisplayName("ScannedCandle claim: against a REAL MongoDB with the real unique index actually created, many concurrent 'instances' racing to claim the identical candle produce exactly one successful claim -- not just that save() was called, but that Mongo's own unique constraint actually enforces it")
    void concurrentClaim_realMongo_exactlyOneWins() throws InterruptedException {
        String claimKey = "integration-test-cred1|BTCUSDT|1h|" + System.currentTimeMillis();
        int concurrentAttempts = 20;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(concurrentAttempts);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateKeyCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentAttempts; i++) {
            executor.submit(() -> {
                try {
                    startLine.await(); // all threads fire as close to simultaneously as possible
                    var claim = new ScannedCandle();
                    claim.setClaimKey(claimKey);
                    try {
                        scannedCandleRepo.save(claim);
                        successCount.incrementAndGet();
                    } catch (DuplicateKeyException e) {
                        duplicateKeyCount.incrementAndGet();
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

        // MongoDB's unique index on claimKey (created by IndexInitializer, part of application
        // startup -- this test relies on the real Spring context creating it, not stubbing it)
        // means exactly one concurrent save() for the identical claimKey can ever succeed,
        // regardless of how many "instances" race for it simultaneously.
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateKeyCount.get()).isEqualTo(concurrentAttempts - 1);
    }

    @Test
    @DisplayName("ScannedCandle claim: two DIFFERENT candle-close-times for the SAME credential/symbol/timeframe are both allowed to claim -- the unique index is scoped to the full claimKey (including the timestamp), not just the credential/symbol/timeframe prefix")
    void differentCandleTimes_bothClaimSuccessfully() {
        String baseKey = "integration-test-cred2|ETHUSDT|1h";
        var claim1 = new ScannedCandle();
        claim1.setClaimKey(baseKey + "|1000000");
        var claim2 = new ScannedCandle();
        claim2.setClaimKey(baseKey + "|1003600");

        // Neither should throw -- these are genuinely different candles, both legitimately
        // claimable, and must not collide just because they share the same credential/symbol/
        // timeframe prefix.
        scannedCandleRepo.save(claim1);
        scannedCandleRepo.save(claim2);

        assertThat(scannedCandleRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);
    }
}
