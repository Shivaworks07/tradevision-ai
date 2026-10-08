package com.tradevision.service;

import com.tradevision.dto.TradeCallRequest;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.model.ScannedCandle;
import com.tradevision.model.StrategyPlan;
import com.tradevision.model.TradeDirection;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Candle;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Autonomous signal discovery: periodically fetches real market data directly from the broker,
 * runs it through the same ServerSignalEngine that already authoritatively validates every
 * signal, and — for anything that clears the bar — creates a TradeCallRecord and dispatches it
 * through the existing evaluateSignal() pipeline, with zero duplicated execution/risk logic. No
 * browser tab, no frontend request, no user action required for a signal to exist in the first
 * place.
 *
 * HONEST SCOPE — what this is NOT, stated plainly rather than implied by omission:
 * - Order-flow specifically remains excluded from the execution-gate combination (a deliberate
 *   choice — a live Binance Futures call on the money-moving path is a real new failure
 *   surface), and this scanner's own enrichment call below still passes null for
 *   smc/orderFlow/vp/regime since it exists only to extract MTF context, not to run the real
 *   combination. An autonomously-discovered TradeCallRecord's features are still sparser than a
 *   frontend-created one in some respects, but ServerSignalEngine does have access to SMC/BOS/
 *   CHOCH/order blocks/FVG/volume profile/order flow/CVD/open interest/funding/regime detection
 *   via SmcEngineService, VolumeProfileService, OrderFlowService, MarketRegimeService and
 *   SignalCombinerService, all wired into this scanner's own enrichment below, and SMC/regime/
 *   volume-profile are folded into the actual execution-gate decision via
 *   NoTradeFilterService's own SignalCombinerService.combine() call.
 * - Not the formal OMS/Signal-lifecycle/FillLedger architecture (GENERATED -> VALIDATING ->
 *   RISK_REJECTED -> APPROVED -> ... states, Order/OrderState, first-class Fill records). This
 *   reuses the existing TradeCallRecord/ExecutedOrder/Position model as-is.
 * - The symbol universe is tiered by liquidity via DynamicUniverseService (exchange-wide USDT
 *   symbol discovery, liquidity/spread filtering, ranked selection), but it is opt-in per
 *   profile (RiskProfile.dynamicUniverseEnabled, default false) rather than always-on, so an
 *   existing profile's own trading behavior never changes just because this shipped. The
 *   ranking itself is liquidity-only (24hr USDT quote volume), not a fuller "volume -> spread ->
 *   volatility -> minimum order size -> liquidity -> risk exclusions" composite -- see
 *   DynamicUniverseService's own class javadoc for exactly which of those this pass does and
 *   doesn't build, and why.
 * - Not rate-limit-budget-aware. getRecentCandles() is called for every distinct symbol this
 *   pass decides to scan, with no tracking of Binance's actual request-weight budget. The
 *   conservative scan interval (60s) and small default symbol set are the mitigation for this
 *   pass, not a real budget tracker.
 * - Market-data-freshness checking is a bare minimum (candle count, not staleness/gaps/spread/
 *   REST-vs-WS-disagreement) — MarketDataQualityService's own further scope covers more.
 */
@Service
@RequiredArgsConstructor
public class AutonomousScannerService {

    private static final Logger log = LoggerFactory.getLogger(AutonomousScannerService.class);

    // Deliberately small and fixed rather than a tiered/liquidity-screened universe — see this
    // class's own javadoc for why. Every auto-trade-enabled profile gets these scanned
    // regardless of its own enabledSymbols configuration, plus whatever it has explicitly added.
    // Package-visible (not private) so the execution-gate authorization can recompute a plan's
    // own allowed symbol set using the same core universe this scanner itself uses, rather than
    // a second, potentially-drifting hardcoded copy. See
    // StrategyPlanService.authorizeExecution's own javadoc.
    static final Set<String> TIER1_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT");
    // A disclosed heuristic, not a statistically-derived value -- same spirit as
    // SlippageMetricsService's own LOW/HIGH volatility thresholds. $100k average quote-asset
    // volume per candle is a conservative floor matching typical "minimum liquidity" heuristics
    // for retail-scale algo trading; a genuinely different risk tolerance would need a different
    // number, stated plainly rather than presented as universally correct.
    private static final double MIN_AVG_QUOTE_VOLUME_FOR_USER_SYMBOL = 100_000;
    // The default/fallback — used when a profile's own scanTimeframe is missing or fails to
    // parse (timeframeToSeconds below) — since a safe fallback still matters when a profile is
    // misconfigured.
    private static final String DEFAULT_SCAN_INTERVAL = "1h";
    // ServerSignalEngine.analyze computes ema(candles, Math.min(200, n-1)), which with 100
    // candles is EMA99, not EMA200, even though the strategy's own logic and labeling assume a
    // real 200-period EMA. Raised to 300 so a genuine EMA200 has the warm-up history it needs.
    private static final int CANDLE_LIMIT = 300;
    private static final int MIN_CANDLES_FOR_SIGNAL = 50;
    private static final Duration SIGNAL_COOLDOWN = Duration.ofMinutes(15);
    // Matches SignalCombinerService's own private NEXT_HIGHER_TF exactly (duplicated here since
    // that one is private to that class) -- used only to decide which interval to fetch; combine()
    // itself independently re-derives the same mapping internally for labeling each MTFContext.
    private static final java.util.Map<String, String> NEXT_HIGHER_TF = java.util.Map.of(
        "1m", "15m", "5m", "1h", "15m", "4h", "30m", "4h", "1h", "4h", "4h", "1d", "1d", "1w"
    );

