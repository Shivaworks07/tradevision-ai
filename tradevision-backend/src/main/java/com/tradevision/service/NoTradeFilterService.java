package com.tradevision.service;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.TradeCallRecord;
import com.tradevision.repository.PositionRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.broker.dto.SpreadInfo;
import com.tradevision.util.IndicatorMath;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Decides whether an incoming trade signal is trustworthy enough to act on at all, independent
 * of whether there's risk budget for it (that's RiskEngineService's job, checked separately).
 * A real trading system spends most of its time declining to trade, and this is where that
 * happens.
 *
 * Gates, in order: exchange health, spread (a top-of-book liquidity proxy, not full depth),
 * signal payload sanity (data quality / R:R / entry+SL present), a full independent server-side
 * signal computation via ServerSignalEngine that a claimed LONG must agree with, server-side
 * indicator recomputation against the signal's claims (RSI/ATR divergence, SL/ATR ratio), and
 * finally a duplicate-position check.
 *
 * ServerSignalEngine is a verified port of the frontend's analyze() decision engine (see its
 * own class javadoc), so this check reflects the server's complete independent trading opinion,
 * not just a single indicator.
 *
 * This is deliberately strict: ServerSignalEngine must independently reach LONG too — a WAIT or
 * SHORT from the server refuses the trade — using its own candle fetch, which won't always
 * exactly match whatever candles the frontend had at signal-generation time (timing, count,
 * minor data differences). That means some signals the frontend considers good can still get
 * refused here purely on timing/data-window mismatch rather than a genuine disagreement. This
 * is an intentional safety-over-throughput choice, and the strictness (e.g. requiring "not
 * SHORT" instead of "must be LONG", or a confidence-band tolerance) can be loosened later once
 * there's live data on how often the two sides actually disagree.
 *
 * This still has no order-book depth check (only top-of-book spread), and ServerSignalEngine
 * itself doesn't cover SMC structure, order flow, volume profile, multi-timeframe context, or
 * adaptive ML weight learning — see its own class javadoc for what it does cover.
 */
@Service
@RequiredArgsConstructor
public class NoTradeFilterService {

    private static final Logger log = LoggerFactory.getLogger(NoTradeFilterService.class);

    private static final double MIN_RR_RATIO = 1.5;
    private static final int MIN_DATA_QUALITY_SCORE = 70;
    private static final double MAX_SPREAD_PERCENT = 0.5;
    private static final double MAX_RSI_DEVIATION = 20.0;
    private static final double MAX_ATR_DEVIATION_PERCENT = 60.0;
    private static final double MIN_SL_ATR_RATIO = 0.3;
    private static final double MAX_SL_ATR_RATIO = 6.0;

    private static final Map<String, String> TIMEFRAME_TO_INTERVAL = Map.ofEntries(
        Map.entry("1M", "1m"), Map.entry("5M", "5m"), Map.entry("15M", "15m"), Map.entry("30M", "30m"),
        Map.entry("1H", "1h"), Map.entry("4H", "4h"), Map.entry("1D", "1d"), Map.entry("D1", "1d"),
        Map.entry("Daily (D1)", "1d"), Map.entry("1W", "1w")
    );

    private final PositionRepository positionRepo;
    private final ExchangeHealthService exchangeHealth;
    private final ServerSignalEngine serverSignalEngine;
    // This gate's own independent re-computation scores against the same weights the
    // autonomous scanner uses, so a claimed signal is verified against the live, adaptive
    // weights rather than fixed ones — comparing against a different version of the scoring
    // logic than what actually produced the signal would be a source of spurious disagreement.
    private final MLWeightService mlWeightService;
    // Scoped conservatively: only regime/SMC/volume-profile context is folded in here, computed
    // from candles already fetched for this gate plus one more spot-candle fetch for a higher
    // timeframe. Order-flow (and its live Binance futures call) is excluded from this critical
    // execution-gating path specifically to avoid adding a new external dependency's
    // latency/failure surface to the decision of whether real money moves.
    private final com.tradevision.service.strategy.MarketRegimeService marketRegimeService;
    private final com.tradevision.service.strategy.SmcEngineService smcEngineService;
    private final com.tradevision.service.strategy.VolumeProfileService volumeProfileService;
    private final com.tradevision.service.strategy.SignalCombinerService signalCombinerService;

    private static final java.util.Map<String, String> NEXT_HIGHER_TF = java.util.Map.of(
        "1m", "15m", "5m", "1h", "15m", "4h", "30m", "4h", "1h", "4h", "4h", "1d", "1d", "1w"
    );

    public record FilterResult(boolean tradeable, String reason, ServerSignalEngine.Signal serverSignal) {
        public static FilterResult ok(ServerSignalEngine.Signal serverSignal) { return new FilterResult(true, null, serverSignal); }
        public static FilterResult noTrade(String reason) { return new FilterResult(false, reason, null); }
    }

    public FilterResult check(String userId, String credentialId, TradeCallRecord signal, BrokerAdapter adapter, String apiKey, BrokerMode mode) {
        // Checks this credential's own connection health specifically, so one unhealthy
        // credential can't affect the health verdict for any other user's credential.
        ExchangeHealthService.HealthStatus health = exchangeHealth.check(apiKey);
        if (!health.healthy()) {
            return FilterResult.noTrade("Exchange connection unhealthy: " + health.reason());
        }

        FilterResult spreadResult = checkSpread(signal, adapter, mode);
        if (!spreadResult.tradeable()) return spreadResult;

        if (signal.getFeatures() != null && signal.getFeatures().getQuality() != null) {
            var quality = signal.getFeatures().getQuality();
            if (!quality.isSufficientHistory()) {
                return FilterResult.noTrade("Insufficient candle history behind this signal.");
            }
            if (quality.getQualityScore() > 0 && quality.getQualityScore() < MIN_DATA_QUALITY_SCORE) {
                return FilterResult.noTrade("Signal data quality score " + quality.getQualityScore()
                    + " is below the minimum " + MIN_DATA_QUALITY_SCORE + ".");
            }
            if (quality.isInvalidIndicators()) {
                return FilterResult.noTrade("Signal reports invalid indicator values.");
            }
        }

        if (signal.getStopLoss().signum() <= 0 || signal.getEntryPrice().signum() <= 0) {
            return FilterResult.noTrade("Signal is missing a valid entry price or stop-loss — refusing to size a trade without one.");
        }

        FilterResult indicatorResult = checkIndicatorDivergence(signal, adapter, mode);
        if (!indicatorResult.tradeable()) return indicatorResult;

        // The R:R check runs after checkIndicatorDivergence and is computed from serverSignal's
        // own entry/stopLoss/target1 — the exact values AutoTradeService sizes and executes the
        // trade with — rather than from the client-claimed rrRatio, so a signal can't claim a
        // healthy R:R while the numbers actually driving execution imply something worse.
        ServerSignalEngine.Signal serverSignal = indicatorResult.serverSignal();
        if (serverSignal != null && serverSignal.entry() > 0 && serverSignal.stopLoss() > 0 && serverSignal.target1() > 0) {
            double riskDistance = "LONG".equals(serverSignal.direction())
                ? serverSignal.entry() - serverSignal.stopLoss()
                : serverSignal.stopLoss() - serverSignal.entry();
            double rewardDistance = "LONG".equals(serverSignal.direction())
                ? serverSignal.target1() - serverSignal.entry()
                : serverSignal.entry() - serverSignal.target1();
            if (riskDistance > 0) {
                double serverRrRatio = rewardDistance / riskDistance;
                if (serverRrRatio < MIN_RR_RATIO) {
                    return FilterResult.noTrade("Server-computed risk:reward (T1-entry)/(entry-SL) is " + String.format("%.2f", serverRrRatio)
                        + ", below the minimum " + MIN_RR_RATIO + " -- refusing a trade whose own execution numbers don't clear the bar, "
                        + "regardless of what the client claimed (" + signal.getRrRatio() + ").");
                }
            }
        }

        boolean alreadyOpen = positionRepo.findByUserIdAndCredentialIdAndStatus(userId, credentialId, "OPEN").stream()
            .anyMatch(p -> p.getSymbol().equalsIgnoreCase(signal.getSymbol()));
        if (alreadyOpen) {
            return FilterResult.noTrade("A position on " + signal.getSymbol() + " is already open for this credential.");
        }

        // The server-computed signal is propagated all the way out — this is what
        // AutoTradeService actually sizes and executes the trade with, not the client's
        // claimed entry/SL/TP.
        return FilterResult.ok(serverSignal);
    }

    private FilterResult checkSpread(TradeCallRecord signal, BrokerAdapter adapter, BrokerMode mode) {
        try {
            SpreadInfo spread = adapter.getSpread(signal.getSymbol(), mode);
            if (spread.spreadPercent() > MAX_SPREAD_PERCENT) {
                return FilterResult.noTrade("Spread " + String.format("%.3f", spread.spreadPercent())
                    + "% on " + signal.getSymbol() + " exceeds the " + MAX_SPREAD_PERCENT + "% max — market too thin to trade cleanly.");
            }
            return FilterResult.ok(null);
        } catch (Exception e) {
            // Fails closed: for an autonomous system, an unverifiable safety check should block
            // the trade rather than silently pass by default.
            log.warn("Could not fetch spread for {} — refusing to trade without a liquidity read: {}", signal.getSymbol(), e.getMessage());
            return FilterResult.noTrade("Could not verify spread/liquidity for " + signal.getSymbol() + " — refusing to trade blind: " + e.getMessage());
        }
    }

    private FilterResult checkIndicatorDivergence(TradeCallRecord signal, BrokerAdapter adapter, BrokerMode mode) {
        String interval = TIMEFRAME_TO_INTERVAL.get(signal.getTimeframe());
        if (interval == null) {
            // Without a server-computed signal, there's nothing to size the trade off other
            // than the client's numbers, which defeats the point of independent verification —
            // so this fails closed, like every other "can't verify" path in this method.
            log.info("No interval mapping for timeframe '{}' on {} — cannot compute a server signal, refusing to trade.", signal.getTimeframe(), signal.getSymbol());
            return FilterResult.noTrade("No server-side interval mapping for timeframe '" + signal.getTimeframe()
                + "' — cannot independently compute or execute this signal.");
        }
        double claimedRsi = signal.getFeatures() != null ? signal.getFeatures().getRsi() : 0;
        // Signal.getAtr() is BigDecimal; converted to double here for the arithmetic below.
        double claimedAtr = signal.getAtr().doubleValue();

        List<Candle> candles;
        try {
            // 220 candles gives ServerSignalEngine's EMA200 real headroom (100 would give it an
            // effective period of only ~99) plus the engine's own lookback needs (divergence/S-R
            // use the trailing ~20).
            candles = adapter.getRecentCandles(signal.getSymbol(), interval, 220, mode);
        } catch (Exception e) {
            // Fails closed: skipping this would also skip ServerSignalEngine's independent
            // direction-agreement check further down, which runs on these same candles. Matches
            // the spread check's fail-closed philosophy — can't verify, don't trade.
            log.warn("Could not fetch candles for {} — refusing to trade without server-side verification: {}", signal.getSymbol(), e.getMessage());
            return FilterResult.noTrade("Could not fetch candles to independently verify this signal for " + signal.getSymbol() + ": " + e.getMessage());
        }
        // adapter.getRecentCandles' last element is always the still-forming, in-progress
        // candle for the current interval. This method runs at execution time, moments after
        // the scanner already made its own decision on the last CLOSED candle, so the
        // in-progress candle here has likely moved further since the scan — sometimes enough to
        // flip RSI/ATR/the server's own direction call, which would reject (or re-decide) a
        // signal the scanner correctly generated off closed data, purely from intra-candle
        // noise rather than a genuine disagreement. Dropping the last candle here, before any
        // indicator computation, keeps scan-time and execution-time decisions computed from the
        // same closed data whenever they're for the same candle.
        if (candles.size() < 21) {
            return FilterResult.noTrade("Only " + candles.size() + " candles available for " + signal.getSymbol()
                + " — too little CLOSED history to independently verify this signal.");
        }
        List<Candle> closedCandles = candles.subList(0, candles.size() - 1);

        double actualAtr = IndicatorMath.atr(closedCandles, 14);

        // ServerSignalEngine.analyze() is a verified port of the frontend's analyze() decision
        // function (see its class javadoc), computing its own independent
        // direction/confidence/entry/SL/TP from these same real candles. A claimed direction the
        // server's own full computation contradicts (server says WAIT or the opposite direction)
        // is refused, since the server's complete independent analysis disagrees, not just one
        // indicator.
        ServerSignalEngine.Signal serverSignal;
        // The ML weight lookup is purely additive to scoring, not essential to whether this
        // gate can run: a transient MongoDB hiccup falls back to fixed-weight scoring rather
        // than blocking every signal evaluation.
        try {
            var mlWeights = mlWeightService.getWeights(signal.getMarket(), signal.getSymbol());
            serverSignal = serverSignalEngine.analyze(closedCandles, mlWeights);
        } catch (Exception e) {
            log.debug("Could not fetch ML weights for {} (non-fatal, falling back to fixed defaults): {}", signal.getSymbol(), e.getMessage());
            serverSignal = serverSignalEngine.analyze(closedCandles);
        }
        String claimedDirection = signal.getDirection();
        if ("LONG".equalsIgnoreCase(claimedDirection) && !"LONG".equals(serverSignal.direction())) {
            return FilterResult.noTrade("Signal claims LONG on " + signal.getSymbol()
                + " but the server's own independent analysis (same real candles, same decision logic as the "
                + "frontend, computed here) says " + serverSignal.direction() + " (" + serverSignal.signalLabel()
                + ", net score " + String.format("%.0f", serverSignal.net()) + ") — refusing a signal the "
                + "server's own complete computation disagrees with.");
        }

        // serverSignal is replaced with the combined result for everything from this point
        // forward. SignalCombinerService never modifies entry/stopLoss/target1-3/atrPercent, so
        // every check below that uses those fields behaves identically either way; only
        // confidence/direction/signalLabel can differ, and a direction flipped to WAIT by
        // conflicting regime/SMC/MTF signals is refused the same as any other direction
        // disagreement above.
        //
        // This enrichment is additive and non-fatal: a failure here falls back to the
        // already-verified base signal unchanged, so it never blocks a trade the rest of this
        // method would otherwise accept, and never substitutes a worse signal for a working one.
        try {
            var smc = smcEngineService.analyze(closedCandles, signal.getSymbol());
            var regimeState = marketRegimeService.detect(closedCandles, signal.getSymbol());
            var vp = volumeProfileService.analyze(closedCandles, 50);
            String higherTf = NEXT_HIGHER_TF.getOrDefault(interval, "4h");
            // This higher-timeframe fetch is a separate call to adapter.getRecentCandles with
            // its own unclosed final candle, which must be dropped here too — otherwise the MTF
            // context folded into `combined` below would reintroduce the same repainting issue
            // on the higher timeframe.
            List<Candle> higherTfCandlesRaw = adapter.getRecentCandles(signal.getSymbol(), higherTf, 220, mode);
            List<Candle> higherTfCandles = higherTfCandlesRaw.size() > 1
                ? higherTfCandlesRaw.subList(0, higherTfCandlesRaw.size() - 1) : higherTfCandlesRaw;
            var combined = signalCombinerService.combine(serverSignal, "", interval, higherTfCandles, null, smc, null, vp, regimeState);
            serverSignal = new ServerSignalEngine.Signal(combined.direction(), combined.signal(), combined.confidence(),
                serverSignal.entry(), serverSignal.stopLoss(), serverSignal.target1(), serverSignal.target2(), serverSignal.target3(),
                serverSignal.bullScore(), serverSignal.bearScore(), serverSignal.net(), serverSignal.atrPercent());
        } catch (Exception e) {
            log.debug("Enrichment (SMC/regime/volume-profile/MTF) failed for {} -- falling back to the unenriched server signal: {}", signal.getSymbol(), e.getMessage());
        }

        if ("LONG".equalsIgnoreCase(claimedDirection) && !"LONG".equals(serverSignal.direction())) {
            return FilterResult.noTrade("Signal claims LONG on " + signal.getSymbol()
                + " but the enriched server analysis (regime/SMC/multi-timeframe context folded in) now says "
                + serverSignal.direction() + " (" + serverSignal.signalLabel()
                + ") — refusing a signal the broader picture disagrees with.");
        }

        if (claimedRsi > 0) {
            double actualRsi = IndicatorMath.wilderRsi(closedCandles, 14);
            double rsiDeviation = Math.abs(claimedRsi - actualRsi);
            if (rsiDeviation > MAX_RSI_DEVIATION) {
                return FilterResult.noTrade("Signal claims RSI " + String.format("%.1f", claimedRsi)
                    + " but server-recomputed RSI from live candles is " + String.format("%.1f", actualRsi)
                    + " (deviation " + String.format("%.1f", rsiDeviation) + " > " + MAX_RSI_DEVIATION + ") — refusing a signal whose own numbers don't check out.");
            }
        }
        if (claimedAtr > 0 && actualAtr > 0) {
            double atrDeviationPct = Math.abs(claimedAtr - actualAtr) / actualAtr * 100.0;
            if (atrDeviationPct > MAX_ATR_DEVIATION_PERCENT) {
                return FilterResult.noTrade("Signal claims ATR " + String.format("%.4f", claimedAtr)
                    + " but server-recomputed ATR from live candles is " + String.format("%.4f", actualAtr)
                    + " (" + String.format("%.0f", atrDeviationPct) + "% deviation) — refusing a signal whose own numbers don't check out.");
            }
        }

        // Validates the SL distance that actually drives execution (serverSignal's own
        // entry/stopLoss) against the server-recomputed ATR, rather than the client-supplied
        // entry/stopLoss — since execution always uses the server's values, validating the
        // client's numbers instead could let an implausible execution SL through even though the
        // client's own SL looked fine. A mismatch between client and server SL is expected,
        // since the server signal is authoritative for execution.
        if (actualAtr > 0 && serverSignal.entry() > 0 && serverSignal.stopLoss() > 0) {
            double slDistance = Math.abs(serverSignal.entry() - serverSignal.stopLoss());
            double slToAtrRatio = slDistance / actualAtr;
            if (slToAtrRatio < MIN_SL_ATR_RATIO) {
                return FilterResult.noTrade("Server-computed stop-loss distance (" + String.format("%.6f", slDistance)
                    + ") is only " + String.format("%.2f", slToAtrRatio) + "x the server-recomputed ATR ("
                    + String.format("%.6f", actualAtr) + ") — too tight to be a genuine technical stop; "
                    + "refusing to size a position off it.");
            }
            if (slToAtrRatio > MAX_SL_ATR_RATIO) {
                return FilterResult.noTrade("Server-computed stop-loss distance (" + String.format("%.6f", slDistance)
                    + ") is " + String.format("%.2f", slToAtrRatio) + "x the server-recomputed ATR ("
                    + String.format("%.6f", actualAtr) + ") — implausibly wide for this symbol's real volatility.");
            }
        }

        return FilterResult.ok(serverSignal);
    }
}
