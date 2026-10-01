package com.tradevision.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Review finding ("Broker health still incomplete" — "You don't track: REQUEST_WEIGHT, ORDERS,
 * RAW_REQUESTS... WebSocket health... Per-symbol rejection rate: Missing"): verifies the actual
 * new tracking this class gained, plus the pre-existing REST-outcome logic that had zero test
 * coverage before this pass despite being safety-relevant (this check() result gates whether the
 * scanner treats a credential's broker connectivity as trustworthy).
 *
 * UPDATE ("Exchange health is primarily an in-memory metric" -- P1, full context in
 * ExchangeHealthService's own updated header javadoc): now backed by MongoDB rather than plain
 * in-memory fields, so this file's own service field is @Mock'd rather than newed up directly.
 * Real Mongo behavior (upsert merges fields into a document rather than replacing it wholesale,
 * findOne returns null for a document that's never existed) is simulated with a small, real,
 * in-memory backing map keyed by collection+id, wired via thenAnswer -- deliberately NOT a bare
 * Mockito stub returning a fixed value per test, since this class's own methods call findOne
 * then upsert then findOne again within a single record()/check() pair, and a stateless stub
 * can't represent that. This keeps nearly every existing test body below unchanged -- they still
 * just call service.record()/check() repeatedly and assert on the result -- while the mock
 * underneath now genuinely behaves like a real, stateful, if in-memory, database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeHealthServiceTest {

    @Mock MongoTemplate mongoTemplate;
    private ExchangeHealthService service;

    // collection name -> document id -> field map. A real, if simplified, stand-in for Mongo's
    // own storage, scoped fresh per test so no state leaks between tests.
    private final Map<String, Map<String, Map<String, Object>>> store = new HashMap<>();

    private String idFromQuery(Query query) {
        // Every query this class issues is Criteria.where("_id").is(someValue) — extracting
        // that value back out of the built Query object, the same way a real Mongo server would
        // interpret it, rather than trying to intercept the Criteria before it's built.
        Object idValue = query.getQueryObject().get("_id");
        return idValue == null ? null : idValue.toString();
    }

    @BeforeEach
    void setup() {
        service = new ExchangeHealthService(mongoTemplate);

        when(mongoTemplate.findOne(any(Query.class), org.mockito.ArgumentMatchers.eq(Document.class), anyString()))
            .thenAnswer(inv -> {
                Query query = inv.getArgument(0);
                String collection = inv.getArgument(2);
                String id = idFromQuery(query);
                var col = store.get(collection);
                if (col == null || !col.containsKey(id)) return null;
                return new Document(col.get(id));
            });

        when(mongoTemplate.upsert(any(Query.class), any(Update.class), anyString()))
            .thenAnswer(inv -> {
                Query query = inv.getArgument(0);
                Update update = inv.getArgument(1);
                String collection = inv.getArgument(2);
                String id = idFromQuery(query);
                var col = store.computeIfAbsent(collection, k -> new HashMap<>());
                var doc = col.computeIfAbsent(id, k -> new HashMap<>());
                // Real Mongo $set/$inc/$unset semantics, applied against this test's own fake
                // store — enough of the real Update document shape to support what this
                // service's own methods actually issue.
                Document updateDoc = update.getUpdateObject();
                if (updateDoc.containsKey("$set")) {
                    Document setDoc = (Document) updateDoc.get("$set");
                    for (var e : setDoc.entrySet()) doc.put(e.getKey(), e.getValue());
                }
                if (updateDoc.containsKey("$inc")) {
                    Document incDoc = (Document) updateDoc.get("$inc");
                    for (var e : incDoc.entrySet()) {
                        // Real MongoDB preserves the numeric type of $inc's result based on both
                        // operands -- int32 + int32 stays int32, only promoting to int64/double
                        // when either operand is wider. This matters here because production
                        // code (ExchangeHealthService.check()) calls doc.getInteger(...) directly
                        // on successCount/failureCount -- always widening to long here (as this
                        // simulation previously did unconditionally) made every one of those
                        // calls throw ClassCastException against a stored Long, even though the
                        // real increment amount (a plain int literal) would never actually
                        // produce one on a real Mongo server.
                        Object currentObj = doc.get(e.getKey());
                        Object deltaObj = e.getValue();
                        if (currentObj instanceof Double || deltaObj instanceof Double) {
                            double current = currentObj instanceof Number n ? n.doubleValue() : 0.0;
                            double delta = deltaObj instanceof Number n ? n.doubleValue() : 0.0;
                            doc.put(e.getKey(), current + delta);
                        } else if (currentObj instanceof Long || deltaObj instanceof Long) {
                            long current = currentObj instanceof Number n ? n.longValue() : 0L;
                            long delta = deltaObj instanceof Number n ? n.longValue() : 0L;
                            doc.put(e.getKey(), current + delta);
                        } else {
                            int current = currentObj instanceof Number n ? n.intValue() : 0;
                            int delta = deltaObj instanceof Number n ? n.intValue() : 0;
                            doc.put(e.getKey(), current + delta);
                        }
                    }
                }
                if (updateDoc.containsKey("$unset")) {
                    Document unsetDoc = (Document) updateDoc.get("$unset");
                    for (var key : unsetDoc.keySet()) doc.remove(key);
                }
                return null; // this class's own callers never use upsert's own return value
            });
    }

    // ── Pre-existing REST health logic (previously untested) ──────────────────────

    @Test
    @DisplayName("check: fewer than 5 samples is always reported healthy — not enough data to judge either way")
    void check_fewSamples_alwaysHealthy() {
        service.record("key1", false, 100);
        service.record("key1", false, 100);
        service.flushPendingHealthDeltas();

        var status = service.check("key1");

        assertThat(status.healthy()).isTrue();
        assertThat(status.sampleSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("check: error rate over 50% across the window is reported unhealthy")
    void check_highErrorRate_unhealthy() {
        for (int i = 0; i < 3; i++) service.record("key2", false, 100);
        for (int i = 0; i < 2; i++) service.record("key2", true, 100);
        service.flushPendingHealthDeltas();

        var status = service.check("key2");

        assertThat(status.healthy()).isFalse();
        assertThat(status.reason()).contains("error rate");
    }

    @Test
    @DisplayName("check: high average latency alone (error rate fine) is also reported unhealthy")
    void check_highLatency_unhealthy() {
        for (int i = 0; i < 5; i++) service.record("key3", true, 5000);
        service.flushPendingHealthDeltas();

        var status = service.check("key3");

        assertThat(status.healthy()).isFalse();
        assertThat(status.reason()).contains("latency");
    }

    @Test
    @DisplayName("check: a credential with no recorded calls at all is reported healthy, not fabricated as unhealthy")
    void check_noRecordsAtAll_healthy() {
        var status = service.check("neverCalled");

        assertThat(status.healthy()).isTrue();
        assertThat(status.sampleSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("check: different credentials are tracked independently — one credential's bad key never affects another's health")
    void check_differentCredentials_independentWindows() {
        for (int i = 0; i < 5; i++) service.record("bad-key", false, 100);
        for (int i = 0; i < 5; i++) service.record("good-key", true, 100);
        service.flushPendingHealthDeltas();

        assertThat(service.check("bad-key").healthy()).isFalse();
        assertThat(service.check("good-key").healthy()).isTrue();
    }

    // ── P3-7 ("ExchangeHealthService.record -- 2-3 Mongo writes per exchange call in the hot
    // path -- in-memory rolling window + periodic flush" -- full context in record's own updated
    // javadoc) ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("record: touches MongoDB zero times -- the whole point of this fix is that the hot broker-call path no longer pays any Mongo round trip")
    void record_neverTouchesMongoDirectly() {
        service.record("key-hot-path", true, 42);
        service.record("key-hot-path", false, 99);

        org.mockito.Mockito.verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("flushPendingHealthDeltas: drains the in-memory deltas into MongoDB with the correct aggregated counts, and resets them so the same delta is never flushed twice")
    void flush_drainsDeltasIntoMongo_thenClearsThem() {
        for (int i = 0; i < 3; i++) service.record("key-flush", false, 100);
        for (int i = 0; i < 2; i++) service.record("key-flush", true, 50);

        service.flushPendingHealthDeltas();
        var afterFirstFlush = service.check("key-flush");
        assertThat(afterFirstFlush.sampleSize()).isEqualTo(5);

        // A second flush with nothing newly recorded must not double-apply the same deltas again.
        service.flushPendingHealthDeltas();
        var afterSecondFlush = service.check("key-flush");
        assertThat(afterSecondFlush.sampleSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("flushPendingHealthDeltas: a MongoDB failure for one key's flush is non-fatal and never propagates -- this is a dashboard-only signal, never worth crashing a shared scheduler pool over")
    void flush_mongoFailure_nonFatal() {
        when(mongoTemplate.findOne(any(Query.class), org.mockito.ArgumentMatchers.eq(Document.class), anyString()))
            .thenThrow(new RuntimeException("simulated Mongo outage"));
        service.record("key-flush-fail", true, 10);

        assertThatCode(() -> service.flushPendingHealthDeltas()).doesNotThrowAnyException();
    }

    // ── WebSocket health ("WebSocket health... no full broker-health state for it") ──

    @Test
    @DisplayName("checkWs: a credential with no WS connection ever recorded is reported unhealthy, honestly, not defaulted to healthy")
    void checkWs_neverConnected_unhealthy() {
        var status = service.checkWs("cred1");

        assertThat(status.healthy()).isFalse();
        assertThat(status.state()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("checkWs: connected with a recent message is healthy")
    void checkWs_connectedWithRecentMessage_healthy() {
        service.recordWsConnected("cred1");
        service.recordWsMessageReceived("cred1");

        var status = service.checkWs("cred1");

        assertThat(status.healthy()).isTrue();
        assertThat(status.state()).isEqualTo("CONNECTED");
        assertThat(status.lastMessageAt()).isNotNull();
    }

    @Test
    @DisplayName("checkWs: connected but no message ever received is unhealthy — open doesn't mean alive")
    void checkWs_connectedNoMessage_unhealthy() {
        service.recordWsConnected("cred1");

        var status = service.checkWs("cred1");

        assertThat(status.healthy()).isFalse();
        assertThat(status.reason()).contains("no message received");
    }

    @Test
    @DisplayName("checkWs: a disconnected credential is reported unhealthy with the disconnect state, not silently treated as connected")
    void checkWs_disconnected_unhealthy() {
        service.recordWsConnected("cred1");
        service.recordWsDisconnected("cred1", "1006 abnormal closure");

        var status = service.checkWs("cred1");

        assertThat(status.healthy()).isFalse();
        assertThat(status.state()).isEqualTo("DISCONNECTED");
        assertThat(status.reason()).contains("abnormal closure");
    }

    @Test
    @DisplayName("checkWs: an error state is reported unhealthy")
    void checkWs_errorState_unhealthy() {
        service.recordWsConnected("cred1");
        service.recordWsError("cred1", "connection reset");

        var status = service.checkWs("cred1");

        assertThat(status.healthy()).isFalse();
        assertThat(status.state()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("checkWs: a reconnect after a disconnect correctly clears the stale message timestamp — no false-healthy from a message received before the drop")
    void checkWs_reconnectAfterDisconnect_clearsStaleMessage() {
        service.recordWsConnected("cred1");
        service.recordWsMessageReceived("cred1");
        service.recordWsDisconnected("cred1", "closed");
        service.recordWsConnected("cred1"); // reconnected, but no new message yet

        var status = service.checkWs("cred1");

        assertThat(status.healthy()).isFalse(); // connected, but the pre-disconnect message doesn't count
        assertThat(status.reason()).contains("no message received");
    }

    // ── Request-weight budget ("You don't track: REQUEST_WEIGHT...") ──────────────

    @Test
    @DisplayName("checkRequestBudget: no weight recorded yet is reported healthy at zero usage")
    void checkRequestBudget_neverRecorded_healthyAtZero() {
        var status = service.checkRequestBudget();

        assertThat(status.usedWeight()).isEqualTo(0);
        assertThat(status.healthy()).isTrue();
    }

    @Test
    @DisplayName("checkRequestBudget: usage below 90% of the limit is healthy")
    void checkRequestBudget_belowThreshold_healthy() {
        // Review finding ("Scanner has no complete Binance request-budget model" -- external
        // review, twenty-second pass, P1, full context in ExchangeHealthService's own updated
        // DEFAULT_SPOT_WEIGHT_LIMIT_PER_MINUTE javadoc): this test's own recorded value was a
        // percentage of the OLD, wrong 6000 limit (3000 = 50% of 6000) -- against the corrected,
        // real 1200 limit, 3000 would actually be 250% (unhealthy), which would have made this
        // "below threshold, healthy" test itself fail once the constant was corrected. Updated
        // to the same 50% intent, now genuinely computed against the real limit.
        service.recordUsedWeight(600); // 50% of 1200

        var status = service.checkRequestBudget();

        assertThat(status.healthy()).isTrue();
        assertThat(status.usedFraction()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("checkRequestBudget: usage at or above 90% of the limit is unhealthy")
    void checkRequestBudget_aboveThreshold_unhealthy() {
        // Review finding, same context as checkRequestBudget_belowThreshold_healthy's own
        // comment above: same fix, same reasoning -- 5500 was ~91.7% of the OLD 6000 limit.
        service.recordUsedWeight(1100); // ~91.7% of 1200

        var status = service.checkRequestBudget();

        assertThat(status.healthy()).isFalse();
    }

    @Test
    @DisplayName("checkRequestBudget: the most recently recorded value wins — reflects the true, shared, per-IP state, not any one credential's own view")
    void checkRequestBudget_mostRecentValueWins() {
        service.recordUsedWeight(1000);
        service.recordUsedWeight(4000);

        var status = service.checkRequestBudget();

        assertThat(status.usedWeight()).isEqualTo(4000);
    }

    // ── Per-symbol rejection rate ("Per-symbol rejection rate: Missing") ──────────

    @Test
    @DisplayName("checkSymbolRejectionRate: no orders recorded for this symbol yet is reported at zero, not fabricated")
    void checkSymbolRejectionRate_noData_zero() {
        var status = service.checkSymbolRejectionRate("BTCUSDT");

        assertThat(status.rejectionRate()).isEqualTo(0);
        assertThat(status.sampleSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("checkSymbolRejectionRate: computes the real rejection rate from recorded outcomes")
    void checkSymbolRejectionRate_computesRealRate() {
        service.recordOrderOutcome("BTCUSDT", true);
        service.recordOrderOutcome("BTCUSDT", true);
        service.recordOrderOutcome("BTCUSDT", false);
        service.recordOrderOutcome("BTCUSDT", false);

        var status = service.checkSymbolRejectionRate("BTCUSDT");

        assertThat(status.rejectionRate()).isEqualTo(0.5);
        assertThat(status.sampleSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("checkSymbolRejectionRate: different symbols are tracked independently")
    void checkSymbolRejectionRate_independentPerSymbol() {
        service.recordOrderOutcome("BTCUSDT", true);
        service.recordOrderOutcome("ETHUSDT", false);

        assertThat(service.checkSymbolRejectionRate("BTCUSDT").rejectionRate()).isEqualTo(0);
        assertThat(service.checkSymbolRejectionRate("ETHUSDT").rejectionRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("checkSymbolRejectionRate: symbol is normalized to uppercase, so lookups aren't case-sensitive")
    void checkSymbolRejectionRate_caseInsensitive() {
        service.recordOrderOutcome("btcusdt", false);

        var status = service.checkSymbolRejectionRate("BTCUSDT");

        assertThat(status.sampleSize()).isEqualTo(1);
        assertThat(status.symbol()).isEqualTo("BTCUSDT");
    }
}
