package com.tradevision.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 follow-up, same package relocation as TradingWorkerHealthIndicator's own
// updated import comment: HealthEndpointGroup/HealthEndpointGroups moved out of
// org.springframework.boot.actuate.health into the new spring-boot-health module's own
// org.springframework.boot.health.actuate.endpoint package -- confirmed directly against the
// jars on this build's own classpath, not assumed.
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit item P2 ("health reporting completeness"): external review, confirmed real by direct
 * inspection -- application.properties' own long-standing comment above the probes.enabled block
 * CLAIMED readiness checks "Mongo and other dependencies actually reachable," but that was never
 * actually true. Spring Boot's two predefined health groups ("liveness"/"readiness") are
 * documented to include ONLY their own respective *State indicator by default
 * (LivenessStateHealthIndicator / ReadinessStateHealthIndicator) -- every other auto-configured
 * HealthIndicator, including the Mongo one spring-boot-starter-data-mongodb + actuator already
 * auto-registers under the key "mongo," is excluded from both groups unless explicitly listed via
 * management.endpoint.health.group.<name>.include. This test proves the actual fix
 * (management.endpoint.health.group.readiness.include=readinessState,mongo in
 * application.properties) by inspecting the real, autoconfigured HealthEndpointGroups bean's
 * membership directly -- not by asserting on an HTTP response body, since
 * management.endpoint.health.show-details=when-authorized + roles=ADMIN (P3-1 fix) means an
 * unauthenticated call to /actuator/health/readiness (the only kind this app's k8s readinessProbe
 * ever makes) never shows a "components" breakdown at all, only the aggregate status -- so
 * asserting against the HTTP body would prove nothing about which indicators actually feed that
 * aggregate.
 *
 * Also proves tradingWorker is deliberately NOT a member of this group -- see
 * TradingWorkerHealthIndicator's own javadoc and this application.properties comment's own
 * updated reasoning: the autonomous trading worker's health must stay independent of whether this
 * instance can serve ordinary HTTP traffic, the same design principle already established for
 * liveness never depending on Binance reachability.
 *
 * HONEST LIMITATION, same as every other Testcontainers-backed integration test in this codebase:
 * no Docker daemon is available in this sandbox ("Cannot connect to the Docker daemon"), so this
 * has NOT been executed here and its correctness is not confirmed by a real run in this session.
 * @Testcontainers(disabledWithoutDocker = true) means it is silently SKIPPED here, not failed --
 * run `mvn test -Dtest=ActuatorReadinessHealthGroupIntegrationTest` on a machine with Docker
 * available to actually execute it.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("local")
@SpringBootTest
class ActuatorReadinessHealthGroupIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    @Test
    @DisplayName("P2 fix (health reporting completeness): the 'readiness' health group genuinely includes the Mongo health indicator, not just readinessState -- closing the gap between this application's own documented intent and what Spring Boot actually checks by default")
    void readinessGroup_includesMongoHealthIndicator() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");

        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("mongo")).isTrue();
    }

    @Test
    @DisplayName("P2 fix (health reporting completeness): tradingWorker is deliberately NOT a member of the readiness group -- a stalled autonomous trading loop must not pull this pod out of Service rotation and stop it answering ordinary API requests, the same separation-of-concerns already established for liveness never depending on Binance reachability")
    void readinessGroup_doesNotIncludeTradingWorker() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");

        assertThat(readiness.isMember("tradingWorker")).isFalse();
    }

    @Test
    @DisplayName("P2 fix (health reporting completeness): the 'liveness' group is unaffected by this fix -- still only liveness itself, never Mongo or tradingWorker, so a transient Mongo/exchange hiccup can never cause Kubernetes to kill and restart an otherwise-healthy JVM")
    void livenessGroup_stillOnlyContainsLivenessState() {
        HealthEndpointGroup liveness = healthEndpointGroups.get("liveness");

        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(liveness.isMember("mongo")).isFalse();
        assertThat(liveness.isMember("tradingWorker")).isFalse();
    }
}
