package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-credential auto-trade configuration and risk caps. One profile per BrokerCredential.
 * autoTradeEnabled is the master switch — off by default, user must explicitly turn it on
 * after a manual test order has proven the credential works.
 *
 * tradingHalted is the kill switch: when true, AutoTradeService refuses every
 * signal for this credential regardless of anything else, until a human flips it back.
 */
@Data @NoArgsConstructor
@Document(collection = "risk_profiles")
public class RiskProfile {
    @Id private String id;

    @Indexed private String userId;
    // One profile per credential is the intent -- a unique index is what actually enforces it
    // at the database level, closing the TOCTOU window a plain find-then-create-or-update
    // upsert() would otherwise leave open to two simultaneous requests.
    @Indexed(unique = true) private String credentialId;

    // A plain integer, incremented by a human whenever this profile's own risk parameters
    // meaningfully change (maxDrawdownPercent, correlationGroupCaps, etc.) -- not an
    // automatically-computed hash of the whole document, which would bump on every unrelated
    // field touch (e.g. tradingHalted flipping during a routine halt/resume) and stop meaning
    // "the risk parameters changed". Starts at 1, so every profile has some version from
    // creation.
    private int version = 1;

    private boolean autoTradeEnabled = false;
    /**
     * The fallback trading universe used only for a signal with no associated StrategyPlan at
     * all (pre-multi-plan, or a manually-submitted signal). A plan-backed signal's own symbol
     * is authorized entirely by StrategyPlanService.authorizeExecution against that plan's own
     * universe (TIER1 + its own enabledSymbols + its own dynamic-universe candidates) -- this
     * field plays no role once a signal has a planId, and is never consulted as an account-wide
     * ceiling the way maxConcurrentTrades/riskPerTradePercent are.
     */
    private Set<String> enabledSymbols = new HashSet<>();
    // The candle timeframe the scanner analyzes for this profile, configurable per profile
    // rather than forcing every profile onto the same hardcoded interval. Validated against
    // the broker's own supported kline intervals at the point this is actually used
    // (AutonomousScannerService.timeframeToSeconds) rather than trusted blindly here -- an
    // invalid value falls back to "1h" there, not silently breaking the scan.
    private String scanTimeframe = "1h";
    /**
     * Opt-in, defaulting to false -- an existing profile does not suddenly start trading dozens
     * of new, previously-unseen symbols just because this feature exists. A profile that wants
     * the full discover/rank/select pipeline (BrokerAdapter.getAllTradableUsdtSymbols +
     * getAll24hrTickers, filtered and ranked by DynamicUniverseService) turns this on
     * explicitly.
     */
    private boolean dynamicUniverseEnabled = false;
    /** How many of the top-ranked dynamic candidates to actually scan, when the above is enabled. */
    private int dynamicUniverseMaxSymbols = 10;
    /**
     * The account-level floor for signal confidence. A plan-backed signal's own effective
     * minimum confidence is Math.max(this field, the plan's own minConfidence) at every point
     * it's actually checked (both the client-claimed confidence in evaluateForProfile and the
     * server-computed confidence in evaluateForProfileLocked) -- a plan can only ever require
     * more confidence than this account-wide floor, never less.
     */
    private double minConfidence = 75.0;

    /** Max quote-currency (e.g. USDT) value of a single auto-placed order. */
    private BigDecimal maxPositionQuoteAmount = BigDecimal.ZERO;
    /**
     * The account-wide total ceiling on concurrent open positions, across every plan combined,
     * enforced two ways: RiskEngineService's own cheap pre-filter counts all open positions for
     * this credential (not one plan's own), and
     * PositionSlotReservationService.reserve(credentialId, this field) is the separate,
     * authoritative atomic gate keyed by credentialId alone. A plan's own maxConcurrentTrades
     * is a separate, additional reservation keyed by "plan:" + planId -- both must pass; this
     * field is never bypassed or narrowed by a plan's own value, it's the outer ceiling every
     * plan's combined activity still has to fit inside.
     */
    private int maxConcurrentTrades = 1;
    private BigDecimal dailyLossLimitQuote = BigDecimal.ZERO;

