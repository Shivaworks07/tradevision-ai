package com.tradevision.service;

import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real tests for the core safety checks, not just "does the app boot". These are the checks
 * that stand between a signal and real money leaving the account.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskEngineServiceTest {

    @Mock PositionRepository positionRepo;
    @Mock RiskProfileRepository riskProfileRepo;
    // recordRealizedLoss uses atomic MongoTemplate operations instead of a plain
    // read-modify-write save(), so this mock is required or these tests NPE on the unmocked
    // MongoTemplate.
    @Mock MongoTemplate mongoTemplate;
    @Mock com.tradevision.repository.OrderRepository orderRepo;
    @InjectMocks RiskEngineService riskEngine;

    // tradingDayZone is a plain @Value field, not a mockable bean type @InjectMocks can wire on
    // its own -- same pattern this codebase already establishes for other @Value fields in a
    // Mockito-only test (FeedbackControllerTest's own buildProperties, for example).
    @BeforeEach
    void setTradingDayZone() {
        org.springframework.test.util.ReflectionTestUtils.setField(riskEngine, "tradingDayZone", "UTC");
    }

    /** Stubs findAndModify to return a RiskProfile reflecting the correct post-increment state,
     *  since the real method's atomicity can't be exercised against a mock — this tests that
     *  RiskEngineService correctly USES whatever the database says the post-increment value is,
     *  not that MongoDB itself is atomic (that's Mongo's own guarantee, not this code's). */
    private void stubFindAndModify(RiskProfile toReturn) {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RiskProfile.class)))
            .thenReturn(toReturn);
    }

    private RiskProfile profile() {
        RiskProfile p = new RiskProfile();
        p.setUserId("u1");
        p.setCredentialId("c1");
        p.setMaxPositionQuoteAmount(BigDecimal.ZERO);
        p.setDailyLossLimitQuote(BigDecimal.ZERO);
        p.setDailyRealizedLossQuote(BigDecimal.ZERO);
        p.setDailyTrackedDate(LocalDate.now());
        p.setMaxConcurrentTrades(5);
        p.setMaxTotalExposureQuote(BigDecimal.ZERO);
        p.setMaxSymbolExposureQuote(BigDecimal.ZERO);
        p.setTradingHalted(false);
        return p;
    }

    private Position openPosition(String symbol, double qty, double entryPrice) {
        Position pos = new Position();
        pos.setSymbol(symbol);
        pos.setQuantity(BigDecimal.valueOf(qty));
        pos.setAvgEntryPrice(BigDecimal.valueOf(entryPrice));
        pos.setStatus("OPEN");
        return pos;
    }

    @Test
    @DisplayName("check: rejects immediately when the kill switch is already engaged, before any other check runs")
    void check_rejectsWhenHalted() {
        RiskProfile p = profile();
        p.setTradingHalted(true);
        p.setHaltReason("manual test halt");

        var result = riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("Kill switch");
        assertThat(result.reason()).contains("manual test halt");
    }

    @Test
    @DisplayName("check: a stale dailyTrackedDate (yesterday) is atomically persisted to today, not just reset in memory")
    void check_persistsDailyResetToDatabase() {
        RiskProfile p = profile();
        p.setDailyTrackedDate(LocalDate.now().minusDays(1)); // yesterday — triggers the reset branch
        p.setDailyRealizedLossQuote(BigDecimal.valueOf(500));

        riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        // The in-memory object is reset either way — what this test verifies is that the reset
        // is ALSO written to Mongo, not just mutated on this local object.
        assertThat(p.getDailyTrackedDate()).isEqualTo(LocalDate.now());
        assertThat(p.getDailyRealizedLossQuote()).isEqualByComparingTo("0");
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(RiskProfile.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.get("dailyTrackedDate")).isEqualTo(LocalDate.now());
        // dailyRealizedLossQuote is DECIMAL128-typed (see RiskProfile model and this service's own
        // toDecimal128 helper) -- a raw BigDecimal $set here would be stored as a String, and
        // MongoDB would then reject a later $inc against it with "Cannot increment with
        // non-numeric argument".
        assertThat(setDoc.get("dailyRealizedLossQuote")).isEqualTo(new org.bson.types.Decimal128(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("check: dailyTrackedDate already today — no reset, no database write at all")
    void check_alreadyToday_noResetWrite() {
        RiskProfile p = profile(); // profile() already defaults dailyTrackedDate to today

        riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("check: rejects and auto-halts when daily realized loss has already reached the limit")
    void check_rejectsAndHaltsAtDailyLossLimit() {
        RiskProfile p = profile();
        p.setDailyLossLimitQuote(BigDecimal.valueOf(100));
        p.setDailyRealizedLossQuote(BigDecimal.valueOf(100)); // already at the limit

        var result = riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("Daily loss limit");
        assertThat(p.isTradingHalted()).isTrue(); // side effect: hitting the limit halts the profile
    }

    @Test
    @DisplayName("check: order-frequency limit disabled by default (maxOrdersPerHour=0) — never queries OrderRepository at all")
    void check_orderFrequencyDisabledByDefault_neverQueries() {
        RiskProfile p = profile(); // maxOrdersPerHour defaults to 0

        riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        verify(orderRepo, never()).countByCredentialIdAndCreatedAtAfter(any(), any());
    }

    @Test
    @DisplayName("check: rejects when the rolling-hour order count has already reached maxOrdersPerHour")
    void check_rejectsAtOrderFrequencyLimit() {
        RiskProfile p = profile();
        p.setMaxOrdersPerHour(5);
        when(orderRepo.countByCredentialIdAndCreatedAtAfter(eq("c1"), any())).thenReturn(5L);

        var result = riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("Order frequency limit");
    }

    @Test
    @DisplayName("check: allows when the rolling-hour order count is still under maxOrdersPerHour")
    void check_allowsUnderOrderFrequencyLimit() {
        RiskProfile p = profile();
        p.setMaxOrdersPerHour(5);
        when(orderRepo.countByCredentialIdAndCreatedAtAfter(eq("c1"), any())).thenReturn(3L);

        var result = riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("check: rejects on tradingHalted before ever reaching the order-frequency check — the kill switch always comes first")
    void check_haltedProfile_neverReachesFrequencyCheck() {
        RiskProfile p = profile();
        p.setMaxOrdersPerHour(5);
        p.setTradingHalted(true);
        p.setHaltReason("manual test halt");

        riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(10));

        verify(orderRepo, never()).countByCredentialIdAndCreatedAtAfter(any(), any());
    }

    @Test
    @DisplayName("check: rejects an order whose value exceeds maxPositionQuoteAmount")
    void check_rejectsOverMaxPositionSize() {
        RiskProfile p = profile();
        p.setMaxPositionQuoteAmount(BigDecimal.valueOf(50));

        var result = riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(51));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("exceeds max position size");
    }

    @Test
    @DisplayName("check: rejects when open position count already meets maxConcurrentTrades")
    void check_rejectsAtMaxConcurrentTrades() {
        RiskProfile p = profile();
        p.setMaxConcurrentTrades(2);
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("u1", "c1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 1, 100), openPosition("ETHUSDT", 1, 100)));

        var result = riskEngine.check(p, "SOLUSDT", BigDecimal.valueOf(10));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("Max concurrent open positions");
    }

    @Test
    @DisplayName("check: allows a new position when under maxConcurrentTrades")
    void check_allowsUnderMaxConcurrentTrades() {
        RiskProfile p = profile();
        p.setMaxConcurrentTrades(2);
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("u1", "c1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 1, 100)));

        var result = riskEngine.check(p, "SOLUSDT", BigDecimal.valueOf(10));

        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("check: rejects when total open exposure plus this order would exceed maxTotalExposureQuote")
    void check_rejectsOverTotalExposureCap() {
        RiskProfile p = profile();
        p.setMaxTotalExposureQuote(BigDecimal.valueOf(1000));
        // existing open: 5 BTC @ 100 = 500 quote exposure
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("u1", "c1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 5, 100)));

        // new order worth 600 -> 500 + 600 = 1100 > 1000 cap
        var result = riskEngine.check(p, "ETHUSDT", BigDecimal.valueOf(600));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("total open exposure");
    }

    @Test
    @DisplayName("check: rejects when a single symbol's exposure plus this order would exceed maxSymbolExposureQuote")
    void check_rejectsOverSymbolExposureCap() {
        RiskProfile p = profile();
        p.setMaxSymbolExposureQuote(BigDecimal.valueOf(500));
        // existing open on BTCUSDT: 3 @ 100 = 300 quote exposure on that symbol specifically
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("u1", "c1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 3, 100), openPosition("ETHUSDT", 10, 100)));

        // another BTCUSDT order worth 300 -> 300 + 300 = 600 > 500 symbol cap, even though
        // total exposure across all symbols may still be under any total cap
        var result = riskEngine.check(p, "BTCUSDT", BigDecimal.valueOf(300));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("BTCUSDT exposure");
    }

    @Test
    @DisplayName("check: correlation group cap rejects when the group's combined exposure would exceed its configured limit")
    void check_rejectsOverCorrelationGroupCap() {
        RiskProfile p = profile();
        Map<String, Set<String>> groups = new HashMap<>();
        groups.put("L1-majors", new HashSet<>(Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT")));
        p.setCorrelationGroups(groups);
        Map<String, BigDecimal> caps = new HashMap<>();
        caps.put("L1-majors", BigDecimal.valueOf(1000));
        p.setCorrelationGroupCaps(caps);

        // BTC 400 + ETH 400 = 800 already in the group
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("u1", "c1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 4, 100), openPosition("ETHUSDT", 4, 100)));

        // a new SOLUSDT order worth 300 -> 800 + 300 = 1100 > 1000 group cap
        var result = riskEngine.check(p, "SOLUSDT", BigDecimal.valueOf(300));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("L1-majors");
    }

    @Test
    @DisplayName("check: a symbol outside any configured correlation group is unaffected by that group's cap")
    void check_correlationGroupCap_ignoresUnrelatedSymbol() {
        RiskProfile p = profile();
        Map<String, Set<String>> groups = new HashMap<>();
        groups.put("L1-majors", new HashSet<>(Set.of("BTCUSDT", "ETHUSDT")));
        p.setCorrelationGroups(groups);
        Map<String, BigDecimal> caps = new HashMap<>();
        caps.put("L1-majors", BigDecimal.valueOf(100)); // tiny cap, but DOGEUSDT isn't in the group
        p.setCorrelationGroupCaps(caps);
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("u1", "c1", "OPEN")).thenReturn(List.of());

        var result = riskEngine.check(p, "DOGEUSDT", BigDecimal.valueOf(500));

        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("recordRealizedLoss: accumulates loss and auto-halts once the daily limit is crossed")
    void recordRealizedLoss_haltsAtLimit() {
        RiskProfile p = profile();
        p.setDailyLossLimitQuote(BigDecimal.valueOf(100));
        p.setDailyRealizedLossQuote(BigDecimal.valueOf(60));

        // Simulates the database's atomic increment result: 60 + 50 = 110.
        RiskProfile postIncrement = profile();
        postIncrement.setDailyLossLimitQuote(BigDecimal.valueOf(100));
        postIncrement.setDailyRealizedLossQuote(BigDecimal.valueOf(110));
        postIncrement.setDailyTrackedDate(p.getDailyTrackedDate());
        stubFindAndModify(postIncrement);

        riskEngine.recordRealizedLoss(p, BigDecimal.valueOf(50)); // 60 + 50 = 110 >= 100

        assertThat(p.getDailyRealizedLossQuote()).isEqualByComparingTo("110");
        assertThat(p.isTradingHalted()).isTrue();
    }

    @Test
    @DisplayName("recordRealizedLoss: a gain (non-positive 'loss' argument) is ignored, never subtracted")
    void recordRealizedLoss_ignoresNonPositiveAmount() {
        RiskProfile p = profile();
        p.setDailyRealizedLossQuote(BigDecimal.valueOf(20));

        riskEngine.recordRealizedLoss(p, BigDecimal.valueOf(-30)); // a "loss" of -30 is actually a gain — must not be applied

        // Non-positive amount returns immediately — findAndModify must never even be called.
        assertThat(p.getDailyRealizedLossQuote()).isEqualByComparingTo("20");
        assertThat(p.isTradingHalted()).isFalse();
        org.mockito.Mockito.verify(mongoTemplate, org.mockito.Mockito.never())
            .findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("recordRealizedLoss: resets the counter when the tracked day has rolled over")
    void recordRealizedLoss_resetsOnNewDay() {
        RiskProfile p = profile();
        p.setDailyLossLimitQuote(BigDecimal.valueOf(1000));
        p.setDailyTrackedDate(LocalDate.now().minusDays(1)); // yesterday
        p.setDailyRealizedLossQuote(BigDecimal.valueOf(900)); // near yesterday's limit

        // Simulates the database's view after both the atomic day-reset AND the increment:
        // reset to 0 for today, THEN +10 — not 900+10=910.
        RiskProfile postIncrement = profile();
        postIncrement.setDailyLossLimitQuote(BigDecimal.valueOf(1000));
        postIncrement.setDailyRealizedLossQuote(BigDecimal.valueOf(10));
        postIncrement.setDailyTrackedDate(LocalDate.now());
        stubFindAndModify(postIncrement);

        riskEngine.recordRealizedLoss(p, BigDecimal.valueOf(10));

        assertThat(p.getDailyRealizedLossQuote()).isEqualByComparingTo("10");
        assertThat(p.getDailyTrackedDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("recordRealizedLoss: every numeric operand sent to Mongo for dailyRealizedLossQuote (both the day-reset $set and the $inc) is a real org.bson.types.Decimal128, never a raw BigDecimal, since a raw BigDecimal would be stored as a String and MongoDB would reject a later $inc against it")
    void recordRealizedLoss_allNumericOperandsAreDecimal128() {
        RiskProfile p = profile();
        p.setDailyTrackedDate(LocalDate.now().minusDays(1)); // yesterday — exercises the day-reset $set path too
        p.setDailyRealizedLossQuote(BigDecimal.valueOf(900));
        stubFindAndModify(profile());

        riskEngine.recordRealizedLoss(p, BigDecimal.valueOf(25));

        ArgumentCaptor<Update> resetCaptor = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<Update> incCaptor = ArgumentCaptor.forClass(Update.class);
        org.mockito.Mockito.verify(mongoTemplate).updateFirst(any(Query.class), resetCaptor.capture(), eq(RiskProfile.class));
        org.mockito.Mockito.verify(mongoTemplate).findAndModify(any(Query.class), incCaptor.capture(), any(FindAndModifyOptions.class), eq(RiskProfile.class));

        Object resetValue = ((org.bson.Document) resetCaptor.getValue().getUpdateObject().get("$set")).get("dailyRealizedLossQuote");
        Object incValue = ((org.bson.Document) incCaptor.getValue().getUpdateObject().get("$inc")).get("dailyRealizedLossQuote");
        assertThat(resetValue).isInstanceOf(org.bson.types.Decimal128.class);
        assertThat(incValue).isInstanceOf(org.bson.types.Decimal128.class);
        assertThat(resetValue).isEqualTo(new org.bson.types.Decimal128(BigDecimal.ZERO));
        assertThat(incValue).isEqualTo(new org.bson.types.Decimal128(BigDecimal.valueOf(25)));
    }

    // ── Strategy consecutive-loss breaker ("Risk" — "strategy consecutive-loss breaker") ────

    @Test
    @DisplayName("recordAutoTradeOutcome: a MANUAL trade never touches the counter at all — no findAndModify, no updateFirst")
    void recordAutoTradeOutcome_manualTrade_noOp() {
        RiskProfile p = profile();
        p.setConsecutiveAutoTradeLosses(3);

        riskEngine.recordAutoTradeOutcome(p, "MANUAL", true);

        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RiskProfile.class));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(RiskProfile.class));
        assertThat(p.getConsecutiveAutoTradeLosses()).isEqualTo(3); // untouched
    }

    @Test
    @DisplayName("recordAutoTradeOutcome: a SIGNAL loss atomically increments the counter")
    void recordAutoTradeOutcome_signalLoss_increments() {
        RiskProfile p = profile();
        p.setConsecutiveAutoTradeLosses(2);
        RiskProfile postIncrement = profile();
        postIncrement.setConsecutiveAutoTradeLosses(3);
        postIncrement.setMaxConsecutiveAutoTradeLosses(0); // disabled — won't halt
        stubFindAndModify(postIncrement);

        riskEngine.recordAutoTradeOutcome(p, "SIGNAL", true);

        assertThat(p.getConsecutiveAutoTradeLosses()).isEqualTo(3);
        assertThat(p.isAutoTradeHalted()).isFalse();
    }

    @Test
    @DisplayName("recordAutoTradeOutcome: a SIGNAL loss that reaches the configured limit halts auto-trading specifically, not the whole profile")
    void recordAutoTradeOutcome_reachesLimit_haltsAutoTradeOnly() {
        RiskProfile p = profile();
        p.setConsecutiveAutoTradeLosses(4);
        RiskProfile postIncrement = profile();
        postIncrement.setConsecutiveAutoTradeLosses(5);
        postIncrement.setMaxConsecutiveAutoTradeLosses(5);
        stubFindAndModify(postIncrement);

        riskEngine.recordAutoTradeOutcome(p, "SIGNAL", true);

        assertThat(p.getConsecutiveAutoTradeLosses()).isEqualTo(5);
        assertThat(p.isAutoTradeHalted()).isTrue();
        assertThat(p.getAutoTradeHaltReason()).contains("5 consecutive");
        assertThat(p.isTradingHalted()).isFalse(); // the broader flag is untouched — manual trading still allowed
    }

    @Test
    @DisplayName("recordAutoTradeOutcome: a SIGNAL win resets the counter to zero, atomically, without ever calling findAndModify")
    void recordAutoTradeOutcome_signalWin_resetsCounter() {
        RiskProfile p = profile();
        p.setConsecutiveAutoTradeLosses(4);

        riskEngine.recordAutoTradeOutcome(p, "SIGNAL", false);

        assertThat(p.getConsecutiveAutoTradeLosses()).isEqualTo(0);
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RiskProfile.class));
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("recordAutoTradeOutcome: already-halted profile is never re-halted (no duplicate audit/incident) if another consecutive loss lands")
    void recordAutoTradeOutcome_alreadyHalted_doesNotReHalt() {
        RiskProfile p = profile();
        p.setConsecutiveAutoTradeLosses(5);
        RiskProfile postIncrement = profile();
        postIncrement.setConsecutiveAutoTradeLosses(6);
        postIncrement.setMaxConsecutiveAutoTradeLosses(5);
        postIncrement.setAutoTradeHalted(true); // already halted from a prior call
        stubFindAndModify(postIncrement);

        riskEngine.recordAutoTradeOutcome(p, "SIGNAL", true);

        // The halt-specific updateFirst (setting autoTradeHalted/autoTradeHaltReason) must not
        // fire again — verify no updateFirst call happened at all for this already-halted case.
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(RiskProfile.class));
    }

    // ── "today" for the daily-loss reset comes from an explicit, configured zone ──────

    @Test
    @DisplayName("today() uses the configured app.trading.day-zone, not the JVM's ambient default zone")
    void today_usesConfiguredZone_notJvmDefault() {
        // A zone far enough from UTC that if today() were using the wrong one, this test would
        // be flaky/wrong around most UTC dates -- explicit rather than relying on the JVM's own
        // default zone, which is exactly the bug being fixed.
        org.springframework.test.util.ReflectionTestUtils.setField(riskEngine, "tradingDayZone", "Pacific/Kiritimati"); // UTC+14

        LocalDate expected = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"));
        assertThat(riskEngine.today()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an invalid/unconfigured app.trading.day-zone fails loudly at the point of use, rather than silently falling back to an undeclared zone")
    void today_invalidZone_throwsRatherThanSilentlyFallingBack() {
        org.springframework.test.util.ReflectionTestUtils.setField(riskEngine, "tradingDayZone", "Not/AZone");

        org.junit.jupiter.api.Assertions.assertThrows(java.time.DateTimeException.class, riskEngine::today);
    }
}
