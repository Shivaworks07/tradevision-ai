package com.tradevision.integration;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.model.Position;
import com.tradevision.repository.OrderRepository;
import com.tradevision.repository.PositionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P3-11 fix ("The new order-ID index will block trading on a symbol after one rejected order" --
 * external review, second pass, re-audit): the review's own suggested proof -- "one integration
 * test would confirm it in seconds" -- for the actual regression: a SPARSE compound unique index
 * on {credentialId, symbol, brokerOrderId} does NOT exclude brokerOrderId=null documents the way
 * a sparse SINGLE-field index would, because credentialId and symbol are always set, and MongoDB
 * includes a document in a sparse compound index if it has ANY of the indexed fields. Every order
 * created but never confirmed by the broker (rejected before an id was assigned, or simply not
 * submitted yet) has brokerOrderId=null, so a second such order for the same credential+symbol
 * would collide as a duplicate key under the old (sparse) index -- exactly the scenario this test
 * drives against a real MongoDB, both before (would fail) and after (passes) the partial-index fix.
 * See Order.java's own @CompoundIndex javadoc and IndexInitializer's own updated comments for the
 * full explanation and the migration this same fix required for existing deployments.
 *
 * HONEST LIMITATION, same as this package's other Testcontainers-backed tests: `docker ps` fails
 * outright in this sandbox ("docker: not found" / no daemon), so I have not executed this test and
 * cannot confirm it passes. Run `mvn test -Dtest=OrderBrokerOrderIdPartialIndexIntegrationTest` on
 * a machine with Docker available to actually confirm this before trusting it -- exactly the
 * confirmation the external review itself asked for.
 */
@Testcontainers(disabledWithoutDocker = true)
// P1-16 fix: spring.profiles.active now defaults to "prod" (fail-closed), which has no default
// secrets at all -- without this, this Testcontainers-backed context would fail to start outside
// a real deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, the same
// secrets this test always implicitly relied on before that default changed.
@ActiveProfiles("local")
@SpringBootTest
class OrderBrokerOrderIdPartialIndexIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    private Order newOrder(String credentialId, String symbol, String clientOrderId) {
        Order order = new Order();
        order.setClientOrderId(clientOrderId);
        order.setBrokerOrderId(null); // the exact case this fix is about -- not yet confirmed by the broker
        order.setUserId("user1");
        order.setCredentialId(credentialId);
        order.setSymbol(symbol);
        order.setStatus(OrderStatus.CREATED);
        order.setSide("BUY");
        order.setRequestedQuantity(BigDecimal.ONE);
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }

    @Test
    @DisplayName("Order: two orders for the SAME credential+symbol, both still brokerOrderId=null, both save successfully against a REAL MongoDB -- the actual P3-11 regression test. Against the old sparse compound index, the second insert would fail with a duplicate-key error; against the fixed partial index, MongoDB never indexes either document at all, since neither has brokerOrderId as a string.")
    void twoOrdersSameCredentialSymbol_bothNullBrokerOrderId_bothSaveSuccessfully() {
        Order first = newOrder("cred1", "BTCUSDT", "client-order-1");
        Order second = newOrder("cred1", "BTCUSDT", "client-order-2");

        assertThatCode(() -> orderRepository.save(first)).doesNotThrowAnyException();
        assertThatCode(() -> orderRepository.save(second)).doesNotThrowAnyException();

        assertThat(orderRepository.findByClientOrderId("client-order-1")).isPresent();
        assertThat(orderRepository.findByClientOrderId("client-order-2")).isPresent();
    }

    @Test
    @DisplayName("Order: once brokerOrderId is actually set, the real uniqueness constraint still holds -- a genuine duplicate {credentialId, symbol, brokerOrderId} is rejected. Proves the partial index didn't just stop enforcing uniqueness altogether to fix the null-collision bug.")
    void duplicateNonNullBrokerOrderId_sameCredentialSymbol_stillRejected() {
        Order first = newOrder("cred1", "ETHUSDT", "client-order-3");
        first.setBrokerOrderId("binance-order-999");
        orderRepository.save(first);

        Order duplicate = newOrder("cred1", "ETHUSDT", "client-order-4");
        duplicate.setBrokerOrderId("binance-order-999"); // same broker order id, same credential+symbol -- a genuine duplicate

        assertThatCode(() -> orderRepository.save(duplicate)).isInstanceOf(Exception.class);
    }

    private Position newPosition(String credentialId, String symbol) {
        Position position = new Position();
        position.setCredentialId(credentialId);
        position.setSymbol(symbol);
        position.setUserId("user1");
        position.setEntryOrderId(null); // same unverified-entry case as Order.brokerOrderId above
        position.setStatus("OPEN");
        return position;
    }

    @Test
    @DisplayName("Position: same fix, same proof -- two positions for the same credential+symbol, both still entryOrderId=null, both save successfully against a REAL MongoDB.")
    void twoPositionsSameCredentialSymbol_bothNullEntryOrderId_bothSaveSuccessfully() {
        Position first = newPosition("cred1", "SOLUSDT");
        Position second = newPosition("cred1", "SOLUSDT");

        assertThatCode(() -> positionRepository.save(first)).doesNotThrowAnyException();
        assertThatCode(() -> positionRepository.save(second)).doesNotThrowAnyException();
    }
}