    /**
     * Converts a profile's own configured scan timeframe into seconds. Binance's kline interval
     * strings verified against its own official docs
     * (developers.binance.com/docs/binance-spot-api-docs) before hardcoding this map. An
     * unrecognized or null value falls back to DEFAULT_SCAN_INTERVAL's own seconds value (3600)
     * rather than throwing — a misconfigured profile shouldn't be able to break the scan for
     * every symbol it's eligible for.
     */
    static long timeframeToSeconds(String timeframe) {
        if (timeframe == null) return 3600;
        return switch (timeframe) {
            case "1m" -> 60;
            case "3m" -> 180;
            case "5m" -> 300;
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h" -> 3600;
            case "2h" -> 7200;
            case "4h" -> 14400;
            case "6h" -> 21600;
            case "8h" -> 28800;
            case "12h" -> 43200;
            case "1d" -> 86400;
            case "3d" -> 259200;
            case "1w" -> 604800;
            case "1M" -> 2592000; // approximated as 30 days — Binance's own "1M" interval isn't a fixed duration either
            default -> 3600; // unrecognized value — fall back to the safe default rather than throw
        };
    }

    private final RiskProfileRepository riskProfileRepo;
    private final BrokerCredentialRepository credentialRepo;
    private final List<BrokerAdapter> adapters;
    private final DynamicUniverseService dynamicUniverseService;
    private final StrategyPlanService strategyPlanService;
    private final com.tradevision.repository.PositionRepository positionRepo;
    private final PositionSafetyService positionSafetyService;
    private final com.tradevision.service.BrokerCredentialService credentialService;
    private final ServerSignalEngine serverSignalEngine;
    // The read side of the ML weight port -- fetches this symbol's current learned weights
    // (or the honest, unadjusted default if none exist yet) immediately before scoring. See
    // ServerSignalEngine.analyze's own overload javadoc.
    private final MLWeightService mlWeightService;
    private final TradeCallService tradeCallService;
    private final com.tradevision.config.ShutdownState shutdownState;
    private final com.tradevision.config.StartupState startupState;
    private final com.tradevision.config.TradingHeartbeatService heartbeatService;
    /** Backs the cross-instance/cross-restart durable candle-dedup claim. See ScannedCandle's own class javadoc. */
    private final com.tradevision.repository.ScannedCandleRepository scannedCandleRepo;
    // The durable backstop this scanner's own in-memory cooldown map falls back to whenever it
    // has no entry for a given dedup key (a fresh process after a restart, or the very first
    // scan of this key in this process). See
    // TradeCallRepository.findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc's own javadoc.
    private final com.tradevision.repository.TradeCallRepository tradeCallRepository;
    private final MarketDataQualityService marketDataQualityService;
    // TradeCallRequest already has smcBias/regime/vpLocation/etc. fields (the manual,
    // frontend-submitted path already populates them), so the scanner populating the same
    // existing fields with server-computed values is additive, not a new schema or a new
    // execution path. Deliberately scoped to what's computable from candles already fetched for
    // this scan (zero new network calls) — order-flow (needs a live Binance futures call) and
    // real multi-timeframe context (needs an additional candle fetch) are not wired in this
    // pass. These three populate observability/feature fields only — they do not touch
    // direction/confidence/entryPrice, which remain driven entirely by ServerSignalEngine +
    // NoTradeFilterService's own independent verification, unchanged.
    private final com.tradevision.service.strategy.MarketRegimeService marketRegimeService;
    private final com.tradevision.service.strategy.SmcEngineService smcEngineService;
    private final com.tradevision.service.strategy.VolumeProfileService volumeProfileService;
    // Order-flow needs a live call to Binance's futures API (a genuinely different risk profile
    // from the other three, which only need candles already fetched for this scan -- see
    // OrderFlowService's own javadoc for the honest disclosure that its live HTTP fetching
    // specifically is unverified end-to-end in a network-blocked development sandbox). Wired in
    // anyway, since OrderFlowService's own per-endpoint graceful-degradation design means a
    // futures-API outage or rate limit here degrades to missing order-flow data for that one
    // scan, not a crash or a blocked trade -- the same additive, non-fatal contract as the other
    // three.
    private final com.tradevision.service.strategy.OrderFlowService orderFlowService;
    // Checks the existing request-weight budget before paying for this specific enrichment's
    // own extra Binance Futures calls (see the gating call below).
    private final ExchangeHealthService exchangeHealth;
    private final com.tradevision.service.strategy.SignalCombinerService signalCombinerService;

