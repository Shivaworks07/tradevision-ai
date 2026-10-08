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
 * Verifies, against a real MongoDB, that a sparse compound unique index on {credentialId,
 * symbol, brokerOrderId} does not exclude brokerOrderId=null documents the way a sparse
 * single-field index would, because credentialId and symbol are always set, and MongoDB
 * includes a document in a sparse compound index if it has any of the indexed fields. Every
 * order created but never confirmed by the broker (rejected before an id was assigned, or not
 * submitted yet) has brokerOrderId=null, so a second such order for the same credential+symbol
 * would collide as a duplicate key under a sparse index -- the partial index avoids this. See
 * Order.java's @CompoundIndex javadoc and IndexInitializer for the full explanation.
 *
 * Requires Docker (Testcontainers); run
 * `mvn test -Dtest=OrderBrokerOrderIdPartialIndexIntegrationTest` on a machine with Docker
 * available.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at all
// -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class OrderBrokerOrderIdPartialIndexIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
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
    @DisplayName("Order: two orders for the same credential+symbol, both still brokerOrderId=null, both save successfully against a real MongoDB. Against a sparse compound index the second insert would fail with a duplicate-key error; against the partial index, MongoDB never indexes either document, since neither has brokerOrderId as a string.")
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