    // Rolling daily-loss tracking, reset when dailyTrackedDate != today.
    private LocalDate dailyTrackedDate = LocalDate.now();
    // Explicitly typed DECIMAL128 so Spring Data's ad-hoc Update/Query mapping stores this as
    // a real numeric type rather than a String -- MongoDB's $inc rejects a non-numeric value,
    // same reasoning as ExposureReservation.reservedTotalExposureQuote.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal dailyRealizedLossQuote = BigDecimal.ZERO;

    /** Kill switch. Set true by the user (or automatically once dailyLossLimit is hit) to stop all auto-trading on this credential. */
    private boolean tradingHalted = false;
    private String haltReason;
    // A fencing token for tradingHalted transitions, incremented on every transition (both
    // halt() and resume() bump it). resume()'s own final write requires "the version is still
    // exactly what it was when resume() started its own safety checks" -- a concurrent new
    // halt landing in that window bumps the version too, making resume()'s stale captured
    // version fail to match, so its write correctly loses the race instead of silently
    // clearing a legitimate, brand-new halt. A plain "WHERE tradingHalted=true" guard would not
    // be sufficient here, since a concurrent new halt also sets tradingHalted=true and would be
    // indistinguishable from the original halt resume() means to clear.
    private long safetyStateVersion = 0;

    /**
     * How many claimed executions are currently in flight for this credential. Living on this
     * same document, alongside lastExecutionClaimId/tradingHalted/autoTradeEnabled/
     * autoTradeHalted/liveAutoTradeAuthorized, is what makes a single atomic findAndModify
     * possible: markExecutionStarted() checks every one of those conditions and increments
     * this count in one MongoDB operation, leaving no gap between "is this claim still valid"
     * and "register this execution as in flight" for a concurrent kill switch to land in.
     */
    private long executionInFlightCount = 0;

    // The increment of executionInFlightCount above is conditional on a specific claimId (see
    // markExecutionStarted's own atomic findAndModify), so the count only ever grows for a
    // currently-valid claim -- but the stored value is still just a count, not a set of
    // {claimId, orderId, workerId} records, so if it's ever 2 there's no way to tell which two
    // executions those are from this field alone. This is sufficient for halt()'s own audit
    // report ("was something in flight the moment this kill switch engaged") but must never be
    // read as proof of any one execution's authority; that needs a real per-execution record
    // correlated through OMS Order/clientOrderId instead.
    /**
     * A fresh UUID generated on every successful execution-authorization claim, alongside
     * safetyStateVersion (the same fencing token halt()/resume() use). Together these let a
     * caller know exactly which claim authorized a given exchange submission, and any
     * concurrent halt/resume bumps safetyStateVersion, making this claim's value stale against
     * it.
     *
     * This narrows the gap between "authorization checked" and "exchange call made" but cannot
     * eliminate it entirely -- the two are fundamentally different kinds of operation (an
     * atomic database write vs. a network call to a third party), and no single atomic
     * operation can span both. What this does provide is a real, traceable identity for every
     * claim, and a fencing token that makes an intervening halt/resume detectable rather than
     * invisible to a caller still holding a claim issued before it.
     */
    private String lastExecutionClaimId;

    /**
     * Percent of available quote-asset balance risked per trade, used with the signal's own
     * stop-loss distance to size the position: quantity = (balance * riskPerTradePercent/100) / |entry - stopLoss|.
     * maxPositionQuoteAmount remains a hard cap on top of this — whichever is smaller wins.
     *
     * This is the account-level ceiling: a plan-backed signal's own effective risk-per-trade is
     * Math.min(this field, the plan's own riskPerTradePercent) at the actual sizing calculation
     * -- a plan can only ever risk less than this account-wide ceiling, never more.
     */
    private double riskPerTradePercent = 1.0;

    /**
     * Sum of (quantity × avgEntryPrice) across all open positions for this credential must stay
     * under this cap — portfolio-level exposure, scoped to one credential; cross-broker/
     * cross-asset correlation limits are out of scope. 0 = disabled.
     */
    private BigDecimal maxTotalExposureQuote = BigDecimal.ZERO;

    /** Cap on exposure to a single symbol specifically, on top of the total portfolio cap
     *  above — stops one symbol from silently eating the whole exposure budget across several
     *  signals before the total cap alone would catch it. 0 = disabled. */
    private BigDecimal maxSymbolExposureQuote = BigDecimal.ZERO;

