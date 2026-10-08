package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.AssetBalance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the portfolio aggregation math and the rule that an unpriceable position is
 * excluded from exposure/P&L rather than fabricated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortfolioRiskServiceTest {

    @Mock PositionRepository positionRepo;
    @Mock RiskProfileRepository riskProfileRepo;
    @Mock BrokerAdapter adapter;
    @InjectMocks PortfolioRiskService service;

    private BrokerCredential credential;
    private RiskProfile profile;

    @BeforeEach
    void setup() {
        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);

        profile = new RiskProfile();
        profile.setCredentialId("cred1");
        profile.setMaxConcurrentTrades(5);

        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(profile));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of());
        when(positionRepo.findByCredentialIdAndClosedAtAfter(any(), any())).thenReturn(List.of());
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(new AssetBalance("USDT", BigDecimal.valueOf(1000), BigDecimal.ZERO)));
    }

    private Position openPosition(String symbol, double qty, double avgEntry) {
        Position p = new Position();
        p.setSymbol(symbol);
        p.setQuantity(BigDecimal.valueOf(qty));
        p.setAvgEntryPrice(BigDecimal.valueOf(avgEntry));
        p.setAvgEntryPriceUnverified(false);
        return p;
    }

    @Test
    @DisplayName("snapshot: equity is free balance plus real market value of open positions — not just balance alone")
    void equity_includesOpenPositionMarketValue() {
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(openPosition("BTCUSDT", 1.0, 100)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(110));

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.freeBalance()).isEqualByComparingTo("1000");
        assertThat(snapshot.grossExposure()).isEqualByComparingTo("110"); // 1.0 * 110 current price
        assertThat(snapshot.equity()).isEqualByComparingTo("1110"); // 1000 free + 110 market value
        assertThat(snapshot.unrealizedPnl()).isEqualByComparingTo("10"); // (110-100)*1.0
    }

    @Test
    @DisplayName("snapshot: a position whose live price lookup fails is excluded from exposure/P&L, not assumed zero — and is reported in unpricedSymbols")
    void unpriceablePosition_excludedNotFabricated() {
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(openPosition("BTCUSDT", 1.0, 100)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenThrow(new RuntimeException("connection timeout"));

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.grossExposure()).isEqualByComparingTo("0"); // NOT fabricated using cost basis or any guessed price
        assertThat(snapshot.unpricedSymbols()).hasSize(1);
        assertThat(snapshot.unpricedSymbols().get(0)).contains("BTCUSDT");
    }

    @Test
    @DisplayName("snapshot: multiple positions in the same symbol correctly merge into one exposureBySymbol entry")
    void multiplePositionsSameSymbol_mergeExposure() {
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(
            List.of(openPosition("BTCUSDT", 0.5, 100), openPosition("BTCUSDT", 0.5, 100)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100));

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        // Extracted and compared via isEqualByComparingTo (value-only, scale-insensitive) rather
        // than containsEntry (which is scale-sensitive via BigDecimal.equals) — avoids the test
        // itself needing to predict exactly which BigDecimal.valueOf overload produced which
        // scale internally.
        assertThat(snapshot.exposureBySymbol()).containsKey("BTCUSDT");
        assertThat(snapshot.exposureBySymbol().get("BTCUSDT")).isEqualByComparingTo("100"); // 0.5*100 + 0.5*100
    }

    @Test
    @DisplayName("snapshot: correlation-group exposure correctly sums only the symbols actually in that group")
    void correlationGroupExposure_sumsOnlyGroupSymbols() {
        profile.setCorrelationGroups(java.util.Map.of("L1-majors", java.util.Set.of("BTCUSDT", "ETHUSDT")));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(
            List.of(openPosition("BTCUSDT", 1.0, 100), openPosition("SOLUSDT", 1.0, 50)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100));
        when(adapter.getCurrentPrice("SOLUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(50));

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.exposureByCorrelationGroup()).containsKey("L1-majors");
        assertThat(snapshot.exposureByCorrelationGroup().get("L1-majors")).isEqualByComparingTo("100"); // only BTCUSDT, not SOLUSDT
    }

    @Test
    @DisplayName("snapshot: realized P&L only counts positions closed within the lookback window")
    void realizedPnl_onlyWithinLookbackWindow() {
        Position closed1 = new Position();
        closed1.setRealizedPnlQuote(BigDecimal.valueOf(50));
        Position closed2 = new Position();
        closed2.setRealizedPnlQuote(BigDecimal.valueOf(-20));
        when(positionRepo.findByCredentialIdAndClosedAtAfter(any(), any())).thenReturn(List.of(closed1, closed2));

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.realizedPnl()).isEqualByComparingTo("30"); // 50 + (-20)
    }

    @Test
    @DisplayName("riskState: well within all limits is GREEN")
    void riskState_wellWithinLimits_green() {
        profile.setMaxTotalExposureQuote(BigDecimal.valueOf(10000));
        profile.setMaxConcurrentTrades(10);

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.riskState()).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("riskState: at 95% of the concurrent-position limit is RED, with the correct reason reported")
    void riskState_nearConcurrentLimit_red() {
        profile.setMaxConcurrentTrades(2);
        List<Position> twoOfTwoPositions = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) twoOfTwoPositions.add(openPosition("BTCUSDT" + i, 0.001, 1)); // 2 of 2 slots = 100% -> RED
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(twoOfTwoPositions);
        when(adapter.getCurrentPrice(anyString(), any())).thenReturn(BigDecimal.valueOf(1));

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.riskState()).isEqualTo("RED");
        assertThat(snapshot.riskStateReason()).contains("concurrent position slots");
    }

    @Test
    @DisplayName("riskState: no risk profile configured returns UNKNOWN, not a fabricated GREEN")
    void riskState_noProfile_unknown() {
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.empty());

        var snapshot = service.snapshot(credential, adapter, "key", "secret", 30);

        assertThat(snapshot.riskState()).isEqualTo("UNKNOWN");
    }
}
