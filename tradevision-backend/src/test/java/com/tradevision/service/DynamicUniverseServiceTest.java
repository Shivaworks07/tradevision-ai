package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.TickerStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicUniverseServiceTest {

    @Mock BrokerAdapter adapter;
    @Mock ExchangeHealthService exchangeHealth;
    DynamicUniverseService service;
    BrokerCredential credential;

    @BeforeEach
    void setUp() {
        service = new DynamicUniverseService(exchangeHealth);
        // Review finding ("Scanner has no complete Binance request-budget model" -- external
        // review, twenty-second pass, P1, full context in DynamicUniverseService's own updated
        // exchangeHealth field javadoc): a healthy default so every existing test in this file,
        // none of which are about budget pressure, is unaffected by this new dependency.
        when(exchangeHealth.checkRequestBudget()).thenReturn(new ExchangeHealthService.RequestBudgetStatus(0, 1200, 0.0, true));
        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setMode(BrokerMode.TESTNET);
    }

    @Test
    @DisplayName("selectTopCandidates: ranks by 24hr USDT quote volume descending, and never returns more than maxSymbols -- the actual review fix (\"Strategy universe is still hard-coded\")")
    void selectTopCandidates_ranksByVolumeDescending_respectsLimit() {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT", "BBBUSDT", "CCCUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(
            tickerOf("AAAUSDT", 1_000_000, 100, 100.1),
            tickerOf("BBBUSDT", 5_000_000, 100, 100.1),
            tickerOf("CCCUSDT", 3_000_000, 100, 100.1)
        ));

        List<String> result = service.selectTopCandidates(adapter, credential, 2);

        assertThat(result).containsExactly("BBBUSDT", "CCCUSDT"); // highest volume first, capped at 2
    }

    @Test
    @DisplayName("selectTopCandidates: a symbol below the minimum 24hr quote volume is excluded entirely, regardless of how it would otherwise rank")
    void selectTopCandidates_belowMinVolume_excluded() {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT", "BBBUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(
            tickerOf("AAAUSDT", 100_000, 100, 100.1),   // below the $500k floor
            tickerOf("BBBUSDT", 5_000_000, 100, 100.1)
        ));

        List<String> result = service.selectTopCandidates(adapter, credential, 10);

        assertThat(result).containsExactly("BBBUSDT");
    }

    @Test
    @DisplayName("selectTopCandidates: a symbol with a wide relative bid-ask spread is excluded even with high volume -- liquidity alone isn't the whole story if the spread makes it costly to actually trade")
    void selectTopCandidates_wideSpread_excluded() {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT", "BBBUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(
            tickerOf("AAAUSDT", 5_000_000, 100, 102), // ~2% spread -- well above the 0.5% ceiling
            tickerOf("BBBUSDT", 5_000_000, 100, 100.1)
        ));

        List<String> result = service.selectTopCandidates(adapter, credential, 10);

        assertThat(result).containsExactly("BBBUSDT");
    }

    @Test
    @DisplayName("selectTopCandidates: a tradable symbol with no matching ticker data is skipped rather than causing an error -- exchangeInfo and 24hr ticker data could theoretically disagree momentarily")
    void selectTopCandidates_noMatchingTicker_skippedSafely() {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT", "BBBUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(
            tickerOf("BBBUSDT", 5_000_000, 100, 100.1)
            // AAAUSDT has no matching ticker at all
        ));

        List<String> result = service.selectTopCandidates(adapter, credential, 10);

        assertThat(result).containsExactly("BBBUSDT");
    }

    @Test
    @DisplayName("selectTopCandidates: if fetching exchange-wide data fails entirely, returns no candidates rather than trading on stale or partial data")
    void selectTopCandidates_fetchFails_returnsEmpty() {
        when(adapter.getAllTradableUsdtSymbols(any())).thenThrow(new IllegalStateException("network error"));

        List<String> result = service.selectTopCandidates(adapter, credential, 10);

        assertThat(result).isEmpty();
    }

    private TickerStats tickerOf(String symbol, double quoteVolume, double bid, double ask) {
        return new TickerStats(symbol, BigDecimal.valueOf(quoteVolume), BigDecimal.ZERO, BigDecimal.valueOf(bid), BigDecimal.valueOf(ask));
    }

    @Test
    @DisplayName("selectTopCandidates: a second call within the cache TTL does NOT re-fetch exchange-wide data -- the actual review fix (\"The dynamic universe is recalculated during every autonomous scan\"), reducing exchange load without materially changing candidate selection")
    void selectTopCandidates_secondCallWithinTtl_doesNotRefetch() {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT", "BBBUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(
            tickerOf("AAAUSDT", 1_000_000, 100, 100.1),
            tickerOf("BBBUSDT", 5_000_000, 100, 100.1)
        ));

        List<String> first = service.selectTopCandidates(adapter, credential, 10);
        List<String> second = service.selectTopCandidates(adapter, credential, 10);

        assertThat(first).isEqualTo(second);
        verify(adapter, times(1)).getAllTradableUsdtSymbols(any());
        verify(adapter, times(1)).getAll24hrTickers(any());
    }

    @Test
    @DisplayName("selectTopCandidates: two calls with DIFFERENT maxSymbols against the same cached universe each get their own correctly-sized slice of the same underlying ranking -- proving the full ranked list is what's cached, not a maxSymbols-limited result")
    void selectTopCandidates_differentMaxSymbols_eachGetsOwnSlice() {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT", "BBBUSDT", "CCCUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(
            tickerOf("AAAUSDT", 1_000_000, 100, 100.1),
            tickerOf("BBBUSDT", 5_000_000, 100, 100.1),
            tickerOf("CCCUSDT", 3_000_000, 100, 100.1)
        ));

        List<String> topOne = service.selectTopCandidates(adapter, credential, 1);
        List<String> topThree = service.selectTopCandidates(adapter, credential, 3);

        assertThat(topOne).containsExactly("BBBUSDT");
        assertThat(topThree).containsExactly("BBBUSDT", "CCCUSDT", "AAAUSDT");
        // Still only one real fetch across both calls -- the second served entirely from cache.
        verify(adapter, times(1)).getAll24hrTickers(any());
    }

    /**
     * Review finding ("Scanner has no complete Binance request-budget model" -- external review,
     * twenty-second pass, P1, full context in exchangeHealth's own field javadoc): the actual
     * tests proving the new gate.
     */
    @Test
    @DisplayName("selectTopCandidates: an unhealthy request-weight budget with NO existing cache yet skips the exchange call entirely and returns empty, rather than forcing a genuinely scarce call through")
    void selectTopCandidates_unhealthyBudgetNoCache_skipsCallReturnsEmpty() {
        when(exchangeHealth.checkRequestBudget()).thenReturn(new ExchangeHealthService.RequestBudgetStatus(1150, 1200, 0.958, false));

        List<String> result = service.selectTopCandidates(adapter, credential, 5);

        assertThat(result).isEmpty();
        verify(adapter, org.mockito.Mockito.never()).getAll24hrTickers(any());
        verify(adapter, org.mockito.Mockito.never()).getAllTradableUsdtSymbols(any());
    }

    @Test
    @DisplayName("selectTopCandidates: an unhealthy request-weight budget with an EXISTING, genuinely STALE cache serves that stale cache instead of spending more budget on a refresh")
    void selectTopCandidates_unhealthyBudgetWithStaleCache_servesStaleCacheWithoutRefetching() throws Exception {
        when(adapter.getAllTradableUsdtSymbols(BrokerMode.TESTNET)).thenReturn(List.of("AAAUSDT"));
        when(adapter.getAll24hrTickers(BrokerMode.TESTNET)).thenReturn(List.of(tickerOf("AAAUSDT", 1_000_000, 100, 100.1)));
        List<String> firstResult = service.selectTopCandidates(adapter, credential, 5); // populates the cache while budget is healthy
        assertThat(firstResult).containsExactly("AAAUSDT");

        // Force genuine staleness by reaching into the private cache directly -- this
        // codebase's own production code has no injectable clock to mock Instant.now() through,
        // and manufacturing a real 5-minute wait in a unit test isn't reasonable either.
        var cachedUniverseClass = java.util.Arrays.stream(DynamicUniverseService.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("CachedUniverse")).findFirst().orElseThrow();
        var ctor = cachedUniverseClass.getDeclaredConstructor(List.class, java.time.Instant.class);
        ctor.setAccessible(true);
        Object staleEntry = ctor.newInstance(List.of("AAAUSDT"), java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
        var cacheField = DynamicUniverseService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cacheMap = (java.util.Map<BrokerMode, java.util.concurrent.atomic.AtomicReference<Object>>) cacheField.get(service);
        cacheMap.get(BrokerMode.TESTNET).set(staleEntry);

        when(exchangeHealth.checkRequestBudget()).thenReturn(new ExchangeHealthService.RequestBudgetStatus(1150, 1200, 0.958, false));
        List<String> secondResult = service.selectTopCandidates(adapter, credential, 5);

        assertThat(secondResult).containsExactly("AAAUSDT"); // served from the stale cache, not an empty result
        // Still only the ONE fetch from populating the cache initially -- the stale-cache path
        // under budget pressure must never trigger a second, real exchange call.
        verify(adapter, times(1)).getAll24hrTickers(any());
    }
}
