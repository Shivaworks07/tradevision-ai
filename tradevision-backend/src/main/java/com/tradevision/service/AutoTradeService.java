package com.tradevision.service;

import com.tradevision.model.*;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The autonomous trading entry point: called from TradeCallService right after a new signal is
 * saved. This is the only place auto-triggered orders originate. A failure anywhere in here is
 * caught and logged; it must never take down signal saving for the user.
 *
 * Position sizing is risk-based: quantity comes from account balance × risk-per-trade%, divided
 * by the signal's own stop-loss distance — not a flat quote amount. maxPositionQuoteAmount
 * remains a hard ceiling on top of that.
 *
 * Spot-only, long-only: SHORT signals are skipped rather than mis-executed.
 *
 * Scope note: this trusts TradeCallRecord as handed to it by TradeCallService, which in turn
 * trusts whatever the client posted — the computed signal fields themselves (confidence,
 * direction, entry, stopLoss) originate from the client's own computation. NoTradeFilterService
 * plus the live-price sanity/slippage check right below add real, independently-verified
 * server-side gates on top of that (data quality, R:R, duplicate position, and — critically —
 * the claimed entry price is checked against the exchange's actual current price before
 * anything is sized). Full server-side signal generation is handled separately by
 * ServerSignalEngine/SignalCombinerService; this class consumes the server-computed signal
 * (serverSignal) as the authoritative basis for sizing and execution, not the client's claim.
 */
@Service
@RequiredArgsConstructor
public class AutoTradeService {

    private static final Logger log = LoggerFactory.getLogger(AutoTradeService.class);
    // Identifies which JVM instance currently holds an evaluation's lease — generated once per
    // process lifetime, not per call. Informational/diagnostic (visible on the record for
    // debugging which worker had it), not itself the reclaim condition — see the lease expiry
    // check in AutoTradeRecoveryService.
    private static final String WORKER_ID = java.util.UUID.randomUUID().toString();
    private static final java.time.Duration EVALUATION_LEASE_DURATION = java.time.Duration.ofMinutes(5);
    // Caps how much of the account's free balance a sized position can actually consume: a tight
    // stop (small stopDistance) would otherwise produce a large quantity regardless of what the
    // account can afford, which Binance would either reject outright or, if
    // maxPositionQuoteAmount happens to be configured, silently trade at a different
    // notional/risk than riskPerTradePercent intended. A 2% buffer below the exact free balance,
    // not the exact figure -- fees and the bid/ask spread mean a MARKET buy for exactly
    // quoteBalance's worth can still be rejected as insufficient funds.
    private static final BigDecimal BALANCE_SAFETY_BUFFER = new BigDecimal("0.02");
    // The existing liquidity gate (NoTradeFilterService.checkSpread) only looks at the
    // top-of-book bid/ask gap, which says nothing about whether the exchange can actually absorb
    // this specific order's own quantity without materially moving price. 50 bps (0.5%) above the
    // best ask, and the top 50 levels of the book -- deliberately generous (this is a "is the
    // book thin at all" check, not a tight slippage budget), so it only fires for genuinely
    // illiquid conditions.
    private static final BigDecimal MAX_PRICE_IMPACT_PERCENT = new BigDecimal("0.005");
    private static final int ORDER_BOOK_DEPTH_LEVELS_FOR_IMPACT_CHECK = 50;
    // The exit OCO's stop leg is a STOP_LOSS_LIMIT order (belowPrice = stopTrigger * (1 - this
    // gap), see placeExitOcoOrEmergencyFlatten below) rather than a market stop. A
    // STOP_LOSS_LIMIT's own resting limit can still be jumped clean over by a fast enough
    // gap-down, leaving the leg triggered but unfilled; the existing watchdog
    // (PositionMonitorService.watchExitProtection, every 10s) detects and emergency-flattens that
    // case. The gap itself is a configurable deployment setting
    // (app.trading.stop-loss-limit-gap-percent) rather than a hardcoded constant, shared by every
    // call site that needs it (this class and three in PositionMonitorService) via
    // STOP_LOSS_LIMIT_GAP (below), so an operator whose traded symbols need a wider cushion
    // against gap-through risk can widen it without a code change.
    @Value("${app.trading.stop-loss-limit-gap-percent:0.005}")
    private BigDecimal stopLossLimitGapPercent;
    // The only staleness protection at evaluation time besides this is
    // NoTradeFilterService's own price-deviation check (the signal's claimed entry price vs. the
    // exchange's current price) -- a real, useful check, but not a substitute for a hard age
    // gate: a symbol that happened to wander back near its original claimed entry price hours
    // later would sail straight through that check even though the market conditions the signal
    // was actually computed from (momentum, volume, the broader setup) are long gone.
    // AutoTradeRecoveryService.expireStaleSignals' own 24-hour EXPIRE_AFTER is a completely
    // different, much looser bound -- it exists to eventually clean up signals that never got any
    // worker at all, not to gate what's actually safe to trade. This is the actual "is this
    // signal still fresh enough to act on" cutoff, checked at the true point of execution (both
    // the normal fresh-dispatch path and every recovery re-dispatch route through
    // evaluateSignal, so this one gate covers both). Also referenced directly by
    // AutoTradeRecoveryService.recoverStuckSignals to decide whether a stuck signal is even worth
    // re-dispatching at all, rather than only being enforced after a wasted re-dispatch attempt.
    public static final java.time.Duration MAX_SIGNAL_AGE = java.time.Duration.ofMinutes(15);

    private final RiskProfileRepository riskProfileRepo;
    private final BrokerCredentialService credentialService;
    private final RiskEngineService riskEngine;
    private final NoTradeFilterService noTradeFilter;
    private final PositionRepository positionRepo;
    private final com.tradevision.repository.StrategyPlanRepository strategyPlanRepo;
    private final StrategyPlanService strategyPlanService;
    private final com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    private final PositionSlotReservationService slotReservationService;
    private final ExposureReservationService exposureReservationService;
    private final com.tradevision.config.ShutdownState shutdownState;
    private final com.tradevision.config.StartupState startupState;
    private final OrderService orderService;
    // Provides the atomic execution-authorization barrier claimed right before
    // adapter.placeOrder below, so a kill switch or halt cannot race a LIVE order submission.
    private final RiskProfileService riskProfileService;
    private final FillLedgerService fillLedgerService;
    private final PositionLedgerService positionLedgerService;
    private final IncidentService incidentService;
    /** Records a traceable execution record for every signal evaluation, from start to terminal outcome. */
    private final ExecutionContextService executionContextService;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final PositionSafetyService positionSafetyService;
    // credentialLocks (below) is an in-process lock only -- it stops two signals for the same
    // credential from racing each other on this instance, but has no relationship to
    // PositionMonitorService's own distributed lock (same DistributedLockService, keyed by
    // credentialId), which reconciliation acquires before touching a credential's
    // positions/orders. Without this field, nothing would stop this method's own entry flow --
    // placing a real order, then creating the Position for it -- from running fully concurrently
    // with a reconciliation pass for that exact credential, on this instance or any other: a
    // fresh fill's own Position-creation window (order placed, not yet saved as a Position) could
    // race a reconciliation pass that discovers the same broker order as a late-fill and tries to
    // create its own Position for it, or an OCO placement here could race reconciliation's own
    // re-protection logic for the very same position. This flow acquires the same distributed
    // lock, under the same key (credentialId) reconciliation uses, before entering the
    // money-moving critical section -- the two can structurally never run concurrently for one
    // credential, on any instance.
    private final DistributedLockService distributedLockService;
    private final String instanceId = java.util.UUID.randomUUID().toString();
    /**
     * Reused directly for this class's own initial entry-OCO placement, so it shares the same
     * hardened pre-submission sequence PositionMonitorService uses elsewhere, rather than a
     * second, independently-maintained copy of the same logic.
     */
    private final PositionMonitorService positionMonitorService;
    private final com.tradevision.repository.TradeCallRepository callRepo;

