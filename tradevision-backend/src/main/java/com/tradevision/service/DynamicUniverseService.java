package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.TickerStats;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers, filters, and ranks a dynamic candidate universe from the exchange's own tradable
 * USDT symbols, rather than relying on a hard-coded symbol list: exchange symbol discovery ->
 * USDT filter -> spot filter -> volume -> spread -> dynamic candidate universe. Built as a
 * separate, injectable service rather than folded directly into AutonomousScannerService, so
 * its filtering/ranking policy can be tested and reasoned about on its own. Does not apply any
 * symbol-level risk-exclusion list (e.g. a sanctions/delisting-risk blocklist) -- this codebase
 * has no such list to draw from, and inventing one with no real data behind it would be worse
 * than simply not filtering on it.
 *
 * Scope:
 * - Ranks by liquidity (24hr USDT quote volume) primarily, filters out wide spreads, and treats
 *   24hr price-change-percent as an available-but-unweighted signal rather than folding it into
 *   the ranking score -- a genuine volatility-based ranking would need a validated methodology
 *   (e.g. ATR-based, over a real lookback window). Ranking purely by liquidity is the more
 *   conservative, defensible default.
 * - "Minimum order size" is handled downstream, not here: BrokerAdapter.getSymbolRules (already
 *   cached per symbol) is what every order is actually validated against before submission.
 *   This service deliberately does not fetch full SymbolRules for every exchange-wide candidate
 *   (hundreds of symbols) just to pre-filter by minNotional, since that would mean hundreds of
 *   extra, mostly-wasted exchangeInfo calls for symbols that won't survive the liquidity filter
 *   anyway. A candidate that clears this service's filter but later fails minNotional validation
 *   at order-placement time is simply skipped there, the same as any other symbol.
 *
 * The discovered/ranked universe refreshes on a slower cadence (5 minutes) than the 60-second
 * strategy scan that reads from it, using the same AtomicReference+fetchedAt caching shape
 * BinanceBrokerAdapter.getSymbolRules already uses, applied per BrokerMode -- the exchange-wide
 * data this call fetches doesn't vary per credential, so every credential on the same mode
 * shares one cache entry. The full ranked list is what's cached, not a maxSymbols-limited
 * result, since different profiles can configure different dynamicUniverseMaxSymbols values and
 * each needs its own slice of the same underlying ranking.
 */
@Service
@RequiredArgsConstructor
public class DynamicUniverseService {

    private static final Logger log = LoggerFactory.getLogger(DynamicUniverseService.class);

    // Same heuristic spirit as AutonomousScannerService's own MIN_AVG_QUOTE_VOLUME_FOR_USER_SYMBOL
    // -- $500k/24hr is a more conservative floor than that service's own $100k-per-candle figure,
    // since this is screening the entire exchange (which includes a long tail of genuinely
    // illiquid pairs) rather than one user-added symbol at a time.
    private static final BigDecimal MIN_24HR_QUOTE_VOLUME = BigDecimal.valueOf(500_000);
    // A relative bid-ask spread above this is treated as too costly to trade -- 0.5% is a
    // conservative ceiling for a strategy that isn't specifically a market-making one.
    private static final BigDecimal MAX_RELATIVE_SPREAD = BigDecimal.valueOf(0.005);
    // Five times slower than the 60-second strategy scan cycle it feeds.
    private static final java.time.Duration UNIVERSE_CACHE_TTL = java.time.Duration.ofMinutes(5);

