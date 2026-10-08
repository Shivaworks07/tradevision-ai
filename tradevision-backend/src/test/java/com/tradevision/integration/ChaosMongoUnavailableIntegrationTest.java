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
 * Chaos test for MongoDB becoming unavailable (docs/CHAOS_TEST_PLAN.md, "Scenario 1"):
 * verifies that AutoTradeService's own OMS-setup catch block can rely on a Mongo write
 * genuinely throwing when the database is unreachable, rather than that being only a
 * theoretical possibility.
 *
 * Requires Docker (via Testcontainers) and is skipped automatically when no Docker daemon is
 * available. Run `mvn test -Dtest=ChaosMongoUnavailableIntegrationTest` on a machine with
 * Docker to execute it.
 *
 * SCOPE: this proves the foundation the halt logic depends on (a Mongo write genuinely
 * throwing when the database is unreachable) -- it does NOT wire up the full
 * AutoTradeService call chain (which needs many more collaborators mocked/wired correctly
 * than this focused test attempts), and does not attempt to time the failure injection to
 * land inside the exact authorization-to-execution race window described in the plan's own
 * "Scenario 2" -- that scenario needs real process-level control this test harness cannot
 * express, as the plan document itself states.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at
// all -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class ChaosMongoUnavailableIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
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

        // The chaos injection.
        mongo.stop();

        assertThatThrownBy(() -> orderService.create("user1", "cred1", null, "sig1", "BTCUSDT", "BUY", "MARKET",
            BigDecimal.valueOf(0.001), BigDecimal.valueOf(50000), "chaos-test-after-stop"))
            .isInstanceOf(Exception.class);
    }
}
