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
    // Review finding (P1 — "RiskProfile needs a unique credential index"): one profile per
    // credential was always the intent — upsert() does find-then-create-or-update, which has a
    // real TOCTOU window two simultaneous requests could both slip through, creating two
    // profiles for the same credential. Without a unique index, nothing in the database itself
    // prevented that. Same fix already applied for users/slot reservations, done here too.
    @Indexed(unique = true) private String credentialId;

    // Review finding ("Strategy/risk-profile/feature versioning fields exist but are
    // unused/null" -- P1, full context in OrderService.create's own updated comment): a real,
    // honest version -- a plain integer, incremented by a human whenever this profile's own risk
    // parameters meaningfully change (maxDrawdownPercent, correlationGroupCaps, etc.), not an
    // automatically-computed hash of the whole document (which would bump on every unrelated
    // field touch, like tradingHalted flipping during a routine halt/resume, and stop meaning
    // "the risk parameters changed"). Starts at 1 -- every profile has SOME version from
    // creation, never null the way this field was before this fix.
    private int version = 1;

    private boolean autoTradeEnabled = false;
    /**
     * Review finding ("Account RiskProfile remains too intertwined with StrategyPlan" --
     * external review, ninth pass, P1): this field's own real, current role, stated plainly --
     * used ONLY as the fallback universe for a signal with NO associated StrategyPlan at all
     * (pre-multi-plan, or a manually-submitted signal). A plan-backed signal's own symbol is
     * authorized entirely by StrategyPlanService.authorizeExecution against THAT plan's own
     * universe (TIER1 + its own enabledSymbols + its own dynamic-universe candidates) -- this
     * field plays no role whatsoever once a signal has a planId, and is never consulted as an
     * account-wide ceiling the way maxConcurrentTrades/riskPerTradePercent genuinely are.
     */
    private Set<String> enabledSymbols = new HashSet<>();
    // Review finding ("Config for scanner universe / interval" — "Hard-coded tier-1 + 1h; should
    // be profile- or config-driven for a real bot"): enabledSymbols above already lets a profile
    // ADD symbols beyond the hardcoded Tier-1 default (see AutonomousScannerService's own
    // TIER1_SYMBOLS constant) — this is the other half, making the candle TIMEFRAME the scanner
    // analyzes per-profile-configurable too, rather than every profile being forced onto the
    // same hardcoded "1h". Validated against Binance's own supported kline intervals at the
    // point this is actually used (AutonomousScannerService.timeframeToSeconds) rather than
    // trusted blindly here — an invalid value falls back to "1h" there, not silently breaking
    // the scan.
    private String scanTimeframe = "1h";
    /**
     * Review finding ("Strategy universe is still hard-coded" -- external review, fifth pass,
     * P1 feature request, confirmed real by direct inspection: AutonomousScannerService's own
     * TIER1_SYMBOLS is exactly the fixed five-symbol set the review describes): opt-in,
     * defaulting to false -- an existing profile must never suddenly start trading dozens of
     * new, previously-unseen symbols just because this feature shipped. A profile that wants
     * the full discover/rank/select pipeline (BrokerAdapter.getAllTradableUsdtSymbols +
     * getAll24hrTickers, filtered and ranked by DynamicUniverseService) turns this on
     * explicitly.
     */
    private boolean dynamicUniverseEnabled = false;
    /** How many of the top-ranked dynamic candidates to actually scan, when the above is enabled. */
    private int dynamicUniverseMaxSymbols = 10;
    /**
     * Review finding ("Account RiskProfile remains too intertwined with StrategyPlan" --
     * external review, ninth pass, P1): this field's own real, current role -- the account-level
     * FLOOR. A plan-backed signal's own effective minimum confidence is
     * Math.max(this field, the plan's own minConfidence) at every point it's actually checked
     * (both the client-claimed confidence in evaluateForProfile and the server-computed
     * confidence in evaluateForProfileLocked) -- a plan can only ever require MORE confidence
     * than this account-wide floor, never less.
     */
    private double minConfidence = 75.0;

    /** Max quote-currency (e.g. USDT) value of a single auto-placed order. */
    private BigDecimal maxPositionQuoteAmount = BigDecimal.ZERO;
    /**
     * Review finding ("Account RiskProfile remains too intertwined with StrategyPlan" --
     * external review, ninth pass, P1): this field's own real, current role -- the account-wide
     * TOTAL ceiling across every plan combined, enforced two ways: RiskEngineService's own cheap
     * pre-filter counts ALL open positions for this credential (not one plan's own), and
     * PositionSlotReservationService.reserve(credentialId, this field) is the separate,
     * authoritative atomic gate keyed by credentialId alone. A plan's own maxConcurrentTrades is
     * a SEPARATE, additional reservation keyed by "plan:" + planId -- both must pass; this field
     * is never bypassed or narrowed by a plan's own value, it's the outer ceiling every plan's
     * own combined activity still has to fit inside.
     */
    private int maxConcurrentTrades = 1;
    private BigDecimal dailyLossLimitQuote = BigDecimal.ZERO;

    // Rolling daily-loss tracking, reset when dailyTrackedDate != today.
    private LocalDate dailyTrackedDate = LocalDate.now();
    // Real production incident, confirmed via MongoDB audit-log evidence ("Cannot increment with
    // non-numeric argument: {dailyRealizedLossQuote: \"0.0000100000000000\"}") that triggered
    // FILL_LEDGER_RECORDING_FAILED_HALT / PROTECTION_FAILED and auto-halted a live credential --
    // same root cause and same fix as ExposureReservation.reservedTotalExposureQuote above.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal dailyRealizedLossQuote = BigDecimal.ZERO;

    /** Kill switch. Set true by the user (or automatically once dailyLossLimit is hit) to stop all auto-trading on this credential. */
    private boolean tradingHalted = false;
    private String haltReason;
    // Review finding ("resume() can still race with a new halt" -- external review, confirmed
    // real by direct inspection before any fix was attempted): resume()'s own atomic update used
    // to be a plain "WHERE id=X" write of tradingHalted=false, with no condition on the current
    // value at all -- if a drawdown check or circuit breaker set tradingHalted=true DURING
    // resume()'s own safety-check sequence (between its initial load and its final write),
    // resume's write would still unconditionally succeed, silently clearing that brand-new halt.
    // A simple "WHERE tradingHalted=true" guard would NOT actually fix this -- a concurrent NEW
    // halt also sets tradingHalted=true, making it indistinguishable from the original halt
    // resume() was meant to clear. This version field is the real fix: incremented on EVERY
    // tradingHalted transition (halt() and resume() both bump it), so resume()'s own final write
    // can require "the version is still exactly what it was when resume() started its own safety
    // checks" -- a concurrent halt landing in that window bumps the version too, making
    // resume()'s own stale captured version fail to match, and its write correctly lose the race
    // instead of silently overwriting a legitimate, brand-new halt.
    private long safetyStateVersion = 0;

    /**
     * Review finding ("There is still a tiny gap between final authorization and
     * markExecutionStarted()" -- external review, fourth pass, P0, confirmed real by direct
     * inspection before any fix was attempted): the actual, correct fix. Living on THIS
     * document, in the SAME collection as lastExecutionClaimId/tradingHalted/autoTradeEnabled/
     * autoTradeHalted/liveAutoTradeAuthorized, is what makes a single atomic findAndModify
     * possible at all -- markExecutionStarted() can now check every one of those conditions AND
     * increment this count in ONE MongoDB operation, with no gap between "is this claim still
     * valid" and "register this execution as in flight" for a concurrent kill switch to land in.
     * The previous version (ExecutionInFlightCounter, a separate collection) could only ever be
     * incremented AFTER a separate isClaimStillValid() check returned true -- and MongoDB has no
     * multi-document atomic transaction in this codebase's own current setup, so two separate
     * collections could never close this gap the way one document's own single atomic update can.
     */
    private long executionInFlightCount = 0;

    /**
     * Review finding ("Execution-in-flight counter is useful, but it isn't tied to a claim" --
     * external review, fourth pass, P1): honest limitation, stated plainly. The INCREMENT
     * itself is conditional on a specific claimId (see markExecutionStarted's own atomic
     * findAndModify), so this count can only ever grow for a currently-valid claim -- but the
     * stored VALUE is still just a count, not a set of {claimId, orderId, workerId} records. If
     * this is ever 2, there is no way to tell which two executions those are from this field
     * alone. This is fine for its actual purpose (halt()'s own audit report -- "was something in
     * flight the moment this kill switch engaged") but must never be read as proof of any one
     * execution's authority, and a caller needing that would need a real per-execution record
     * (correlated through OMS Order/clientOrderId, not this aggregate), not this field.
     */
    /**
     * Review finding ("claimExecutionAuthorization() is still an authorization claim, not a
     * lease" -- external review, second pass, confirmed real by direct inspection before any
     * fix was attempted): the actual claim identity -- a fresh UUID generated on every
     * successful claim, alongside safetyStateVersion (already this codebase's own real fencing
     * token for exactly this class of race, built for halt()/resume() and reused here rather
     * than introducing a second, parallel generation counter). Together these let a caller (and
     * anything downstream that records this claim's own id) know EXACTLY which claim authorized
     * a given exchange submission, and any concurrent halt/resume bumps safetyStateVersion,
     * which this same field's own value becomes stale against.
     *
     * Honest limitation, stated plainly rather than implied away: this narrows the theoretical
     * gap between "authorization checked" and "exchange call made" but cannot eliminate it
     * entirely -- the two are fundamentally different kinds of operation (an atomic database
     * write vs. a network call to a third party), and no single atomic operation can span both.
     * A JVM-level pause (GC, thread scheduling) between the claim and the network call remains
     * theoretically possible even with adjacent code and zero intervening logic. What this DOES
     * provide: a real, traceable identity for every claim, and a fencing token that makes an
     * intervening halt/resume detectable rather than silently invisible to a caller still
     * holding a claim issued before it.
     */
    private String lastExecutionClaimId;

    /**
     * Percent of available quote-asset balance risked per trade, used with the signal's own
     * stop-loss distance to size the position (review item #9): quantity = (balance * riskPerTradePercent/100) / |entry - stopLoss|.
     * maxPositionQuoteAmount remains a hard cap on top of this — whichever is smaller wins.
     *
     * Review finding ("Account RiskProfile remains too intertwined with StrategyPlan" --
     * external review, ninth pass, P1): this field's own real, current role -- the account-level
     * CEILING. A plan-backed signal's own effective risk-per-trade is
     * Math.min(this field, the plan's own riskPerTradePercent) at the actual sizing calculation
     * -- a plan can only ever risk LESS than this account-wide ceiling, never more.
     */
    private double riskPerTradePercent = 1.0;

    /**
     * Sum of (quantity × avgEntryPrice) across all OPEN positions for this credential must stay
     * under this cap — review item #10 (portfolio-level exposure, scoped to one credential; true
     * cross-broker/cross-asset correlation limits are out of scope for this pass). 0 = disabled.
     */
    private BigDecimal maxTotalExposureQuote = BigDecimal.ZERO;

    /** Review item #10: cap on exposure to a single symbol specifically, on top of the total
     *  portfolio cap above — stops one symbol from silently eating the whole exposure budget
     *  across several signals before the total cap alone would catch it. 0 = disabled. */
    private BigDecimal maxSymbolExposureQuote = BigDecimal.ZERO;

    /**
     * Review item #25, scoped honestly: real correlation risk needs either a computed rolling
     * correlation matrix or configured coefficients, neither of which this pass fabricates.
     * What's here instead is the mechanism without invented numbers: the user tags symbols into
     * named groups (e.g. "L1-majors": BTC, ETH, SOL) and sets a cap per group. Empty map =
     * feature inert, changes nothing versus before. This is honestly a manual proxy for
     * correlation, not correlation itself — accurate coefficients are a real next step, not a
     * default value.
     *
     * Review finding (P1 #5 — "Correlation risk controls are still dead/incomplete", re-flagged
     * across multiple review passes: "Don't expose a risk-control feature that isn't actually
     * operational"): the API-reachability gap is now closed — RiskProfileRequest carries these
     * fields and RiskProfileService.upsert() persists them, so a configured group is genuinely
     * enforced by RiskEngineService's existing check, not silently dropped.
     *
     * HONEST LIMITATION still disclosed, not fixed: the check itself remains a plain
     * read-current-positions-then-compare, not the atomic reservation ExposureReservationService
     * gives total/symbol exposure — two concurrent orders that would each individually stay
     * under a correlation-group cap could still both pass the check simultaneously, the same
     * race that fix closed for total/symbol exposure specifically. Building that same atomicity
     * for an arbitrary number of user-configured, possibly-overlapping groups without a live
     * MongoDB to verify against remains real risk not taken on blind in this pass — see
     * ExposureReservation's own javadoc for the identical reasoning applied there.
     */
    private java.util.Map<String, java.util.Set<String>> correlationGroups = new java.util.HashMap<>();
    private java.util.Map<String, BigDecimal> correlationGroupCaps = new java.util.HashMap<>();

    /**
     * Max allowed deviation between a signal's claimed entryPrice and the live exchange price at
     * order time, as a fraction (0.015 = 1.5%). Review items #2 (partial defense against a
     * tampered/stale signal) and #11 (slippage protection). Default is deliberately tight.
     */
    private double maxPriceDeviationPercent = 1.5;

    /**
     * Review item #27: a crude circuit breaker. Consecutive signal-order failures (network
     * errors, broker rejections, anything) increment this; hitting the threshold auto-halts
     * the credential the same way a naked-position emergency does. Reset to 0 on any success.
     */
    private int consecutiveOrderFailures = 0;
    private int circuitBreakerThreshold = 3;

    /**
     * Review item #24, updated ("#17" — now mark-to-market): equity = free quote-asset balance
     * + the current market value of every open position on this credential, computed fresh each
     * reconciliation cycle in PositionMonitorService.checkDrawdown(). An underwater open
     * position now moves this number in real time instead of waiting for it to close.
     * Honest scope carried over from that method's own javadoc: this assumes every open
     * position's symbol quotes in drawdownQuoteAsset directly (no cross-asset conversion), and
     * the whole check is skipped for a cycle — not computed from a partial position list — if
     * any open position's current price can't be fetched.
     */
    private String drawdownQuoteAsset = "USDT";
    private java.math.BigDecimal peakEquityQuote;
    private double maxDrawdownPercent = 0; // 0 = disabled

    // Review finding ("Other important remaining production work" — "order-frequency limits"):
    // caps NEW ENTRY orders (not exits — SL/TP fills are risk-reducing, not the runaway-entry
    // scenario this guards against) within a rolling window. 0 = disabled, same convention as
    // every other optional limit on this profile.
    private int maxOrdersPerHour = 0;

    // Review finding ("Risk" — "strategy-level loss breaker" / "strategy consecutive-loss
    // breaker") — the review's own language is "strategy halted", not "account halted", and
    // this codebase's only genuine, honest strategy-like distinction is triggerSource. Checked
    // this precisely rather than assumed: SIGNAL means "went through AutoTradeService's
    // risk-evaluated execution pipeline" (AutoTradeService.java) — true for BOTH
    // scanner-discovered signals and frontend-submitted ones, since both call the same
    // evaluateSignal() path. MANUAL means the separate OrderExecutionService path (the Stage-1
    // manual test-order flow, which RiskEngineService's own class javadoc already documents as
    // deliberately bypassing risk checks entirely). Not a full multi-strategy versioning system,
    // which was never built (a separately-scoped, much larger ask) — this is real, but it's
    // "risk-evaluated vs not", not "autonomous vs human-initiated".
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

    // Review finding ("Kill switch can race with LIVE order submission" -- P0, full context at
    // the actual atomic claim in AutoTradeService): the money-moving-side half of the fix.
    // Written by RiskProfileService.claimExecutionAuthorization()'s own atomic conditional
    // update, immediately before every real exchange order this codebase places -- its own
    // update succeeding is the actual, honest proof that autoTradeEnabled/tradingHalted/
    // autoTradeHalted/liveAutoTradeAuthorized were ALL simultaneously true at this exact moment,
    // not read separately and trusted as still-true moments later. Purely a timestamp of the
    // last successful claim -- not itself read by anything else, its only purpose is being the
    // field the atomic $set targets so the conditional update is a real write, not just a read.
    private java.time.LocalDateTime lastExecutionClaimAt;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
