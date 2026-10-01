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
 * Review item #23: a real trading system spends most of its time saying NO TRADE. Checked
 * before RiskEngineService (which handles caps/limits) — this asks "is the signal itself even
 * trustworthy enough to act on", not "do we have budget for it".
 *
 * Gates, in order: exchange health (#24), spread (#23, partial "liquidity" proxy — top-of-book
 * only, not full depth), signal payload sanity (data quality / R:R / entry+SL present), a full
 * independent server-side signal computation via ServerSignalEngine that a claimed LONG must
 * agree with, server-side indicator recomputation vs. the signal's claims (RSI/ATR divergence,
 * SL/ATR ratio), then duplicate-position check.
 *
 * ServerSignalEngine is a genuinely verified port of the frontend's real analyze() decision
 * engine (cross-checked bit-for-bit against the actual TypeScript source — see its class
 * javadoc), not a rough approximation. This is real progress on "the backend doesn't
 * independently calculate the trading decision" — the server now has a complete opinion, not
 * just one indicator's opinion, and requires agreement.
 *
 * HONEST TRADEOFF, stated plainly rather than discovered the hard way: this is strict.
 * ServerSignalEngine must independently reach LONG too — WAIT or SHORT from the server both
 * refuse the trade — using its own candle fetch, which won't always exactly match whatever
 * candles the frontend had at signal-generation time (timing, count, minor data differences).
 * That means some signals the frontend considers genuinely good will get refused here purely on
 * timing/data-window mismatch, not because anything is wrong. That's an intentional
 * safety-over-throughput choice for now, not an oversight — loosening it (e.g. requiring
 * "not SHORT" instead of "must be LONG", or a confidence-band tolerance) is a real, legitimate
 * option to revisit once there's live testnet data on how often this actually disagrees.
 *
 * Honest scope note, unchanged: still no order-book depth check (only top-of-book spread), and
 * ServerSignalEngine itself doesn't port SMC structure, order flow, volume profile, multi-
 * timeframe context, or the frontend's adaptive ML weight learning — see its own class javadoc.
 * That remains the fuller signal-generation port; this closes a real, verified, substantial part
 * of the gap without pretending to close all of it.
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
    // Review finding ("Strategy engine is not the complete strategy actually represented by the
    // frontend" -- P1, full context in AutonomousScannerService's own identical addition): this
    // gate's own independent re-computation should score against the SAME weights the actual
    // autonomous scanner uses -- verifying a claimed signal against fixed weights while the real
    // scanner scores with adaptive ones would mean comparing against two different versions of
    // the same decision logic, a real source of spurious disagreement.
    private final MLWeightService mlWeightService;
    // Review finding ("Client-Side Signal Generation" — continuing the TA-engine port into the
    // one place that actually gates real execution, not just observability, as the 5-service
    // scanner wiring earlier this session deliberately stopped short of): deliberately scoped
    // the same conservative way as that earlier pass — regime/SMC/volume-profile only (zero new
    // network-call TYPE, since these compute from candles already fetched here plus one more
    // same-kind spot-candle fetch for a higher timeframe), order-flow and its live Binance
    // futures call excluded from this critical gate path specifically to avoid adding a new
    // external dependency's latency/failure surface to the decision of whether real money moves.
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
        // Review item #18 (fixed): now checks THIS credential's own connection health, not a
        // shared global window one bad user could poison for everyone.
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

        // P2-2 fix ("R:R >=1.5 checked on client-claimed rrRatio, not on server SL/T1" --
        // external review, confirmed real by direct inspection: the check this replaced ran
        // BEFORE checkIndicatorDivergence even executed, on signal.getRrRatio() -- a value this
        // application never independently recomputes, taken entirely on the client's word. A
        // client (or a bug/compromise in whatever produced the signal) could claim any R:R at
        // all here; nothing before this fix ever verified it against the numbers this
        // application actually executes with. Moved to run AFTER checkIndicatorDivergence, and
        // now computed from serverSignal's own entry/stopLoss/target1 -- the exact values
        // AutoTradeService sizes and executes the trade with (per item #11's own established
        // "server signal is authoritative for execution" fix) -- so a signal can no longer claim
        // a healthy R:R while the numbers actually driving execution imply something worse.
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

        // Review finding (this doc, "#11"): propagate the server-computed signal all the way
        // out — this is what AutoTradeService now actually sizes and executes the trade with,
        // not the client's claimed entry/SL/TP.
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
            // Review finding (this doc, "spread check fails open"): previously returned ok() here
            // — "can't verify liquidity" silently became "trade allowed". For an autonomous
            // system, an unverifiable safety check should block, not pass by default.
            log.warn("Could not fetch spread for {} — refusing to trade without a liquidity read: {}", signal.getSymbol(), e.getMessage());
            return FilterResult.noTrade("Could not verify spread/liquidity for " + signal.getSymbol() + " — refusing to trade blind: " + e.getMessage());
        }
    }

    private FilterResult checkIndicatorDivergence(TradeCallRecord signal, BrokerAdapter adapter, BrokerMode mode) {
        String interval = TIMEFRAME_TO_INTERVAL.get(signal.getTimeframe());
        if (interval == null) {
            // Review finding (this doc, "#11" — server signal must become authoritative for
            // execution): without a server-computed signal, there's nothing to size the trade
            // off other than the client's numbers — which defeats the whole point of this pass.
            // This used to skip verification and fall through to trusting the client; now it
            // fails closed like every other "can't verify" path in this method.
            log.info("No interval mapping for timeframe '{}' on {} — cannot compute a server signal, refusing to trade.", signal.getTimeframe(), signal.getSymbol());
            return FilterResult.noTrade("No server-side interval mapping for timeframe '" + signal.getTimeframe()
                + "' — cannot independently compute or execute this signal.");
        }
        double claimedRsi = signal.getFeatures() != null ? signal.getFeatures().getRsi() : 0;
        // Review finding ("Financial values still mix double and BigDecimal" -- external
        // review, twenty-fourth pass, P2, full context in TradeCallRecord's own updated field
        // comment): signal.getAtr() is BigDecimal now -- .doubleValue() converts here.
        double claimedAtr = signal.getAtr().doubleValue();

        List<Candle> candles;
        try {
            // Review finding (this doc, "candle window mismatch"): 100 candles gave ServerSignalEngine's
            // EMA200 an effective period of ~99, not a genuine 200-period EMA — not equivalent to
            // whatever window the frontend actually used. 220 gives EMA200 real headroom plus the
            // engine's own lookback needs (divergence/S-R use the trailing ~20).
            candles = adapter.getRecentCandles(signal.getSymbol(), interval, 220, mode);
        } catch (Exception e) {
            // Review finding (this doc, "indicator-fetch failure still fails open"): this isn't
            // just skipping the RSI/ATR divergence check — ServerSignalEngine's independent
            // direction-agreement check (the main defense from this pass) also runs on these
            // same candles, further down this method. Failing open here silently skips BOTH.
            // Matches the spread check's fail-closed philosophy: can't verify, don't trade.
            log.warn("Could not fetch candles for {} — refusing to trade without server-side verification: {}", signal.getSymbol(), e.getMessage());
            return FilterResult.noTrade("Could not fetch candles to independently verify this signal for " + signal.getSymbol() + ": " + e.getMessage());
        }
        // P2-1 fix ("Repainting — execution-time server signal computed on candles including the
        // unclosed bar" -- external review, confirmed real by direct inspection: every indicator
        // computation in this method (ATR/RSI/ServerSignalEngine/SMC/regime/volume-profile) used
        // the raw `candles` list as fetched, whose LAST element is always the still-forming,
        // in-progress candle for the current interval -- adapter.getRecentCandles' own documented
        // behavior, and the exact same fact AutonomousScannerService's own scanOneSymbol already
        // has to account for, see its closedCandles field comment for the full reasoning). Because
        // this method runs at EXECUTION time (moments after the scanner already made its own
        // decision on the last CLOSED candle), the in-progress candle here has almost certainly
        // moved further since the scan -- sometimes enough to flip RSI/ATR/the server's own
        // direction call entirely, so a signal the scanner correctly generated off closed data
        // could be rejected (or worse, silently re-decided differently) by this gate purely
        // because of intra-candle noise, not a genuine disagreement about the same market state.
        // The fix: drop the last (unclosed) candle here too, before ANY indicator computation --
        // matching the scanner's own closed-candle-only policy exactly, so scan-time and
        // execution-time decisions are being computed from the literal same closed data whenever
        // they're for the same candle.
        if (candles.size() < 21) {
            return FilterResult.noTrade("Only " + candles.size() + " candles available for " + signal.getSymbol()
                + " — too little CLOSED history to independently verify this signal.");
        }
        List<Candle> closedCandles = candles.subList(0, candles.size() - 1);

        double actualAtr = IndicatorMath.atr(closedCandles, 14);

        // Review finding, this doc: "the backend doesn't independently calculate the trading
        // decision." This used to be a narrow EMA50-only trend check — now the full engine.
        // ServerSignalEngine.analyze() is a verified port of the frontend's real analyze()
        // decision function (see its class javadoc for the cross-verification method), computing
        // its own independent direction/confidence/entry/SL/TP from these same real candles. A
        // claimed direction the server's own full computation flatly contradicts (server says
        // WAIT or the opposite direction) is refused — not because one indicator disagrees, but
        // because the server's complete independent analysis does.
        ServerSignalEngine.Signal serverSignal;
        // Review finding ("Strategy engine is not the complete strategy actually represented by
        // the frontend" -- P1, full context in this class's own new field comment): same
        // graceful-degradation reasoning as AutonomousScannerService's own identical addition --
        // this lookup is purely additive to scoring, not essential to whether this gate can run
        // at all. A transient MongoDB hiccup falls back to fixed-weight scoring, the same
        // behavior this gate has always had, rather than blocking every signal evaluation.
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

        // Review finding ("Client-Side Signal Generation" -- full context in this class's own
        // field comments): the enrichment that was, until now, observability-only in the
        // scanner now genuinely gates execution here too. serverSignal is REPLACED with the
        // combined result for everything from this point forward -- entry/stopLoss/target1-3/
        // atrPercent are untouched by design (SignalCombinerService itself never modifies them,
        // confirmed by its own verification), so every check below that uses those specific
        // fields behaves identically either way; only confidence/direction/signalLabel can
        // differ, and a direction that gets flipped to WAIT by conflicting regime/SMC/MTF
        // signals is refused the same as any other direction disagreement above.
        //
        // Additive, non-fatal, matching this whole codebase's own established contract: a bug
        // in this enrichment falls back to the ALREADY-VERIFIED base signal unchanged -- exactly
        // today's pre-enrichment behavior -- never blocks a trade the rest of this method would
        // otherwise accept, and never silently substitutes a worse signal for a working one.
        try {
            var smc = smcEngineService.analyze(closedCandles, signal.getSymbol());
            var regimeState = marketRegimeService.detect(closedCandles, signal.getSymbol());
            var vp = volumeProfileService.analyze(closedCandles, 50);
            String higherTf = NEXT_HIGHER_TF.getOrDefault(interval, "4h");
            // P2-1 fix (full context above): this higher-timeframe fetch is its own separate call
            // to adapter.getRecentCandles, with its own unclosed final candle -- must be dropped
            // here too, or the MTF context folded into `combined` below would reintroduce the
            // exact same repainting this fix closes, just on the higher timeframe instead.
            List<Candle> higherTfCandlesRaw = adapter.getRecentCandles(signal.getSymbol(), higherTf, 220, mode);
            List<Candle> higherTfCandles = higherTfCandlesRaw.size() > 1
                ? higherTfCandlesRaw.subList(0, higherTfCandlesRaw.size() - 1) : higherTfCandlesRaw;
            var combined = signalCombinerService.combine(serverSignal, "", interval, higherTfCandles, null, smc, null, vp, regimeState);
            serverSignal = new ServerSignalEngine.Signal(combined.direction(), combined.signal(), combined.confidence(),
                serverSignal.entry(), serverSignal.stopLoss(), serverSignal.target1(), serverSignal.target2(), serverSignal.target3(),
                serverSignal.bullScore(), serverSignal.bearScore(), serverSignal.net(), serverSignal.atrPercent());
        } catch (Exception e) {
            log.debug("Enrichment (SMC/regime/volume-profile/MTF) failed for {} -- falling back to the unenriched server signal, unchanged from today's pre-enrichment behavior: {}", signal.getSymbol(), e.getMessage());
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

        // Review finding (P1 #9 — "SL validation is still partly based on the CLIENT signal"):
        // confirmed real, and fixed. This used to validate signal.getEntryPrice()/getStopLoss()
        // (client-supplied, untrusted) against the server-recomputed ATR — but execution uses
        // serverSignal.entry()/serverSignal.stopLoss() (per items #1/#11's earlier fix), which
        // are not necessarily the same values. Validating the client's numbers while executing
        // the server's meant this gate could pass a client SL that looked fine while the actual
        // execution SL — computed independently by ServerSignalEngine — was the one that could
        // still turn into an enormous position via the sizing formula. Now validates the values
        // that actually drive execution; the client's own numbers are no longer checked here at
        // all, since a mismatch between client and server SL is exactly what "P0 #1" (server
        // signal being authoritative) already treats as expected and correct, not suspicious.
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