    private Map<BrokerType, BrokerAdapter> adapterMap;
    // Dedup: don't re-signal the same user+credential+symbol within the cooldown window, even if
    // the scan runs again a minute later and the indicators still say the same thing.
    private final Map<String, Instant> lastSignalAt = new ConcurrentHashMap<>();
    // The lastSignalAt dedup above is purely time-based (a flat 15-minute cooldown), which is a
    // genuinely different thing from "have I already evaluated this exact closed candle" -- a
    // symbol scanned every 60 seconds on a 1h timeframe gets
    // re-analyzed ~60 times per real candle close, all but the first of which is wasted API/CPU
    // work on data that hasn't changed. Keyed by credentialId+symbol+timeframe (not just
    // credentialId+symbol, since a profile's own scanTimeframe can differ from another's for
    // the same symbol) -> the last-closed candle's own timestamp that was actually analyzed.
    private final Map<String, Long> lastProcessedCandleTime = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000, scheduler = "scanScheduler")
    public void scan() {
        if (shutdownState.isShuttingDown()) return;
        if (!startupState.isTradingEnabled()) return; // don't discover new trades before startup reconciliation confirms real broker state

        ensureAdapterMap();

        List<RiskProfile> activeProfiles = riskProfileRepo.findByAutoTradeEnabledTrueAndTradingHaltedFalse().stream()
            // tradingHalted (the query above) and autoTradeHalted are deliberately separate
            // flags — see RiskProfile's own field comments for exactly what distinguishes them.
            // Filtered in-memory rather than adding a new compound repository query method for
            // one additional boolean condition.
            .filter(p -> !p.isAutoTradeHalted())
            .toList();
        // Recorded here, unconditionally, once the gates above pass — a cycle with genuinely
        // zero active profiles to scan is still a healthy cycle (the worker ran, it just had no
        // work), not something a watchdog should ever treat as "stuck". Recording this only
        // inside the loop below would produce a false DOWN for any user with zero
        // auto-trade-enabled profiles configured.
        heartbeatService.recordScanCompleted();
        if (activeProfiles.isEmpty()) return;

        // This scheduler's own fixedDelay means a scan that takes 45s effectively becomes a
        // ~105s cycle -- multiple plans/dynamic symbols make this worse, and for a 1m strategy
        // that's long enough to miss candle opportunities. This does not redesign the scheduler
        // (a genuine fix needs either per-timeframe scheduling or a closed-kline WebSocket) --
        // this is only visibility, so an operator can actually see the overrun happening rather
        // than silently losing scan cadence. 50s is deliberately short of the 60s interval
        // itself, so this fires before the next cycle's own delay compounds the problem further.
        long scanStartedAt = System.currentTimeMillis();
        for (RiskProfile profile : activeProfiles) {
            try {
                scanForProfile(profile);
            } catch (Exception e) {
                // One profile's scan failing (bad credential, adapter error, whatever) must
                // never stop the scan for every other profile in the same pass.
                log.warn("Autonomous scan failed for profile {} (credential {}): {}", profile.getId(), profile.getCredentialId(), e.getMessage());
            }
        }
        long scanDurationMs = System.currentTimeMillis() - scanStartedAt;
        if (scanDurationMs > 50_000) {
            log.warn("Autonomous scan took {}ms across {} profile(s) -- approaching or exceeding this scheduler's own 60s interval. "
                + "For fast timeframes (1m/3m), this means real candle opportunities can be missed; consider reducing enabled symbols/"
                + "plans per credential, or per-timeframe scheduling / a closed-kline WebSocket if it recurs.",
                scanDurationMs, activeProfiles.size());
        }
    }

    private void scanForProfile(RiskProfile profile) {
        BrokerCredential credential = credentialRepo.findById(profile.getCredentialId()).orElse(null);
        if (credential == null || !credential.isActive()) return;
        // adapterMap is keyed by BrokerType only and has no PAPER concept; routing through
        // adapterForCredential is what special-cases BrokerMode.PAPER to the simulated adapter,
        // so a PAPER credential never resolves to the real broker adapter.
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        if (adapter == null) return;

        // User's own explicit multi-strategy-plan design ("Run multiple plans simultaneously"),
        // full context in StrategyPlanService.getEnabledPlans's own javadoc: iterates every
        // enabled plan for this credential, each with its own timeframe/direction/universe --
        // replaces the earlier single-default-plan-id-stamped-onto-one-global-scan version.
        java.util.List<StrategyPlan> plans;
        try {
            plans = strategyPlanService.getEnabledPlans(profile.getCredentialId());
        } catch (Exception e) {
            log.warn("Autonomous scan: could not fetch strategy plans for credential {} ({}) -- skipping this credential this pass.",
                profile.getCredentialId(), e.getMessage());
            return;
        }
        for (StrategyPlan plan : plans) {
            // A defensive, final re-check at the actual point of execution, never trusting a
            // single upstream method's own name/contract alone for a real-money safety
            // property. Belt-and-suspenders: getEnabledPlans is correctly named, but this
            // scanner must never silently process a disabled plan even if a future change to
            // that method's own logic reopened the gap. See
            // StrategyPlanService.getEnabledPlans's own javadoc.
            if (!plan.isEnabled()) continue;
            scanForPlan(profile, credential, adapter, plan);
        }
    }

    /**
     * Each plan independently owns its own coin universe (TIER1 + its own enabledSymbols + its
     * own dynamic-universe setting/cap).
     */
    private void scanForPlan(RiskProfile profile, BrokerCredential credential, BrokerAdapter adapter, StrategyPlan plan) {
        // A plan outside its own configured trading window is never scanned for new entries at
        // all this pass -- ALWAYS_ON (the default) is always within session, so this is a no-op
        // for every plan that hasn't explicitly configured a session. See
        // StrategyPlanService.isWithinSession's own javadoc.
        if (!strategyPlanService.isWithinSession(plan)) return;

        Set<String> symbolsToScan = new HashSet<>(TIER1_SYMBOLS);
        if (plan.getEnabledSymbols() != null) symbolsToScan.addAll(plan.getEnabledSymbols());
        if (plan.isDynamicUniverseEnabled()) {
            try {
                symbolsToScan.addAll(dynamicUniverseService.selectTopCandidates(adapter, credential, plan.getDynamicUniverseMaxSymbols()));
            } catch (Exception e) {
                log.warn("Autonomous scan: dynamic universe selection failed for credential {} plan {} ({}) -- scanning TIER1/"
                    + "enabledSymbols only for this plan this pass.", profile.getCredentialId(), plan.getId(), e.getMessage());
            }
        }

        // A plan's own universe is never bigger than what the account's own profile has
        // explicitly enabled -- TIER1 membership is not a free pass on its own; a symbol only
        // survives this intersection when the user separately, explicitly enabled that exact
        // symbol too. Without this, a user who configured "only ADAUSDT" could still have this
        // scanner discover and trade BTC/ETH/SOL/BNB/XRP with real money, purely because those
        // five are hardcoded as always-on candidates.
        symbolsToScan.retainAll(profile.getEnabledSymbols());

        for (String symbol : symbolsToScan) {
            scanOneSymbol(profile, credential, adapter, symbol, plan);
        }
    }

    /**
     * Attempts to insert a durable claim record for this exact credential/symbol/timeframe/
     * candle-close-time combination -- MongoDB's own unique index on claimKey (created in
     * IndexInitializer) is what makes this a genuine, cross-instance guarantee (see
     * ScannedCandle's own class javadoc for the full "why a durable claim record" background).
     *
     * The fail-open-vs-fail-closed-by-mode policy below (LIVE fails closed on an ambiguous claim
     * result, TESTNET/PAPER fails open) is a codebase-wide policy, not something specific to
     * this one method: see docs/adr/0001-fail-open-vs-fail-closed-by-mode.md for the full
     * reasoning. DuplicateKeyException specifically always means genuinely already claimed
     * (returns false) regardless of mode -- that part is specific to this method, not the ADR.
     */
    private boolean tryClaimCandleProcessing(String candleDedupKey, long candleCloseTimeMillis, BrokerMode mode) {
        String claimKey = candleDedupKey + "|" + candleCloseTimeMillis;
        try {
            var claim = new ScannedCandle();
            claim.setClaimKey(claimKey);
            scannedCandleRepo.save(claim);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        } catch (Exception e) {
            if (mode == BrokerMode.LIVE) {
                log.error("Could not verify the cross-instance candle claim for {} on a LIVE credential -- failing CLOSED (skipping "
                    + "this scan) rather than risk an unknown duplicate evaluation. A later scan cycle will retry once this resolves: {}",
                    claimKey, e.getMessage());
                return false;
            }
            log.warn("Could not verify the cross-instance candle claim for {} (non-fatal for TESTNET/PAPER -- proceeding with this scan "
                + "regardless, since no real money is at risk here): {}", claimKey, e.getMessage());
            return true;
        }
    }

    /**
     * This scanner's own @Scheduled(fixedDelay = 60_000) means a 1m candle can close and this
     * scanner may not act on it for up to a full minute -- for a 1m strategy specifically, this
     * can genuinely miss the intended entry. This method is the data-gathering half of the
     * event-driven path, extracted here so Kline1mStreamService (the WebSocket half) and this
     * class's own periodic scan share one traversal of "which profiles/credentials/plans are
     * actively watching which symbol", not two independently-maintained copies that could drift.
     *
     * HONEST SCOPE, stated plainly: dynamic-universe symbols (plan.isDynamicUniverseEnabled())
     * are deliberately excluded from this method's own result -- computing them requires a live
     * REST call (dynamicUniverseService.selectTopCandidates), and this method is also called to
     * build the WebSocket's own subscription list, where making a REST call just to decide what
     * to subscribe to would defeat a real part of the point. A 1m plan's own TIER1 and explicitly
     * enabledSymbols are covered by the immediate, event-driven trigger; its dynamic-universe
     * symbols remain covered only by this class's own existing 60s REST poll -- narrower than
     * "every symbol a 1m plan could ever touch", but a real, meaningful improvement for the
     * common case (a plan's own explicitly-configured symbols), not nothing.
     */
    record OneMinuteScanTarget(RiskProfile profile, BrokerCredential credential, BrokerAdapter adapter,
                                StrategyPlan plan, String symbol) {}

    List<OneMinuteScanTarget> compute1mScanTargets() {
        List<OneMinuteScanTarget> targets = new ArrayList<>();
        List<RiskProfile> activeProfiles = riskProfileRepo.findByAutoTradeEnabledTrueAndTradingHaltedFalse().stream()
            .filter(p -> !p.isAutoTradeHalted())
            .toList();
        for (RiskProfile profile : activeProfiles) {
            BrokerCredential credential = credentialRepo.findById(profile.getCredentialId()).orElse(null);
            if (credential == null || !credential.isActive()) continue;
            // 1m scan targets must be resolved through adapterForCredential so a PAPER
            // credential's scans and any downstream orders stay on the simulated adapter, same
            // as scanForProfile.
            BrokerAdapter adapter = credentialService.adapterForCredential(credential);
            if (adapter == null) continue;
            List<StrategyPlan> plans;
            try {
                plans = strategyPlanService.getEnabledPlans(profile.getCredentialId());
            } catch (Exception e) {
                continue; // a market-data/DB hiccup for one credential shouldn't stop the rest
            }
            for (StrategyPlan plan : plans) {
                if (!plan.isEnabled()) continue;
                if (!"1m".equalsIgnoreCase(plan.getTimeframe())) continue;
                if (!strategyPlanService.isWithinSession(plan)) continue;
                Set<String> symbols = new HashSet<>(TIER1_SYMBOLS);
                if (plan.getEnabledSymbols() != null) symbols.addAll(plan.getEnabledSymbols());
                // The 1m target list must be gated by the account's own explicit whitelist too --
                // this is the other place this codebase builds a plan's symbol universe, same
                // reasoning as scanForPlan above.
                symbols.retainAll(profile.getEnabledSymbols());
                for (String symbol : symbols) {
                    targets.add(new OneMinuteScanTarget(profile, credential, adapter, plan, symbol));
                }
            }
        }
        return targets;
    }

    /** Lazily built, same as scan() already did inline -- extracted so compute1mScanTargets()
     *  (which may run independently of, and before, scan()'s own first scheduled invocation)
     *  can also use it without duplicating this initialization. */
    private Map<BrokerType, BrokerAdapter> ensureAdapterMap() {
        if (adapterMap == null) {
            adapterMap = adapters.stream().collect(Collectors.toMap(BrokerAdapter::getType, Function.identity()));
        }
        return adapterMap;
    }

    // Package-visible (not private) so Kline1mStreamService can trigger an immediate re-scan the
    // instant a real 1m candle closes, reusing this exact method -- including its own
    // cooldown/dedup check, its own REST candle fetch, and its own full analysis pipeline --
    // rather than a second, potentially-drifting copy of any of that logic. See
    // Kline1mStreamService's own class javadoc.
    void scanOneSymbol(RiskProfile profile, BrokerCredential credential, BrokerAdapter adapter, String symbol,
                                StrategyPlan plan) {
        // Dedup key includes the plan id -- two plans watching the same symbol at different
        // timeframes (the user's own explicit design: "Plan A -> 1m LONG+SHORT" and "Plan B ->
        // 5m LONG" both watching the same coin) must never share a cooldown, or one plan's
        // signal would suppress the other's.
        String dedupKey = profile.getUserId() + "|" + profile.getCredentialId() + "|" + plan.getId() + "|" + symbol;
        java.time.Duration cooldown = java.time.Duration.ofMinutes(Math.max(1, plan.getCooldownMinutes()));
        Instant last = lastSignalAt.get(dedupKey);
        // A fresh process (just restarted, or this is the first scan of this exact dedup key
        // since startup) has no in-memory entry yet -- treating that as "never signaled, cooldown
        // does not apply" would be wrong whenever a signal was actually emitted moments before
        // the restart. Falls back to the last persisted TradeCallRecord for this exact
        // user+plan+symbol only
        // when the in-memory map has nothing (one extra DB read on the miss path only, not every
        // scan), and immediately re-populates the in-memory map either way so this fallback is
        // never repeated for the same key while this process keeps running.
        if (last == null) {
            last = tradeCallRepository.findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc(profile.getUserId(), plan.getId(), symbol)
                .map(rec -> rec.getCalledAt().atZone(java.time.ZoneId.systemDefault()).toInstant())
                .orElse(null);
            if (last != null) lastSignalAt.put(dedupKey, last);
        }
        if (last != null && last.isAfter(Instant.now().minus(cooldown))) return;

        // User's own explicit design: "A plan explicitly owns its timeframe." Falls back to
        // DEFAULT_SCAN_INTERVAL only if the plan's own timeframe is somehow blank -- matches
        // this codebase's own pre-existing fallback convention for a missing/invalid interval.
        String scanInterval = (plan.getTimeframe() != null && !plan.getTimeframe().isBlank())
            ? plan.getTimeframe() : DEFAULT_SCAN_INTERVAL;

        List<Candle> candles;
        try {
            candles = adapter.getRecentCandles(symbol, scanInterval, CANDLE_LIMIT, credential.getMode());
        } catch (Exception e) {
            log.debug("Autonomous scan: could not fetch candles for {} ({}, {}): {}", symbol, credential.getMode(), profile.getCredentialId(), e.getMessage());
            return; // a market-data hiccup for one symbol shouldn't spam logs or stop other symbols
        }
        // +1: the last element gets dropped as the forming candle before analysis below — this
        // must guarantee MIN_CANDLES_FOR_SIGNAL CLOSED candles remain, not count the soon-to-be-
        // dropped one toward the minimum.
        if (candles == null || candles.size() < MIN_CANDLES_FOR_SIGNAL + 1) return;

        // TIER1_SYMBOLS is a fixed, known-liquid list this codebase already trusts, but
        // profile.getEnabledSymbols() lets a user add any symbol, with no check that it's
        // actually liquid enough to trade safely. Uses the candles already fetched above (no new API call, no new data pipeline
        // -- Candle.volume already exists), so this costs nothing extra. Deliberately scoped to
        // non-TIER1 symbols only: applying this to the core, already-vetted symbols too would
        // risk silently skipping BTC/ETH/etc. during a temporary, ordinary volume dip, which is
        // exactly the kind of behavior change this pass shouldn't introduce for symbols that
        // were never the actual problem.
        if (!TIER1_SYMBOLS.contains(symbol)) {
            // Candle.volume is BASE-asset volume (e.g. BTC, not USDT) -- a fixed absolute
            // threshold on that raw number wouldn't mean the same thing for two symbols with
            // very different prices (a low-price altcoin's base-asset volume can be huge in its
            // own units while being genuinely illiquid in real dollar terms). volume * close
            // approximates real, comparable QUOTE-asset (USDT) volume per candle instead, the
            // actual, cross-symbol-comparable liquidity measure this check needs.
            double avgQuoteVolume = candles.stream().mapToDouble(c -> c.volume() * c.close()).average().orElse(0);
            if (avgQuoteVolume < MIN_AVG_QUOTE_VOLUME_FOR_USER_SYMBOL) {
                log.info("Autonomous scan: skipping user-added symbol {} -- average quote-asset volume ${} over the last "
                    + "{} candles is below the minimum ${} this codebase considers safely liquid enough to trade.",
                    symbol, String.format("%.2f", avgQuoteVolume), candles.size(), MIN_AVG_QUOTE_VOLUME_FOR_USER_SYMBOL);
                return;
            }
        }

        // Market-data quality gate: every strategy effectively asks isMarketSafeToTrade(symbol)?
        // before analysis runs, not after.
        var quality = marketDataQualityService.isMarketSafeToTrade(symbol, candles, timeframeToSeconds(scanInterval), adapter, credential.getMode());
        if (!quality.safe()) {
            log.info("Autonomous scan: skipping {} ({}) — market data quality check failed: {}",
                symbol, credential.getMode(), String.join("; ", quality.issues()));
            return;
        }

        // Declared at this method's own scope so both the signal-analysis try block below and
        // the SMC/regime/volume-profile enrichment try block further down can both see it.
        //
        // getRecentCandles' own last element is the in-progress candle for the current
        // interval, not a closed one -- without dropping it, the signal could flip repeatedly
        // within the same still-forming candle (10:00 -> LONG, 10:15 -> WAIT, 10:30 -> LONG,
        // 10:45 -> SHORT all inside the same hourly candle). Dropped before strategy analysis
        // specifically (not from the quality check above, which benefits from seeing the
        // freshest — possibly still-forming — candle to judge whether data is actually
        // flowing right now). For autonomous trading, this is an explicit choice, not
        // accidental behavior — closed-candle-only is that choice.
        List<Candle> closedCandles = candles.subList(0, candles.size() - 1);

        com.tradevision.service.ServerSignalEngine.Signal signal;
        try {
            // If the last-closed candle's own timestamp is the same one already analyzed for
            // this exact credential+symbol+timeframe, skip re-running the strategy engine on
            // data that hasn't changed. See lastProcessedCandleTime's own field comment. A
            // genuinely new closed candle (a different timestamp) always proceeds. This check is
            // deliberately scoped to skipping wasted strategy analysis work specifically -- it
            // runs after the quality check above (which still benefits from checking every scan,
            // not just new candles) and before signal creation, not as an early-return before
            // this method's other responsibilities.
            String candleDedupKey = profile.getCredentialId() + "|" + symbol + "|" + scanInterval;
            long lastClosedCandleTime = closedCandles.get(closedCandles.size() - 1).time();
            Long previouslyProcessed = lastProcessedCandleTime.get(candleDedupKey);
            if (previouslyProcessed != null && previouslyProcessed == lastClosedCandleTime) {
                log.debug("Autonomous scan: skipping {} ({}) -- candle at {} already analyzed, nothing new to evaluate.",
                    symbol, scanInterval, lastClosedCandleTime);
                return;
            }
            // The in-memory check just above is a fast, same-JVM-only pre-filter (kept for its
            // own sake, avoiding a DB round trip on the common case of repeatedly scanning the
            // same still-current candle within one process); this is the cross-instance,
            // cross-restart authoritative claim. See ScannedCandle's own class javadoc. If this
            // exact credential/symbol/timeframe/candle-close-time was already claimed by any
            // instance (including this one, in an earlier process before a restart), skip -- a
            // genuinely new candle (a different close time) always proceeds regardless of
            // history.
            if (!tryClaimCandleProcessing(candleDedupKey, lastClosedCandleTime, credential.getMode())) {
                log.debug("Autonomous scan: skipping {} ({}) -- candle at {} already claimed by another instance/process, "
                    + "nothing new to evaluate.", symbol, scanInterval, lastClosedCandleTime);
                lastProcessedCandleTime.put(candleDedupKey, lastClosedCandleTime); // sync the fast, in-memory check too
                return;
            }

            // Fetched fresh before every scan, not cached -- this is a single, cheap, indexed
            // lookup (or a fast in-memory default when nothing's been learned yet for this
            // symbol), not worth caching at the cost of scoring against a stale weight snapshot.
            // See ServerSignalEngine.analyze's own overload javadoc. "CRYPTO" matches this scanner's own hardcoded market value
            // used identically a few lines below when this signal is later saved. Wrapped in its
            // own, inner try/catch specifically: this lookup is purely additive to scoring, not
            // essential to whether a signal can be computed at all -- a transient MongoDB
            // hiccup on just this call must degrade to the same fixed-weight scoring this
            // method has always used, not skip the entire scan the way the outer catch below
            // would (that outer catch exists for failures in the actual analysis itself).
            com.tradevision.model.MLWeights mlWeights = null;
            try {
                mlWeights = mlWeightService.getWeights("CRYPTO", symbol);
            } catch (Exception e) {
                log.debug("Autonomous scan: could not fetch ML weights for {} (non-fatal, falling back to fixed defaults): {}", symbol, e.getMessage());
            }
            signal = serverSignalEngine.analyze(closedCandles, mlWeights);
            // Marked as processed only AFTER a successful analysis, not before -- if analyze()
            // itself throws (caught below), a transient failure must not permanently mark this
            // candle as "already evaluated" when it never actually was, which would silently
            // skip a genuine signal opportunity on every subsequent scan of the same candle.
            lastProcessedCandleTime.put(candleDedupKey, lastClosedCandleTime);
        } catch (Exception e) {
            log.warn("Autonomous scan: signal analysis failed for {}: {}", symbol, e.getMessage());
            return;
        }

        // Spot-only, long-only — matches this codebase's own design everywhere else
        // (AutoTradeService never executes SHORT/WAIT). No point creating a record that would
        // just be silently ignored downstream.
        if (!"LONG".equalsIgnoreCase(signal.direction())) {
            // A SHORT/bearish signal is exactly what this codebase would otherwise silently drop
            // here -- but before dropping it, it's still the real, ground-truth signal a plan
            // with exitOnSignalReversal enabled needs to see, for the position it might already
            // hold on this exact symbol. See checkSignalReversalExit's own javadoc.
            checkSignalReversalExit(profile, credential, symbol, plan, signal);
            return;
        }
        // A plan explicitly owns its direction. A plan configured SHORT-only must not silently
        // substitute a LONG signal it never asked for -- even though this codebase can only ever
        // execute long-only anyway (the check above), a plan that only wants SHORT setups
        // shouldn't have LONG ones sneaked in just because SHORT isn't executable.
        if (plan.getDirection() == TradeDirection.SHORT) return;
        // Cheap pre-filter, not a substitute for AutoTradeService's own authoritative
        // confidence/finite-value gate — that gate still runs regardless, this just avoids
        // creating a database record for a signal that would obviously fail it anyway.
        if (!Double.isFinite(signal.confidence()) || signal.confidence() < plan.getMinConfidence()) return;
        if (!Double.isFinite(signal.entry()) || !Double.isFinite(signal.stopLoss()) || !Double.isFinite(signal.target1())
                || signal.entry() <= 0 || signal.stopLoss() <= 0 || signal.target1() <= 0) return;

        TradeCallRequest req = new TradeCallRequest();
        // candleCount feeds NoTradeFilterService's sufficientHistory check (candleCount >= 50) --
        // must be set explicitly here or every signal on every symbol would fail that gate
        // regardless of how much real history existed.
        req.setCandleCount(closedCandles.size());
        // Additive, non-fatal by design (matching this whole codebase's own established
        // pattern) — a bug in this enrichment must never block a signal the rest of the
        // pipeline would otherwise accept. Populates TradeCallRequest's own
        // pre-existing smcBias/regime/vpLocation/etc. fields, which the manual frontend path
        // never actually populates either (checked directly, not assumed) -- these feed the ML
        // feature-export pipeline (TradeFeatures/MLDatasetExportService), not execution.
        try {
            var smc = smcEngineService.analyze(closedCandles, symbol);
            var regimeState = marketRegimeService.detect(closedCandles, symbol);
            var vp = volumeProfileService.analyze(closedCandles, 50);
            double price = signal.entry();

            req.setSmcBias(smc.bias());
            req.setSmcBiasStrength(smc.biasStrength());
            req.setBosDetected(smc.structureBreaks().stream().anyMatch(b -> b.type().equals("BOS")));
            req.setChochDetected(smc.structureBreaks().stream().anyMatch(b -> b.type().equals("CHOCH")));
            // "Near" defined the same way this class's own ported SMC engine already defines
            // proximity for its own entry-setup detection (see SmcEngineService.findEntrySetup) —
            // reused for consistency rather than inventing a second, different definition, since
            // no pre-existing frontend or backend contract for these two specific fields exists
            // to match instead (checked directly -- the frontend never populates them at all).
            req.setOrderBlockNear(smc.orderBlocks().stream().anyMatch(ob -> price >= ob.bottom() * 0.995 && price <= ob.top() * 1.01));
            req.setFvgNear(smc.fairValueGaps().stream().anyMatch(f -> price >= f.bottom() * 0.99 && price <= f.top() * 1.01));
            if (smc.premiumDiscount() != null) req.setPdZone(smc.premiumDiscount().currentZone());

            req.setVpLocation(vp.priceLocation());
            req.setVpPoc(vp.poc());
            req.setVpVah(vp.vah());
            req.setVpVal(vp.val());
            if (price > 0) req.setPocDistancePct(Math.abs(price - vp.poc()) / price * 100);

            req.setRegime(regimeState.regime());
            req.setRegimeAdx(regimeState.adx());
            req.setRegimeBbWidth(regimeState.bbWidth());
        } catch (Exception e) {
            log.debug("Autonomous scan: SMC/regime/volume-profile enrichment failed for {} (non-fatal, additive only): {}", symbol, e.getMessage());
        }

        // This enrichment does not feed the actual trading decision: this call's own combine()
        // below passes null for smc/orderFlow/volumeProfile/regime, since this is pure
        // observability populating TradeCallRequest fields, not the execution gate --
        // NoTradeFilterService's own separate combine() call is that gate, and deliberately
        // excludes order-flow too, since a live Binance Futures call is a new external failure
        // surface this codebase has chosen not to add to a money-moving decision path. Since it
        // doesn't affect the decision, it's made skippable under real budget pressure, using the
        // request-weight tracking that already exists (ExchangeHealthService.recordUsedWeight is
        // wired to Binance's own X-MBX-USED-WEIGHT-1M header on every response).
        var budget = exchangeHealth.checkRequestBudget();
        if (!budget.healthy()) {
            log.debug("Autonomous scan: skipping order-flow/MTF enrichment for {} -- request-weight budget at {}/{} ({}%), "
                + "reserving remaining capacity for actual trading calls.", symbol, budget.usedWeight(), budget.limit(),
                Math.round(budget.usedFraction() * 100));
        } else {
        // Order-flow and real multi-timeframe context, in their own separate try/catch -- a
        // failure here must not discard the SMC/regime/volume-profile enrichment already
        // captured above, and vice versa. Independent per-component failure isolation, the same
        // principle as everywhere else in this codebase.
        try {
            var orderFlow = orderFlowService.analyze(symbol);
            req.setOfBias(orderFlow.overallBias());
            req.setOfScore((int) Math.round(orderFlow.score()));
            if (orderFlow.funding() != null) req.setFundingRate(orderFlow.funding().fundingRate());
            if (orderFlow.openInterest() != null) req.setOiSignal(orderFlow.openInterest().signal());
            if (orderFlow.cvd() != null) req.setCvdTrend(orderFlow.cvd().cvdTrend());

            String higherTf = NEXT_HIGHER_TF.getOrDefault(scanInterval, "4h");
            List<Candle> higherTfCandles = adapter.getRecentCandles(symbol, higherTf, CANDLE_LIMIT, credential.getMode());
            var combined = signalCombinerService.combine(signal, "", scanInterval, higherTfCandles, null, null, null, null, null);
            req.setMtfAlignment(combined.mtfAlignment());
            if (!combined.mtfContext().isEmpty()) req.setHtf1Trend(combined.mtfContext().get(0).trend());
            if (combined.mtfContext().size() > 1) req.setHtf2Trend(combined.mtfContext().get(1).trend());
        } catch (Exception e) {
            log.debug("Autonomous scan: order-flow/MTF enrichment failed for {} (non-fatal, additive only): {}", symbol, e.getMessage());
        }
        }

        req.setSymbol(symbol);
        req.setMarket("CRYPTO");
        req.setTimeframe(scanInterval);
        req.setPlanId(plan.getId());
        req.setPlanVersion(plan.getVersion());
        req.setDirection(signal.direction());
        req.setSignal(signal.signalLabel());
        req.setConfidence((int) Math.round(signal.confidence()));
        req.setEntryPrice(signal.entry());
        req.setStopLoss(signal.stopLoss());
        req.setTarget1(signal.target1());
        req.setTarget2(signal.target2());
        req.setTarget3(signal.target3());
        // These 4 fields must be set explicitly for an autonomously-discovered signal, or this
        // record's own TradeFeatures would silently default to rsi=0/macdBull=false/
        // patterns=[]/volumeRatio=0, which MLWeightService.recordOutcome would then learn from
        // as if those were the real values. Wired from the same Signal object that already
        // computed them internally in ServerSignalEngine.analyze -- see Signal's own record
        // definition for why these 4 raw values are captured on it at all. See
        // MLWeightService's own header javadoc.
        req.setRsi(signal.rsi14());
        req.setMacdBull(signal.macdBull());
        req.setPatterns(signal.patterns());
        req.setVolumeRatio(signal.volumeRatio());
        req.setSummary("Autonomously discovered by the server-side scanner — no browser or frontend interaction involved.");

        try {
            tradeCallService.saveCall(profile.getUserId(), req, true);
            lastSignalAt.put(dedupKey, Instant.now());
            log.info("Autonomous scan: discovered {} LONG on {} (confidence {}) for user {} — dispatched for evaluation.",
                symbol, credential.getMode(), signal.confidence(), profile.getUserId());
        } catch (Exception e) {
            log.warn("Autonomous scan: failed to save/dispatch discovered signal for {} on {}: {}", symbol, profile.getUserId(), e.getMessage());
        }
    }

    /**
     * Lets a strategy plan exit on a signal reversal rather than only via TP/SL. Opt-in per plan
     * (exitOnSignalReversal, default false) -- matches every other exit-policy feature in this
     * codebase. Only ever closes the position this plan itself opened on this exact symbol (via
     * PositionRepository.findByPlanIdAndSymbolAndStatus's own precise, plan-scoped lookup), so a
     * bearish signal under one plan can never reach across and close a different plan's own,
     * separate position on the same coin -- the same exposure-isolation concern as multiple
     * plans accidentally creating excessive combined exposure applies just as much to
     * accidentally closing each other's positions.
     */
    private void checkSignalReversalExit(RiskProfile profile, BrokerCredential credential, String symbol,
                                          StrategyPlan plan, ServerSignalEngine.Signal signal) {
        if (!plan.isExitOnSignalReversal()) return;
        if (!"SHORT".equalsIgnoreCase(signal.direction())) return; // WAIT/neutral is not a reversal, only a genuine opposite-direction signal is

        List<Position> openPositions;
        try {
            openPositions = positionRepo.findByPlanIdAndSymbolAndStatus(plan.getId(), symbol, "OPEN");
        } catch (Exception e) {
            log.warn("Signal-reversal exit check failed for plan {} symbol {} ({}) -- leaving any existing position untouched this pass.",
                plan.getId(), symbol, e.getMessage());
            return;
        }
        if (openPositions.isEmpty()) return;

        // Reversal-exit flattens must go through the same adapter reconcile/entry uses, same as
        // scanForProfile, or a "paper" credential gets real testnet orders sent against it.
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        if (adapter == null) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        for (Position position : openPositions) {
            log.warn("Plan \"{}\" ({}) detected a SHORT/bearish reversal signal on {} while holding an OPEN position from this exact "
                + "plan -- emergency-flattening per the plan's own configured exit policy.", plan.getName(), plan.getId(), symbol);
            try {
                // A signal-reversal exit is routine, plan-configured behavior, not a protection
                // failure -- uses exitPosition() so a clean close doesn't halt the whole profile
                // or raise a CRITICAL incident.
                positionSafetyService.exitPosition(credential, adapter, apiKey, apiSecret, position,
                    "SIGNAL_REVERSAL: plan \"" + plan.getName() + "\" (" + plan.getId() + ") has exitOnSignalReversal enabled, and a "
                        + "new SHORT/bearish signal was detected on " + symbol + " while this position was still open.");
            } catch (Exception e) {
                log.error("Signal-reversal emergency-flatten failed for position {} ({}): {} -- will be retried by the next scheduled "
                    + "reconciliation pass's own max-hold/other checks if applicable, but is not automatically retried here.",
                    position.getId(), symbol, e.getMessage());
            }
        }
    }
}
