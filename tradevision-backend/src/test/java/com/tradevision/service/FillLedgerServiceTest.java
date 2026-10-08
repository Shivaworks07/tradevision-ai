package com.tradevision.service;

import com.tradevision.model.FillRecord;
import com.tradevision.repository.FillRecordRepository;
import com.tradevision.service.broker.dto.Fill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies per-fill persistence and the "don't fabricate" rules FillRecord's javadoc
 * describes -- quoteCommission only when genuinely known, isAggregate only when genuinely
 * synthesized, never as a stand-in for missing per-trade data. Also covers fillIdentity
 * computation and duplicate-key handling -- see FillLedgerService's javadoc for the full design.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FillLedgerServiceTest {

    @Mock FillRecordRepository fillRecordRepo;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @Mock com.tradevision.service.broker.BrokerAdapter adapter;
    // Needed because a WEAK-identity duplicate-key collision raises a real incident (see
    // FillLedgerService.saveOne's javadoc).
    @Mock IncidentService incidentService;
    @InjectMocks FillLedgerService service;

    @Test
    @DisplayName("recordFills: genuine per-trade fills produce one FillRecord each, not one aggregate record")
    void genuinePerTradeFills_oneRecordEach() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        List<Fill> fills = List.of(
            new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", "111", LocalDateTime.now()),
            new Fill(BigDecimal.valueOf(101), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", "112", LocalDateTime.now())
        );

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(FillRecord::isAggregate);
        assertThat(result.get(0).getBrokerTradeId()).isEqualTo("111");
        assertThat(result.get(1).getBrokerTradeId()).isEqualTo("112");
        verify(fillRecordRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("recordFills: quoteCommission is set only when the commission was actually paid in the quote asset — never fabricated for a BNB fee")
    void quoteCommission_onlySetWhenGenuinelyKnown() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        List<Fill> fills = List.of(
            new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"), // quote-asset fee
            new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BNB")  // non-quote fee
        );

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result.get(0).getQuoteCommission()).isEqualByComparingTo("0.05");
        assertThat(result.get(1).getQuoteCommission()).isNull(); // genuinely unknown in quote terms — not guessed
        assertThat(result.get(1).getCommissionAmount()).isEqualByComparingTo("0.001"); // the raw amount is still recorded, just not converted
    }

    @Test
    @DisplayName("recordFills: no per-fill data available, but a real fill happened — one aggregate=true record from order-level totals, not silently dropped")
    void noPerFillData_fallsBackToAggregateRecord() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT",
            List.of(), BigDecimal.valueOf(1.0), BigDecimal.valueOf(100));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isAggregate()).isTrue();
        assertThat(result.get(0).getQuantity()).isEqualByComparingTo("1.0");
        assertThat(result.get(0).getPrice()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("recordFills: no per-fill data and no aggregate quantity either — nothing to record, no fabricated record created")
    void noDataAtAll_recordsNothing() {
        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", List.of(), null, null);

        assertThat(result).isEmpty();
        verify(fillRecordRepo, never()).save(any());
    }

    @Test
    @DisplayName("recordFills: every record carries orderId, positionId, userId, credentialId, symbol, and side correctly")
    void recordFields_populatedCorrectly() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"));

        ArgumentCaptor<FillRecord> captor = ArgumentCaptor.forClass(FillRecord.class);
        service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);
        verify(fillRecordRepo).save(captor.capture());

        FillRecord r = captor.getValue();
        assertThat(r.getOrderId()).isEqualTo("order1");
        assertThat(r.getPositionId()).isEqualTo("pos1");
        assertThat(r.getUserId()).isEqualTo("user1");
        assertThat(r.getCredentialId()).isEqualTo("cred1");
        assertThat(r.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(r.getSide()).isEqualTo("BUY");
        assertThat(r.getReceivedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordFills: a repository failure is caught and logged, not propagated — additive record, same non-fatal design as the OMS wiring")
    void repositoryFailure_doesNotPropagate() {
        when(fillRecordRepo.save(any())).thenThrow(new RuntimeException("simulated database error"));
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"));

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result).isEmpty(); // nothing succeeded, but no exception escaped either
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recordFills: fillIdentity for a genuine per-trade fill with a broker trade id is credentialId+brokerTradeId")
    void fillIdentity_realTradeId_usesCredentialAndTradeId() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT", "555", LocalDateTime.now()));

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result.get(0).getFillIdentity()).isEqualTo("cred1:555");
    }

    @Test
    @DisplayName("recordFills: fillIdentity for an aggregate (synthesized) fill is credentialId+orderId+aggregate — deterministic across retries")
    void fillIdentity_aggregate_usesCredentialOrderIdAndAggregateMarker() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT",
            List.of(), BigDecimal.valueOf(1.0), BigDecimal.valueOf(100));

        assertThat(result.get(0).getFillIdentity()).isEqualTo("cred1:order1:aggregate");
    }

    @Test
    @DisplayName("recordFills: fillIdentity for a real per-trade fill WITHOUT a broker trade id falls back to credentialId+orderId+price+qty+commission+executedAt, documented as honestly weaker but still narrower than price+qty alone")
    void fillIdentity_noTradeId_fallsBackToPriceQtyKey() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT")); // no tradeId (4-arg constructor)

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result.get(0).getFillIdentity()).isEqualTo("cred1:order1:100:1.0:0.05:?");
    }

    @Test
    @DisplayName("recordFills: identityConfidence is STRONG when a real broker trade id backs the fill, WEAK when it's the price/qty/commission/time fallback")
    void identityConfidence_strongVsWeak() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        Fill withTradeId = new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT", "555", LocalDateTime.now());
        Fill withoutTradeId = new Fill(BigDecimal.valueOf(101), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT");

        List<FillRecord> strong = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", List.of(withTradeId), null, null);
        List<FillRecord> weak = service.recordFills("order2", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", List.of(withoutTradeId), null, null);

        assertThat(strong.get(0).getIdentityConfidence()).isEqualTo("STRONG");
        assertThat(weak.get(0).getIdentityConfidence()).isEqualTo("WEAK");
    }

    @Test
    @DisplayName("recordFills: an aggregate (synthesized) record is STRONG confidence — its identity is genuinely deterministic despite having no per-trade data")
    void identityConfidence_aggregateIsStrong() {
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT",
            List.of(), BigDecimal.valueOf(1.0), BigDecimal.valueOf(100));

        assertThat(result.get(0).getIdentityConfidence()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("recordFills: a duplicate fill (DuplicateKeyException from the real unique index) is caught, logged, and skipped — never propagates, never blocks the rest of the batch")
    void duplicateFill_caughtAndSkipped_restOfBatchStillSaves() {
        List<Fill> fills = List.of(
            new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", "111", LocalDateTime.now()),
            new Fill(BigDecimal.valueOf(101), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", "112", LocalDateTime.now())
        );
        // First fill (tradeId=111) is a duplicate — already in the ledger. Second (tradeId=112) is genuinely new.
        when(fillRecordRepo.save(argThat(r -> r != null && "cred1:111".equals(r.getFillIdentity()))))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("E11000 duplicate key"));
        when(fillRecordRepo.save(argThat(r -> r != null && "cred1:112".equals(r.getFillIdentity()))))
            .thenAnswer(i -> i.getArguments()[0]);

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        // Only the genuinely-new fill made it into the returned list — the duplicate was
        // skipped, not double-counted, and did not prevent the second fill from saving.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFillIdentity()).isEqualTo("cred1:112");
        verify(fillRecordRepo, times(2)).save(any()); // both were ATTEMPTED
    }

    @Test
    @DisplayName("recordFills: the SAME batch of fills recorded twice (simulating reconciliation rediscovering an already-ledgered order) results in zero new records the second time")
    void sameBatchRecordedTwice_secondCallProducesNothingNew() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT", "999", LocalDateTime.now()));
        // First call: succeeds normally.
        // Second call: the same fillIdentity now collides in the (real) unique index.
        when(fillRecordRepo.save(any()))
            .thenAnswer(i -> i.getArguments()[0])
            .thenThrow(new org.springframework.dao.DuplicateKeyException("E11000 duplicate key"));

        List<FillRecord> first = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);
        List<FillRecord> second = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty(); // no double-counted fill
    }

    @Test
    @DisplayName("recordFills: a WEAK-identity duplicate-key collision (no real broker trade id -- price/qty/commission/timestamp fallback) raises a CRITICAL incident rather than a routine, quiet log line, since this collision might be a real second fill silently dropped, not a confirmed duplicate")
    void weakIdentityCollision_raisesCriticalIncident() {
        // No tradeId -- this Fill's own identityConfidence resolves to WEAK.
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", null, LocalDateTime.now()));
        when(fillRecordRepo.save(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("E11000 duplicate key"));

        List<FillRecord> result = service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result).isEmpty(); // still skipped, same as a STRONG duplicate -- this is about escalation, not about changing the actual dedup behavior
        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), eq("pos1"), eq("order1"), eq("BTCUSDT"), eq("WEAK_FILL_IDENTITY_COLLISION"), any());
    }

    @Test
    @DisplayName("recordFills: a STRONG-identity duplicate-key collision (a real broker trade id backs it) stays a routine, quiet log line -- no incident, matching the existing, unchanged behavior for the overwhelmingly common case")
    void strongIdentityCollision_staysRoutine_noIncident() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", "111", LocalDateTime.now()));
        when(fillRecordRepo.save(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("E11000 duplicate key"));

        service.recordFills("order1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordFills (overload with brokerOrderId): the resulting FillRecord carries both orderId (unchanged from the caller) and the new brokerOrderId, verified for the specific caller (AutoTradeService's entry path) that has a genuine broker-side id to add alongside its existing OMS orderId")
    void recordFillsOverload_populatesBothOrderIdAndBrokerOrderId() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.05), "USDT", "111", LocalDateTime.now()));
        when(fillRecordRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        List<FillRecord> result = service.recordFills("oms-order-1", "broker-order-1", "pos1", "user1", "cred1", "BTCUSDT", "BUY", "USDT", fills, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo("oms-order-1"); // unchanged semantic — still the OMS reference
        assertThat(result.get(0).getBrokerOrderId()).isEqualTo("broker-order-1"); // the new, additive field
    }

    @Test
    @DisplayName("backfillHistoricalCommissionConversion: a commission in a non-quote asset (BNB on a BTCUSDT trade) is converted using the real historical price at the fill's own timestamp, not the current price")
    void backfillHistoricalCommissionConversion_nonQuoteAssetCommission_convertsUsingHistoricalPrice() {
        var executedAt = java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        var record = new com.tradevision.model.FillRecord(
            "fill1", "order1", null, "pos1", null, "ident1", "STRONG", "user1", "cred1", "BTCUSDT", "BUY",
            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.1),
            BigDecimal.valueOf(0.001), "BNB", null, // quoteCommission still null -- BNB, not USDT
            executedAt, java.time.LocalDateTime.now(), false);
        long expectedTimestamp = executedAt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        when(adapter.getHistoricalPrice("BNBUSDT", expectedTimestamp, com.tradevision.model.BrokerMode.TESTNET))
            .thenReturn(BigDecimal.valueOf(600));
        when(mongoTemplate.updateFirst(any(), any(), eq(com.tradevision.model.FillRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        // No linked position for this test -- applyBackfilledCommissionToPosition below returns
        // immediately once findById comes back null, so no Position update is expected here.
        when(mongoTemplate.findById("pos1", com.tradevision.model.Position.class)).thenReturn(null);

        service.backfillHistoricalCommissionConversion(record, "USDT", adapter, com.tradevision.model.BrokerMode.TESTNET);

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(com.tradevision.model.FillRecord.class));
        var setDoc = updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        // 0.001 BNB * 600 USDT/BNB = 0.6 USDT
        assertThat(((java.math.BigDecimal) setDoc.get("quoteCommission")).compareTo(BigDecimal.valueOf(0.6))).isEqualTo(0);
    }

    // ── The backfill must also correct the linked Position, not just the FillRecord ──

    @Test
    @DisplayName("backfillHistoricalCommissionConversion: backfilling a BNB commission for an already-CLOSED position's exit fill corrects that position's realizedPnlQuote by the newly-discovered fee, atomically")
    void backfillHistoricalCommissionConversion_closedPositionExitFill_correctsRealizedPnl() {
        var executedAt = java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        var record = new com.tradevision.model.FillRecord(
            "fill1", "order1", null, "pos1", null, "ident1", "STRONG", "user1", "cred1", "BTCUSDT", "SELL",
            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.1),
            BigDecimal.valueOf(0.001), "BNB", null,
            executedAt, java.time.LocalDateTime.now(), false);
        when(adapter.getHistoricalPrice(any(), anyLong(), any())).thenReturn(BigDecimal.valueOf(600));
        when(mongoTemplate.updateFirst(any(), any(), eq(com.tradevision.model.FillRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        var closedPosition = new com.tradevision.model.Position();
        closedPosition.setId("pos1");
        closedPosition.setStatus("CLOSED");
        when(mongoTemplate.findById("pos1", com.tradevision.model.Position.class)).thenReturn(closedPosition);

        service.backfillHistoricalCommissionConversion(record, "USDT", adapter, com.tradevision.model.BrokerMode.TESTNET);

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(com.tradevision.model.Position.class));
        var incDoc = updateCaptor.getValue().getUpdateObject().get("$inc", org.bson.Document.class);
        assertThat(((java.math.BigDecimal) incDoc.get("exitFeeQuote")).compareTo(BigDecimal.valueOf(0.6))).isEqualTo(0);
        // Realized P&L is corrected DOWN by exactly the newly-discovered fee, since it was
        // originally computed treating this fee as zero.
        assertThat(((java.math.BigDecimal) incDoc.get("realizedPnlQuote")).compareTo(BigDecimal.valueOf(-0.6))).isEqualTo(0);
    }

    @Test
    @DisplayName("backfillHistoricalCommissionConversion: backfilling a BNB commission for a STILL-OPEN position's entry fill only updates entryFeeQuote -- realizedPnlQuote is untouched since close-time calculation will pick up the corrected fee itself")
    void backfillHistoricalCommissionConversion_openPositionEntryFill_onlyUpdatesFeeField() {
        var executedAt = java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        var record = new com.tradevision.model.FillRecord(
            "fill1", "order1", null, "pos1", null, "ident1", "STRONG", "user1", "cred1", "BTCUSDT", "BUY",
            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.1),
            BigDecimal.valueOf(0.001), "BNB", null,
            executedAt, java.time.LocalDateTime.now(), false);
        when(adapter.getHistoricalPrice(any(), anyLong(), any())).thenReturn(BigDecimal.valueOf(600));
        when(mongoTemplate.updateFirst(any(), any(), eq(com.tradevision.model.FillRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        var openPosition = new com.tradevision.model.Position();
        openPosition.setId("pos1");
        openPosition.setStatus("OPEN");
        when(mongoTemplate.findById("pos1", com.tradevision.model.Position.class)).thenReturn(openPosition);

        service.backfillHistoricalCommissionConversion(record, "USDT", adapter, com.tradevision.model.BrokerMode.TESTNET);

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(com.tradevision.model.Position.class));
        var incDoc = updateCaptor.getValue().getUpdateObject().get("$inc", org.bson.Document.class);
        assertThat(((java.math.BigDecimal) incDoc.get("entryFeeQuote")).compareTo(BigDecimal.valueOf(0.6))).isEqualTo(0);
        assertThat(incDoc.containsKey("realizedPnlQuote")).isFalse();
    }

    @Test
    @DisplayName("backfillHistoricalCommissionConversion: losing the race on the FillRecord's own conditional update (a concurrent backfill already won) never double-applies the position adjustment")
    void backfillHistoricalCommissionConversion_lostRaceOnFillRecordUpdate_neverTouchesPosition() {
        var executedAt = java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        var record = new com.tradevision.model.FillRecord(
            "fill1", "order1", null, "pos1", null, "ident1", "STRONG", "user1", "cred1", "BTCUSDT", "SELL",
            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.1),
            BigDecimal.valueOf(0.001), "BNB", null,
            executedAt, java.time.LocalDateTime.now(), false);
        when(adapter.getHistoricalPrice(any(), anyLong(), any())).thenReturn(BigDecimal.valueOf(600));
        when(mongoTemplate.updateFirst(any(), any(), eq(com.tradevision.model.FillRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 0L, null)); // lost the race

        service.backfillHistoricalCommissionConversion(record, "USDT", adapter, com.tradevision.model.BrokerMode.TESTNET);

        verify(mongoTemplate, never()).findById(any(), eq(com.tradevision.model.Position.class));
        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(com.tradevision.model.Position.class));
    }

    @Test
    @DisplayName("backfillHistoricalCommissionConversion: a record that already has quoteCommission is never overwritten")
    void backfillHistoricalCommissionConversion_alreadyKnown_neverOverwritten() {
        var record = new com.tradevision.model.FillRecord(
            "fill1", "order1", null, "pos1", null, "ident1", "STRONG", "user1", "cred1", "BTCUSDT", "BUY",
            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.1),
            BigDecimal.valueOf(50), "USDT", BigDecimal.valueOf(50), // already known
            java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), false);

        service.backfillHistoricalCommissionConversion(record, "USDT", adapter, com.tradevision.model.BrokerMode.TESTNET);

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(com.tradevision.model.FillRecord.class));
        verify(adapter, never()).getHistoricalPrice(any(), anyLong(), any());
    }

    @Test
    @DisplayName("backfillHistoricalCommissionConversion: when no historical candle is available at all, quoteCommission stays genuinely unknown -- never a fabricated fallback value")
    void backfillHistoricalCommissionConversion_noHistoricalCandleAvailable_staysUnknown() {
        var executedAt = java.time.LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        var record = new com.tradevision.model.FillRecord(
            "fill1", "order1", null, "pos1", null, "ident1", "STRONG", "user1", "cred1", "BTCUSDT", "BUY",
            BigDecimal.valueOf(50000), BigDecimal.valueOf(0.1),
            BigDecimal.valueOf(0.001), "BNB", null,
            executedAt, java.time.LocalDateTime.now(), false);
        when(adapter.getHistoricalPrice(any(), anyLong(), any())).thenReturn(null);

        service.backfillHistoricalCommissionConversion(record, "USDT", adapter, com.tradevision.model.BrokerMode.TESTNET);

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(com.tradevision.model.FillRecord.class));
    }
}
