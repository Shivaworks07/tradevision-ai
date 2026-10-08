package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * User's own explicit design: a user can create N strategy plans per credential, each
 * independently owning its own timeframe, direction, coin universe, risk, and exit rules,
 * feeding into the SAME account-level risk ceiling and execution pipeline -- RiskProfile remains
 * the account-level controller (tradingHalted, the kill switch, the execution-claim system,
 * account-wide riskPerTradePercent/maxConcurrentTrades/minConfidence as a hard ceiling); this
 * model is the plan-level layer underneath it.
 *
 * "The account-level limits always win" means every check this model's own fields drive is a
 * NARROWING of the account-level ceiling, never an override of it -- enforced at the point
 * plans are actually evaluated for execution, not by this model itself (a plan document has no
 * way to enforce anything on its own; it's data, not a gate).
 *
 * Per-plan scanning, direction/session/ownership/universe authorization at execution time
 * (StrategyPlanService.authorizeExecution), two-tier risk (slots and sizing), and the full exit
 * policy (TP/SL, max-hold-time, signal-reversal, risk-emergency-exit, end-of-session) are all
 * implemented and wired -- see StrategyPlanService's own class javadoc and
 * AutonomousScannerService/AutoTradeService/PositionMonitorService for where each piece runs.
 * The Strategy Plan management UI (create/edit/enable-disable/delete) lives at
 * /app/strategy-plans in the frontend.
 */
@Data @NoArgsConstructor
@Document(collection = "strategy_plans")
public class StrategyPlan {
    @Id
    private String id;
    @Indexed
    private String userId;
    @Indexed
    private String credentialId;

    private String name = "Default";
    private boolean enabled = true;
    /**
     * A monotonic configuration-generation number for this plan. A signal is stamped with this
     * value at the moment it was generated, and that stamp is atomically verified against the
     * plan's current version in the same findAndModify that registers the plan-level execution
     * claim (StrategyPlanService.claimPlanExecution) — not a separate read followed by a
     * hopeful claim. If the plan was edited or disabled after the signal was generated, the
     * version no longer matches and the claim atomically fails, so "is this signal still
     * authorized under the configuration that generated it" is answered honestly rather than
     * assumed from a stale read.
     *
     * @Version is Spring Data MongoDB's built-in optimistic-locking mechanism — it, not
     * application code, owns the increment, atomically as part of every save() call, and
     * throws OptimisticLockingFailureException the instant a save's expected version no longer
     * matches what's actually in the database. Must stay a primitive `long` (not `Long`) --
     * Spring Data MongoDB's @Version support requires the primitive type for repository-based
     * saves.
     */
    @org.springframework.data.annotation.Version
    private long version;
    /**
     * The plan-level counterpart to RiskProfile.executionInFlightCount -- an auditable record
     * of whether a claimed execution is currently in flight for this specific plan,
     * incremented atomically as part of the same findAndModify that verifies
     * enabled+version+ownership+credential. This is a diagnostic count, not a set of
     * per-execution identity records.
     */
    private long executionInFlightCount = 0L;
    /**
     * Marks the single migration-created plan per credential that mirrors this codebase's
     * pre-multi-plan behavior (TIER1 symbols, 1h timeframe, LONG only, no max-hold). Never a
     * user-facing toggle -- set only by StrategyPlanService.getOrCreateDefaultPlan, and used to
     * identify which plan absorbs a credential's pre-existing RiskProfile-level settings during
     * migration so nothing is lost or duplicated.
     */
    private boolean defaultPlan = false;

    // ---- Signal matching: which candles, which direction ----
    private String timeframe = "1h"; // Binance kline interval string, same convention as RiskProfile.scanTimeframe before this model existed
    private TradeDirection direction = TradeDirection.LONG; // see TradeDirection's own enum javadoc for why SHORT/BOTH are conditionally valid

    // ---- Coin universe: same two mechanisms AutonomousScannerService already has, now per-plan ----
    private Set<String> enabledSymbols = new HashSet<>();
    private boolean dynamicUniverseEnabled = false;
    private int dynamicUniverseMaxSymbols = 10;

    // ---- Plan-level risk: a NARROWING of RiskProfile's own account-level ceiling, never a bypass of it ----
    private double riskPerTradePercent = 1.0;
    private int maxConcurrentTrades = 1;
    /** Optional hard cap on this plan's own total capital in quote-currency terms (e.g. USDT). Null = no plan-specific cap beyond the account ceiling. */
    private Double maxCapital;

    // ---- Exit policy: independently toggleable, per the user's own explicit list ----
    /** Minutes after entry at which this plan force-exits regardless of TP/SL, if enabled. Null/0 = no max-hold enforcement for this plan. */
    private Integer maxHoldMinutes;
    private boolean exitOnSignalReversal = false;
    private boolean exitOnRiskEmergency = true; // matches this codebase's own existing, always-on kill-switch/halt behavior -- defaulting true keeps a new plan at least as safe as the account-level system already is

    // ---- Trading session: user's own explicit design ("A user-configurable recurring trading
    // window attached to each Strategy Plan, with its own timezone... I'd make 24/7 the default,
    // so existing strategies aren't unexpectedly flattened") ----
    private SessionMode sessionMode = SessionMode.ALWAYS_ON;
    /** Local time-of-day the session opens, in sessionTimezone. Null/unused when sessionMode is ALWAYS_ON. */
    private java.time.LocalTime sessionStart;
    /** Local time-of-day the session closes, in sessionTimezone. Null/unused when sessionMode is ALWAYS_ON. */
    private java.time.LocalTime sessionEnd;
    /** IANA zone id (e.g. "Asia/Kolkata") the session's own start/end times are interpreted in. */
    private String sessionTimezone = "UTC";
    /** Which days of the week the session applies to -- only meaningful when sessionMode is CUSTOM_DAYS. */
    private Set<java.time.DayOfWeek> sessionDays = new HashSet<>();
    private EndOfSessionAction endOfSessionAction = EndOfSessionAction.CLOSE_POSITIONS;

    private double minConfidence = 75.0;
    /** Minutes to wait before this plan will act on another signal for the same symbol -- same purpose as AutonomousScannerService's own existing SIGNAL_COOLDOWN, now per-plan. */
    private int cooldownMinutes = 15;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