    // Two signals for the same credential arriving on separate request threads could both read
    // "0 open positions" before either has written its own — a classic check-then-act race. This
    // locks the whole evaluate-then-place critical section per credential. Scope: this is an
    // in-process lock. It only protects a single backend instance; if this is ever run as more
    // than one replica, this needs a distributed lock (e.g. a Mongo findAndModify-based
    // reservation) instead of, or in addition to, this one.
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> credentialLocks
        = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @Async moves the actual Binance work (which can take seconds, especially with retry/backoff)
     * off the HTTP thread handling POST /api/calls/save — that endpoint returns as soon as the
     * call is saved, not after auto-trading finishes. Runs on the bounded pool from AsyncConfig,
     * not Spring's unbounded default.
     */
    @org.springframework.scheduling.annotation.Async("autoTradeExecutor")
    public void evaluateSignal(String userId, TradeCallRecord signal) {
        if (shutdownState.isShuttingDown()) {
            log.info("Shutdown in progress — skipping evaluation of signal on {} for user {}.", signal.getSymbol(), userId);
            return;
        }
        // Refuses to evaluate anything until the startup reconciliation pass has at least run to
        // completion once. The signal stays PENDING (never claimed) here —
        // AutoTradeRecoveryService's own stuck-signal sweep will pick it up once this is reached,
        // an acceptable bounded delay (worst case, the existing 10-minute recovery window) in
        // exchange for never evaluating against unverified broker state right after a cold
        // start.
        //
        // This coarse check only confirms the startup pass itself ran to completion -- the
        // per-credential decision is made per profile, in evaluateForProfile below, via
        // startupState.isCredentialTradingEnabled(profile.getCredentialId()). That keeps one
        // credential's failed startup reconciliation (e.g. a revoked API key) from blocking every
        // other user's every other credential too: only the credential that actually failed its
        // own startup reconciliation is blocked, and it recovers automatically the moment a
        // later reconciliation cycle succeeds for it.
        if (!startupState.isTradingEnabled()) {
            log.info("Startup reconciliation not yet complete (phase: {}) — leaving signal {} on {} PENDING for now.",
                startupState.getPhase(), signal.getId(), signal.getSymbol());
            return;
        }
        // Atomic claim, PENDING -> EVALUATING. Whether this call came from the normal
        // save-then-dispatch flow or from AutoTradeRecoveryService re-dispatching a stuck signal,
        // only one caller can win this — MongoDB's single-document atomicity, same pattern used
        // everywhere else in this codebase.
        //
        // signalStatus must also be GENERATED or VALIDATING (the same allowed set
        // AutoTradeRecoveryService's own recoverStuckSignals query uses, since a signal recovered
        // mid-evaluation can legitimately still be at VALIDATING, not just fresh at GENERATED)
        // for this claim to succeed at all -- a user-cancelled (CANCELLED) or expired (EXPIRED)
        // signal must never be claimed and traded, regardless of what its autoTradeEvalStatus
        // happens to read.
        if (signal.getId() != null) {
            var claimed = mongoTemplate.findAndModify(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(signal.getId()).and("autoTradeEvalStatus").is("PENDING")
                    .and("signalStatus").in(com.tradevision.model.SignalStatus.GENERATED, com.tradevision.model.SignalStatus.VALIDATING)),
                new org.springframework.data.mongodb.core.query.Update().set("autoTradeEvalStatus", "EVALUATING").set("autoTradeEvalStartedAt", java.time.LocalDateTime.now())
                    // The lease itself — set at claim time, checked at reclaim time in
                    // AutoTradeRecoveryService, so a stuck EVALUATING signal is reclaimed based
                    // on an actual lease expiry rather than a fixed elapsed-time guess.
                    .set("evaluationOwner", WORKER_ID).set("evaluationLeaseUntil", java.time.LocalDateTime.now().plus(EVALUATION_LEASE_DURATION)),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), TradeCallRecord.class);
            if (claimed == null) {
                log.debug("Signal {} was not in PENDING state (already claimed, evaluated, or from before this field existed) — skipping.", signal.getId());
                return;
            }
        }
        boolean evaluationSucceeded = true;
        try {
            List<RiskProfile> profiles = riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue(userId);
            for (RiskProfile profile : profiles) {
                evaluateForProfile(profile, signal);
            }
        } catch (Exception e) {
            log.error("Auto-trade evaluation failed for user {} symbol {}: {}", userId, signal.getSymbol(), e.getMessage(), e);
            evaluationSucceeded = false;
        } finally {
            // EVALUATED only when the loop above actually completed without an exception
            // interrupting it — an EVALUATION_FAILED signal is visibly distinct, not silently
            // indistinguishable from a genuine completion. Deliberately not reset to PENDING here
            // (a real design choice: endlessly auto-retrying a signal that just threw is worse
            // than a human seeing EVALUATION_FAILED and investigating).
            //
            // Conditional on evaluationOwner still being this worker. If a different worker has
            // since reclaimed this signal (this worker's own lease having expired while it was
            // still genuinely mid-evaluation, not dead), this update simply matches nothing and
            // silently no-ops, rather than overwriting the new owner's own autoTradeEvalStatus
            // once this worker finally finishes -- the same "whoever still owns it wins"
            // principle this codebase uses for execution-claim conditional updates elsewhere.
            if (signal.getId() != null) {
                mongoTemplate.updateFirst(
                    new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(signal.getId())
                        .and("evaluationOwner").is(WORKER_ID)),
                    new org.springframework.data.mongodb.core.query.Update().set("autoTradeEvalStatus", evaluationSucceeded ? "EVALUATED" : "EVALUATION_FAILED"),
                    TradeCallRecord.class);
            }
        }
    }

    /**
     * Advances signal.signalStatus, but only forward (by declaration order) — never regresses an
     * already-more-advanced status. This matters for the multi-profile case: if profile A
     * already got this signal to APPROVED, a later, less-permissive profile B evaluating the
     * same signal must not visibly roll its status back to VALIDATING. Persisted via a targeted
     * atomic update, not a full-object save, so it can't stomp a concurrent change to some other
     * field on the same document. Non-fatal by design, same as every other additive OMS/ledger
     * call in this class — a bug in this bookkeeping must never block the real evaluation flow
     * below it.
     */
    /**
     * A shared helper for the "halt with a reason" field pair (tradingHalted, haltReason),
     * written via a targeted atomic update rather than a full RiskProfile.save() so it can't
     * stomp a concurrent change to some other field on the same document.
     */
    private void atomicHaltProfile(RiskProfile profile, String reason) {
        if (profile == null) return;
        profile.setTradingHalted(true);
        profile.setHaltReason(reason);
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", reason),
            RiskProfile.class);
    }

    /**
     * A defensive wrapper around getOrderStatusByClientOrderId -- the verification query itself
     * failing (a network issue, a transient exchange error) is genuinely different from the
     * order being confirmed absent, and the caller needs to tell the two apart: null here means
     * "couldn't verify," never "confirmed doesn't exist".
     */
    private com.tradevision.service.broker.dto.OrderStatusInfo safeGetOrderStatusByClientOrderId(
            com.tradevision.service.broker.BrokerAdapter adapter, String apiKey, String apiSecret,
            BrokerMode mode, String symbol, String clientOrderId) {
        try {
            return adapter.getOrderStatusByClientOrderId(apiKey, apiSecret, mode, symbol, clientOrderId);
        } catch (Exception e) {
            log.warn("Could not verify real order status for clientOrderId {} on {}: {}", clientOrderId, symbol, e.getMessage());
            return null;
        }
    }

    /**
     * The single, centralized release for both the account-level and plan-level reservation
     * tiers, used at every point this flow gives up on a signal after having reserved a slot --
     * releasing only the account-level slot and forgetting the plan-level one would permanently
     * leak a plan's own concurrent-trade capacity every time this method's many early-return
     * paths ran, eventually starving that plan of its own configured slots for no real reason.
     */
    private void releaseSlots(RiskProfile profile, TradeCallRecord signal, boolean planSlotReserved,
                               String accountSlotReservationId, String planSlotReservationId) {
        // Releases the exact reservation by id when available, falling back to the narrower
        // key-based release only when no id was ever captured.
        if (accountSlotReservationId != null) {
            slotReservationService.release(accountSlotReservationId);
        } else {
            slotReservationService.releaseByKey(profile.getCredentialId());
        }
        if (signal.getPlanId() != null && planSlotReserved) {
            if (planSlotReservationId != null) {
                slotReservationService.release(planSlotReservationId);
            } else {
                slotReservationService.releaseByKey("plan:" + signal.getPlanId());
            }
        }
    }

    /**
     * Marks an entry order SUBMISSION_FAILED right at the moment an abort happens, for every
     * call site in evaluateSignal that aborts strictly before adapter.placeOrder() is ever
     * called (lease-lost, execution-authorization-claim-failed, plan-authorization-lost,
     * atomic-claim-failed, lock-renewal-failure). Without this, omsOrder would be left sitting
     * in SUBMITTING with no further transition for every one of those aborts -- exactly the
     * state OrderService.recoverStuckSubmittingOrders's own 5-minute sweep exists to catch, but
     * that sweep can't tell "genuinely never reached the exchange, safe to auto-resolve" from
     * "may have reached it, needs a human" on its own, so it would raise a CRITICAL incident and
     * block Resume for what is actually a completely ordinary, already-safely-aborted rejection.
     * Resolving it here instead, at the exact moment each abort happens -- since nothing
     * exchange-facing has happened yet at any of these call sites, it's safe to resolve omsOrder
     * immediately rather than waiting on the sweep to guess. Non-fatal and additive, same as
     * every other OMS bookkeeping call in this method: the real abort (releasing reservations,
     * recording the terminal execution outcome, returning) still happens unconditionally at each
     * call site regardless of whether this write itself succeeds.
     */
    private void abortEntrySubmission(com.tradevision.model.Order omsOrder, String reason) {
        if (omsOrder == null) {
            return; // OMS setup itself already failed earlier -- nothing to resolve
        }
        try {
            orderService.markSubmissionFailed(omsOrder, reason);
        } catch (Exception e) {
            log.warn("Could not mark OMS order {} SUBMISSION_FAILED after aborting before the exchange call (non-fatal, additive record "
                + "only): {} -- the 5-minute stuck-in-SUBMITTING sweep remains the fallback if this write itself failed.",
                omsOrder.getId(), e.getMessage());
        }
    }

    private void advanceSignalStatus(TradeCallRecord signal, com.tradevision.model.SignalStatus newStatus) {
        if (signal.getId() == null) return; // not yet persisted — nothing to update in the database
        try {
            com.tradevision.model.SignalStatus current = signal.getSignalStatus();
            if (current != null && newStatus.ordinal() <= current.ordinal()) return;
            signal.setSignalStatus(newStatus);
            // The in-memory ordinal check above only protects against this in-memory `signal`
            // object being stale; it does nothing to stop a genuinely concurrent call (Thread A
            // and Thread B both loaded the signal before either wrote) from both passing their
            // own in-memory check and then both writing unconditionally, with whichever one's
            // own updateFirst() happens to run last winning regardless of which status is
            // actually higher — e.g. Thread B reaching APPROVED first, then Thread A (still
            // holding a stale VALIDATING in memory) overwriting it back down. So the database
            // write itself is conditional on the current stored status still being lower than
            // newStatus, not just the in-memory copy -- MongoDB can't compare enum ordinals
            // directly (it would compare the stored strings lexicographically, meaningless for
            // ordering), so the set of "still lower" status values is computed in Java from the
            // enum's own real ordinal order and checked via $in, including the not-yet-set case
            // via a separate $exists:false branch.
            java.util.List<String> lowerStatuses = java.util.Arrays.stream(com.tradevision.model.SignalStatus.values())
                .filter(s -> s.ordinal() < newStatus.ordinal())
                .map(Enum::name)
                .toList();
            var criteria = new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                org.springframework.data.mongodb.core.query.Criteria.where("signalStatus").in(lowerStatuses),
                org.springframework.data.mongodb.core.query.Criteria.where("signalStatus").exists(false));
            var result = mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(signal.getId()).andOperator(criteria)),
                new org.springframework.data.mongodb.core.query.Update().set("signalStatus", newStatus),
                TradeCallRecord.class);
            if (result.getModifiedCount() == 0) {
                // Lost the race, or the stored status is already >= newStatus for some other
                // reason -- not an error, just means this specific advance lost or was
                // redundant. The in-memory `signal.setSignalStatus(newStatus)` above is now
                // arguably wrong (it optimistically assumed this write would win), but this
                // in-memory object isn't re-read by anything else in this same call after this
                // point, so it's a harmless, momentary inconsistency, not a propagated one.
                log.debug("Signal status advance to {} for signal {} did not apply -- likely lost a race with a concurrent, "
                    + "equal-or-higher status write.", newStatus, signal.getId());
            }
        } catch (Exception e) {
            log.warn("Signal status update to {} failed for signal {} (non-fatal, additive record only): {}", newStatus, signal.getId(), e.getMessage());
        }
    }

    private void evaluateForProfile(RiskProfile profile, TradeCallRecord signal) {
        // Created at the true start of evaluation, not inside evaluateForProfileLocked, so every
        // signal this method is called for gets a real, traceable ExecutionContext record --
        // including ones rejected by the early filter checks a few lines below, before
        // evaluateForProfileLocked is ever reached. The cost: a new document for every signal
        // evaluated, including the large fraction that fail a cheap, fast pre-filter (wrong
        // symbol, low confidence, wrong direction) in the next few lines -- accepted in favor of
        // lifecycle consistency across every rejection path.
        String executionId = executionContextService.start(signal.getId(), profile.getCredentialId(), profile.getUserId(), signal.getPlanId());

        // The per-credential startup gate: a credential whose own startup reconciliation attempt
        // failed (or whose most recent periodic reconciliation attempt failed) is blocked here,
        // scoped to this one credential only -- every other profile for this same signal, and
        // every other user's credential entirely, evaluates normally regardless.
        if (!startupState.isCredentialTradingEnabled(profile.getCredentialId())) {
            String reason = "This credential's own reconciliation against the real broker state has not yet succeeded "
                + "(startup phase: " + startupState.getPhase() + ") -- autonomous trading is disabled for this credential specifically "
                + "until a reconciliation pass succeeds for it, not restarted for every credential.";
            log.info("Skipping SIGNAL trigger for {} on credential {}: {}", signal.getSymbol(), profile.getCredentialId(), reason);
            executionContextService.recordTerminal(executionId, "REJECTED_CREDENTIAL_RECONCILIATION_PENDING", reason);
            return;
        }

        // Checked here, before any other evaluation work, so a signal old enough to fail this
        // can never reach sizing, risk checks, or an exchange call regardless of how it arrived
        // (fresh dispatch or a recovery re-dispatch). See MAX_SIGNAL_AGE's own field comment.
        if (signal.getCalledAt() != null && signal.getCalledAt().isBefore(LocalDateTime.now().minus(MAX_SIGNAL_AGE))) {
            String reason = "Signal called at " + signal.getCalledAt() + " is older than the maximum actionable age of "
                + MAX_SIGNAL_AGE.toMinutes() + " minute(s) -- the market conditions it was computed from are no longer "
                + "considered fresh enough to trade on, independent of the separate live-price-deviation check.";
            log.info("Skipping SIGNAL trigger for {}: {}", signal.getSymbol(), reason);
            executionContextService.recordTerminal(executionId, "REJECTED_SIGNAL_TOO_OLD", reason);
            return;
        }

        // VALIDATING is the first real stage past GENERATED (set at signal creation) --
        // advanceSignalStatus never regresses an already-more-advanced status, which matters for
        // the multi-profile case.
        advanceSignalStatus(signal, com.tradevision.model.SignalStatus.VALIDATING);
        // Only gates a signal with no plan at all (pre-multi-plan, or a manually-submitted
        // signal) -- a plan-backed signal's own symbol is authorized later, by
        // StrategyPlanService.authorizeExecution's own actual plan-universe check, once
        // adapter/credential are resolved (this early stage doesn't have them yet). Running this
        // unconditionally would incorrectly reject a symbol the Dynamic Universe legitimately
        // discovered for a plan but that was never added to this account-level list.
        if (signal.getPlanId() == null && !profile.getEnabledSymbols().contains(signal.getSymbol())) {
            executionContextService.recordTerminal(executionId, "REJECTED_SYMBOL_NOT_ENABLED", "Symbol not in this profile's own enabled-symbols list, and this signal has no plan of its own.");
            return;
        }
        // A second, defense-in-depth enforcement of the USDT-only rule (see
        // RiskProfileService.doUpsert's identical check), right at the actual money-moving gate
        // -- exposure/equity/drawdown calculations assume a single quote currency, and this
        // catches an existing profile that could still have a non-USDT symbol stored in
        // enabledSymbols, not just newly-saved profiles.
        if (!signal.getSymbol().toUpperCase().endsWith("USDT")) {
            log.error("Skipping SIGNAL trigger for {}: this application only trades USDT-quoted symbols (exposure/equity/drawdown "
                + "calculations assume a single quote currency) -- this symbol is enabled on the risk profile but is not USDT-quoted, "
                + "which should have been rejected when the profile was saved.", signal.getSymbol());
            executionContextService.recordTerminal(executionId, "REJECTED_NON_USDT_SYMBOL", "Symbol is enabled on the risk profile but is not USDT-quoted.");
            return;
        }
        if (signal.getConfidence() < profile.getMinConfidence()) {
            executionContextService.recordTerminal(executionId, "REJECTED_CLIENT_MIN_CONFIDENCE", "Client-claimed confidence " + signal.getConfidence() + " is below the account's own minimum " + profile.getMinConfidence() + "%.");
            return;
        }
        if (!"LONG".equalsIgnoreCase(signal.getDirection())) {
            log.info("Skipping SIGNAL trigger for {}: direction {} not supported (spot, long-only)", signal.getSymbol(), signal.getDirection());
            executionContextService.recordTerminal(executionId, "REJECTED_DIRECTION_NOT_SUPPORTED", "Direction " + signal.getDirection() + " not supported (spot, long-only).");
            return;
        }

        // Acquire this credential's lock before any risk check or order placement, and hold it
        // through the whole thing — no other signal for the same credential can run this
        // critical section concurrently.
        java.util.concurrent.locks.ReentrantLock lock = credentialLocks.computeIfAbsent(
            profile.getCredentialId(), k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            // The cross-instance half of the same mutual-exclusion guarantee the in-process lock
            // above only provides locally -- acquired under the exact same key (credentialId)
            // PositionMonitorService.reconcileCredential uses, so a reconciliation pass already
            // running for this credential (on this instance or any other) is detected here and
            // this evaluation backs off rather than racing it. A short hold duration (30s, not
            // reconciliation's own 90s) -- this is a bounded, single-signal evaluation, not an
            // open-ended reconciliation sweep, and a shorter lease means a crashed/stuck
            // evaluation self-clears far sooner than reconciliation's own budget would allow.
            var acquireLease = distributedLockService.tryAcquireWithDiagnosis(profile.getCredentialId(), instanceId, java.time.Duration.ofSeconds(30));
            if (!acquireLease.acquired()) {
                String reason = acquireLease.result() == DistributedLockService.AcquireResult.HELD_BY_OTHER
                    ? "A reconciliation pass is currently in progress for this credential -- refusing to place a new entry order while "
                        + "reconciliation may be concurrently discovering/adopting fills or replacing protection for existing positions on "
                        + "this exact credential. This signal is not retried automatically; a later signal on this symbol will be evaluated "
                        + "normally once reconciliation has released the lock."
                    : "Could not acquire the cross-instance reconciliation lock for this credential due to a genuine infrastructure failure "
                        + "-- refusing to place a new entry order against an unverified lock state rather than risk racing a reconciliation "
                        + "pass that may or may not actually be running.";
                log.warn("Signal on {} for credential {} blocked: {}", signal.getSymbol(), profile.getCredentialId(), reason);
                credentialService.audit(profile.getUserId(), profile.getCredentialId(), null, "SIGNAL_BLOCKED_RECONCILIATION_LOCK", reason);
                executionContextService.recordTerminal(executionId, "REJECTED_RECONCILIATION_IN_PROGRESS", reason);
                return;
            }
            try {
                evaluateForProfileLocked(profile, signal, executionId);
            } finally {
                distributedLockService.release(profile.getCredentialId(), instanceId);
            }
        } finally {
            lock.unlock();
        }
    }

    private void evaluateForProfileLocked(RiskProfile profile, TradeCallRecord signal, String executionId) {
        BrokerCredential credential;
        try {
            credential = credentialService.ownedCredential(profile.getUserId(), profile.getCredentialId());
        } catch (IllegalArgumentException e) {
            executionContextService.recordTerminal(executionId, "REJECTED_CREDENTIAL_UNAVAILABLE", "Credential was deleted or deactivated since this risk profile was set up.");
            return; // credential deleted/deactivated since profile was set up
        }

        // LIVE requires a second, explicit, separately-confirmed authorization
        // (isLiveAutoTradeAuthorized). Scoped specifically to LIVE, not "anything other than
        // TESTNET" -- PaperBrokerAdapter never sends an authenticated request to Binance at all,
        // so there is no real-money exposure for this re-verified, explicitly-granted flag to
        // actually be gating for PAPER mode.
        if (credential.getMode() == BrokerMode.LIVE && !profile.isLiveAutoTradeAuthorized()) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_BLOCKED_LIVE_NOT_AUTHORIZED",
                "Signal on " + signal.getSymbol() + " blocked: credential is LIVE but autonomous live trading isn't authorized.");
            executionContextService.recordTerminal(executionId, "REJECTED_LIVE_NOT_AUTHORIZED", "Credential is LIVE but autonomous live trading isn't authorized.");
            return;
        }

        // NO-TRADE gate before any sizing or order work happens.
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        // The authoritative execution-time gate for plan authorization (universe membership,
        // session window, plan enabled/disabled, ownership). Deliberately checked here (as early
        // as adapter/credential are available) and again immediately before the exchange call
        // further below -- checking only once would leave a window for the plan to be disabled
        // or its session to end between async signal creation and this evaluation actually
        // running.
        var planAuth = strategyPlanService.authorizeExecution(signal.getPlanId(), profile.getUserId(), profile.getCredentialId(),
            signal.getSymbol(), adapter, credential, profile.getEnabledSymbols());
        if (!planAuth.authorized()) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_BLOCKED_PLAN_AUTHORIZATION", "Signal on " + signal.getSymbol() + " blocked: " + planAuth.reason());
            executionContextService.recordTerminal(executionId, "REJECTED_PLAN_AUTHORIZATION", planAuth.reason());
            return;
        }

        NoTradeFilterService.FilterResult gate = noTradeFilter.check(profile.getUserId(), profile.getCredentialId(), signal, adapter, apiKey, credential.getMode());
        if (!gate.tradeable()) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_NO_TRADE", "Signal on " + signal.getSymbol() + " (conf " + signal.getConfidence() + "): " + gate.reason());
            executionContextService.recordTerminal(executionId, "REJECTED_NO_TRADE_FILTER", gate.reason());
            return;
        }
        // gate.tradeable()==true guarantees a non-null server signal (every success path in
        // NoTradeFilterService.check() carries one). This is what actually drives sizing and OCO
        // placement below, not signal.getEntryPrice()/getStopLoss()/getTarget1() -- the server's
        // own independently-computed values are authoritative for execution, not the client's.
        ServerSignalEngine.Signal serverSignal = gate.serverSignal();

        // Validates the server signal's actual execution values before ever submitting the entry
        // order at all — a signal that can't be protected should never become a position in the
        // first place, rather than discovering only after the order is placed and filled that SL
        // or TP was zero/negative or non-finite (NaN/Infinity can come from indicator math under
        // edge-case inputs) and leaving the position open with no protection order.
        if (!Double.isFinite(serverSignal.entry()) || !Double.isFinite(serverSignal.stopLoss())
                || !Double.isFinite(serverSignal.target1()) || !Double.isFinite(serverSignal.target2())
                || !Double.isFinite(serverSignal.target3()) || !Double.isFinite(serverSignal.confidence())) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Signal on " + signal.getSymbol() + " refused: server-computed entry/SL/TP/confidence contains a "
                    + "non-finite value (NaN or Infinity) — indicator math can produce this under edge-case inputs, "
                    + "and nothing should size a real order off it.");
            executionContextService.recordTerminal(executionId, "REJECTED_NON_FINITE_SIGNAL", "Server-computed entry/SL/TP/confidence contains a non-finite value (NaN or Infinity).");
            return;
        }
        if (serverSignal.entry() <= 0 || serverSignal.stopLoss() <= 0 || serverSignal.target1() <= 0) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Signal on " + signal.getSymbol() + " refused: server-computed entry/stop-loss/target1 must all be "
                    + "positive — a position that can't be protected should never be opened in the first place.");
            executionContextService.recordTerminal(executionId, "REJECTED_NON_POSITIVE_LEVELS", "Server-computed entry/stop-loss/target1 must all be positive.");
            return;
        }

        // evaluateForProfile's earlier gate only checked the client's claimed confidence — a
        // client could claim 92% while the server's own independent computation only reaches
        // 51%, so the user's minConfidence setting must also be enforced against the
        // server-computed confidence, the number that's actually authoritative for execution.
        // The effective minimum is the stricter (higher) of account vs. plan -- a plan configured
        // for 85% must not have its own threshold silently ignored just because the
        // account-level setting is looser (e.g. 60%). Unlike riskPerTradePercent/maxCapital
        // (where a smaller number is the narrower one), a higher confidence requirement is what
        // actually narrows this specific gate, so Math.max, not Math.min, is the correct
        // "account never gets widened by a plan" operation here.
        double effectiveMinConfidence = profile.getMinConfidence();
        if (signal.getPlanId() != null) {
            var confidencePlan = strategyPlanRepo.findById(signal.getPlanId()).orElse(null);
            if (confidencePlan != null) {
                effectiveMinConfidence = Math.max(effectiveMinConfidence, confidencePlan.getMinConfidence());
            }
        }
        if (serverSignal.confidence() < effectiveMinConfidence) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Signal on " + signal.getSymbol() + ": server-computed confidence " + serverSignal.confidence()
                    + " is below the effective configured minimum " + effectiveMinConfidence + "% (account "
                    + profile.getMinConfidence() + "%, this signal's own plan may be stricter) (client claimed "
                    + signal.getConfidence() + "%).");
            executionContextService.recordTerminal(executionId, "REJECTED_MIN_CONFIDENCE",
                "Server-computed confidence " + serverSignal.confidence() + " is below the effective minimum " + effectiveMinConfidence + "%.");
            return;
        }

        // Independently verify the signal's claimed entry price against what the exchange
        // actually says right now, before trusting it for anything.
        BigDecimal livePrice;
        try {
            livePrice = adapter.getCurrentPrice(signal.getSymbol(), credential.getMode());
        } catch (Exception e) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_NO_TRADE", "Signal on " + signal.getSymbol() + ": could not fetch live price to validate signal — " + e.getMessage());
            executionContextService.recordTerminal(executionId, "REJECTED_LIVE_PRICE_FETCH_FAILED", "Could not fetch live price to validate signal: " + e.getMessage());
            return;
        }
        // signal.getEntryPrice() is BigDecimal -- .doubleValue() converts at this specific
        // deviation-percentage calculation.
        double deviationPct = livePrice.signum() > 0
            ? Math.abs(signal.getEntryPrice().doubleValue() - livePrice.doubleValue()) / livePrice.doubleValue() * 100.0
            : 100.0;
        if (deviationPct > profile.getMaxPriceDeviationPercent()) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Signal on " + signal.getSymbol() + " claims entry " + signal.getEntryPrice().toPlainString() + " but live price is "
                    + livePrice + " (" + String.format("%.2f", deviationPct) + "% deviation, max allowed "
                    + profile.getMaxPriceDeviationPercent() + "%) — refusing to trade a stale/suspect signal.");
            executionContextService.recordTerminal(executionId, "REJECTED_CLIENT_PRICE_DEVIATION",
                "Claimed entry price deviates " + String.format("%.2f", deviationPct) + "% from live price (max allowed " + profile.getMaxPriceDeviationPercent() + "%).");
            return;
        }

        // The check above only validated the client's claimed entry — but sizing and OCO
        // placement use serverSignal.entry(), which was computed from a candle snapshot fetched
        // earlier in NoTradeFilterService.check(), not this exact moment. Time passes between
        // that fetch and here; the live price could have moved since. The value actually
        // driving execution needs its own staleness check, not just the client's number.
        double serverDeviationPct = livePrice.signum() > 0
            ? Math.abs(serverSignal.entry() - livePrice.doubleValue()) / livePrice.doubleValue() * 100.0
            : 100.0;
        if (serverDeviationPct > profile.getMaxPriceDeviationPercent()) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Server-computed entry " + serverSignal.entry() + " on " + signal.getSymbol() + " is now " + String.format("%.2f", serverDeviationPct)
                    + "% away from the current live price " + livePrice + " (max allowed " + profile.getMaxPriceDeviationPercent()
                    + "%) — the candle snapshot behind this signal is stale relative to the current market, refusing to trade it.");
            executionContextService.recordTerminal(executionId, "REJECTED_SERVER_PRICE_DEVIATION",
                "Server-computed entry deviates " + String.format("%.2f", serverDeviationPct) + "% from live price (max allowed " + profile.getMaxPriceDeviationPercent() + "%).");
            return;
        }

        BigDecimal quantity = sizePosition(profile, signal, serverSignal, adapter, apiKey, apiSecret, credential.getMode(), livePrice);
        if (quantity == null || quantity.signum() <= 0) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_NO_TRADE", "Signal on " + signal.getSymbol() + ": could not size a valid position (balance/quote-asset/sizing check failed).");
            executionContextService.recordTerminal(executionId, "REJECTED_SIZING", "Could not size a valid position (balance/quote-asset/sizing check failed).");
            return;
        }

        // The order-book depth/impact check -- the existing liquidity gate
        // (NoTradeFilterService.checkSpread) only looks at the top-of-book spread, which says
        // nothing about whether the book can actually absorb this quantity. Checked here, right
        // after sizing, before this quantity is used for anything else (MIN_NOTIONAL, risk
        // engine, slot reservation) -- a thin book is refused before any of those real side
        // effects happen.
        if (!hasSufficientOrderBookDepth(signal.getSymbol(), adapter, credential.getMode(), quantity)) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Signal on " + signal.getSymbol() + ": order book does not have enough depth within "
                    + MAX_PRICE_IMPACT_PERCENT.multiply(BigDecimal.valueOf(100)) + "% of the best ask to fill a quantity of "
                    + quantity + " -- refusing to submit a MARKET order into a thin book.");
            executionContextService.recordTerminal(executionId, "REJECTED_INSUFFICIENT_DEPTH",
                "Order book does not have enough depth within " + MAX_PRICE_IMPACT_PERCENT.multiply(BigDecimal.valueOf(100))
                    + "% of the best ask to fill a quantity of " + quantity + ".");
            return;
        }

        // Use the independently-fetched live price for the pre-trade exposure estimate, not the
        // client-supplied signal price — livePrice was already verified above and is strictly
        // more trustworthy than anything the client sent.
        BigDecimal orderQuoteValue = quantity.multiply(livePrice);

        // MIN_NOTIONAL is parsed into SymbolRules but must also be checked before an order
        // reaches Binance — an order below the exchange's minimum trade value would otherwise
        // simply get rejected there instead of being caught here first. (MARKET_LOT_SIZE is
        // already handled separately, tightening step/minQty from LOT_SIZE in
        // BinanceBrokerAdapter.getSymbolRules. PERCENT_PRICE doesn't apply to MARKET orders,
        // which specify no price at all.)
        var symbolRulesPreCheck = adapter.getSymbolRules(signal.getSymbol(), credential.getMode());
        if (symbolRulesPreCheck.minNotional() != null && symbolRulesPreCheck.minNotional().signum() > 0
                && orderQuoteValue.compareTo(symbolRulesPreCheck.minNotional()) < 0) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SIGNAL_NO_TRADE",
                "Order value " + orderQuoteValue + " on " + signal.getSymbol() + " is below the exchange's minimum "
                    + "notional " + symbolRulesPreCheck.minNotional() + " — would be rejected by Binance, refusing before submission.");
            executionContextService.recordTerminal(executionId, "REJECTED_MIN_NOTIONAL",
                "Order value " + orderQuoteValue + " is below the exchange's minimum notional " + symbolRulesPreCheck.minNotional() + ".");
            return;
        }

        RiskEngineService.RiskCheckResult check = riskEngine.check(profile, signal.getSymbol(), orderQuoteValue);
        if (!check.allowed()) {
            advanceSignalStatus(signal, com.tradevision.model.SignalStatus.RISK_REJECTED);
            credentialService.audit(profile.getUserId(), profile.getCredentialId(), credential.getBroker(),
                "SIGNAL_SKIPPED_RISK", "Signal on " + signal.getSymbol() + " (conf " + signal.getConfidence()
                    + ") skipped: " + check.reason());
            executionContextService.recordTerminal(executionId, "REJECTED_RISK_ENGINE", check.reason());
            return;
        }

        // The authoritative, multi-instance-safe gate. RiskEngineService.check() above already
        // did a cheap read-based concurrent-trades check as a fast pre-filter (saves a DB
        // round-trip in the common case), but that read is not what prevents the race — this
        // atomic Mongo operation is. If this fails, some other signal (possibly on a different
        // instance) won the race for the last slot.
        var accountSlotResult = slotReservationService.reserve(profile.getCredentialId(), profile.getMaxConcurrentTrades(), executionId, credential.getMode() == BrokerMode.LIVE);
        if (!accountSlotResult.reserved()) {
            credentialService.audit(profile.getUserId(), profile.getCredentialId(), credential.getBroker(),
                "SIGNAL_SKIPPED_RISK", "Signal on " + signal.getSymbol() + " (conf " + signal.getConfidence()
                    + ") skipped: max concurrent trades slot unavailable (atomic reservation).");
            executionContextService.recordTerminal(executionId, "REJECTED_SLOT_CAP_ACCOUNT", "Max concurrent trades slot unavailable at the account level.");
            return;
        }
        String accountSlotReservationId = accountSlotResult.reservationId();
        // The plan-level tier of the same atomic reservation mechanism, using the same
        // PositionSlotReservationService the account-level check just above uses -- keyed on
        // "plan:" + planId rather than credentialId, so a plan's own maxConcurrentTrades is
        // enforced independently and atomically, on top of (never instead of) the account-level
        // ceiling that already passed. Account-level is checked first and rolled back here if
        // this second, narrower check fails -- account-level is the outer gate a plan-level
        // failure can never bypass, not an optional extra.
        //
        // Whether this specific execution actually reserved the plan-level slot is tracked
        // explicitly (planSlotReserved) and threaded through to releaseSlots(), rather than
        // re-derived from signal.getPlanId() alone: when the plan record itself can no longer be
        // found (plan == null, e.g. deleted or otherwise missing by the time this signal is
        // evaluated), the plan-level reserve() call below is never even attempted, so
        // releaseSlots() must not release a "plan:" slot this execution never acquired --
        // because this is a shared, aggregate counter, that release could decrement a slot
        // belonging to a different, genuinely still-active execution for the same plan.
        boolean planSlotReserved = false;
        String planSlotReservationId = null;
        if (signal.getPlanId() != null) {
            var plan = strategyPlanRepo.findById(signal.getPlanId()).orElse(null);
            if (plan != null) {
                var planSlotResult = slotReservationService.reserve("plan:" + plan.getId(), plan.getMaxConcurrentTrades(), executionId, credential.getMode() == BrokerMode.LIVE);
                if (!planSlotResult.reserved()) {
                    // Releases the exact account-level reservation by id, not a bare key-based
                    // decrement.
                    slotReservationService.release(accountSlotReservationId);
                    credentialService.audit(profile.getUserId(), profile.getCredentialId(), credential.getBroker(),
                        "SIGNAL_SKIPPED_RISK", "Signal on " + signal.getSymbol() + " (conf " + signal.getConfidence()
                            + ") skipped: max concurrent trades slot unavailable for plan \"" + plan.getName() + "\" (plan-level, atomic "
                            + "reservation) -- the account-level slot reserved above was rolled back since this plan-level check failed.");
                    executionContextService.recordTerminal(executionId, "REJECTED_SLOT_CAP_PLAN", "Max concurrent trades slot unavailable for plan \"" + plan.getName() + "\".");
                    return;
                }
                planSlotReserved = true;
                planSlotReservationId = planSlotResult.reservationId();
            }
        }
        executionContextService.recordSlotsReserved(executionId, accountSlotReservationId, planSlotReservationId);

        // Same reasoning as the slot reservation immediately above — RiskEngineService.check()
        // already did a cheap read-based exposure pre-filter, but that read alone can't prevent
        // two concurrent orders from both passing a cap that their combined value would actually
        // exceed. This is the atomic gate, released alongside the slot on every path that
        // releases the slot.
        //
        // Passing the profile's own correlationGroups/correlationGroupCaps atomically reserves
        // any matching correlation group alongside total/symbol exposure, with full rollback
        // across all three if any step fails. See ExposureReservationService's own reserve()
        // javadoc for the mechanism.
        var exposureReserved = exposureReservationService.reserve(profile.getCredentialId(), signal.getSymbol(),
            orderQuoteValue, profile.getMaxTotalExposureQuote(), profile.getMaxSymbolExposureQuote(),
            profile.getCorrelationGroups(), profile.getCorrelationGroupCaps(), executionId, credential.getMode() == BrokerMode.LIVE);
        if (!exposureReserved.allowed()) {
            releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
            credentialService.audit(profile.getUserId(), profile.getCredentialId(), credential.getBroker(),
                "SIGNAL_SKIPPED_RISK", "Signal on " + signal.getSymbol() + ": " + exposureReserved.reason() + " (atomic reservation).");
            executionContextService.recordTerminal(executionId, "REJECTED_EXPOSURE_CAP", exposureReserved.reason());
            return;
        }
        executionContextService.recordExposureReserved(executionId, exposureReserved.reservationId());

        // Idempotent per-signal client order id. A retried evaluation of the same
        // TradeCallRecord (e.g. a duplicate delivery) is rejected by Binance as a repeat rather
        // than opening a second position for the same signal.
        //
        // evaluateForProfile already gates on signal.getDirection() == LONG before this method
        // is ever reached, so this specific check is currently unreachable in practice. Kept
        // anyway as defense-in-depth immediately before the actual order is constructed: relying
        // on exactly one gate for "never send a hidden SELL/short as a BUY" is bad practice
        // regardless of whether it's the only gate today.
        if (!"LONG".equalsIgnoreCase(signal.getDirection())) {
            log.error("Refusing to place a BUY order for a non-LONG signal ({}) on {} — this should be unreachable; evaluateForProfile's earlier gate should have already stopped this.",
                signal.getDirection(), signal.getSymbol());
            releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
            exposureReservationService.release(exposureReserved.reservationId());
            executionContextService.recordTerminal(executionId, "REJECTED_NON_LONG_DIRECTION", "Signal direction was not LONG at final submission -- should be unreachable.");
            return;
        }

        // Generated once here and threaded through to both the OMS record below and the real
        // OrderRequest, so there is exactly one client order id, not two independently-generated
        // strings that happen to usually agree, and it's guaranteed to fit Binance's own
        // 36-character limit regardless of the signal id's own length. credentialId is part of
        // the hash basis (not signal.getId() alone) so that a user running the same signal
        // against two auto-trade credentials gets two distinct, still-deterministic client
        // order ids instead of colliding on Order.clientOrderId's unique index.
        String clientOrderId = com.tradevision.service.OrderService.generateClientOrderId("tv-s", signal.getId() + ":" + profile.getCredentialId());
        // Submits as a plain MARKET order rather than with explicit price protection (e.g. a
        // LIMIT IOC at ask×(1+maxSlippage)) -- that would be a genuinely separate, cross-cutting
        // change (a new OrderRequest price/timeInForce field, LIMIT-order construction and
        // tick-size rounding in BinanceBrokerAdapter, matching PAPER-mode IOC simulation in
        // PaperBrokerAdapter) touching order placement for every caller of OrderRequest. The
        // balance cap (BALANCE_SAFETY_BUFFER) and the order-book depth/impact check
        // (hasSufficientOrderBookDepth) just above instead close the two concrete failure modes
        // that matter here: an order that exceeds the account's own balance, and a MARKET order
        // submitted into a book too thin to absorb it.
        OrderRequest orderReq = new OrderRequest(signal.getSymbol().toUpperCase(), "BUY", "MARKET", quantity, clientOrderId);

        // The entry-order path, wired through the formal order lifecycle as an additive,
        // parallel record (see OrderService for the scope boundary -- this does not replace
        // ExecutedOrder as the authoritative record everywhere; only this specific path creates
        // and drives an Order through OrderService). positionId is left null here — no Position
        // exists yet at this point in the flow.
        //
        // The entire OMS sequence (create through markSubmitting) is wrapped here, not just
        // recordBrokerResult — a bug anywhere in this additive bookkeeping, including setup,
        // must never crash or block the real order placement below, which is what actually
        // moves money and must remain fully authoritative regardless of what happens to omsOrder.
        // All risk gates above have passed at this point — APPROVED, then ORDER_PENDING right as
        // submission actually begins below.
        advanceSignalStatus(signal, com.tradevision.model.SignalStatus.APPROVED);

        com.tradevision.model.Order omsOrder = null;
        try {
            // requestedPrice is repurposed as the slippage reference price (the signal's
            // expected entry), not a literal limit price. This is what makes slippage
            // measurement possible at all for a MARKET order, which has no real limit price
            // otherwise.
            omsOrder = orderService.create(profile.getUserId(), profile.getCredentialId(),
                null, signal.getId(), signal.getSymbol().toUpperCase(), "BUY", "MARKET", quantity,
                BigDecimal.valueOf(serverSignal.entry()), clientOrderId);
            omsOrder.setPlanId(signal.getPlanId());
            // riskProfileVersion is per-profile (unlike strategyVersion, a global constant set
            // inside create() itself), so it's stamped here by the caller who actually has the
            // real profile in hand.
            omsOrder.setRiskProfileVersion(String.valueOf(profile.getVersion()));
            // The entry side of orderRole -- distinguishes this record's purpose from the OCO
            // exit records placeExitOcoOrEmergencyFlatten stamps.
            omsOrder.setOrderRole("ENTRY");
            orderService.markRiskAccepted(omsOrder);
            orderService.markSubmitting(omsOrder);
            orderService.recordVolatility(omsOrder, serverSignal.atrPercent());
        } catch (Exception e) {
            log.error("OMS setup (create/markRiskAccepted/markSubmitting) failed (non-fatal, additive record only): {}", e.getMessage());
            omsOrder = null; // don't attempt recordBrokerResult below against a possibly-half-initialized record
            // Scoped specifically to LIVE -- for TESTNET/PAPER, no real money is at risk, so the
            // existing, deliberate "never let bookkeeping block the real trade" design below
            // remains unchanged for those modes. For LIVE specifically, this application's own
            // ability to answer "what exchange order corresponds to this position" is itself a
            // real-money invariant: a database failure right before a LIVE order could otherwise
            // result in a real exchange order with no local record of how it got there -- no
            // clientOrderId mapping, no fill-ledger linkage, nothing recovery could discover
            // independently. Halting here instead, releasing exactly what this execution has
            // reserved so far (releaseSlots + exposure); the execution-level claim
            // (claimExecutionAtomicWithPlan) hasn't been acquired yet at this point in the flow
            // and therefore needs no release of its own here.
            if (credential.getMode() == BrokerMode.LIVE) {
                log.error("LIVE credential {} -- aborting this execution entirely rather than placing a real exchange order with no "
                    + "durable local record of it. Signal on {} will not be acted on this cycle; a fresh signal on a later scan will "
                    + "retry from scratch.", profile.getCredentialId(), signal.getSymbol());
                incidentService.raiseCritical(profile.getUserId(), profile.getCredentialId(), null, null, signal.getSymbol(),
                    "OMS_SETUP_FAILED_LIVE_HALT",
                    "OMS record creation failed for a LIVE signal on " + signal.getSymbol() + " (" + e.getMessage() + "). This "
                        + "execution was halted before any exchange call was made, specifically because this application would "
                        + "otherwise have no durable local record mapping a real exchange order to its own OMS/position tracking. "
                        + "No exchange order was placed for this specific signal. Investigate the underlying database/OMS failure.");
                releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
                exposureReservationService.release(exposureReserved.reservationId());
                executionContextService.recordTerminal(executionId, "REJECTED_OMS_SETUP_FAILED_LIVE_HALT", "OMS record creation failed for a LIVE signal: " + e.getMessage());
                return;
            }
        }

        advanceSignalStatus(signal, com.tradevision.model.SignalStatus.ORDER_PENDING);
        // The evaluation lease (evaluationOwner/evaluationLeaseUntil) is claimed once at the
        // very start of evaluateSignal, but this worker must re-verify it still owns that lease
        // before actually reaching the exchange -- if this worker simply became slow (not dead)
        // and the lease genuinely expired while still mid-evaluation, AutoTradeRecoveryService
        // could reset this same signal back to PENDING and let a second worker claim and
        // evaluate it too, while this original worker is still running and could still reach
        // adapter.placeOrder() below unaware its own lease is long gone.
        //
        // Once signalStatus reaches ORDER_PENDING above, recovery can never touch this signal
        // again (see TradeCallRepository's lease-based recovery query), which closes the race for
        // the remainder of this signal's lifetime. What remains open is the narrower window
        // before that point (while a slow evaluation is still at an earlier signalStatus): a
        // plain read of the lease would not stop a second worker from claiming and reaching its
        // own exchange call in the gap between that read and adapter.placeOrder() firing a few
        // lines below. The fix is an atomic findAndModify that extends this worker's own lease as
        // part of the same operation that verifies it, rather than merely reading it -- if a
        // second worker (via recovery) has already reclaimed ownership by this exact moment,
        // evaluationOwner no longer matches WORKER_ID and this findAndModify fails to match, but
        // when it succeeds the extension itself also pushes this worker's own lease further out,
        // so recovery's own next sweep (bounded by AutoTradeRecoveryService's own 2-minute
        // schedule) cannot beat this worker to the exchange in the remaining, much narrower,
        // window.
        if (signal.getId() != null) {
            var extendedLease = mongoTemplate.findAndModify(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(signal.getId())
                        .and("evaluationOwner").is(WORKER_ID)
                        .and("evaluationLeaseUntil").gt(java.time.LocalDateTime.now())),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("evaluationLeaseUntil", java.time.LocalDateTime.now().plus(EVALUATION_LEASE_DURATION)),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), TradeCallRecord.class);
            if (extendedLease == null) {
                log.warn("Evaluation lease for signal {} was lost (expired, or reclaimed by another worker) before reaching the exchange "
                    + "-- another worker may already be evaluating this same signal, or already has. Aborting without contacting the "
                    + "exchange rather than risk two workers both placing a real order for the same signal.", signal.getId());
                releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
                exposureReservationService.release(exposureReserved.reservationId());
                executionContextService.recordTerminal(executionId, "REJECTED_LEASE_LOST", "Evaluation lease was lost (expired or reclaimed by another worker) before reaching the exchange.");
                abortEntrySubmission(omsOrder, "Evaluation lease was lost before reaching the exchange.");
                return;
            }
        }
        // The execution-authorization barrier, placed as close to the real exchange call as this
        // method's own structure allows -- an atomic, fresh-from-the-database re-check of
        // autoTradeEnabled/tradingHalted/autoTradeHalted/liveAutoTradeAuthorized, not a trust of
        // the in-memory `profile` object loaded at the very start of this long evaluation
        // sequence (NO-TRADE check, pricing, sizing, risk checks, slot/exposure reservation all
        // happen between that load and here). A caller that loses this race releases its
        // reservations exactly like a confirmed placement failure and never reaches Binance at
        // all -- a kill switch, a LIVE-authorization revocation, or a circuit-breaker halt that
        // landed during this sequence is a real, hard execution barrier, not a flag this method
        // could race past.
        //
        // The claim's own identity is captured here, not just its true/false outcome -- used for
        // a second, final re-verification immediately before the real network call below, using
        // this specific claim's identity rather than re-running the same broad check a second
        // time (which would pass even for a different, later claim). This narrows rather than
        // eliminates the theoretical gap between the claim and the network call, since a
        // fencing token still cannot recall a network request already sent once it's in flight.
        var claim = riskProfileService.claimExecutionAuthorization(profile.getCredentialId(), credential.getMode() == BrokerMode.LIVE);
        if (claim == null) {
            log.warn("Execution authorization claim failed for credential {} immediately before order submission -- "
                + "auto-trade/halt/LIVE-authorization state changed during evaluation. Aborting without contacting the exchange.",
                profile.getCredentialId());
            releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
            exposureReservationService.release(exposureReserved.reservationId());
            // Deliberately does not call advanceSignalStatus(signal, RISK_REJECTED) here:
            // RISK_REJECTED's own ordinal is before ORDER_PENDING's in this enum's declaration
            // order, and advanceSignalStatus never regresses, so that call would silently
            // no-op. It would also be semantically wrong regardless: this signal passed every
            // earlier risk gate and only failed at this freshest, last-moment re-check, not an
            // earlier rejection. ORDER_PENDING (already set) honestly reflects "an order was
            // attempted but never reached the exchange" -- no other state in this enum fits this
            // exact moment better.
            executionContextService.recordTerminal(executionId, "REJECTED_CLAIM", "Execution authorization claim failed immediately before order submission.");
            abortEntrySubmission(omsOrder, "Execution authorization claim failed immediately before order submission.");
            return;
        }
        executionContextService.recordClaimed(executionId, claim.claimId());
        // Stamped as soon as the claim exists -- omsOrder was already created/submitted earlier
        // in this method (before a claim could even be requested), so this is a durable,
        // additive follow-up write, same non-fatal pattern as recordVolatility above. Skipped
        // entirely if OMS setup itself already failed (omsOrder == null) -- nothing to stamp.
        if (omsOrder != null) {
            try {
                orderService.recordExecutionClaim(omsOrder, claim.claimId());
            } catch (Exception e) {
                log.warn("Could not record execution claim {} on OMS order {} (non-fatal, additive record only): {}",
                    claim.claimId(), omsOrder.getId(), e.getMessage());
            }
        }
        // The second, final re-verification -- this specific claim's own identity, immediately
        // before the network call. A no-op in the overwhelmingly common case (the claim above
        // and this check are adjacent, with zero intervening logic), but a real, cheap guard
        // against a JVM-level pause landing in the gap, during which a concurrent halt/resume
        // issued a newer claim this caller was never told about.
        //
        // This single call performs the same condition validation a separate isClaimStillValid()
        // check would, but atomically, as the query of the same MongoDB operation that registers
        // this execution as in flight. That removes the window between "confirm this claim is
        // still valid" and "register in flight" where a concurrent kill switch could land and go
        // unnoticed -- two separate calls would be two separate opportunities for something to
        // change in between, no matter how small the code between them looks.
        //
        // The second of the two deliberate calls to strategyPlanService.authorizeExecution.
        //
        // This check and markExecutionStarted() just below are two separate, sequential MongoDB
        // operations on two different collections (StrategyPlan and RiskProfile) -- there is no
        // multi-document transaction tying them together, so this narrows the race window from
        // "the whole async evaluation" down to "the few milliseconds between this read and that
        // atomic claim," without eliminating it. A user disabling a plan in that exact narrow
        // window could theoretically still have an already-in-flight signal reach the exchange.
        // Fully closing this would need either a genuine multi-document MongoDB transaction
        // spanning both collections, or restructuring plan state to live on the same document
        // markExecutionStarted's own atomic findAndModify already claims against.
        var finalPlanAuth = strategyPlanService.authorizeExecution(signal.getPlanId(), profile.getUserId(), profile.getCredentialId(),
            signal.getSymbol(), adapter, credential, profile.getEnabledSymbols());
        if (!finalPlanAuth.authorized()) {
            log.warn("Signal on {} for credential {} lost strategy plan authorization immediately before the exchange call: {} -- "
                + "aborting without contacting the exchange.", signal.getSymbol(), profile.getCredentialId(), finalPlanAuth.reason());
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_BLOCKED_PLAN_AUTHORIZATION", "Signal on " + signal.getSymbol() + " blocked immediately before execution: "
                    + finalPlanAuth.reason());
            releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
            exposureReservationService.release(exposureReserved.reservationId());
            executionContextService.recordTerminal(executionId, "REJECTED_PLAN_AUTHORIZATION_LOST", "Strategy plan authorization was lost immediately before execution: " + finalPlanAuth.reason());
            abortEntrySubmission(omsOrder, "Strategy plan authorization was lost immediately before execution: " + finalPlanAuth.reason());
            return;
        }
        // authorizeExecution just above is still a plain read (kept for session/direction/
        // universe, which aren't the kind of concurrent-write race this exists to close).
        //
        // The plan claim and the account-level execution claim below are one call attempting a
        // genuine MongoDB transaction spanning both (with an honest, logged fallback to
        // sequential behavior on a deployment that doesn't support transactions -- see
        // RiskProfileService.claimExecutionAtomicWithPlan's own javadoc for the scope of what
        // that fallback does and doesn't close), rather than two separate, sequential calls.
        if (!riskProfileService.claimExecutionAtomicWithPlan(profile.getCredentialId(), claim.claimId(),
                credential.getMode() == BrokerMode.LIVE, signal.getPlanId(), signal.getPlanVersion(), profile.getUserId())) {
            log.warn("Could not atomically claim both the strategy plan's own execution slot and the account-level execution claim for "
                + "signal on {} (credential {}) -- either the plan was disabled/edited since this signal was generated, or the account-"
                + "level claim was superseded by a concurrent halt/resume. Aborting without contacting the exchange.",
                signal.getSymbol(), profile.getCredentialId());
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "SIGNAL_BLOCKED_ATOMIC_CLAIM_FAILED", "Signal on " + signal.getSymbol() + " blocked: could not atomically claim both "
                    + "the plan-level and account-level execution slots.");
            releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
            exposureReservationService.release(exposureReserved.reservationId());
            executionContextService.recordTerminal(executionId, "REJECTED_ATOMIC_CLAIM_FAILED", "Could not atomically claim both the plan-level and account-level execution slots.");
            abortEntrySubmission(omsOrder, "Could not atomically claim both the plan-level and account-level execution slots.");
            return;
        }
        // adapter.placeOrder() below is already documented (see the next comment block) as able
        // to throw with the exchange call's own outcome genuinely unknown, and this count must
        // still be released in that exact case or it would overcount every future halt() report
        // on this credential forever after a single such exception -- try/finally is deliberate
        // here, not just tidiness.
        OrderResult result;
        try {
            // The 30s cross-instance credential lock acquired in evaluateSignal (see
            // tryAcquireWithDiagnosis) is never renewed elsewhere in this method, yet a slow
            // Binance response -- can outlast that 30s lease on its own. Once the lease lapses
            // mid-call, PositionMonitorService.reconcileCredential can acquire the same
            // credential's lock and start reconciling while this evaluation is still genuinely
            // in flight against the exchange -- exactly the race this lock exists to prevent,
            // reopened by the lease simply running out under a slow call rather than by any logic
            // bug. Renewed here, so this check runs before anything exchange-facing happens.
            //
            // This runs first, before markExchangeCallStarted: at this exact point,
            // adapter.placeOrder() has not been called yet -- nothing exchange-facing or
            // irreversible has happened, so this is exactly as safe an abort point as
            // finalPlanAuth/claimExecutionAtomicWithPlan's own checks immediately above in this
            // same method, both of which abort outright on a lost claim rather than proceeding
            // anyway. (The identical-looking renew() call in
            // PositionSafetyService.placeExitOcoOrEmergencyFlatten instead proceeds on a failed
            // renewal, because an exchange call may already be in flight or about to protect an
            // already-open position there -- a genuinely different situation from this one.)
            // Running this check before markExchangeCallStarted also means an aborted attempt
            // never leaves a false "exchange call started" record behind on an order that was
            // never actually submitted.
            if (!distributedLockService.renew(profile.getCredentialId(), instanceId, java.time.Duration.ofSeconds(30))) {
                log.error("Could not renew the credential lock lease immediately before placing the entry order for {} "
                    + "(credential {}) -- another instance may now own this credential's lock. Aborting BEFORE contacting "
                    + "the exchange, rather than risk a concurrent reconciliation pass racing this still-in-flight evaluation.",
                    signal.getSymbol(), profile.getCredentialId());
                credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                    "SIGNAL_BLOCKED_LOCK_RENEWAL_FAILED", "Signal on " + signal.getSymbol() + " blocked immediately before "
                        + "the exchange call: could not renew the credential lock lease -- another instance may now own it.");
                releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
                exposureReservationService.release(exposureReserved.reservationId());
                executionContextService.recordTerminal(executionId, "REJECTED_LOCK_RENEWAL_FAILED",
                    "Could not renew the credential lock lease immediately before the exchange call.");
                abortEntrySubmission(omsOrder, "Could not renew the credential lock lease immediately before the exchange call.");
                return;
            }
            // Stamped here, immediately before the real network call, never earlier -- this is
            // the line that draws the boundary recoverStuckSubmittingOrders uses to distinguish
            // "never reached the exchange" from "may have reached it." omsOrder may be null here
            // if OMS setup itself failed earlier -- this stamp is simply skipped in that case,
            // same as every other OMS bookkeeping call in this method that guards against a null
            // omsOrder.
            if (omsOrder != null) {
                try {
                    orderService.markExchangeCallStarted(omsOrder);
                } catch (Exception e) {
                    log.warn("Could not stamp exchangeCallStartedAt for order {} (non-fatal, additive record only): {}", omsOrder.getId(), e.getMessage());
                }
            }
            result = adapter.placeOrder(apiKey, apiSecret, credential.getMode(), orderReq);
        } finally {
            riskProfileService.markExecutionFinished(profile.getCredentialId());
            // The plan-level counterpart to the account-level release just above --
            // unconditionally releases whatever this signal's own atomic claimPlanExecution
            // registered, same discipline, same finally.
            strategyPlanService.releasePlanExecution(signal.getPlanId());
        }
        if (omsOrder != null) {
            try {
                orderService.recordBrokerResult(omsOrder, result);
            } catch (Exception e) {
                log.error("OMS recordBrokerResult failed for order {} (non-fatal, additive record only): {}", omsOrder.getId(), e.getMessage());
            }
        }

        // evaluateForProfileLocked's own caller wraps this whole method in try { ... } finally {
        // lock.unlock(); }, with no catch block at all. adapter.placeOrder() has already
        // returned by this point -- meaning the exchange call itself may have genuinely
        // succeeded -- but everything from here through persisting the resulting order/position
        // record is still a real, unprotected window: an unexpected runtime exception anywhere
        // in it (a null field, a database hiccup) would propagate all the way up uncaught, and
        // this application would have no record at all that an order was ever placed, leaving a
        // real exchange fill with nothing tracking it locally. Wrapped specifically from here
        // through the position/order persistence below -- the highest-value, most bounded window
        // to close without rewriting this method's remaining position-creation/protection logic.
        try {
        if (!result.success()) {
            // "UNKNOWN" means the placement call failed and we couldn't verify the real state on
            // the exchange — the order might actually exist. Releasing the slot/exposure here
            // exactly like a confirmed failure would let a second trade start while this one is
            // still genuinely unresolved — held instead, released only once a human resolves it
            // (a manual reconcile / credential review, outside this automated flow).
            if (!"UNKNOWN".equals(result.status())) {
                releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId);
                exposureReservationService.release(exposureReserved.reservationId());
            }
        }

        // omsOrder can genuinely be null here (the OMS setup a few lines up has its own
        // non-fatal try/catch), so a fallback Order is attempted right here. A second failure
        // does not abort this method -- a bug in order-record-keeping must never block or
        // corrupt the real, money-moving order flow, which by this point has already happened
        // (adapter.placeOrder() already ran, above). Returning early here would abandon position
        // creation for a trade that already executed on the exchange -- strictly worse than
        // proceeding with omsOrder left null, the same as this method's design for every other
        // omsOrder-dependent step throughout it. Escalated as a critical incident either way,
        // since a real order now has no local metadata record regardless of how the
        // position/fill tracking below proceeds.
        if (omsOrder == null) {
            try {
                omsOrder = orderService.create(profile.getUserId(), credential.getId(), null, signal.getId(),
                    orderReq.symbol(), "BUY", "MARKET", quantity, BigDecimal.valueOf(serverSignal.entry()), clientOrderId);
                omsOrder.setOrderRole("ENTRY");
                omsOrder.setPlanId(signal.getPlanId());
            } catch (Exception e) {
                log.error("Fallback Order creation ALSO failed after the earlier OMS setup already failed for {} (clientOrderId={}) -- "
                    + "the exchange call's own result (success={}) now has NO local record able to represent it at all: {}",
                    orderReq.symbol(), clientOrderId, result.success(), e.getMessage(), e);
                incidentService.raiseCritical(profile.getUserId(), credential.getId(), null, null, orderReq.symbol(), "ORDER_STATE_UNKNOWN",
                    "Both the initial OMS setup AND a fallback Order creation failed for " + orderReq.symbol() + " (clientOrderId="
                        + clientOrderId + "), broker success=" + result.success() + " -- the exchange-side order state must be manually "
                        + "verified by clientOrderId, since no local record of this order exists at all. Position/fill tracking below "
                        + "proceeds regardless, since the exchange-side trade already happened.");
                omsOrder = null;
            }
        }
        // Written through OrderService (which owns the OMS order repository) rather than a
        // direct save here. Guarded, same reasoning as the fallback block above -- omsOrder can
        // still be null if both create() attempts failed.
        if (omsOrder != null) {
            try {
                omsOrder = orderService.recordEntryMetadata(omsOrder, credential.getBroker(), credential.getMode(), "SIGNAL",
                    result.rawResponse(), BigDecimal.valueOf(serverSignal.stopLoss()), BigDecimal.valueOf(serverSignal.target1()));
            } catch (Exception e) {
                log.error("OMS recordEntryMetadata failed (non-fatal, additive record only): {}", e.getMessage());
            }
        }

        // If this omsOrder was already fully set up and had recordBrokerResult() called on it a
        // few lines up (the normal case), that call already correctly transitioned its own
        // status/brokerOrderId/filledQuantity through the OMS state machine -- calling it again
        // here would be redundant and risks a double-transition error against that same state
        // machine's own legality checks. Only the fallback-creation branch above (where
        // recordBrokerResult was never called at all, since omsOrder didn't exist yet when that
        // call happened) needs it run now. Also guarded for the still-null case.
        if (omsOrder != null && omsOrder.getStatus() == OrderStatus.CREATED) {
            try {
                omsOrder = orderService.recordBrokerResult(omsOrder, result);
            } catch (Exception e) {
                log.error("OMS recordBrokerResult failed for the fallback-created order (non-fatal, additive record only): {}", e.getMessage());
            }
        }
        } catch (Exception e) {
            // Falls through to let Position creation still run below, rather than returning:
            // any exception escaping the OMS bookkeeping above must never abandon this method
            // entirely before Position creation further down. A real exchange fill with an
            // exception here but a `return` would leave no local Position, no automated TP/SL,
            // and no monitor/safety service watching it at all -- capital deployed with nothing
            // managing it. An exchange order call that already may have succeeded must never be
            // abandoned by a local bookkeeping bug, the same principle the "fallback Order
            // creation also failed" case above follows: falling through is strictly safer than
            // returning, since the trade already executed on the exchange regardless of what
            // this try block's own state ended up as.
            log.error("Unexpected exception persisting order state for {} after adapter.placeOrder() returned (clientOrderId={}, broker success={}) "
                + "-- the exchange call itself may have succeeded with NO local record now able to represent it: {}",
                orderReq.symbol(), clientOrderId, result.success(), e.getMessage(), e);
            if (omsOrder != null) {
                try {
                    orderService.markUnknown(omsOrder, "Unexpected exception persisting order state after broker placement: " + e.getMessage());
                } catch (Exception e2) {
                    log.warn("Could not mark OMS order {} UNKNOWN after persistence failure (non-fatal, additive record only): {}", omsOrder.getId(), e2.getMessage());
                }
            }
            incidentService.raiseCritical(profile.getUserId(), credential.getId(), null, omsOrder != null ? omsOrder.getId() : null,
                orderReq.symbol(), "ORDER_STATE_UNKNOWN",
                "An exchange order call for " + orderReq.symbol() + " (clientOrderId=" + clientOrderId + ") returned with broker success=" + result.success()
                    + ", but persisting the local order record then threw an unexpected exception (" + e.getMessage() + "). The exchange-side order state "
                    + "must be manually verified by clientOrderId before any further automated action against this credential. Position creation and "
                    + "protection placement below will still be attempted regardless, since the exchange trade itself already happened.");
        }

        credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
            result.success() ? "SIGNAL_ORDER_PLACED" : "SIGNAL_ORDER_FAILED",
            "Signal on " + signal.getSymbol() + " (conf " + signal.getConfidence() + ") -> "
                + (result.success() ? "BUY " + quantity + " -> " + result.status() : result.errorMessage()));

        // Circuit breaker on repeated failures — never retry blindly forever. Atomic $inc here
        // (same pattern used for dailyRealizedLossQuote elsewhere), evaluating the
        // circuit-breaker threshold against the actual post-increment value MongoDB returns, not
        // a value that might already be stale by the time this thread reads it back.
        if (result.success()) {
            if (profile.getConsecutiveOrderFailures() != 0) {
                var reset = mongoTemplate.findAndModify(
                    new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                    new org.springframework.data.mongodb.core.query.Update().set("consecutiveOrderFailures", 0),
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), RiskProfile.class);
                if (reset != null) profile.setConsecutiveOrderFailures(reset.getConsecutiveOrderFailures());
            }
        } else {
            var updated = mongoTemplate.findAndModify(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                new org.springframework.data.mongodb.core.query.Update().inc("consecutiveOrderFailures", 1),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), RiskProfile.class);
            if (updated != null) {
                profile.setConsecutiveOrderFailures(updated.getConsecutiveOrderFailures());
                if (updated.getConsecutiveOrderFailures() >= profile.getCircuitBreakerThreshold()) {
                    profile.setTradingHalted(true);
                    profile.setHaltReason("Circuit breaker: " + updated.getConsecutiveOrderFailures()
                        + " consecutive order failures — halted for review.");
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                        new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", profile.getHaltReason()),
                        RiskProfile.class);
                    credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                        "CIRCUIT_BREAKER_TRIPPED", profile.getHaltReason());
                    incidentService.raiseCritical(profile.getUserId(), credential.getId(), null, null,
                        signal.getSymbol(), "RISK_LIMIT_BREACH", profile.getHaltReason());
                }
            }
        }

        // The order record is already durably saved (above) with its UNKNOWN status preserved,
        // so this halt happens with a real audit trail behind it — unlike the slot/exposure
        // reservations, which are deliberately left held rather than released a few lines up.
        if ("UNKNOWN".equals(result.status())) {
            String reason = "Order placement for " + signal.getSymbol() + " failed and its real state on the "
                + "exchange could not be verified (clientOrderId " + clientOrderId + ") — halted until this is manually resolved.";
            atomicHaltProfile(profile, reason);
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "ORDER_STATE_UNKNOWN_HALT", profile.getHaltReason());
            incidentService.raiseCritical(profile.getUserId(), credential.getId(), null, clientOrderId,
                signal.getSymbol(), "ORDER_STATE_UNKNOWN", profile.getHaltReason());
        }

        if (!result.success()) return;

        // result.success() being true does not guarantee executedQty is real. Fail safe instead:
        // if we can't confirm real filled quantity, don't guess — flag it and stop, rather than
        // falling back to the full requested quantity and opening a position sized on a number
        // nobody confirmed.
        if (result.executedQty() == null || result.executedQty().signum() <= 0) {
            // Releasing the slot/exposure and returning unconditionally on any zero-fill success
            // response would wrongly assume "success + zero fill = nothing remains on exchange"
            // -- an assumption that doesn't hold for a genuinely non-terminal order
            // (NEW/PARTIALLY_FILLED), which could still fill later and leave a real position on
            // the exchange with no slot/exposure reservation protecting it. Verify the real
            // order state before releasing anything: only a genuinely terminal, no-fill state
            // (CANCELED, EXPIRED, REJECTED) is safe to release for; NEW/PARTIALLY_FILLED (or a
            // verification query that itself fails) keeps the reservation held and raises an
            // incident instead of guessing.
            var verifiedStatus = safeGetOrderStatusByClientOrderId(adapter, apiKey, apiSecret, credential.getMode(), signal.getSymbol(), clientOrderId);
            java.util.Set<String> terminalNoFillStatuses = java.util.Set.of("CANCELED", "EXPIRED", "REJECTED");
            if (verifiedStatus != null && terminalNoFillStatuses.contains(verifiedStatus.status())) {
                releaseSlots(profile, signal, planSlotReserved, accountSlotReservationId, planSlotReservationId); // no position was created — free the slot
                exposureReservationService.release(exposureReserved.reservationId());
                credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                    "ENTRY_QUANTITY_UNCONFIRMED",
                    "Order " + result.brokerOrderId() + " on " + signal.getSymbol() + " reported success with status "
                        + result.status() + " but no confirmed executed quantity — verified against the exchange directly, "
                        + "confirmed genuinely terminal with no fill (" + verifiedStatus.status() + ") — safe to release.");
                executionContextService.recordTerminal(executionId, "REJECTED_ZERO_FILL_CONFIRMED", "Order reported success but confirmed terminal with no fill (" + verifiedStatus.status() + ").");
            } else {
                // Either genuinely non-terminal (NEW/PARTIALLY_FILLED — could still fill), or
                // the verification query itself failed (genuinely unknown) — neither is safe to
                // release against. Keep the reservation held; PositionMonitorService's own
                // reconciliation is the real safety net for whatever this order eventually does.
                credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                    "ENTRY_QUANTITY_UNCONFIRMED_STILL_TRACKED",
                    "Order " + result.brokerOrderId() + " on " + signal.getSymbol() + " reported success with status "
                        + result.status() + " but no confirmed executed quantity, and the real exchange state is "
                        + (verifiedStatus != null ? "non-terminal (" + verifiedStatus.status() + ")" : "could not be verified")
                        + " — NOT releasing the slot/exposure reservation, since this order could still fill. "
                        + "Left for reconciliation to resolve.");
                incidentService.raiseCritical(profile.getUserId(), credential.getId(), null, clientOrderId,
                    signal.getSymbol(), "ENTRY_ORDER_UNCONFIRMED_NON_TERMINAL",
                    "Order " + result.brokerOrderId() + " on " + signal.getSymbol() + " (clientOrderId " + clientOrderId
                        + ") reported success with no confirmed fill, and its real exchange state is non-terminal or "
                        + "unverifiable — manual review required.");
                executionContextService.recordTerminal(executionId, "AMBIGUOUS_ZERO_FILL_STILL_TRACKED",
                    "Order reported success with no confirmed fill; real exchange state is non-terminal or unverifiable -- reservation held, left for reconciliation.");
            }
            return;
        }
        BigDecimal filledQty = result.executedQty();

        // result.success() and a confirmed executedQty don't guarantee fillPrice is populated.
        // The client's claimed entry price is never used as the position's cost basis — that is
        // exactly the kind of client-controlled number this whole file exists to stop trusting.
        // If fillPrice is missing, query the real fills for the weighted average instead; if
        // even that fails, don't fabricate a number — mark the position for reconciliation and
        // say so honestly.
        //
        // result.fills() is empty for a recovered order (see OrderResult's convenience
        // constructor) — exactly the scenario most likely to need this same fills lookup for
        // price verification. resolvedFills is shared by both price verification below and the
        // base-asset commission/quantity handling further down, so the two blocks work from the
        // same, consistent data rather than silently diverging.
        List<com.tradevision.service.broker.dto.Fill> resolvedFills = result.fills();
        BigDecimal avgEntryPrice = result.fillPrice();
        boolean entryPriceVerified = avgEntryPrice != null && avgEntryPrice.signum() > 0;
        if (!entryPriceVerified || resolvedFills == null || resolvedFills.isEmpty()) {
            try {
                var fetchedFills = adapter.getFillsForOrder(apiKey, apiSecret, credential.getMode(), orderReq.symbol(), result.brokerOrderId());
                if (!fetchedFills.isEmpty()) {
                    resolvedFills = fetchedFills;
                    if (!entryPriceVerified) {
                        BigDecimal totalQty = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
                        for (var f : fetchedFills) { totalQty = totalQty.add(f.qty()); totalCost = totalCost.add(f.qty().multiply(f.price())); }
                        if (totalQty.signum() > 0) {
                            avgEntryPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
                            entryPriceVerified = true;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not fetch fills to verify entry price for order {}: {}", result.brokerOrderId(), e.getMessage());
            }
        }

        Position position = new Position();
        // The position's id is assigned explicitly here, before positionRepo.save() is ever
        // called (which happens later, in one of several possible branches below, depending on
        // entry-price verification and protection outcome) — so fillLedgerService.recordFills()
        // below can reference the real, final position id immediately, rather than a null
        // placeholder. Spring Data MongoDB respects a pre-set String id rather than generating
        // its own, so this is safe regardless of which branch eventually calls save() first.
        position.setId(java.util.UUID.randomUUID().toString());
        position.setUserId(profile.getUserId());
        position.setCredentialId(credential.getId());
        position.setBroker(credential.getBroker());
        position.setMode(credential.getMode());
        position.setSymbol(orderReq.symbol());
        position.setEntryOrderId(result.brokerOrderId());
        position.setEntryClientOrderId(clientOrderId);
        position.setSignalId(signal.getId());
        position.setPlanId(signal.getPlanId());
        position.setTriggerSource("SIGNAL");
        position.setOpenedAt(LocalDateTime.now());
        // This position's own exact reservation id, so closing it later releases exactly this
        // reservation rather than a recomputed amount. See ExposureReservationRecord's own class
        // javadoc.
        position.setExposureReservationId(exposureReserved.reservationId());
        // Same reasoning as the exposure reservation id just above, for both slot-reservation
        // tiers. See PositionSlotReservationRecord's own class javadoc.
        position.setSlotReservationId(accountSlotReservationId);
        position.setPlanSlotReservationId(planSlotReservationId);

        var symbolRules = adapter.getSymbolRules(orderReq.symbol(), credential.getMode());
        position.setEntryFeeQuote(positionSafetyService.sumCommissionInQuoteAsset(resolvedFills, symbolRules.quoteAsset()));

        // A real fill is now fully confirmed (filledQty, and resolvedFills/symbolRules just
        // established above for the exact same purpose) — the natural point to persist it.
        // Additive and non-fatal, same as the OMS wiring above: a bug here must never block or
        // corrupt the real position creation that follows.
        if (omsOrder != null) {
            // orderId here is intentionally omsOrder.getId(), not the broker order id --
            // PositionLedgerService.reconcileAgainstLedger() a few lines below this same method
            // queries fillRecordRepo.findByOrderId() using this exact OMS id (see its own call
            // site), so changing what orderId means here would silently break that
            // reconciliation's own lookup, turning every genuine match into a false
            // NO_LEDGER_DATA and triggering a halt+incident on every single entry. brokerOrderId
            // is a separate, additive field carrying the real broker-side reference alongside
            // the OMS one.
            var recorded = fillLedgerService.recordFills(omsOrder.getId(), result.brokerOrderId(), position.getId(), profile.getUserId(), credential.getId(), orderReq.symbol(), "BUY",
                symbolRules.quoteAsset(), resolvedFills, filledQty, avgEntryPrice);
            // Expected count is one record per genuine fill, or exactly one (the aggregate
            // fallback) when there was no per-fill data but a real fill still happened. Fewer
            // records than expected means the ledger write silently lost data — made visible
            // here, not silently accepted. See Position.ledgerRecordingIncomplete's own javadoc.
            int expected = (resolvedFills != null && !resolvedFills.isEmpty()) ? resolvedFills.size()
                : (filledQty != null && filledQty.signum() > 0 ? 1 : 0);
            if (recorded.size() < expected) {
                position.setLedgerRecordingIncomplete(true);
                // The position itself is still created and saved below regardless -- a real
                // fill genuinely happened on the exchange (filledQty/avgEntryPrice are already
                // confirmed by this point), and refusing to record that at all would leave a
                // real position with zero internal tracking, which is worse than one with a
                // known, flagged data gap. What this triggers instead is that further automated
                // trading on this credential halts until a human investigates -- a ledger write
                // failing on the very first (entry) fill of a brand-new position is worth
                // treating as unsafe/unresolved rather than silently continuing, since a
                // systemic cause (a database problem, a real bug) would silently corrupt every
                // subsequent trade's ledger too if left running. Same halt-and-incident
                // mechanism already used for ORDER_STATE_UNKNOWN.
                String reason = "Fill ledger recording failed for a new position on " + orderReq.symbol()
                    + " (expected " + expected + " fill record(s), got " + recorded.size() + ") — the position itself "
                    + "was still created since the fill genuinely happened on the exchange, but automated trading is "
                    + "halted until this is manually investigated and resolved.";
                atomicHaltProfile(profile, reason);
                credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                    "FILL_LEDGER_RECORDING_FAILED_HALT", profile.getHaltReason());
                incidentService.raiseCritical(profile.getUserId(), credential.getId(), position.getId(), omsOrder.getId(),
                    orderReq.symbol(), "FILL_LEDGER_INCOMPLETE", profile.getHaltReason());
            }

            // Wires in the historical commission backfill for P&L accuracy. Best-effort,
            // non-blocking, per-record -- a P&L-accuracy backfill must never affect whether a
            // real position/order gets created, so failures here are logged and swallowed, never
            // propagated into this method's own control flow.
            for (var fillRecord : recorded) {
                try {
                    fillLedgerService.backfillHistoricalCommissionConversion(fillRecord, symbolRules.quoteAsset(), adapter, credential.getMode());
                } catch (Exception e) {
                    log.warn("Historical commission backfill failed for fill {} (non-fatal, quoteCommission stays genuinely unknown): {}",
                        fillRecord.getId(), e.getMessage());
                }
            }
        }

        // Base asset is Binance's default commission asset for a BUY order unless BNB fee
        // discount is enabled, so this applies to the common configuration, not a rare one.
        // executedQty from the order response is the GROSS traded quantity; if commission was
        // charged in the base asset, the wallet only actually received executedQty minus that
        // commission. Using the gross figure as position.quantity would size the exit OCO larger
        // than the account can actually deliver, and Binance would reject it — sumCommissionInQuoteAsset
        // deliberately leaves non-quote fees unknown for P&L purposes (don't fabricate a
        // conversion), but quantity accounting needs the actual base-asset deduction regardless
        // of whether we can express its dollar value. Uses resolvedFills (not result.fills()
        // directly) since the recovery path's OrderResult always carries empty fills — this is
        // exactly the scenario most likely to need the fallback fetch above.
        BigDecimal netQuantity = filledQty;
        if (resolvedFills != null && !resolvedFills.isEmpty()) {
            var netResult = positionSafetyService.computeNetQuantity(filledQty, resolvedFills, symbolRules.baseAsset());
            netQuantity = netResult.netBaseQty();
            if (netResult.baseAssetCommission().signum() > 0) {
                credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "BASE_ASSET_COMMISSION_DEDUCTED",
                    "Order " + result.brokerOrderId() + " on " + signal.getSymbol() + " paid " + netResult.baseAssetCommission() + " " + symbolRules.baseAsset()
                        + " in commission — position quantity recorded as " + netQuantity + " (gross fill was " + filledQty + "), not the gross figure.");
            }
        }
        position.setQuantity(netQuantity);

        // A real fill is confirmed at this exact point (netQuantity finalized, position about to
        // be saved) — the natural place to mark the signal's lifecycle complete.
        advanceSignalStatus(signal, com.tradevision.model.SignalStatus.EXECUTED);

        // A genuine mismatch between what the ledger reconstructs for this order and what this
        // method believes halts trading and raises a CRITICAL incident, the same escalation used
        // for fill-ledger recording failures. See PositionLedgerService's own javadoc for the
        // full scope. Still never corrects anything itself (this method doesn't know which
        // figure is actually right), but a silent disagreement no longer stays silent.
        if (omsOrder != null) {
            try {
                var reconcileResult = positionLedgerService.reconcileAgainstLedger(omsOrder.getId(), netQuantity);
                if (!reconcileResult.matches()) {
                    // A genuine mismatch with a known-complete ledger recording means the ledger
                    // wins -- position is saved with its quantity going forward, not the
                    // locally-computed one it disagreed with. See
                    // ReconcileResult.resolvedQuantity's own javadoc. Still halts and raises an
                    // incident regardless -- the disagreement itself is worth investigating even
                    // after being corrected.
                    BigDecimal resolvedQty = reconcileResult.resolvedQuantity(position.isLedgerRecordingIncomplete());
                    if (resolvedQty.compareTo(position.getQuantity()) != 0) {
                        position.setQuantity(resolvedQty);
                    }
                    String reason = "Position ledger mismatch for a new position on " + orderReq.symbol()
                        + ": the fill ledger reconstructs " + reconcileResult.ledgerQuantity() + " for this order, but "
                        + netQuantity + " was believed correct — automated trading is halted until this is manually "
                        + "investigated and resolved.";
                    atomicHaltProfile(profile, reason);
                    credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                        "POSITION_LEDGER_MISMATCH_HALT", reason);
                    incidentService.raiseCritical(profile.getUserId(), credential.getId(), position.getId(), omsOrder.getId(),
                        orderReq.symbol(), "POSITION_LEDGER_MISMATCH", reason);
                }
            } catch (Exception e) {
                log.warn("Position ledger reconciliation check failed (non-fatal, observability only): {}", e.getMessage());
            }
        }

        if (!entryPriceVerified) {
            // Real coins exist on the exchange at this point (executedQty was confirmed
            // positive) — marking the position and returning without attempting to protect or
            // flatten it would leave exactly the naked-position state this whole file exists to
            // prevent. Can't protect it here (there's no verified price to size an OCO against),
            // so flatten it and halt for review.
            //
            // status is set to "OPEN" (not a separate status value) so every existing "OPEN"
            // filter in the codebase — reconciliation, exposure, concurrent-trade count, slot
            // self-healing, resume validation — picks this position up for free, with no changes
            // needed anywhere else. avgEntryPriceUnverified is the flag that actually
            // distinguishes it: if the emergency flatten below fails, this still keeps the
            // position visible to every "OPEN" consumer rather than forgotten by everything
            // except an audit log line while real coins sit unprotected. RiskEngineService
            // excludes it from dollar-exposure math it can't safely compute without a price, but
            // still counts it toward concurrent-trade limits; RiskProfileService.resume() refuses
            // to resume while one exists.
            position.setStatus("OPEN");
            position.setAvgEntryPriceUnverified(true);
            position = savePositionOrRaiseIncident(position, profile, credential, omsOrder, orderReq, result);
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "ENTRY_PRICE_UNVERIFIED",
                "Order " + result.brokerOrderId() + " on " + signal.getSymbol() + " filled " + filledQty
                    + " but neither the order response nor a fills lookup could confirm a real entry price — "
                    + "NOT recording the client's claimed price as cost basis. Attempting emergency flatten "
                    + "rather than leaving a naked, unpriced position.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Entry price could not be verified after fill — no cost basis to protect the position with.");
            executionContextService.recordFilled(executionId, position.getId());
            executionContextService.recordTerminal(executionId, "EMERGENCY_FLATTENED_UNPRICED", "Entry price could not be verified after fill -- emergency-flattened rather than left with no cost basis.");
            return;
        }

        position.setAvgEntryPrice(avgEntryPrice);
        position.setStatus("OPEN");
        position = savePositionOrRaiseIncident(position, profile, credential, omsOrder, orderReq, result);
        executionContextService.recordFilled(executionId, position.getId());
        // Links each reservation to this now-real position, so reconcile() can tell "ACTIVE but
        // genuinely not yet linked to any position" (must be counted) apart from "ACTIVE and
        // stale" (safe to exclude), instead of relying purely on actual-position totals plus a
        // time-based grace window. See ExposureReservationRecord.positionId's own field javadoc.
        exposureReservationService.linkToPosition(exposureReserved.reservationId(), position.getId());
        if (accountSlotReservationId != null) slotReservationService.linkToPosition(accountSlotReservationId, position.getId());
        if (planSlotReservationId != null) slotReservationService.linkToPosition(planSlotReservationId, position.getId());

        // This is a genuinely structural, irreducible limitation, not something tighter code can
        // close further -- the atomic claim right before adapter.placeOrder() above already
        // closes the gap as tightly as possible on this application's own side, but the moment
        // that claim succeeds, a real HTTP request is in flight to Binance, and no atomic
        // database check can recall a network request already sent. What is genuinely
        // achievable, and implemented here: a post-submission safety net. If the
        // order succeeded but a FRESH read of this credential's own trading-halted state (not
        // this method's own stale in-memory `profile`) shows it's now halted, the race actually
        // happened during this evaluation -- immediately reverse the exposure via emergency
        // flatten rather than silently proceeding to protect and hold a position that only
        // exists because of a race the kill switch was supposed to prevent. This bounds the
        // real-money exposure window to "however long this evaluation took," not indefinitely.
        RiskProfile freshProfile = riskProfileRepo.findById(profile.getId()).orElse(null);
        if (freshProfile != null && freshProfile.isTradingHalted()) {
            log.error("Kill-switch race detected for credential {}: trading was halted DURING this evaluation, after the execution "
                + "authorization claim already succeeded -- a real order reached Binance despite the halt. Immediately reversing this "
                + "position via emergency flatten rather than proceeding to protect and hold it.", credential.getId());
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "KILL_SWITCH_RACE_DETECTED",
                "Position on " + position.getSymbol() + " was opened after trading was halted mid-evaluation -- the atomic claim "
                    + "succeeded before the halt, and the exchange order was already in flight by the time the halt took effect. "
                    + "Emergency-flattening this position immediately rather than leaving it open despite the active halt.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Kill-switch race: trading was halted during this evaluation, after the exchange order was already in flight.");
            executionContextService.recordTerminal(executionId, "EMERGENCY_FLATTENED_KILL_SWITCH_RACE", "Trading was halted during this evaluation, after the exchange order was already in flight.");
            return;
        }

        // The pre-flight validation above (right after serverSignal is extracted) guarantees
        // stopLoss/target1 are both positive and finite before the entry order is ever
        // submitted — this branch can no longer actually be reached with invalid levels. Kept as
        // a defensive fail-safe rather than removed outright: if a future edit to that
        // validation ever weakens it without this line being noticed, the correct behavior here
        // is still "protect or flatten", never "silently continue unprotected".
        if (serverSignal.stopLoss() > 0 && serverSignal.target1() > 0) {
            placeExitOcoOrEmergencyFlatten(profile, credential, adapter, apiKey, apiSecret, position, serverSignal, omsOrder);
        } else {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(),
                "PROTECTION_LEVELS_INVALID_POST_ENTRY", "Position " + position.getId() + " on " + position.getSymbol()
                    + " reached protection placement with invalid SL/TP despite the pre-flight validation — this "
                    + "should be unreachable. Emergency-flattening rather than leaving it unprotected.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Stop-loss/target levels were invalid when protection placement was attempted.");
        }
    }

    /**
     * Sizes a position: quantity = (available quote balance × riskPerTradePercent) / stop-loss distance,
     * capped by maxPositionQuoteAmount. Returns null if a quote-asset balance can't be established —
     * that's treated as "can't size safely", not "assume zero risk and trade anyway".
     */
    private BigDecimal sizePosition(RiskProfile profile, TradeCallRecord signal, ServerSignalEngine.Signal serverSignal,
                                     BrokerAdapter adapter, String apiKey, String apiSecret, BrokerMode mode, BigDecimal livePrice) {
        // Quote asset comes from real exchange metadata (SymbolRules, sourced from
        // exchangeInfo), not a hardcoded suffix-matching list.
        String quoteAsset;
        try {
            SymbolRules rules = adapter.getSymbolRules(signal.getSymbol(), mode);
            quoteAsset = rules.quoteAsset();
        } catch (Exception e) {
            log.warn("Could not load symbol rules to determine quote asset for {}: {}", signal.getSymbol(), e.getMessage());
            return null;
        }
        if (quoteAsset == null) {
            log.warn("Exchange metadata for {} didn't include a quoteAsset — refusing to size a position.", signal.getSymbol());
            return null;
        }

        BigDecimal quoteBalance;
        try {
            List<AssetBalance> balances = adapter.getBalance(apiKey, apiSecret, mode);
            quoteBalance = balances.stream().filter(b -> b.asset().equalsIgnoreCase(quoteAsset))
                .map(AssetBalance::free).findFirst().orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("Could not fetch balance to size position for {}: {}", signal.getSymbol(), e.getMessage());
            return null;
        }
        if (quoteBalance.signum() <= 0) return null;

        // Stop distance comes from the server's own computed levels, not the client's claimed
        // entry/stopLoss — the money math runs on what the server independently calculated, not
        // a client-controlled number.
        BigDecimal stopDistance = BigDecimal.valueOf(Math.abs(serverSignal.entry() - serverSignal.stopLoss()));
        if (stopDistance.signum() <= 0) return null;

        // A plan's own riskPerTradePercent can only ever make the effective risk smaller than
        // the account-level ceiling, never larger, no matter what a plan document itself claims.
        // Math.min, not a plan-wins-outright substitution, is what enforces "account-level
        // always wins" at the point real money size is decided.
        double effectiveRiskPercent = profile.getRiskPerTradePercent();
        com.tradevision.model.StrategyPlan plan = null;
        if (signal.getPlanId() != null) {
            plan = strategyPlanRepo.findById(signal.getPlanId()).orElse(null);
            if (plan != null) {
                effectiveRiskPercent = Math.min(effectiveRiskPercent, plan.getRiskPerTradePercent());
            }
        }
        BigDecimal riskAmount = quoteBalance.multiply(BigDecimal.valueOf(effectiveRiskPercent / 100.0));

        // Regime-differentiated risk sizing. This doesn't change strategy logic (that's still
        // the frontend's TA engine deciding direction/entry) but it does change how much is
        // actually risked — the one lever available server-side without a full engine port.
        // High-volatility regimes get less capital per trade; anything the signal
        // itself can't identify a regime for is treated as normal risk, not reduced — an unknown
        // regime is not evidence of danger on its own.
        String regime = signal.getFeatures() != null ? signal.getFeatures().getRegime() : null;
        if (regime != null) {
            double regimeMultiplier = switch (regime.toUpperCase()) {
                case "HIGH_VOLATILITY", "PANIC" -> 0.5;
                case "ILLIQUID", "LOW_LIQUIDITY" -> 0.25;
                default -> 1.0;
            };
            if (regimeMultiplier < 1.0) {
                riskAmount = riskAmount.multiply(BigDecimal.valueOf(regimeMultiplier));
                log.info("Regime {} on {} — reducing risk amount to {}x", regime, signal.getSymbol(), regimeMultiplier);
            }
        }

        BigDecimal quantity = riskAmount.divide(stopDistance, 8, RoundingMode.DOWN);

        // The balance cap: riskAmount/stopDistance alone never guarantees quantity *
        // livePrice fits within what this account can actually afford. See
        // BALANCE_SAFETY_BUFFER's own field javadoc. Applied against the real,
        // independently-fetched live price (not serverSignal.entry(), which is a snapshot that
        // may already have drifted -- see this method's caller for the staleness check that
        // already ran on it), and net of a safety buffer for fees/spread.
        if (livePrice != null && livePrice.signum() > 0) {
            BigDecimal maxAffordableQuoteValue = quoteBalance.multiply(BigDecimal.ONE.subtract(BALANCE_SAFETY_BUFFER));
            BigDecimal maxAffordableQuantity = maxAffordableQuoteValue.divide(livePrice, 8, RoundingMode.DOWN);
            if (quantity.compareTo(maxAffordableQuantity) > 0) {
                log.info("Sizing on {}: risk-based quantity {} would cost more than this account's free balance allows -- "
                        + "capped to {} (affordable at live price {} net of a {}% buffer).",
                    signal.getSymbol(), quantity, maxAffordableQuantity, livePrice, BALANCE_SAFETY_BUFFER.multiply(BigDecimal.valueOf(100)));
                quantity = maxAffordableQuantity;
            }
        }

        if (profile.getMaxPositionQuoteAmount() != null && profile.getMaxPositionQuoteAmount().signum() > 0) {
            BigDecimal capQuantity = profile.getMaxPositionQuoteAmount()
                .divide(BigDecimal.valueOf(serverSignal.entry()), 8, RoundingMode.DOWN);
            if (quantity.compareTo(capQuantity) > 0) quantity = capQuantity;
        }
        // Same approach as effectiveRiskPercent above: a plan's own optional maxCapital is a
        // further narrowing on top of the account-level cap just applied, never a replacement
        // for it -- both caps apply, whichever is smaller wins.
        if (plan != null && plan.getMaxCapital() != null && plan.getMaxCapital() > 0) {
            BigDecimal planCapQuantity = BigDecimal.valueOf(plan.getMaxCapital())
                .divide(BigDecimal.valueOf(serverSignal.entry()), 8, RoundingMode.DOWN);
            if (quantity.compareTo(planCapQuantity) > 0) quantity = planCapQuantity;
        }
        return quantity;
    }

    /**
     * Checks whether the real order book is deep enough, within a bounded price-impact band
     * above the best ask, to fill this exact quantity. See MAX_PRICE_IMPACT_PERCENT's own field
     * javadoc. Fails closed (refuses the trade) on an empty book or any error fetching depth --
     * the same "cannot verify, so don't trade" posture this codebase applies to every other
     * pre-trade market-data check (see MarketDataQualityService's own checkSpread/checkClockDrift
     * for the established pattern).
     */
    private boolean hasSufficientOrderBookDepth(String symbol, BrokerAdapter adapter, BrokerMode mode, BigDecimal quantity) {
        OrderBookDepth depth;
        try {
            depth = adapter.getOrderBookDepth(symbol, mode, ORDER_BOOK_DEPTH_LEVELS_FOR_IMPACT_CHECK);
        } catch (Exception e) {
            log.warn("Could not fetch order book depth for {} before sizing -- refusing to trade rather than submit into an unverified book: {}",
                symbol, e.getMessage());
            return false;
        }
        if (depth == null || depth.asks().isEmpty()) {
            log.warn("Order book for {} has no asks at all within the top {} levels -- refusing to trade.", symbol, ORDER_BOOK_DEPTH_LEVELS_FOR_IMPACT_CHECK);
            return false;
        }
        // asks() is sorted lowest-to-highest (best ask first) -- see OrderBookDepth's own class javadoc.
        BigDecimal bestAsk = depth.asks().get(0).price();
        BigDecimal maxImpactPrice = bestAsk.multiply(BigDecimal.ONE.add(MAX_PRICE_IMPACT_PERCENT));
        BigDecimal availableWithinBand = BigDecimal.ZERO;
        for (var level : depth.asks()) {
            if (level.price().compareTo(maxImpactPrice) > 0) break; // walked past the allowed impact band
            availableWithinBand = availableWithinBand.add(level.quantity());
            if (availableWithinBand.compareTo(quantity) >= 0) return true;
        }
        return availableWithinBand.compareTo(quantity) >= 0;
    }

    /**
     * Wraps positionRepo.save(position) (called from both the ENTRY_FILLED_UNVERIFIED
     * emergency-flatten path and the normal open-position path) so a save failure after a
     * confirmed fill raises a CRITICAL incident immediately, the same treatment this method
     * already gives every other failure mode, rather than propagating silently up to
     * evaluateSignal's own generic catch-all with no incident ever raised -- a real broker fill
     * already happened and the position genuinely exists on the exchange at this point.
     *
     * The position is not left unmanaged forever even if this save fails --
     * PositionMonitorService.reconcileEntryOrders's existing late-fill-discovery sweep still
     * finds the FILLED order with no matching Position within its own ~60-second reconciliation
     * cycle and recreates/protects it from there. This raises the incident for immediate
     * visibility and rethrows unchanged so existing control flow (propagating to
     * evaluateSignal's own EVALUATION_FAILED handling) is otherwise unaffected.
     */
    private Position savePositionOrRaiseIncident(Position position, RiskProfile profile, BrokerCredential credential,
                                                  com.tradevision.model.Order omsOrder, OrderRequest orderReq, OrderResult result) {
        try {
            return positionRepo.save(position);
        } catch (Exception e) {
            log.error("Failed to persist Position for a confirmed fill on {} (clientOrderId omsOrder={}, brokerOrderId={}): {} -- "
                    + "the broker-side fill already happened and the OMS order is already FILLED; the existing late-fill-discovery "
                    + "reconciliation sweep (PositionMonitorService.reconcileEntryOrders, runs every ~60s) will find this order and "
                    + "create/protect the position from it, but raising this now for immediate visibility rather than waiting on that sweep.",
                orderReq.symbol(), omsOrder != null ? omsOrder.getId() : null, result.brokerOrderId(), e.getMessage(), e);
            incidentService.raiseCritical(profile.getUserId(), credential.getId(), null, omsOrder != null ? omsOrder.getId() : null,
                orderReq.symbol(), "POSITION_SAVE_FAILED",
                "A confirmed broker fill for " + orderReq.symbol() + " (brokerOrderId=" + result.brokerOrderId() + ") could not be "
                    + "persisted as a Position (" + e.getMessage() + "). The exchange-side fill already happened -- the existing "
                    + "late-fill-discovery reconciliation sweep will recreate and protect this position automatically within about a "
                    + "minute, but this must be manually confirmed rather than assumed.");
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Failed to persist Position after a confirmed fill on " + orderReq.symbol(), e);
        }
    }

    private void placeExitOcoOrEmergencyFlatten(RiskProfile profile, BrokerCredential credential, BrokerAdapter adapter,
                                                 String apiKey, String apiSecret, Position position,
                                                 ServerSignalEngine.Signal serverSignal, com.tradevision.model.Order omsOrder) {
        BigDecimal takeProfit = BigDecimal.valueOf(serverSignal.target1());
        BigDecimal stopTrigger = BigDecimal.valueOf(serverSignal.stopLoss());
        BigDecimal stopLimit = stopTrigger.multiply(BigDecimal.ONE.subtract(stopLossLimitGapPercent));

        // omsOrder can genuinely be null here (both the initial OMS setup and its own fallback
        // creation could have failed, while the real exchange trade still proceeded) -- every
        // use of it throughout this method is null-guarded, matching this codebase's design
        // principle that a record-keeping failure must never block real trading logic.
        if (omsOrder != null) {
            omsOrder.setStopLossTriggerPrice(stopTrigger);
            omsOrder.setTakeProfitPrice(takeProfit);
            // Stamped on the entry order record (the one every later resize/late-fill/remainder
            // re-placement for this position reads TP/SL back from) so this position's
            // protection always re-derives its stop-limit using the same gap its original OCO
            // was placed under, never a live config value that may have since changed. See
            // Order.stopLossLimitGapPercent's own field javadoc.
            omsOrder.setStopLossLimitGapPercent(stopLossLimitGapPercent);
        }

        // The signal's TP/SL were validated against price at signal time, but
        // the market can move between that check, the entry fill, and now. For a SELL-side exit
        // OCO on a long, Binance requires TP (above leg) > current price > SL (below leg) — check
        // that against a FRESH price fetch right before placing, not the stale signal price.
        // A violation here means submitting the OCO would fail anyway; route straight to the
        // same emergency-flatten path a genuine OCO rejection would take, rather than burning an
        // API call on a request that can't succeed.
        BigDecimal freshPrice;
        try {
            freshPrice = adapter.getCurrentPrice(position.getSymbol(), credential.getMode());
        } catch (Exception e) {
            freshPrice = null; // can't verify — fall through and let Binance's own validation be the backstop
        }
        if (freshPrice != null && freshPrice.signum() > 0
                && (takeProfit.compareTo(freshPrice) <= 0 || stopTrigger.compareTo(freshPrice) >= 0)) {
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SLTP_OCO_FAILED",
                "SL/TP for entry " + (omsOrder != null ? omsOrder.getBrokerOrderId() : "unknown (no local order record)") + " on " + position.getSymbol() + " is no longer valid "
                    + "against the current price " + freshPrice + " (TP " + takeProfit + ", SL " + stopTrigger
                    + ") — market moved since the signal. Routing to emergency flatten instead of submitting an "
                    + "OCO that would be rejected.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "TP/SL invalid against current market price before OCO placement");
            return;
        }

        // Deterministic per-position idempotency key — a retried OCO placement for the same
        // position is recognized as a duplicate by Binance itself, matching the pattern used for
        // entry orders. Uses generateClientOrderId (same helper as entry orders) to stay within
        // Binance's documented 36-character limit for listClientOrderId. Deliberately still
        // deterministic (no timestamp) -- this OCO is only ever placed once per position, so a
        // genuine retry of this same call should collide with itself at Binance and be treated
        // as a duplicate, not create a second OCO.
        String listClientOrderId = OrderService.generateClientOrderId("tv-o", position.getId() + ":OCO");

        // The OCO gets its own OMS Order record, not just a timestamp stamped on the entry
        // order (recordProtectionPlaced below is kept as a separate, still-useful signal: "when
        // did the entry order first get real protection", answerable even when this record's
        // own setup fails). Same three-phase, non-fatal setup as the entry order's own OMS
        // wiring: never blocks or delays the real placement call below. See OrderService's own
        // recordOcoPlacementResult javadoc.
        com.tradevision.model.Order ocoOmsOrder;
        try {
            // takeProfitPrice/stopLossTriggerPrice/stopLossLimitPrice are captured directly, not
            // just requestedPrice=takeProfit. Position.ocoOrderListId and the broker's own OCO
            // record remain the actual source of truth for both legs regardless -- this OMS
            // record's purpose is tracking the OCO placement's own lifecycle, with its stored
            // data complete for that purpose. See Order's own orderRole/takeProfitPrice field
            // comments.
            ocoOmsOrder = orderService.create(profile.getUserId(), profile.getCredentialId(),
                position.getId(), position.getSignalId(), position.getSymbol(), "SELL", "OCO",
                position.getQuantity(), takeProfit, listClientOrderId);
            ocoOmsOrder.setOrderRole("OCO_EXIT");
            ocoOmsOrder.setTakeProfitPrice(takeProfit);
            ocoOmsOrder.setStopLossTriggerPrice(stopTrigger);
            ocoOmsOrder.setStopLossLimitPrice(stopLimit);
            ocoOmsOrder.setStopLossLimitGapPercent(stopLossLimitGapPercent);
            orderService.markRiskAccepted(ocoOmsOrder);
            orderService.markSubmitting(ocoOmsOrder);
        } catch (Exception e) {
            // This record failing here does not affect LIVE safety, since ProtectionAttempt
            // (created next, unconditionally) is the authoritative pre-operation gate -- this
            // remains deliberately non-fatal, additive bookkeeping.
            log.warn("OMS setup for OCO placement failed (non-fatal, additive record only): {}", e.getMessage());
            ocoOmsOrder = null;
        }

        // Created with the same listClientOrderId about to be sent, before the exchange call
        // itself. Reuses PositionMonitorService's own already-hardened sequence directly rather
        // than duplicating it a second time in this class. See
        // PositionMonitorService.createProtectionAttempt's own javadoc.
        String protectionAttemptId = positionMonitorService.createProtectionAttempt(position, listClientOrderId,
            position.getQuantity(), takeProfit, stopTrigger);
        if (protectionAttemptId == null && credential.getMode() == BrokerMode.LIVE) {
            log.error("LIVE credential {} -- could not persist the pre-submission protection record for the initial entry OCO on "
                + "position {} ({}). The OCO exchange call will NOT be made; halting further autonomous trading on this credential "
                + "and raising a critical incident, since this position is now genuinely unprotected.",
                credential.getId(), position.getId(), position.getSymbol());
            positionMonitorService.haltForProtectionAttemptPersistenceFailure(credential, position);
            incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), null, position.getSymbol(),
                "PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT",
                "Could not persist the pre-submission protection record for the initial entry OCO on " + position.getSymbol()
                    + " (position " + position.getId() + ") -- the OCO exchange call was NOT made. This position is currently "
                    + "UNPROTECTED and this credential has been auto-halted. Manual intervention required.");
            return;
        }
        // Same reasoning as the entry order call's own renew() immediately before
        // adapter.placeOrder() above -- this OCO call is the second network call in the same
        // entry flow, made after the entry order's own call already consumed some of the lease's
        // remaining budget, so it's at least as exposed to the lease lapsing mid-call as the
        // first one was. Renewed the same way: best-effort,
        // non-fatal, proceeds regardless (aborting here would leave a real filled position with
        // no protective OCO at all, strictly worse than a possible reconciliation race).
        if (!distributedLockService.renew(profile.getCredentialId(), instanceId, java.time.Duration.ofSeconds(30))) {
            log.warn("Could not renew the credential lock lease immediately before placing the protective OCO for position {} "
                + "({}, credential {}) -- proceeding anyway (an unprotected filled position is the greater risk to leave "
                + "unresolved), but reconciliation may now race this evaluation if the lease has already lapsed.",
                position.getId(), position.getSymbol(), profile.getCredentialId());
        }
        OcoOrderResult oco = adapter.placeExitOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(),
            position.getQuantity(), takeProfit, stopTrigger, stopLimit, listClientOrderId);
        positionMonitorService.resolveProtectionAttempt(protectionAttemptId, oco.success());
        // The very first thing that happens after the exchange call succeeds, before any other
        // processing that could throw and abandon this operation with zero durable trace. See
        // createOrphanForOco's own javadoc.
        String ocoOrphanId = (oco.success() && oco.ocoOrderListId() != null)
            ? positionMonitorService.createOrphanForOco(position, oco.ocoOrderListId(), oco.actualProtectedQuantity())
            : null;
        // Records what this OCO genuinely protects, which can be less than position.getQuantity()
        // due to step-size rounding from base-asset fees. See Position.protectedQuantity's own
        // field javadoc.
        if (oco.success() && oco.actualProtectedQuantity() != null) {
            position.setProtectedQuantity(oco.actualProtectedQuantity());
            BigDecimal unprotectedResidual = position.getQuantity().subtract(oco.actualProtectedQuantity());
            if (unprotectedResidual.signum() > 0) {
                log.warn("OCO for {} protects {} of this position's real {} -- a residual {} is NOT covered by any stop-loss or take-profit "
                    + "(exchange step-size rounding). This residual needs manual review if it's economically significant.",
                    position.getSymbol(), oco.actualProtectedQuantity(), position.getQuantity(), unprotectedResidual);
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "OCO_PROTECTION_GAP",
                    "OCO for " + position.getSymbol() + " protects " + oco.actualProtectedQuantity() + " of this position's real "
                        + position.getQuantity() + " -- a residual " + unprotectedResidual + " is NOT covered by any stop-loss or take-profit.");
                // The residual is classified against the symbol's own minQty (the exchange's
                // authoritative definition of "a tradable amount," not an arbitrary threshold
                // this application invents) -- a residual at or above that is real, not rounding
                // noise. Rather than accept a known, partially-unprotected position, this cancels
                // the just-placed OCO and emergency-flattens the whole position. Flattening
                // everything for a gap in only part of it is a deliberately conservative choice,
                // not an over-reaction -- a partially-protected position with a known,
                // un-remediated gap is unacceptable for unattended, real-money trading.
                // emergencyFlatten() already handles cancelling an active OCO before selling, so
                // this is safe to call even though an OCO was just successfully placed.
                var residualRules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
                if (residualRules.minQty() == null || unprotectedResidual.compareTo(residualRules.minQty()) >= 0) {
                    credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "OCO_PROTECTION_GAP_MEANINGFUL",
                        "The unprotected residual " + unprotectedResidual + " on " + position.getSymbol() + " is at or above the "
                            + "exchange's own minQty (" + residualRules.minQty() + ") -- a real, tradable-size gap, not rounding dust. "
                            + "Cancelling the just-placed OCO and emergency-flattening the whole position rather than accepting a known, "
                            + "partially-unprotected state.");
                    positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                        "OCO placement left a meaningful unprotected residual (" + unprotectedResidual + ") -- not accepted as a successful protection outcome.");
                    return;
                }
            }
        }
        if (ocoOmsOrder != null) {
            try {
                orderService.recordOcoPlacementResult(ocoOmsOrder, oco);
            } catch (Exception e) {
                log.warn("OMS recordOcoPlacementResult failed for order {} (non-fatal, additive record only): {}", ocoOmsOrder.getId(), e.getMessage());
            }
        }

        if (oco.success()) {
            if (omsOrder != null) {
                omsOrder = orderService.recordOcoOrderId(omsOrder, oco.ocoOrderListId());
            }
            // By this point the position was already saved as OPEN earlier in this same method
            // (the initial entry-creation save) and is now a real, database-visible record a
            // concurrent reconciliation pass could theoretically pick up -- unlike that earlier
            // save, this one is a genuine update to an existing record, not a fresh insert with
            // nothing to race against, so it is applied atomically rather than via a plain save.
            // See PositionMonitorService's own identical conversion comment.
            positionMonitorService.atomicSetOcoPlaced(position, oco.ocoOrderListId(), oco.actualProtectedQuantity(), ocoOrphanId);
            credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SLTP_OCO_PLACED",
                "SL " + stopTrigger + " / TP " + takeProfit + " for entry order " + (omsOrder != null ? omsOrder.getBrokerOrderId() : "unknown (no local order record)"));
            // Marks the "Fill -> Protection" latency stage. A pure timestamp stamp, non-fatal by
            // design — same pattern as every other additive OMS/ledger call.
            if (omsOrder != null) {
                try {
                    orderService.recordProtectionPlaced(omsOrder);
                } catch (Exception e) {
                    log.warn("Recording protection-placed timestamp failed (non-fatal, additive record only): {}", e.getMessage());
                }
            }
            return;
        }

        // Naked-position handling lives in one shared service (PositionSafetyService), used here
        // and from PositionMonitorService's partial-fill quantity-correction path — one rule,
        // not two different ones.
        if (omsOrder != null) {
            omsOrder = orderService.persistCurrentSlTp(omsOrder);
        }
        credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "SLTP_OCO_FAILED",
            "SL/TP placement FAILED for entry " + (omsOrder != null ? omsOrder.getBrokerOrderId() : "unknown (no local order record)") + " on " + position.getSymbol()
                + ": " + oco.errorMessage() + " — attempting emergency flatten.");

        // A "failed" placement can still carry a real ocoOrderListId — recovery found an actual
        // OCO record (e.g. listOrderStatus=ALL_DONE, possibly with a filled leg) even though it
        // wasn't active protection. If an OCO is known to exist at all, it's recorded on the
        // position before flattening — emergencyFlatten's own OCO-aware state machine will then
        // verify actual fill state and refuse to double-sell on top of it, rather than this path
        // silently discarding the fact that an OCO record was ever found.
        if (oco.ocoOrderListId() != null) {
            positionMonitorService.atomicSetOcoPlaced(position, oco.ocoOrderListId(), null, ocoOrphanId);
        }

        // When the recovery check itself couldn't verify anything (verificationUncertain=true),
        // oco.ocoOrderListId() is null, meaning emergencyFlatten's own OCO-aware protection just
        // above has nothing to check a known OCO against. Blindly flattening here could mean
        // selling into a position that genuinely still has a real, active OCO on the exchange.
        // Halt and escalate for manual review instead of guessing. See OcoOrderResult's own
        // class javadoc.
        if (oco.verificationUncertain()) {
            log.error("Could not determine whether a real OCO exists for {} (position {}) after a placement-call error -- refusing "
                + "to emergency-flatten against an unverified state. Halting further autonomous trading on this credential and "
                + "raising a critical incident.", position.getSymbol(), position.getId());
            if (credential.getMode() == BrokerMode.LIVE) {
                positionMonitorService.haltForProtectionAttemptPersistenceFailure(credential, position);
            }
            incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), null, position.getSymbol(),
                "OCO_STATE_UNKNOWN_AFTER_PLACEMENT_ERROR",
                "An OCO placement call failed for " + position.getSymbol() + " (position " + position.getId() + "), and this "
                    + "application could not verify whether a real OCO actually exists on the exchange (" + oco.errorMessage()
                    + "). This position was NOT emergency-flattened, since doing so against an unverified state risks selling into "
                    + "a position that may still have real, active protection. Manual verification required: check this position's "
                    + "real state directly against the exchange.");
            return;
        }
        positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position, oco.errorMessage());
    }
}