    /**
     * A manual proxy for correlation risk, not a computed correlation itself: the user tags
     * symbols into named groups (e.g. "L1-majors": BTC, ETH, SOL) and sets a cap per group, so
     * one cluster of correlated symbols can't each individually pass risk checks while still
     * producing concentrated exposure as a group. Empty map = feature inert.
     *
     * A configured group is enforced by RiskEngineService's existing check, via
     * RiskProfileRequest/RiskProfileService.upsert() persisting these fields.
     *
     * LIMITATION: the check itself remains a plain read-current-positions-then-compare, not
     * the atomic reservation ExposureReservationService gives total/symbol exposure — two
     * concurrent orders that would each individually stay under a correlation-group cap could
     * still both pass the check simultaneously. Building that same atomicity for an arbitrary
     * number of user-configured, possibly-overlapping groups is real further work — see
     * ExposureReservation's own javadoc for the identical reasoning applied there.
     */
    private java.util.Map<String, java.util.Set<String>> correlationGroups = new java.util.HashMap<>();
    private java.util.Map<String, BigDecimal> correlationGroupCaps = new java.util.HashMap<>();

    /**
     * Max allowed deviation between a signal's claimed entryPrice and the live exchange price
     * at order time, as a fraction (0.015 = 1.5%) — partial defense against a tampered/stale
     * signal, and slippage protection. Default is deliberately tight.
     */
    private double maxPriceDeviationPercent = 1.5;

    /**
     * A crude circuit breaker. Consecutive signal-order failures (network errors, broker
     * rejections, anything) increment this; hitting the threshold auto-halts the credential
     * the same way a naked-position emergency does. Reset to 0 on any success.
     */
    private int consecutiveOrderFailures = 0;
    private int circuitBreakerThreshold = 3;

    /**
     * Mark-to-market equity: free quote-asset balance plus the current market value of every
     * open position on this credential, computed fresh each reconciliation cycle in
     * PositionMonitorService.checkDrawdown(), so an underwater open position moves this number
     * in real time instead of waiting for it to close. Assumes every open position's symbol
     * quotes in drawdownQuoteAsset directly (no cross-asset conversion); the whole check is
     * skipped for a cycle, rather than computed from a partial position list, if any open
     * position's current price can't be fetched.
     */
    private String drawdownQuoteAsset = "USDT";
    private java.math.BigDecimal peakEquityQuote;
    private double maxDrawdownPercent = 0; // 0 = disabled

    // Caps new entry orders (not exits -- SL/TP fills are risk-reducing, not the runaway-entry
    // scenario this guards against) within a rolling window. 0 = disabled, same convention as
    // every other optional limit on this profile.
    private int maxOrdersPerHour = 0;

    // A strategy-level (not account-level) consecutive-loss breaker. triggerSource is this
    // codebase's genuine strategy-like distinction: SIGNAL means the signal went through
    // AutoTradeService's risk-evaluated execution pipeline (true for both scanner-discovered
    // signals and frontend-submitted ones, since both call the same evaluateSignal() path).
    // MANUAL means the separate OrderExecutionService path (the manual test-order flow, which
    // deliberately bypasses risk checks entirely). This is "risk-evaluated vs not", not a full
    // multi-strategy versioning system.
    private int consecutiveAutoTradeLosses = 0;
    private int maxConsecutiveAutoTradeLosses = 0; // 0 = disabled, same convention as every other optional limit
    private boolean autoTradeHalted = false;
    private String autoTradeHaltReason;

    /**
     * Second, independent unlock required before AutoTradeService will place a LIVE order —
     * separate from BrokerCredential.mode == LIVE. Turning auto-trade on and switching the
     * credential to live mode are each individually reversible and neither one alone authorizes
     * autonomous LIVE trading; both plus this flag must be true. See RiskProfileService.authorizeLiveAutoTrade().
     */
    private boolean liveAutoTradeAuthorized = false;

    // Written by RiskProfileService.claimExecutionAuthorization()'s own atomic conditional
    // update, immediately before every real exchange order this codebase places -- the update
    // succeeding is proof that autoTradeEnabled/tradingHalted/autoTradeHalted/
    // liveAutoTradeAuthorized were all simultaneously true at this exact moment, not read
    // separately and trusted as still-true moments later. Purely a timestamp of the last
    // successful claim -- not itself read by anything else, its only purpose is being the
    // field the atomic $set targets so the conditional update is a real write, not just a read.
    private java.time.LocalDateTime lastExecutionClaimAt;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
