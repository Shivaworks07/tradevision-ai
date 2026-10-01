package com.tradevision.integration;

import com.tradevision.service.OrderService;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review finding ("formal chaos-test suite" -- external review, P3, full context in
 * docs/CHAOS_TEST_PLAN.md's own "Scenario 1"): the actual, written chaos test for MongoDB
 * becoming unavailable -- the real failure mode AutoTradeService's own OMS-setup catch block
 * (this session's own P0-2 fix) depends on being genuinely detectable as a thrown exception,
 * not just a theoretical possibility.
 *
 * HONEST LIMITATION, same as every other integration test in this codebase: `docker ps` fails
 * outright in this sandbox ("docker: not found") -- no Docker daemon is available here, so this
 * has NOT been executed and its correctness is not confirmed. Run
 * `mvn test -Dtest=ChaosMongoUnavailableIntegrationTest` on a machine with Docker available.
 *
 * SCOPE, stated plainly: this proves the foundation P0-2's own halt logic depends on (a Mongo
 * write genuinely throwing when the database is unreachable) -- it does NOT wire up the full
 * AutoTradeService call chain (which needs many more collaborators mocked/wired correctly than
 * this focused test attempts), and does not attempt to time the failure injection to land
 * inside the exact authorization-to-execution race window described in the plan's own
 * "Scenario 2" -- that scenario needs real process-level control this test harness cannot
 * express, as the plan document itself states.
 */
@Testcontainers(disabledWithoutDocker = true)
// P1-16 fix: spring.profiles.active now defaults to "prod" (fail-closed), which has no default
// secrets at all -- without this, this Testcontainers-backed context would fail to start
// outside a real deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, the
// same secrets this test always implicitly relied on before that default changed.
@ActiveProfiles("local")
@SpringBootTest
class ChaosMongoUnavailableIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("chaos: MongoDB stopped mid-run -- a genuine order-creation attempt throws, proving the failure this application's own LIVE-halt logic depends on is real and detectable, not theoretical")
    void mongoStoppedMidRun_orderCreationGenuinelyThrows() {
        // Confirm the container is genuinely running and the application can reach it before
        // injecting the failure -- a test that "passes" because Mongo was never reachable in
        // the first place would prove nothing real.
        orderService.create("user1", "cred1", null, "sig1", "BTCUSDT", "BUY", "MARKET",
            BigDecimal.valueOf(0.001), BigDecimal.valueOf(50000), "chaos-test-baseline");

        // The actual chaos injection.
        mongo.stop();

        assertThatThrownBy(() -> orderService.create("user1", "cred1", null, "sig1", "BTCUSDT", "BUY", "MARKET",
            BigDecimal.valueOf(0.001), BigDecimal.valueOf(50000), "chaos-test-after-stop"))
            .isInstanceOf(Exception.class);
    }
}