    private record CachedUniverse(List<String> rankedSymbols, java.time.Instant fetchedAt) {}
    private final Map<com.tradevision.model.BrokerMode, java.util.concurrent.atomic.AtomicReference<CachedUniverse>> cache
        = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Gates this class's expensive exchange-wide calls (getAllTradableUsdtSymbols,
     * getAll24hrTickers) behind the same request-weight budget check AutonomousScannerService's
     * own order-flow/MTF enrichment uses, so a universe refresh never fires during acute
     * rate-limit pressure even though the 5-minute cache already keeps it infrequent most of the
     * time. ExchangeHealthService has no reference back to this class, so this stays free of a
     * circular bean dependency.
     */
    private final ExchangeHealthService exchangeHealth;

    /**
     * Discovers, filters, and ranks the exchange's own tradable USDT universe for this specific
     * credential's own broker connection, returning at most maxSymbols candidates ordered by
     * liquidity descending. Deliberately takes the caller's own already-resolved BrokerAdapter
     * and BrokerCredential (rather than looking either up itself), since this service has no
     * opinion about which broker/mode a given profile actually uses -- that's
     * AutonomousScannerService's own concern.
     */
    public List<String> selectTopCandidates(BrokerAdapter adapter, BrokerCredential credential, int maxSymbols) {
        var ref = cache.computeIfAbsent(credential.getMode(), k -> new java.util.concurrent.atomic.AtomicReference<>());
        CachedUniverse cached = ref.get();
        if (cached == null || cached.fetchedAt().isBefore(java.time.Instant.now().minus(UNIVERSE_CACHE_TTL))) {
            // A stale-but-still-usable cache is preferred over spending scarce request-weight
            // budget on this refresh during genuine pressure -- the same "serve what we have
            // rather than nothing" reasoning the fetch-failure branch below uses, just triggered
            // by budget pressure instead of an outright failure. If there's no cache at all yet
            // (a cold start under budget pressure), this still returns an empty list rather than
            // forcing the call through -- the first scan cycle simply won't have a discovered
            // universe yet, a smaller cost than risking this server's shared IP getting
            // rate-limited or banned.
            var budget = exchangeHealth.checkRequestBudget();
            if (!budget.healthy()) {
                log.debug("Dynamic universe selection: skipping exchange-wide refresh for {} -- request-weight budget at {}/{} ({}%), "
                    + "reserving remaining capacity for actual trading calls. Serving {}.", credential.getMode(), budget.usedWeight(),
                    budget.limit(), Math.round(budget.usedFraction() * 100), cached != null ? "the existing cached ranking" : "an empty result (no cache yet)");
                if (cached == null) return List.of();
                return cached.rankedSymbols().stream().limit(Math.max(0, maxSymbols)).collect(Collectors.toList());
            }
            List<String> fresh = fetchAndRankFullUniverse(adapter, credential);
            // Only overwrite the cache with a genuinely fresh result -- an empty list from a
            // failed fetch (see fetchAndRankFullUniverse's own catch block) must never evict a
            // still-recent, still-valid cached ranking just because this one attempt failed.
            if (!fresh.isEmpty()) {
                cached = new CachedUniverse(fresh, java.time.Instant.now());
                ref.set(cached);
            } else if (cached == null) {
                return List.of();
            }
            // else: fetch failed but a stale (past-TTL) cached ranking still exists -- better to
            // serve a slightly stale universe than none at all for one failed refresh attempt.
        }
        return cached.rankedSymbols().stream().limit(Math.max(0, maxSymbols)).collect(Collectors.toList());
    }

    private List<String> fetchAndRankFullUniverse(BrokerAdapter adapter, BrokerCredential credential) {
        Set<String> tradableSymbols;
        List<TickerStats> tickers;
        try {
            tradableSymbols = Set.copyOf(adapter.getAllTradableUsdtSymbols(credential.getMode()));
            tickers = adapter.getAll24hrTickers(credential.getMode());
        } catch (Exception e) {
            log.warn("Dynamic universe selection: could not fetch exchange-wide symbol/ticker data for credential {} ({}) -- returning "
                + "no dynamic candidates this pass rather than trading on stale or partial data.", credential.getId(), e.getMessage());
            return List.of();
        }

        Map<String, TickerStats> tickersBySymbol = tickers.stream()
            .collect(Collectors.toMap(TickerStats::symbol, t -> t, (a, b) -> a));

        return tradableSymbols.stream()
            .map(tickersBySymbol::get)
            .filter(t -> t != null)
            .filter(t -> t.quoteVolume() != null && t.quoteVolume().compareTo(MIN_24HR_QUOTE_VOLUME) >= 0)
            .filter(this::hasAcceptableSpread)
            .sorted(Comparator.comparing(TickerStats::quoteVolume).reversed())
            .map(TickerStats::symbol)
            .collect(Collectors.toList());
    }

    private boolean hasAcceptableSpread(TickerStats t) {
        if (t.bidPrice() == null || t.askPrice() == null || t.bidPrice().signum() <= 0) return false;
        BigDecimal mid = t.bidPrice().add(t.askPrice()).divide(BigDecimal.valueOf(2), 12, java.math.RoundingMode.HALF_UP);
        if (mid.signum() <= 0) return false;
        BigDecimal relativeSpread = t.askPrice().subtract(t.bidPrice()).divide(mid, 12, java.math.RoundingMode.HALF_UP);
        return relativeSpread.compareTo(MAX_RELATIVE_SPREAD) <= 0;
    }
}
