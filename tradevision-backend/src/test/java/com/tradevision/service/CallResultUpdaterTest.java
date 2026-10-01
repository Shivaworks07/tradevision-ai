package com.tradevision.service;

import com.tradevision.model.TradeCallRecord;
import com.tradevision.model.TradeOutcome;
import com.tradevision.repository.OrderRepository;
import com.tradevision.repository.TradeCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review finding ("CallResultUpdater can race with real position outcomes" -- P1, full context
 * in CallResultUpdater.updateResult's own comment): this file did not exist before this fix.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallResultUpdaterTest {

    @Mock TradeCallRepository callRepo;
    @Mock OrderRepository orderRepo;
    @Mock MongoTemplate mongoTemplate;
    @Mock com.tradevision.config.ShutdownState shutdownState;

    @InjectMocks CallResultUpdater updater;

    private TradeCallRecord call;

    @BeforeEach
    void setup() {
        call = new TradeCallRecord();
        call.setId("call1");
        call.setSymbol("BTCUSDT");
        call.setDirection("LONG");
        // Review finding ("Financial values still mix double and BigDecimal" -- external
        // review, twenty-fourth pass, P2, full context in TradeCallRecord's own updated field
        // comment): these 5 fields are BigDecimal now.
        call.setEntryPrice(java.math.BigDecimal.valueOf(100.0));
        call.setStopLoss(java.math.BigDecimal.valueOf(90.0));
        call.setTarget1(java.math.BigDecimal.valueOf(110.0));
        call.setTarget2(java.math.BigDecimal.valueOf(120.0));
        call.setTarget3(java.math.BigDecimal.valueOf(130.0));
        call.setOutcome(new TradeOutcome());
    }

    @Test
    @DisplayName("updateResult: applies the theoretical result via an ATOMIC conditional update (outcome.result still PENDING), not a plain unconditional save -- the actual review fix (\"CallResultUpdater can race with real position outcomes\")")
    void updateResult_appliesViaAtomicConditionalUpdate() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        updater.updateResult(call, 111.0); // crosses target1

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(queryCaptor.capture(), any(Update.class), eq(TradeCallRecord.class));
        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains("call1").contains("PENDING");
        // The plain, unconditional save must never be used for this path.
        verify(callRepo, never()).save(any());
    }

    @Test
    @DisplayName("P1-5: updateResult never touches MLWeightService at all, win or lose -- its own ticker-crossing threshold check is a guess against whatever entry/SL/target this call was SAVED with (client-suppliable via POST /api/calls/save), never a real broker fill, so it must never feed the global, unscoped-by-user ML weights every live signal reads from. Only PositionMonitorService.writeRealOutcomeBackToSignal (a genuine, verified fill) may do that now.")
    void updateResult_neverRecordsMLOutcome_evenOnAppliedResult() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        updater.updateResult(call, 111.0); // crosses target1 -> HIT_T1, applied

        // No MLWeightService dependency exists on this class at all any more -- nothing to verify
        // beyond the fact that the real outcome write still went through.
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class));
    }

    @Test
    @DisplayName("updateResult: losing the atomic race (a real outcome was written between price-fetch and this save) does NOT throw and does not retry -- it's silently, correctly skipped")
    void updateResult_lostRace_skippedSilently() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 0L, null)); // lost the race

        // Must not throw.
        updater.updateResult(call, 111.0);

        verify(callRepo, never()).save(any());
    }

    @Test
    @DisplayName("updateResult: no price threshold crossed -- never even attempts the update")
    void updateResult_noThresholdCrossed_neverAttemptsUpdate() {
        updater.updateResult(call, 105.0); // between entry and target1 -- no result

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(TradeCallRecord.class));
        verify(callRepo, never()).save(any());
    }

    /**
     * Review finding ("Graceful shutdown does not stop @Scheduled work or WebSocket listeners
     * from starting new work" -- external review, nineteenth pass, P1, confirmed real by direct
     * inspection: this scheduled method had no shutdown-awareness at all before this fix).
     */
    @Test
    @DisplayName("updatePendingCalls: does nothing at all when the process is shutting down")
    void updatePendingCalls_shuttingDown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        updater.updatePendingCalls();

        verify(callRepo, never()).findByOutcome_ResultOrderByCalledAtDesc(any(), any());
    }

    /**
     * P2-19 fix ("CallResultUpdater: ... newest 50 only" -- external review, full context in
     * TradeCallRepository.findByOutcome_ResultOrderByCalledAtAsc's own updated javadoc): the
     * actual review-required proof -- the updater now queries OLDEST-first, not newest-first, so
     * a backlog beyond 50 pending calls actually drains instead of permanently starving.
     */
    @Test
    @DisplayName("P2-19: updatePendingCalls queries oldest-pending-first (ASC), not newest-first -- a backlog beyond the page size actually drains")
    void updatePendingCalls_queriesOldestPendingFirst() {
        when(callRepo.findByOutcome_ResultOrderByCalledAtAsc(eq("PENDING"), any()))
            .thenReturn(java.util.List.of());

        updater.updatePendingCalls();

        verify(callRepo).findByOutcome_ResultOrderByCalledAtAsc(eq("PENDING"), any());
        verify(callRepo, never()).findByOutcome_ResultOrderByCalledAtDesc(any(), any());
    }

    // ── P2-19: point-in-time price -> actual high/low range over the elapsed window ─────────

    @Test
    @DisplayName("P2-19: updateResultFromRange -- a LONG call whose range never touched SL or T1 applies nothing")
    void updateResultFromRange_long_noThresholdTouched_appliesNothing() {
        updater.updateResultFromRange(call, 108.0, 95.0); // between entry(100) and target1(110)/stopLoss(90)

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(TradeCallRecord.class));
    }

    @Test
    @DisplayName("P2-19: updateResultFromRange -- a LONG call whose range's HIGH touched target1, even though the period's current/closing price never did, still applies HIT_T1 (the actual review fix: a spike-and-revert is no longer invisible)")
    void updateResultFromRange_long_highTouchedTargetEvenThoughItReverted_appliesHitTarget() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        // Price spiked up to 111 (past target1=110) at some point in the window, then fell back
        // to 102 by "now" -- a single point-in-time check at 102 would see nothing crossed.
        updater.updateResultFromRange(call, 111.0, 102.0);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(TradeCallRecord.class));
        assertThat(updateCaptor.getValue().getUpdateObject().toString()).contains("HIT_T1");
    }

    @Test
    @DisplayName("P2-19: updateResultFromRange -- a LONG call whose range dipped to the stop loss is reported as HIT_SL even if the same window's high also touched a target (conservative: SL always wins when both are ambiguously possible)")
    void updateResultFromRange_long_bothStopAndTargetInRange_stopLossTakesPrecedence() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        // Both the stop loss (90) and target1 (110) fall inside [85, 115] -- can't know which was
        // touched first from a high/low pair alone, so the conservative outcome (SL) is reported.
        updater.updateResultFromRange(call, 115.0, 85.0);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(TradeCallRecord.class));
        assertThat(updateCaptor.getValue().getUpdateObject().toString()).contains("HIT_SL");
    }

    @Test
    @DisplayName("P2-19: updateResultFromRange -- a SHORT call's stop loss is checked against the period HIGH, and its targets against the period LOW (direction-correct, mirrored from the LONG case)")
    void updateResultFromRange_short_usesCorrectSideOfRangeForEachThreshold() {
        call.setDirection("SHORT");
        call.setEntryPrice(java.math.BigDecimal.valueOf(100.0));
        call.setStopLoss(java.math.BigDecimal.valueOf(110.0));
        call.setTarget1(java.math.BigDecimal.valueOf(90.0));
        call.setTarget2(java.math.BigDecimal.valueOf(80.0));
        call.setTarget3(java.math.BigDecimal.valueOf(70.0));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        // High never reached the stop (110); low dipped to target1 (90).
        updater.updateResultFromRange(call, 105.0, 89.0);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(TradeCallRecord.class));
        assertThat(updateCaptor.getValue().getUpdateObject().toString()).contains("HIT_T1");
    }

    @Test
    @DisplayName("P2-19: fetchPriceRange -- parses a Binance klines array into the actual max-high/min-low across every returned candle, not just the last one")
    void fetchPriceRange_binanceKlines_computesMaxHighMinLowAcrossAllCandles() throws Exception {
        call.setMarket("CRYPTO");
        call.setCalledAt(java.time.LocalDateTime.now().minusHours(2));
        var restTemplateField = CallResultUpdater.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        var mockHttp = org.mockito.Mockito.mock(RestTemplate.class);
        // restTemplate is `final`, but not static -- reflection can still overwrite a plain final
        // instance field in Java 21 (same technique already established in this codebase's own
        // BinanceBrokerAdapterTest.injectMockRestTemplate).
        restTemplateField.set(updater, mockHttp);
        // Three candles: highs 105/108/103, lows 99/101/97 -- true range across all three is
        // high=108, low=97, neither of which is the last candle's own high/low alone.
        String klinesJson = "[[0,\"100\",\"105\",\"99\",\"104\",\"1\"],"
            + "[0,\"104\",\"108\",\"101\",\"106\",\"1\"],"
            + "[0,\"106\",\"103\",\"97\",\"100\",\"1\"]]";
        when(mockHttp.getForObject(org.mockito.ArgumentMatchers.contains("api.binance.com"), eq(String.class)))
            .thenReturn(klinesJson);

        var range = updater.fetchPriceRange(call);

        assertThat(range).isNotNull();
        assertThat(range.high()).isEqualTo(108.0);
        assertThat(range.low()).isEqualTo(97.0);
    }
}
