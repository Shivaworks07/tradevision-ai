package com.tradevision.integration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.model.Position;
import com.tradevision.repository.OrderRepository;
import com.tradevision.repository.PositionRepository;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
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
 * Second re-audit fix ("Old unique indexes are never removed" -- external review, third pass,
 * item #1 of its own "before real money" list): "Add an integration test that seeds the old
 * indexes first" -- this file's own explicit fix instruction. Seeds a real MongoDB with the
 * EXACT legacy single-field unique(sparse) indexes this codebase's own earlier revision created
 * directly on Order.brokerOrderId / Position.entryOrderId (before the scoped compound-index fix
 * existed at all) -- via a raw MongoClient, in the @DynamicPropertySource hook, which runs after
 * the Testcontainers Mongo instance is up but BEFORE the Spring context (and therefore
 * IndexInitializer's own ApplicationReadyEvent listener) is created at all -- then proves the
 * real migration this fix adds (IndexInitializer.migrateLegacySingleFieldUniqueIndex) actually
 * removes that legacy constraint against a real database, not just a mocked IndexOperations, and
 * that the specific bug it caused (global brokerOrderId/entryOrderId collision across
 * credentials/symbols) is actually gone afterward.
 *
 * HONEST LIMITATION, same as this package's other Testcontainers-backed tests: `docker ps` fails
 * outright in this sandbox ("docker: not found" / no daemon), so I have not executed this test
 * and cannot confirm it passes. Run `mvn test -Dtest=LegacyIndexMigrationIntegrationTest` on a
 * machine with Docker available to actually confirm this before trusting it.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("local")
@SpringBootTest
class LegacyIndexMigrationIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    // Explicit database name (rather than the no-arg getReplicaSetUrl(), whose default database
    // isn't part of Testcontainers' own documented contract) so the raw seeding client below and
    // the actual application both unambiguously target the exact same database.
    private static final String DB_NAME = "trade_vision_test";

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongo.getReplicaSetUrl(DB_NAME));
        // Seed the OLD, actually-broken indexes BEFORE the Spring context (and therefore
        // IndexInitializer) is ever created -- this is the whole point of doing it here rather
        // than in a @BeforeEach, which would run too late to prove the migration, not just the
        // steady-state creation path OrderBrokerOrderIdPartialIndexIntegrationTest already covers.
        // Collection names match Order/Position's own @Document(collection = "...") annotations
        // ("orders"/"positions"), not the default lower-cased class name.
        try (MongoClient rawClient = MongoClients.create(mongo.getReplicaSetUrl(DB_NAME))) {
            var db = rawClient.getDatabase(DB_NAME);
            db.getCollection("orders").createIndex(Indexes.ascending("brokerOrderId"),
                new IndexOptions().name("brokerOrderId_1").unique(true).sparse(true));
            db.getCollection("positions").createIndex(Indexes.ascending("entryOrderId"),
                new IndexOptions().name("entryOrderId_1").unique(true).sparse(true));
        }
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private Order newOrder(String credentialId, String symbol, String clientOrderId) {
        Order order = new Order();
        order.setClientOrderId(clientOrderId);
        order.setBrokerOrderId(null);
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
    @DisplayName("startup migration: the legacy single-field unique index on Order.brokerOrderId (seeded before this Spring context ever started) is gone by the time the application is up -- IndexInitializer actually dropped it, not just logged that it would")
    void legacyOrderBrokerOrderIdIndex_isGoneAfterStartup() {
        var indexNames = mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
            .map(info -> info.getName()).toList();
        assertThat(indexNames).doesNotContain("brokerOrderId_1");
    }

    @Test
    @DisplayName("startup migration: the legacy single-field unique index on Position.entryOrderId is likewise gone after startup")
    void legacyPositionEntryOrderIdIndex_isGoneAfterStartup() {
        var indexNames = mongoTemplate.indexOps(Position.class).getIndexInfo().stream()
            .map(info -> info.getName()).toList();
        assertThat(indexNames).doesNotContain("entryOrderId_1");
    }

    @Test
    @DisplayName("the actual bug the legacy index caused is fixed: two orders for DIFFERENT credentials, both still brokerOrderId=null, both save -- the legacy index enforced GLOBAL uniqueness of brokerOrderId on its own and would have rejected the second insert as a duplicate-null-key collision even with the new, correctly-scoped compound index also present")
    void legacyIndexNoLongerCausesCrossCredentialNullCollision() {
        Order first = newOrder("cred-A", "BTCUSDT", "client-order-A1");
        Order second = newOrder("cred-B", "ETHUSDT", "client-order-B1"); // different credential AND symbol

        assertThatCode(() -> orderRepository.save(first)).doesNotThrowAnyException();
        assertThatCode(() -> orderRepository.save(second)).doesNotThrowAnyException();

        assertThat(orderRepository.findByClientOrderId("client-order-A1")).isPresent();
        assertThat(orderRepository.findByClientOrderId("client-order-B1")).isPresent();
    }

    @Test
    @DisplayName("the new, correctly-scoped compound index still enforces real uniqueness once brokerOrderId is actually known -- the migration removed the WRONG constraint, not constraint enforcement altogether")
    void newCompoundIndexStillEnforcesRealDuplicates() {
        Order first = newOrder("cred-A", "BTCUSDT", "client-order-A2");
        first.setBrokerOrderId("binance-order-555");
        orderRepository.save(first);

        Order duplicate = newOrder("cred-A", "BTCUSDT", "client-order-A3");
        duplicate.setBrokerOrderId("binance-order-555"); // same credential+symbol+brokerOrderId -- a genuine duplicate

        assertThatCode(() -> orderRepository.save(duplicate)).isInstanceOf(Exception.class);
    }
}
