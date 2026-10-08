package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.repository.TradeCallRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Fill;
import com.tradevision.service.broker.dto.OcoOrderResult;
import com.tradevision.service.broker.dto.OcoStatusInfo;
import com.tradevision.service.broker.dto.OpenOrderInfo;
import com.tradevision.service.broker.dto.OrderRequest;
import com.tradevision.service.broker.dto.OrderResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The one rule this exists to enforce, from a single place: an autonomous position must never
 * remain naked after a protection failure. Either protection gets re-placed, or the position
 * gets emergency-flattened and trading halts for review. Every code path that can leave a
 * position naked (OCO placement failure, partial-fill quantity correction, routine planned
 * exits) routes through this one service, so there is exactly one rule applied consistently
 * rather than several call sites each with their own, possibly weaker, fallback.
 */
@Service
@RequiredArgsConstructor
public class PositionSafetyService {

    private static final Logger log = LoggerFactory.getLogger(PositionSafetyService.class);

    private final BrokerCredentialService credentialService;
    private final PositionRepository positionRepo;
    private final RiskEngineService riskEngine;
    private final RiskProfileRepository riskProfileRepo;
    private final PositionSlotReservationService slotReservationService;
    private final ExposureReservationService exposureReservationService;
    private final IncidentService incidentService;
    private final TradeCallRepository callRepo;
    private final FillLedgerService fillLedgerService;
    // The single, consolidated realized-P&L formula used by every exit path in this class.
    private final RealizedPnlService realizedPnlService;
    // Gives the emergency-flatten market SELL order a real OMS Order record, same as entry
    // and OCO placement, so it is tracked consistently across the whole order lifecycle.
    private final OrderService orderService;
    private final PositionLedgerService positionLedgerService;
    // Used for atomic conditional updates on Position/RiskProfile documents, so a mutation only
    // applies when the document is still in the expected state, instead of blindly overwriting it.
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Cross-process mutual exclusion for a single position's flatten, backed by MongoDB's
    // unique-index mechanism, with a lock-key namespace ("flatten:" + positionId) scoped
    // per-position rather than per-credential.
    private final DistributedLockService distributedLockService;
    /** Durable record of a flatten attempt's exact clientOrderId, written before the real exchange-facing sell. */
    private final com.tradevision.repository.FlattenAttemptRepository flattenAttemptRepo;
    private final ExecutionContextService executionContextService;
    /**
     * Used to look up whether a given open order on this symbol is already tracked by this
     * application's own OMS records, and if so, which position it belongs to -- the only way
     * to tell "another position's own still-active stop" (must NOT be touched) apart from
     * "genuinely untracked / this exact position's own leftover order" (safe to cancel).
     */
    private final com.tradevision.repository.OrderRepository orderRepository;
    private final String instanceId = java.util.UUID.randomUUID().toString();

    /**
     * Emergency-closes a position that has lost its protection, as a market sell. Before
     * sending the sell, this verifies the state of any existing OCO rather than assuming it's
     * safe to sell on top of it: Binance can lock quantity against an open OCO, so the sell
     * could be rejected outright, or worse, race with a still-active protection order. The
     * state machine here verifies OCO state first, cancels it if active, treats any
     * verification failure as UNKNOWN (never guessed as safe), and checks for an already-filled
     * leg before ever sending the market sell — a filled leg means the position may already be
     * closed via the OCO itself, and selling on top of that would be a double-sell.
     *
     * This check runs exactly once per emergencyFlatten call (not on every attemptFlatten retry)
     * — once the OCO is confirmed cleared, retries don't need to re-verify it.
     */
    public void emergencyFlatten(BrokerCredential credential, BrokerAdapter adapter,
                                  String apiKey, String apiSecret, Position position, String failureReason) {
        emergencyFlatten(credential, adapter, apiKey, apiSecret, position, failureReason, true);
    }

    /**
     * A successful, routine planned exit should not halt the whole account and raise a
     * CRITICAL incident the way a genuine protection failure does — doing so anyway would
     * train operators to treat CRITICAL as noise, which then hides a real protection failure
     * when one actually occurs.
     *
     * This is the entry point routine, planned exits should call instead of emergencyFlatten():
     * identical mechanics (same OCO-aware cancel/verify state machine, same balance-capped
     * market sell, same retry-once-then-halt behavior), but a clean, fully-confirmed close does
     * NOT halt the profile or raise an incident. Every failure/ambiguity path inside the flatten
     * itself (could not verify or cancel an existing OCO, ambiguous held balance, order failed
     * outright, partial fill not fully resolved after the one retry) is still a genuine
     * protection failure regardless of why the flatten was triggered, and still halts
     * unconditionally — only the single unconditional halt on a fully successful close is
     * conditional on the caller's intent.
     */
    public void exitPosition(BrokerCredential credential, BrokerAdapter adapter,
                              String apiKey, String apiSecret, Position position, String reason) {
        emergencyFlatten(credential, adapter, apiKey, apiSecret, position, reason, false);
    }

    private void emergencyFlatten(BrokerCredential credential, BrokerAdapter adapter,
                                  String apiKey, String apiSecret, Position position, String failureReason, boolean haltOnSuccess) {
        // The exchange-facing calls below (OCO status check, cancel, the eventual market SELL
        // in attemptFlatten) all need a real, atomic, cross-process lock held BEFORE any of
        // them run, for the entire flatten attempt (including the eventual retry in
        // attemptFlatten) -- an atomic database update on the final close alone would only
        // stop two concurrent callers from both writing "CLOSED" to the database; it would do
        // nothing to stop both from reaching the exchange with a real MARKET SELL first. Two
        // triggers for the same position (a double-click, a manual request racing with
        // reconciliation's own automatic trigger, a WebSocket event racing with either) could
        // otherwise both cancel the same OCO and both submit a real sell.
        //
        // The lock is released via try/finally so every one of this method's own several
        // early-return paths (OCO verification failure, cancel failure, filled-leg detection)
        // releases it correctly, not just the success path. A caller that loses the race
        // returns immediately, touching nothing -- not even a status read -- since by
        // definition another process already owns this exact position's flatten.
        String lockKey = "flatten:" + position.getId();
        // Distinguishes "another instance genuinely holds this lock" (safe, expected) from "a
        // real infrastructure failure meant this couldn't even be determined" (e.g. MongoDB
        // itself unreachable) via tryAcquireWithDiagnosis, rather than treating both the same
        // way a plain boolean would: collapsing them would mean silently returning as if
        // another instance were already flattening this naked position, when an
        // infrastructure failure actually means NO instance is flattening it at all. The
        // infrastructure-failure case is escalated specifically: halts trading on this
        // credential and raises a critical incident, rather than silently doing nothing while
        // a real, unprotected position sits there.
        var acquireLease = distributedLockService.tryAcquireWithDiagnosis(lockKey, instanceId, java.time.Duration.ofSeconds(60));
        if (acquireLease.result() == DistributedLockService.AcquireResult.HELD_BY_OTHER) {
            log.info("Emergency flatten for position {} on {} is already in progress (another trigger got there first) -- skipping this duplicate trigger.",
                position.getId(), position.getSymbol());
            return;
        }
        if (acquireLease.result() == DistributedLockService.AcquireResult.INFRASTRUCTURE_FAILURE) {
            String reason = "Could not even attempt an emergency flatten on " + position.getSymbol() + " (position " + position.getId()
                + ") -- the distributed lock itself could not be acquired due to a genuine infrastructure failure (not another instance "
                + "already handling it), meaning NO instance may currently be flattening this naked/at-risk position. Halted until this "
                + "is manually investigated and resolved.";
            log.error(reason);
            RiskProfile lockFailureProfile = riskProfileRepo.findByCredentialId(credential.getId()).orElse(null);
            haltProfile(lockFailureProfile, position, reason, "EMERGENCY_FLATTEN_LOCK_INFRASTRUCTURE_FAILURE");
            return;
        }
        // This is the exact generation this acquisition's own insert just wrote -- no separate
        // currentGeneration() query, so there's no window for it to have changed underneath
        // this call between acquiring and reading it back (see DistributedLockService.LockLease).
        long lockGeneration = acquireLease.generation();
        try {
            emergencyFlattenLocked(credential, adapter, apiKey, apiSecret, position, failureReason, lockGeneration, haltOnSuccess);
        } finally {
            distributedLockService.release(lockKey, instanceId);
        }
    }

    private void emergencyFlattenLocked(BrokerCredential credential, BrokerAdapter adapter,
                                  String apiKey, String apiSecret, Position position, String failureReason, long lockGeneration,
                                  boolean haltOnSuccess) {
        RiskProfile profile = riskProfileRepo.findByCredentialId(credential.getId()).orElse(null);

        if (position.getOcoOrderListId() != null) {
            String ocoId = position.getOcoOrderListId();
            OcoStatusInfo status;
            try {
                status = adapter.getOcoStatus(apiKey, apiSecret, credential.getMode(), ocoId);
            } catch (Exception e) {
                // Genuinely unknown state — never guessed as safe. Halting instead of risking a
                // market sell colliding with an OCO that might still be fully active is the only
                // defensible choice here.
                log.error("Cannot verify OCO {} status before emergency-flattening position {} on {}: {} — halting rather than risking a collision.",
                    ocoId, position.getId(), position.getSymbol(), e.getMessage());
                haltProfile(profile, position,
                    "Emergency flatten on " + position.getSymbol() + " could not verify existing OCO " + ocoId + " state (" + e.getMessage()
                        + ") — halted rather than risk colliding with a possibly-still-active protection order. Original trigger: " + failureReason,
                    "PROTECTION_UNKNOWN");
                return;
            }

            boolean stillActive = !"ALL_DONE".equalsIgnoreCase(status.listStatus());
            if (stillActive) {
                // getOcoStatus above already ran (a real network call) before this point is
                // reached. The lock is renewed immediately before cancelOco -- the first real
                // exchange-mutating call in this method, and the actual point of no return for
                // the existing OCO -- since a long-running step could otherwise let the lease
                // expire mid-operation.
                if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                    log.warn("Could not renew flatten lock for position {} immediately before cancelOco -- another instance may now own "
                        + "this lock. Aborting before mutating.", position.getId());
                    return;
                }
                OcoOrderResult cancel;
                try {
                    cancel = adapter.cancelOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(), ocoId);
                    // Records the cancel result in the OMS, same as every other OCO cancel site.
                    // Non-fatal, additive -- a failure here doesn't block the real cancel above.
                    try {
                        orderService.recordOcoCancelResult(position.getCredentialId(), position.getSymbol(), ocoId, cancel);
                    } catch (Exception e) {
                        log.warn("OMS recordOcoCancelResult failed for OCO {} (non-fatal, additive record only): {}", ocoId, e.getMessage());
                    }
                } catch (Exception e) {
                    log.error("Cannot cancel active OCO {} before emergency-flattening position {} on {}: {} — halting rather than risking a collision.",
                        ocoId, position.getId(), position.getSymbol(), e.getMessage());
                    haltProfile(profile, position,
                        "Emergency flatten on " + position.getSymbol() + " could not cancel the still-active OCO " + ocoId + " (" + e.getMessage()
                            + ") — halted rather than risk colliding with it. Original trigger: " + failureReason,
                        "PROTECTION_UNKNOWN");
                    return;
                }
                if (!cancel.success()) {
                    // A confirmed cancel failure (e.g. Binance says there's nothing left to
                    // cancel) — re-check status once rather than assuming that alone means it's
                    // safe to proceed; it may have resolved itself in the meantime.
                    OcoStatusInfo recheck;
                    try {
                        recheck = adapter.getOcoStatus(apiKey, apiSecret, credential.getMode(), ocoId);
                    } catch (Exception e) {
                        haltProfile(profile, position,
                            "Emergency flatten on " + position.getSymbol() + ": OCO " + ocoId + " cancel failed (" + cancel.errorMessage()
                                + ") and re-verifying its state also failed (" + e.getMessage() + ") — halted. Original trigger: " + failureReason,
                            "PROTECTION_UNKNOWN");
                        return;
                    }
                    if (!"ALL_DONE".equalsIgnoreCase(recheck.listStatus())) {
                        haltProfile(profile, position,
                            "Emergency flatten on " + position.getSymbol() + ": OCO " + ocoId + " is still active and could not be cancelled ("
                                + cancel.errorMessage() + ") — halted rather than risk colliding with it. Original trigger: " + failureReason,
                            "PROTECTION_UNKNOWN");
                        return;
                    }
                    status = recheck; // resolved itself between our check and the cancel attempt — proceed using the fresh status
                }
            }

            // OCO is now confirmed ALL_DONE (either it already was, or we just cancelled it) —
            // check for a filled leg BEFORE ever sending a market sell. A filled leg means the
            // position may already be closed via the OCO itself; selling on top of that would be
            // a double-sell, not a safety action.
            boolean anyLegFilled = status.legs().stream().anyMatch(l -> "FILLED".equalsIgnoreCase(l.status()));
            if (anyLegFilled) {
                log.warn("OCO {} for position {} on {} shows a filled leg — the position may already be closed via the OCO itself. "
                    + "Not sending a market sell on top of it; halting to let reconciliation resolve the real state.",
                    ocoId, position.getId(), position.getSymbol());
                haltProfile(profile, position,
                    "Emergency flatten on " + position.getSymbol() + " found OCO " + ocoId + " already has a filled leg — the position may "
                        + "already be closed. Halted to let reconciliation resolve the real state rather than risk a double-sell. Original trigger: " + failureReason,
                    "RECONCILIATION_MISMATCH");
                return;
            }

            // Confirmed: no leg filled, OCO is done/cancelled — genuinely safe to clear and proceed.
            position.setOcoOrderListId(null);
            // Atomic conditional update, consistent with how every position mutation in this
            // class is applied. Up to two more real network calls (cancelOco, and the recheck
            // getOcoStatus in its failure branch) can have run since the earlier renewal check,
            // so the lock is renewed again immediately before this position mutation.
            if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                log.warn("Could not renew flatten lock for position {} immediately before clearing its OCO id -- another instance may "
                    + "now own this lock. Aborting before mutating.", position.getId());
                return;
            }
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
                new org.springframework.data.mongodb.core.query.Update().set("ocoOrderListId", (Object) null),
                Position.class);
        }

        // success() alone doesn't mean the whole position is gone — a MARKET SELL can itself
        // come back PARTIALLY_FILLED. Treating any success() as a full close with the full
        // original quantity could mark a position CLOSED in the database while real coins
        // remained on the exchange, completely unprotected. One bounded retry for the
        // remainder, then HALT — never loop forever.
        //
        // The renewal check below runs BEFORE attemptFlatten's own real sell, so stopping here
        // on a failed renewal is safe and correct: it prevents THIS instance from placing a
        // real order while another instance may already believe it owns this exact flatten and
        // could be doing the same thing concurrently. This is different from the OMS fallback
        // elsewhere in this codebase (where the exchange call had ALREADY happened by the
        // failure point, making a hard stop itself dangerous) -- here nothing exchange-facing
        // has happened yet, so a failed renewal is treated as a hard LOCK_LOST condition rather
        // than a warning to note and proceed past.
        if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
            log.error("Could not renew the flatten lock for position {} before the exchange-facing sell -- another instance may now own "
                + "this exact flatten. Stopping BEFORE submitting any real order, rather than proceeding and risking a genuine double-sell.",
                position.getId());
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "FLATTEN_LOCK_LOST",
                "Lost the flatten lock for " + position.getSymbol() + " (position " + position.getId() + ") before the exchange-facing "
                    + "sell could be submitted -- another instance may already own this exact flatten and be handling it. This attempt "
                    + "stopped rather than risk a real double-sell; if no other instance is actually handling this, the position remains "
                    + "unprotected until the next reconciliation pass or trigger picks it up again.");
            return;
        }
        // The OCO-aware cancel above only knows about the ONE order list this application's
        // own records track via position.ocoOrderListId. It says nothing about any OTHER order
        // resting on this same symbol -- a stop leg that somehow persisted after its own list
        // was reported ALL_DONE, a manually-placed order on the account, or a stale order left
        // over from an earlier, incomplete protection attempt this application no longer
        // references. Any of these can lock real coins against the sell attemptFlatten is
        // about to make, leading straight into the "free balance reads zero, locked balance is
        // real, halt" dead end inside attemptFlatten's own balance check -- so nothing should
        // be left resting on this symbol before an emergency sell is attempted. Placed here,
        // right after the lock renewal immediately above (reusing it rather than renewing a
        // second time just for this) and right before the real sell -- best-effort by design: a
        // cancellation failure here does not itself halt (attemptFlatten's own balance check
        // remains the real, authoritative safety net for "coins are still locked"), but every
        // attempt is logged and audited so a stray order that could NOT be cancelled is visible
        // before that eventual halt, not just a bare "balance is locked" message with no
        // further context.
        cancelOtherOpenOrdersForSymbol(credential, adapter, apiKey, apiSecret, position);
        attemptFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, 0, lockGeneration, haltOnSuccess, false);
    }

    /**
     * Best-effort: cancels open orders this account currently has resting on
     * position.getSymbol() so nothing is left locking real coins by the time the balance check
     * a few lines later runs (see the full reasoning at this method's own call site in
     * emergencyFlattenLocked). A failure to list or cancel is logged (and, for a
     * successfully-identified order, audited) but never halts this call on its own -- the
     * balance check downstream is the actual, authoritative safety net if coins remain locked
     * despite this best-effort pass.
     *
     * Uses the real, symbol-scoped getOpenOrders overload rather than an unfiltered
     * full-account sweep, since only this one symbol's orders are ever relevant here. Applies
     * real ownership filtering via this application's own OMS records (orderRepository) before
     * cancelling anything: an order this application recognizes as belonging to a DIFFERENT,
     * still-open position is left completely untouched, since cancelling it would leave that
     * other position naked until the next reconciliation pass -- not this flatten's business.
     * Only an order that belongs to THIS position, or that this application has no record of at
     * all (a genuinely untracked/stray order, including one placed manually -- this emergency
     * safety path has always treated those as fair game to clear before a real-money sell), is
     * cancelled.
     */
    private void cancelOtherOpenOrdersForSymbol(BrokerCredential credential, BrokerAdapter adapter,
                                                 String apiKey, String apiSecret, Position position) {
        List<OpenOrderInfo> openOrders;
        try {
            openOrders = adapter.getOpenOrders(apiKey, apiSecret, credential.getMode(), position.getSymbol());
        } catch (Exception e) {
            log.warn("Could not list open orders on {} before emergency-flattening position {}: {} -- proceeding without cancelling any "
                + "stray orders; the balance check below is the real safety net if any remain and lock real coins.",
                position.getSymbol(), position.getId(), e.getMessage());
            return;
        }
        for (OpenOrderInfo order : openOrders) {
            if (order == null || order.orderId() == null || !position.getSymbol().equalsIgnoreCase(order.symbol())) {
                continue;
            }
            // An OCO leg is never recorded as a standalone Order keyed by its own orderId --
            // only the OCO's orderListId is recorded, on Position.ocoOrderListId. So the
            // orderId-only lookup just below could never recognize another still-OPEN
            // position's protective OCO legs as tracked, and would cancel them right along
            // with genuine stray orders. This check runs first and specifically closes that
            // gap: if the exchange says this order belongs to an order list, and any OTHER
            // open position on this credential+symbol is currently relying on that exact list
            // as its own OCO, leave it alone -- it is that position's stop-loss/take-profit,
            // not a stray.
            if (order.orderListId() != null) {
                List<Position> otherOpenPositionsOnSymbol = positionRepo
                    .findByCredentialIdAndStatus(credential.getId(), "OPEN").stream()
                    .filter(p -> !p.getId().equals(position.getId()))
                    .filter(p -> position.getSymbol().equalsIgnoreCase(p.getSymbol()))
                    .toList();
                boolean belongsToAnotherOpenPositionsOco = otherOpenPositionsOnSymbol.stream()
                    .anyMatch(p -> order.orderListId().equals(p.getOcoOrderListId()));
                if (belongsToAnotherOpenPositionsOco) {
                    log.info("Leaving open order {} (order list {}) on {} alone before emergency-flattening position {} -- it is a leg of "
                        + "another OPEN position's own OCO on this symbol, not a stray order or something belonging to the position being "
                        + "flattened.",
                        order.orderId(), order.orderListId(), position.getSymbol(), position.getId());
                    continue;
                }
            }
            var tracked = orderRepository.findByCredentialIdAndSymbolAndBrokerOrderId(
                credential.getId(), position.getSymbol(), order.orderId()).orElse(null);
            if (tracked != null && tracked.getPositionId() != null && !tracked.getPositionId().equals(position.getId())) {
                log.info("Leaving open order {} on {} alone before emergency-flattening position {} -- this application's own records "
                    + "show it belongs to a DIFFERENT position ({}), not this one. Only orders belonging to this position, or genuinely "
                    + "untracked ones, are cancelled here.",
                    order.orderId(), position.getSymbol(), position.getId(), tracked.getPositionId());
                continue;
            }
            try {
                OrderResult cancel = adapter.cancelOrder(apiKey, apiSecret, credential.getMode(), position.getSymbol(), order.orderId());
                if (cancel != null && cancel.success()) {
                    credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "FLATTEN_STRAY_ORDER_CANCELLED",
                        "Emergency flatten on " + position.getSymbol() + " (position " + position.getId() + "): cancelled an open order ("
                            + order.orderId() + ", " + order.side() + " " + order.quantity() + " @ " + order.price() + ") belonging to this "
                            + "position or untracked by this application's own records, before attempting the sell -- prevents it from "
                            + "locking coins the sell needs.");
                } else {
                    log.warn("Cancelling open order {} on {} before emergency-flattening position {} did not report success ({}) -- "
                        + "may have already resolved itself, or may still be locking coins; the balance check below is the real safety net "
                        + "either way.", order.orderId(), position.getSymbol(), position.getId(),
                        cancel != null ? cancel.errorMessage() : "no result returned");
                }
            } catch (Exception e) {
                log.warn("Could not cancel open order {} on {} before emergency-flattening position {}: {} -- proceeding; the balance "
                    + "check below is the real safety net if this keeps coins locked.",
                    order.orderId(), position.getSymbol(), position.getId(), e.getMessage());
            }
        }
    }

    private void attemptFlatten(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                 Position position, RiskProfile profile, String failureReason, int attempt, long lockGeneration,
                                 boolean haltOnSuccess) {
        attemptFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, attempt, lockGeneration, haltOnSuccess, false);
    }

    /**
     * {@code reprotectAttempted} guards against infinite recursion between this method and
     * tryReprotectAfterFailedFlatten: it is false on every normal entry into this method (the
     * original attempt, and its one same-episode retry), and is set true ONLY by
     * tryReprotectAfterFailedFlatten's own single escalation call below, immediately before
     * handing control back into this exact method for one more backed-off market-sell attempt.
     * Every "give up" branch below checks this flag before ever calling
     * tryReprotectAfterFailedFlatten again -- so the escalation attempt's own outcome (sold,
     * partially sold, or failed again) is handled once, right here, and can never loop back into
     * another re-protect-or-escalate decision a second time.
     */
    private void attemptFlatten(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                 Position position, RiskProfile profile, String failureReason, int attempt, long lockGeneration,
                                 boolean haltOnSuccess, boolean reprotectAttempted) {
        // Marks the position FLATTENING as a persistent, crash-survivable marker -- only on
        // the FIRST attempt (the retry, attempt==1, finds the position already correctly in
        // FLATTENING from this same transition, not OPEN). The WHERE status="OPEN" condition
        // is an extra, database-level guard: if this matches zero documents, something else (a
        // concurrent flatten, a reconciliation pass that already closed it) has already
        // changed this position's status since this instance last read it, and this attempt
        // must not proceed to a real sell believing it still holds an OPEN position that may
        // no longer exist as such.
        if (attempt == 0) {
            // This transition runs exactly once per flatten EPISODE (never on the attempt==1
            // retry, which is the same episode continuing) -- the natural, already-atomic
            // place to stamp a fresh, durable episode number too (see Position.flattenEpisode).
            // findAndModify with returnNew(true) reads back the actual post-increment value
            // straight from MongoDB rather than trusting this in-memory position's own
            // (possibly stale, if this object was loaded before an earlier episode on the same
            // position id already advanced it) flattenEpisode field.
            var flatteningResult = mongoTemplate.findAndModify(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
                new org.springframework.data.mongodb.core.query.Update().set("status", "FLATTENING").inc("flattenEpisode", 1),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                Position.class);
            if (flatteningResult == null) {
                log.warn("Could not transition position {} to FLATTENING (status was no longer OPEN) -- another process has already "
                    + "changed this position's status since this flatten attempt began. Aborting this attempt before any real sell.",
                    position.getId());
                return;
            }
            position.setStatus("FLATTENING");
            position.setFlattenEpisode(flatteningResult.getFlattenEpisode());
        }
        BigDecimal internalQuantity = position.getQuantity();
        BigDecimal quantityToFlatten = internalQuantity;

        // position.getQuantity(), the internal record, can be stale after a partial fill, a
        // manual broker action, an OCO race, a restart, or a reconciliation gap, so it is
        // cross-checked against what the exchange actually reports available. Caps the sell at
        // the account's actual free balance for this asset (never sell MORE than what's
        // genuinely free — that would fail or behave unpredictably anyway), while still
        // attempting the full internal quantity if the balance check itself can't be verified —
        // failing open here (attempt the original amount) rather than failing closed (refusing
        // to flatten at all) is the deliberate choice for an EMERGENCY action specifically: an
        // unprotected position sitting open because a balance lookup failed is worse than one
        // sell attempt using the best information already on hand.
        BigDecimal totalHeldBalance = null; // free + locked, when verifiable — null means "could not verify," handled below
        try {
            var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
            if (rules.baseAsset() != null) {
                var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
                var assetBalance = balances.stream()
                    .filter(b -> b.asset().equalsIgnoreCase(rules.baseAsset()))
                    .findFirst().orElse(null);
                BigDecimal freeBalance = assetBalance != null && assetBalance.free() != null ? assetBalance.free() : BigDecimal.ZERO;
                BigDecimal lockedBalance = assetBalance != null && assetBalance.locked() != null ? assetBalance.locked() : BigDecimal.ZERO;
                totalHeldBalance = freeBalance.add(lockedBalance);
                if (freeBalance.compareTo(internalQuantity) < 0) {
                    log.warn("Emergency flatten on position {} ({}): internal quantity {} exceeds actual free balance {} {} — "
                        + "capping the sell at what's actually free rather than attempting the stale internal figure.",
                        position.getId(), position.getSymbol(), internalQuantity, freeBalance, rules.baseAsset());
                    credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "FLATTEN_QUANTITY_CAPPED_TO_BALANCE",
                        "Emergency flatten on " + position.getSymbol() + ": internal quantity " + internalQuantity + " exceeded actual free balance "
                            + freeBalance + " " + rules.baseAsset() + " — selling the actual free amount instead of the stale internal figure.");
                    quantityToFlatten = freeBalance;
                }
            }
        } catch (Exception e) {
            log.warn("Could not verify exchange balance before emergency-flattening position {} on {}: {} — attempting the internal quantity as-is.",
                position.getId(), position.getSymbol(), e.getMessage());
        }

        // quantityToFlatten.signum() <= 0 alone becomes true whenever freeBalance is zero,
        // since quantityToFlatten gets capped to exactly freeBalance above -- but free==0 with
        // locked>0 (coins genuinely still held, just locked by another open order) does NOT
        // mean the position is gone, the same principle PositionMonitorService's own
        // total-holdings check applies elsewhere. So this only treats the position as "already
        // closed on the exchange" when totalHeldBalance (free+locked) was actually verified
        // AND is genuinely zero. If the balance check itself failed (totalHeldBalance stays
        // null) or found real locked coins, this falls through to the normal sell attempt
        // below instead of falsely closing.
        if (quantityToFlatten.signum() <= 0 && totalHeldBalance != null && totalHeldBalance.signum() <= 0) {
            // internalQuantity here is recorded as unverifiedClosedQuantity, genuinely
            // unaccounted-for rather than falsely credited as sold by this operation -- it may
            // already be a reduced remainder from an earlier confirmed partial sell by a prior
            // attempt, and writing it straight into closedQuantity would silently manufacture a
            // "sale" this specific attempt never actually confirmed. Any already-set
            // closedQuantity from an earlier CONFIRMED partial fill is preserved untouched, not
            // overwritten.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_NOTHING_TO_SELL",
                "Emergency flatten on " + position.getSymbol() + " found zero actual free+locked balance to sell — the position appears "
                    + "already gone from the exchange, but this specific flatten attempt confirmed no sale of its own. Recording "
                    + internalQuantity + " as unverified (not a confirmed sale) rather than assuming it was sold.");
            position.setStatus("CLOSED_UNVERIFIED_PNL");
            position.setClosedAt(LocalDateTime.now());
            position.setUnverifiedClosedQuantity(internalQuantity);
            position.setQuantity(BigDecimal.ZERO);
            // Atomic conditional update matching status=FLATTENING, since this method's own
            // earlier transition already moved the position there, not OPEN. getSymbolRules
            // and getBalance above have both already run real network calls since the
            // caller's own last renewal, so the lock is renewed immediately before this
            // position mutation, the same discipline as this method's own market-sell path
            // below.
            if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                log.warn("Could not renew the flatten lock for position {} immediately before marking it closed (nothing to sell) -- "
                    + "another instance may now own this lock. Aborting before mutating.", position.getId());
                return;
            }
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("status", "CLOSED_UNVERIFIED_PNL").set("closedAt", position.getClosedAt())
                    .set("unverifiedClosedQuantity", internalQuantity).set("quantity", BigDecimal.ZERO),
                Position.class);
            return;
        }

        // The other outcome possible here -- free==0 but locked>0 (or the balance check itself
        // could not be verified at all). The coins are genuinely still there (or their status
        // is genuinely unknown), so a zero-quantity sell attempt below would be meaningless,
        // and marking this closed would be actively wrong. Remain in FLATTENING and halt for a
        // human/reconciliation rather than doing either.
        if (quantityToFlatten.signum() <= 0) {
            String reason = totalHeldBalance != null
                ? "free balance is zero but " + totalHeldBalance + " " + position.getSymbol() + " remains held (likely locked by another "
                    + "open order) -- the coins are genuinely still there, not gone."
                : "free balance came back zero but total held balance could not be verified -- refusing to assume the position is gone "
                    + "on an unverified zero.";
            log.error("Emergency flatten on position {} ({}): {} Halting rather than attempting a meaningless zero-quantity sell or "
                + "falsely marking this closed.", position.getId(), position.getSymbol(), reason);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_HELD_BALANCE_AMBIGUOUS",
                "Emergency flatten on " + position.getSymbol() + " (position " + position.getId() + "): " + reason
                    + " Position remains FLATTENING and unprotected -- manual intervention or reconciliation required, original issue: " + failureReason);
            haltProfile(profile, position);
            return;
        }

        // Uses the centralized generateClientOrderId() helper, the same one every other
        // order-placement site in this codebase uses (entry, OCO placement/resize/late-fill/
        // remainder), rather than constructing a raw UUID directly into a broker-facing id --
        // position.getId() alone (a 36-character UUID) plus a prefix and attempt number would
        // exceed Binance's 36-character clientOrderId limit. attempt is included in the hash
        // basis specifically so a retry (attempt 0 vs 1) produces a genuinely different,
        // still-unique id, not a collision.
        //
        // flattenEpisode is also part of the hash basis, so a LATER flatten episode on this
        // exact same position (it can legitimately return to OPEN after a recovered partial
        // flatten -- see Position.flattenEpisode) never reproduces an earlier episode's own
        // clientOrderId. position's own flattenEpisode was just durably stamped (attempt==0) or
        // already carries this episode's value from that same stamping (attempt==1, the retry
        // continuing this same episode).
        String flattenClientOrderId = OrderService.generateClientOrderId("tv-flat",
            position.getId() + ":FLATTEN:" + position.getFlattenEpisode() + ":" + attempt);
        // A minimal, durable record of THIS attempt's exact clientOrderId, written
        // independently of whether the OMS Order setup below succeeds, so recovery has
        // something to work from even if that fuller setup fails. Deliberately best-effort (a
        // failure here does not block the real flatten -- a naked position is still more
        // dangerous than a missing durable record), and this insert has a far smaller failure
        // surface than orderService.create's own full risk/validation pipeline, so it succeeds
        // in strictly more cases than that does.
        try {
            var intent = new com.tradevision.model.FlattenAttempt();
            intent.setPositionId(position.getId());
            intent.setAttempt(attempt);
            intent.setClientOrderId(flattenClientOrderId);
            intent.setQuantity(quantityToFlatten);
            intent.setSymbol(position.getSymbol());
            intent.setCredentialId(position.getCredentialId());
            flattenAttemptRepo.insert(intent);
        } catch (Exception e) {
            log.warn("Could not write the durable FlattenAttempt pre-submission record for position {} attempt {} (non-fatal -- "
                + "proceeding with the real flatten regardless, same reasoning as the OMS setup below): {}",
                position.getId(), attempt, e.getMessage());
        }
        com.tradevision.model.Order flattenOmsOrder;
        try {
            flattenOmsOrder = orderService.create(position.getUserId(), position.getCredentialId(),
                position.getId(), position.getSignalId(), position.getSymbol(), "SELL", "MARKET",
                quantityToFlatten, null, flattenClientOrderId);
            // Setting orderRole lets recovery find and query THIS specific order's own real,
            // definitive exchange-side status by clientOrderId, rather than relying solely on
            // account balance (which can be low or high for reasons entirely unrelated to this
            // specific flatten attempt -- manual exchange activity, another order, an asset
            // transfer, unrelated application activity). See
            // PositionMonitorService.recoverStuckFlattening.
            flattenOmsOrder.setOrderRole("FLATTEN");
            orderService.markRiskAccepted(flattenOmsOrder);
            orderService.markSubmitting(flattenOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for emergency-flatten order failed (non-fatal, additive record only): {}", e.getMessage());
            flattenOmsOrder = null;
        }

        // By this point, the FLATTENING transition, getSymbolRules, getBalance, and the OMS
        // setup above have all already run. This is the actual point of no return for the
        // whole method -- the real market sell -- so the lock is renewed immediately before it,
        // not just once in the caller before attemptFlatten was even invoked.
        if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
            log.error("Could not renew the flatten lock for position {} immediately before the market sell -- another instance may now "
                + "own this exact flatten. Stopping before submitting any real order, rather than risking a genuine double-sell.",
                position.getId());
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "FLATTEN_LOCK_LOST",
                "Lost the flatten lock for " + position.getSymbol() + " (position " + position.getId() + ") immediately before the "
                    + "market sell could be submitted -- another instance may already own this exact flatten. Stopped rather than risk a "
                    + "real double-sell; the position remains in FLATTENING until the next reconciliation pass or trigger picks it up.");
            return;
        }
        OrderResult flatten = adapter.placeOrder(apiKey, apiSecret, credential.getMode(),
            new OrderRequest(position.getSymbol(), "SELL", "MARKET", quantityToFlatten, flattenClientOrderId));
        if (flattenOmsOrder != null) {
            try {
                orderService.recordBrokerResult(flattenOmsOrder, flatten);
            } catch (Exception e) {
                log.warn("OMS recordBrokerResult failed for emergency-flatten order {} (non-fatal, additive record only): {}", flattenOmsOrder.getId(), e.getMessage());
            }
        }

        BigDecimal executedQty = flatten.success() ? flatten.executedQty() : null;
        boolean confirmedFilled = executedQty != null && executedQty.signum() > 0;

        // Full closure is judged against internalQuantity (the real position size at the start
        // of THIS attempt -- already correctly reduced by any prior attempt's own partial
        // fill, via position.getQuantity() itself, which is why no separate cross-attempt
        // accumulator is needed here), never against quantityToFlatten, the balance-CAPPED
        // sell target. When free balance genuinely caps the sell below the full position (say
        // 0.4 free out of a real 1.0 position), a fill that completes the capped order (0.4
        // filled of a 0.4 target) must NOT be read as "fully flattened" -- doing so would zero
        // the position's own quantity and mark it NAKED_FLATTENED while 0.6 still genuinely sat
        // on the exchange, still exposed, with nothing tracking it anymore. A capped sell that
        // fully fills its own (smaller) target correctly falls through to the PARTIAL branch
        // below instead.
        if (flatten.success() && confirmedFilled && executedQty.compareTo(internalQuantity) >= 0) {
            // Fully flattened, confirmed by the broker's own executedQty against the REAL
            // position size -- genuinely closed. executedQty itself (the real, confirmed amount
            // actually sold), not quantityToFlatten, is what's recorded as closed -- matching
            // this method's own established "record what was actually sold, not the request"
            // principle already used elsewhere (see finalizeFlatten's own exitQty comment).
            finalizeFlatten(credential, adapter, position, profile, flatten, executedQty, haltOnSuccess);
            return;
        }

        if (flatten.success() && confirmedFilled) {
            // Partial flatten: some quantity is now gone, but not all of it. Reduce the position
            // to what's actually left, record the partial P&L, and — critically — this partial
            // amount is STILL unprotected. One retry for the remainder before giving up.
            //
            // The real remaining position is always internalQuantity minus what was actually,
            // confirmedly sold -- never quantityToFlatten (the capped target) minus executedQty,
            // which would understate the real remaining position by exactly however much the
            // balance cap itself removed (e.g. a position of 1.0 capped to a 0.4 sell target,
            // with 0.2 filled, really has 0.8 remaining, not 0.4 - 0.2 = 0.2).
            BigDecimal remaining = internalQuantity.subtract(executedQty);
            recordPartialFlattenPnl(credential, adapter, position, flatten, executedQty, profile);
            position.setQuantity(remaining);
            // Accumulated here too, matching every other per-leg field on this position
            // (exitFeeQuote, realizedPnlQuote) that already accumulates rather than overwrites
            // -- without this, finalizeFlatten's own accumulation of closedQuantity would have
            // nothing from THIS leg to add to, since recordPartialFlattenPnl computes this
            // leg's P&L but doesn't touch closedQuantity on its own.
            BigDecimal cumulativeClosedQuantity = (position.getClosedQuantity() != null ? position.getClosedQuantity() : BigDecimal.ZERO)
                .add(executedQty);
            position.setClosedQuantity(cumulativeClosedQuantity);
            // Atomic conditional update matching status=FLATTENING, not OPEN -- same as this
            // method's own earlier "nothing to sell" conversion above.
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
                new org.springframework.data.mongodb.core.query.Update().set("quantity", remaining).set("closedQuantity", cumulativeClosedQuantity),
                Position.class);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_PARTIAL",
                "Emergency flatten on " + position.getSymbol() + " only filled " + executedQty + " of " + quantityToFlatten
                    + " — " + remaining + " remains, still unprotected. " + (attempt == 0 ? "Retrying once." : "No more retries — halting."));

            if (attempt == 0) {
                // Unlike the first renewal check, a real partial sell has ALREADY happened by
                // here (see this branch's own "only filled X of Y" audit line above). Losing
                // the lock now still means another instance may believe it owns this exact
                // flatten and could independently retry the SAME remaining quantity
                // concurrently with this instance's own retry -- a real over-sell risk, not a
                // hypothetical one, since neither instance's retry re-derives the remaining
                // quantity from a fresh, shared source before submitting. Stopping here leaves
                // the remainder for whichever instance actually holds the lock to retry
                // correctly.
                if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                    log.error("Could not renew the flatten lock for position {} before the retry attempt -- another instance may now own "
                        + "this exact flatten. A partial sell already happened; stopping before retrying the remainder, rather than risk "
                        + "a genuine over-sell if another instance is concurrently retrying the same remaining quantity.", position.getId());
                    credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "FLATTEN_LOCK_LOST",
                        "Lost the flatten lock for " + position.getSymbol() + " (position " + position.getId() + ") after a partial sell "
                            + "but before retrying the remaining " + remaining + " -- another instance may already own this exact flatten. "
                            + "Stopped rather than risk a real over-sell; the remainder needs the owning instance's own retry, or the next "
                            + "reconciliation pass, to be resolved.");
                    return;
                }
                attemptFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, 1, lockGeneration, haltOnSuccess, reprotectAttempted);
                return;
            }
            // Retry also came back partial/incomplete — stop looping, this needs a human now.
            // One best-effort attempt to re-place protection for the still-open remainder
            // before falling through to the naked halt below (see
            // tryReprotectAfterFailedFlatten). If this already IS the escalation attempt
            // (reprotectAttempted), tryReprotectAfterFailedFlatten is not called a second time
            // -- fall straight through to the naked halt below, exactly as if no re-protect
            // were possible.
            if (reprotectAttempted) {
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_FAILED",
                    "CRITICAL: " + remaining + " of " + position.getSymbol() + " could NOT be flattened after the re-protect escalation "
                        + "retry (original issue: " + failureReason + ") — MANUAL INTERVENTION REQUIRED, position remains OPEN and unprotected.");
                haltProfile(profile, position);
                return;
            }
            ReprotectOutcome outcome = tryReprotectAfterFailedFlatten(credential, adapter, apiKey, apiSecret, position, profile,
                failureReason, lockGeneration, haltOnSuccess);
            if (outcome == ReprotectOutcome.ESCALATED) {
                // The escalation's own attemptFlatten call (attempt=2, reprotectAttempted=true)
                // has already run its own full audit/halt/position-mutation logic above, under
                // whichever outcome it reached. Nothing further to do here.
                return;
            }
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_FAILED",
                "CRITICAL: " + remaining + " of " + position.getSymbol() + " could NOT be flattened after a retry "
                    + "(original issue: " + failureReason + ") — MANUAL INTERVENTION REQUIRED, position remains OPEN"
                    + (outcome == ReprotectOutcome.REPROTECTED
                        ? " and was successfully RE-PROTECTED with a new OCO at its prior TP/SL levels." : " and unprotected."));
            haltProfile(profile, position);
            return;
        }

        // This one extra retry on an outright failure is deliberately narrow and safe, unlike
        // a blind N-times-with-backoff loop would be: !flatten.success() here is NOT an
        // ambiguous "we don't know" state. BinanceBrokerAdapter.placeOrder already calls
        // tryRecoverOrderByClientId on exactly this path (a BinanceApiException, e.g. a
        // timeout) BEFORE ever returning success=false, independently re-querying the exchange
        // by this exact attempt's own clientOrderId to confirm the order genuinely never went
        // live. So a confirmed success=false here is a verified non-placement, not the
        // genuinely unresolved state recoverStuckFlattening's own javadoc (and this class's
        // "succeeded with no confirmed fill quantity" branch just below, left deliberately
        // un-retried) is about -- retrying a confirmed non-placement with a fresh,
        // attempt-specific clientOrderId (the same generateClientOrderId(..., attempt) scheme
        // already used for the partial-fill retry) cannot double-sell, because nothing was
        // ever placed the first time.
        if (attempt == 0 && !flatten.success()) {
            if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                log.error("Could not renew the flatten lock for position {} before retrying an outright-failed emergency sell -- another "
                    + "instance may now own this exact flatten. Stopping before retrying, rather than risk two instances independently "
                    + "retrying the same sell concurrently.", position.getId());
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "FLATTEN_LOCK_LOST",
                    "Lost the flatten lock for " + position.getSymbol() + " (position " + position.getId() + ") after an outright-failed "
                        + "emergency sell (" + flatten.errorMessage() + ") but before retrying -- another instance may already own this "
                        + "exact flatten. Stopped rather than risk a concurrent retry; the next reconciliation pass or trigger will pick "
                        + "this up.");
                return;
            }
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_RETRYING",
                "Emergency flatten on " + position.getSymbol() + " failed outright (" + flatten.errorMessage() + ") -- confirmed by the "
                    + "broker adapter's own clientOrderId verification that nothing was placed, so retrying once with a fresh order id "
                    + "before escalating. Original issue: " + failureReason + ".");
            attemptFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, 1, lockGeneration, haltOnSuccess, reprotectAttempted);
            return;
        }

        // Either the retry above also failed outright, or the order "succeeded" with no
        // confirmed fill quantity (a genuinely ambiguous exchange-side state, e.g. a live but
        // unconfirmed NEW/PENDING order -- never safe to retry blindly, since a second sell on
        // top of a still-live first one is a real double-sell risk, not a hypothetical one) --
        // in both cases, don't assume anything closed. Same "don't fabricate a confirmed state"
        // rule as everywhere else in this file.
        //
        // One best-effort attempt to re-place protection before falling through to the naked
        // halt below (see tryReprotectAfterFailedFlatten) -- nothing was ever sold in this
        // branch, so the full internalQuantity is what needs re-protecting, not a partial
        // remainder. If this already IS the escalation attempt, skip straight to the naked
        // halt below rather than calling tryReprotectAfterFailedFlatten a second time.
        ReprotectOutcome outcome = reprotectAttempted ? ReprotectOutcome.FAILED
            : tryReprotectAfterFailedFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, lockGeneration, haltOnSuccess);
        if (outcome == ReprotectOutcome.ESCALATED) {
            // Same reasoning as the partial-fill branch's own identical check above: the
            // escalation's own attemptFlatten call has already fully handled this outcome.
            return;
        }
        boolean reprotected = outcome == ReprotectOutcome.REPROTECTED;
        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_FAILED",
            "CRITICAL: " + (reprotected ? "position" : "naked position") + " on " + position.getSymbol() + " could NOT be emergency-closed: "
                + (flatten.errorMessage() != null ? flatten.errorMessage() : "no confirmed executed quantity")
                + " (original issue: " + failureReason + ")"
                + (reprotectAttempted ? " -- the re-protect escalation retry also failed." : attempt > 0 ? " -- retry also failed." : "")
                + (reprotected ? " -- position was successfully RE-PROTECTED with a new OCO at its prior TP/SL levels."
                    : "") + " MANUAL INTERVENTION REQUIRED.");
        // This records the position's real quantity after a FAILED emergency flatten (naked,
        // unprotected, about to be halted) via an atomic conditional update -- a genuine
        // financial state change on the money-critical failure path this whole class exists
        // for. The position deliberately stays FLATTENING here, not reverted to OPEN -- a real
        // flatten attempt happened and failed, which OPEN would misleadingly suggest never
        // occurred. This is exactly the state PositionMonitorService.recoverStuckFlattening's
        // own reconciliation pass is built to find and resolve.
        //
        // Skipped when reprotected -- tryReprotectAfterFailedFlatten's own atomic update already
        // transitioned this position to OPEN with its new ocoOrderListId (conditioned on the
        // same starting "FLATTENING" status this update itself requires), so this one would
        // simply no-op against zero matching documents; quantity itself is unchanged in this
        // branch either way (nothing was ever sold), so there is nothing left to persist here.
        if (!reprotected) mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
            new org.springframework.data.mongodb.core.query.Update().set("quantity", position.getQuantity()),
            Position.class);
        haltProfile(profile, position);
    }

    /**
     * Outcome of tryReprotectAfterFailedFlatten. ESCALATED means this method handed control to
     * one more attemptFlatten escalation attempt, which has ALREADY run its own complete
     * audit/halt/position-mutation logic by the time this value is returned -- the caller must
     * not audit or halt again on top of it.
     */
    private enum ReprotectOutcome { REPROTECTED, ESCALATED, FAILED }

    /**
     * When every sell attempt is exhausted and the flatten is about to fall through to a
     * naked halt, makes one best-effort attempt to re-place a protective OCO at the position's
     * own last known TP/SL levels (read back from its own most recent OCO_EXIT order record —
     * the exact numbers this exact position was already trading under before the flatten
     * began) before giving up. The OCO was cancelled before the sell because the exchange
     * locks the asset behind an open OCO leg, so there is no way to submit the market sell at
     * all without cancelling it first -- re-placing protection here is what keeps a position
     * from sitting naked for the whole review window just because the sell itself failed.
     *
     * Deliberately conservative in every direction a failure can take it: no prior OCO_EXIT
     * record, no TP/SL stored on it, a stale price the market has since moved through (the same
     * TP>price>SL check placeExitOcoOrEmergencyFlatten already performs before any real OCO
     * placement), a lost lock renewal, or the exchange call itself failing all fall through to
     * returning FAILED — the existing naked-halt path is the fallback of last resort either way,
     * never skipped, only possibly preceded by a real re-protection. A position this method
     * successfully re-protects is no longer naked, but the account still halts regardless — a
     * flatten that had to fail this way is still a real anomaly needing human review; this
     * narrows how exposed the position sits DURING that review, it does not narrow the review
     * itself.
     *
     * Re-placing an OCO at the OLD stop level when price has already fallen through it would be
     * actively unsafe, not merely unhelpful: the stop leg would submit already past its own
     * trigger, which exchanges generally reject outright (or worse, behave unpredictably on).
     * So that specific case — price already through the old stop, the most urgent one this
     * whole class exists to cover — is not treated the same as "no TP/SL on record" or "no
     * prior OCO at all": it escalates to one more backed-off market-sell attempt via
     * attemptFlatten itself (same balance-aware, lock-renewing, OMS-recorded sell every other
     * flatten attempt in this class already uses, just with a fresh clientOrderId and attempt
     * number), rather than silently giving up. See attemptFlatten's own reprotectAttempted
     * javadoc for how this avoids any recursion back into this method a second time.
     */
    private ReprotectOutcome tryReprotectAfterFailedFlatten(BrokerCredential credential, BrokerAdapter adapter,
                                                     String apiKey, String apiSecret, Position position, RiskProfile profile,
                                                     String failureReason, long lockGeneration, boolean haltOnSuccess) {
        if (position.getQuantity() == null || position.getQuantity().signum() <= 0) return ReprotectOutcome.FAILED;
        if (position.getOcoOrderListId() != null) return ReprotectOutcome.FAILED; // already protected somehow — nothing to do
        List<com.tradevision.model.Order> priorOco =
            orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT");
        if (priorOco.isEmpty()) {
            log.warn("Could not re-protect position {} ({}) after a failed flatten -- no prior OCO_EXIT order record found to recover "
                + "TP/SL levels from.", position.getId(), position.getSymbol());
            return ReprotectOutcome.FAILED;
        }
        com.tradevision.model.Order lastOco = priorOco.get(0);
        BigDecimal takeProfit = lastOco.getTakeProfitPrice();
        BigDecimal stopTrigger = lastOco.getStopLossTriggerPrice();
        BigDecimal stopLimit = lastOco.getStopLossLimitPrice();
        if (takeProfit == null || stopTrigger == null || stopLimit == null) {
            log.warn("Could not re-protect position {} ({}) after a failed flatten -- its last OCO_EXIT order record ({}) is missing "
                + "one or more of TP/SL-trigger/SL-limit.", position.getId(), position.getSymbol(), lastOco.getId());
            return ReprotectOutcome.FAILED;
        }

        BigDecimal freshPrice;
        try {
            freshPrice = adapter.getCurrentPrice(position.getSymbol(), credential.getMode());
        } catch (Exception e) {
            log.warn("Could not re-protect position {} ({}) after a failed flatten -- could not fetch a fresh price to validate the "
                + "old TP/SL levels against: {}", position.getId(), position.getSymbol(), e.getMessage());
            return ReprotectOutcome.FAILED;
        }
        if (freshPrice == null || freshPrice.signum() <= 0) {
            log.warn("Could not re-protect position {} ({}) after a failed flatten -- could not obtain a valid fresh price.",
                position.getId(), position.getSymbol());
            return ReprotectOutcome.FAILED;
        }

        // Price has already fallen through the old stop trigger — the single most urgent case
        // this whole class exists to cover, and the one case where re-placing the OLD OCO is
        // not just stale but actively unsafe (a stop leg already past its own trigger).
        // Escalate to one more backed-off market-sell attempt instead of falling straight
        // through to a naked halt.
        if (stopTrigger.compareTo(freshPrice) >= 0) {
            log.warn("Position {} ({}) cannot be re-protected with its old OCO -- current price {} has already fallen through its old "
                + "stop trigger {}. Escalating to one more backed-off market-sell attempt instead of giving up.",
                position.getId(), position.getSymbol(), freshPrice, stopTrigger);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_THROUGH_STOP_ESCALATING",
                "Emergency flatten on " + position.getSymbol() + " (position " + position.getId() + ") exhausted its normal retry with "
                    + "price " + freshPrice + " already through the old stop trigger " + stopTrigger + " -- re-protecting with a new OCO "
                    + "at that stale level is not safe (it would submit already past its own trigger), so escalating to one more "
                    + "market-sell attempt after a short backoff rather than leaving the position naked without trying again.");
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during the through-stop escalation backoff for position {} -- not attempting it.", position.getId());
                return ReprotectOutcome.FAILED;
            }
            if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                log.warn("Could not renew the flatten lock for position {} immediately before the through-stop escalation sell -- "
                    + "another instance may now own this lock. Not attempting it.", position.getId());
                return ReprotectOutcome.FAILED;
            }
            attemptFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, 2, lockGeneration, haltOnSuccess, true);
            return ReprotectOutcome.ESCALATED;
        }

        // Price at/above the old take-profit level is NOT the urgent downside case above, it
        // means price has already moved FAVORABLY past where this position was planning to
        // take profit. Re-placing the old, now-stale OCO is still unsafe (a TP leg already
        // past its own trigger), but "can't re-protect" and "leave it naked" are not the same
        // thing here either -- there is real profit sitting on the table and no reason to wait
        // for it to retrace. Escalates to the same plain market-sell attempt the through-stop
        // case uses, just without that case's own backoff sleep (that delay exists to give a
        // thin/volatile book a moment after an adverse move; there is no equivalent reason to
        // wait when the move was favorable).
        if (takeProfit.compareTo(freshPrice) <= 0) {
            log.warn("Position {} ({}) cannot be re-protected with its old OCO -- current price {} has already moved past its old "
                + "take-profit {}. Escalating to a market sell to lock in the gain instead of leaving it naked.",
                position.getId(), position.getSymbol(), freshPrice, takeProfit);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_THROUGH_TAKEPROFIT_ESCALATING",
                "Emergency flatten on " + position.getSymbol() + " (position " + position.getId() + ") exhausted its normal retry with "
                    + "price " + freshPrice + " already past the old take-profit " + takeProfit + " -- re-protecting with a new OCO at "
                    + "that stale level is not safe (it would submit already past its own trigger), so escalating to a market sell to "
                    + "lock in the gain rather than leaving the position naked.");
            if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
                log.warn("Could not renew the flatten lock for position {} immediately before the through-take-profit escalation sell "
                    + "-- another instance may now own this lock. Not attempting it.", position.getId());
                return ReprotectOutcome.FAILED;
            }
            attemptFlatten(credential, adapter, apiKey, apiSecret, position, profile, failureReason, 2, lockGeneration, haltOnSuccess, true);
            return ReprotectOutcome.ESCALATED;
        }
        if (!distributedLockService.renew("flatten:" + position.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(60))) {
            log.warn("Could not renew the flatten lock for position {} immediately before a re-protect attempt -- another instance "
                + "may now own this lock. Not attempting re-protection.", position.getId());
            return ReprotectOutcome.FAILED;
        }
        // Same three-phase, non-fatal OMS setup every other OCO placement site in this codebase
        // uses (placeExitOcoOrEmergencyFlatten, resize, late-fill, remainder) -- a record-keeping
        // failure here must never block the real, exchange-facing re-protection call below.
        String listClientOrderId = OrderService.generateClientOrderId("tv-ro",
            position.getId() + ":REPROTECT:" + System.currentTimeMillis());
        com.tradevision.model.Order reprotectOmsOrder;
        try {
            reprotectOmsOrder = orderService.create(position.getUserId(), position.getCredentialId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(), takeProfit, listClientOrderId);
            reprotectOmsOrder.setOrderRole("OCO_EXIT");
            reprotectOmsOrder.setTakeProfitPrice(takeProfit);
            reprotectOmsOrder.setStopLossTriggerPrice(stopTrigger);
            reprotectOmsOrder.setStopLossLimitPrice(stopLimit);
            orderService.markRiskAccepted(reprotectOmsOrder);
            orderService.markSubmitting(reprotectOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for re-protect OCO placement failed (non-fatal, additive record only): {}", e.getMessage());
            reprotectOmsOrder = null;
        }
        OcoOrderResult oco;
        try {
            oco = adapter.placeExitOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(),
                position.getQuantity(), takeProfit, stopTrigger, stopLimit, listClientOrderId);
        } catch (Exception e) {
            log.error("Re-protect attempt after a failed flatten threw for position {} ({}): {}",
                position.getId(), position.getSymbol(), e.getMessage());
            return ReprotectOutcome.FAILED;
        }
        if (reprotectOmsOrder != null) {
            try {
                orderService.recordOcoPlacementResult(reprotectOmsOrder, oco);
            } catch (Exception e) {
                log.warn("OMS recordOcoPlacementResult failed for re-protect OCO (non-fatal, additive record only): {}", e.getMessage());
            }
        }
        if (!oco.success() || oco.ocoOrderListId() == null) {
            log.error("Re-protect attempt after a failed flatten was rejected for position {} ({}): {}",
                position.getId(), position.getSymbol(), oco.errorMessage());
            return ReprotectOutcome.FAILED;
        }
        position.setOcoOrderListId(oco.ocoOrderListId());
        position.setStatus("OPEN");
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
            new org.springframework.data.mongodb.core.query.Update().set("status", "OPEN").set("ocoOrderListId", oco.ocoOrderListId()),
            Position.class);
        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_FAILED_REPROTECTED",
            "Emergency flatten on " + position.getSymbol() + " (position " + position.getId() + ") failed, but a protective OCO was "
                + "successfully re-placed at the position's last known TP " + takeProfit + " / SL " + stopTrigger + " levels (new OCO "
                + oco.ocoOrderListId() + "). The position is no longer naked -- trading remains halted pending manual review of why the "
                + "flatten itself failed.");
        return ReprotectOutcome.REPROTECTED;
    }

    private void finalizeFlatten(BrokerCredential credential, BrokerAdapter adapter, Position position,
                                  RiskProfile profile, OrderResult flatten, BigDecimal exitQty, boolean haltOnSuccess) {
        position.setStatus("NAKED_FLATTENED");
        position.setExitPrice(flatten.fillPrice());
        position.setCloseReason("EMERGENCY_FLATTEN");
        position.setClosedAt(LocalDateTime.now());
        // A closed position with a nonzero quantity is exactly the kind of record a future
        // "quantity > 0" filter, exposure sum, or reconciliation query could misinterpret as
        // still open. closedQuantity preserves what was actually closed.
        //
        // Uses exitQty here — the actual amount sold, matching the P&L calculation two lines
        // below — never position.getQuantity(), the internal figure, which can genuinely
        // differ from exitQty on a capped flatten.
        //
        // closedQuantity is always the running total of every confirmed exit execution on this
        // position, never just the most recent leg's own amount, so this adds to any
        // already-recorded closedQuantity from an earlier confirmed partial leg rather than
        // overwriting it. (For example: initial 1.0, partial #1 sells 0.4 (recorded), final #2
        // sells the remaining 0.6 -- closedQuantity ends at the real total 1.0, not just 0.6.)
        // This keeps the live flatten path's semantics consistent with the crash-recovery
        // path's semantics, which also accumulates across legs.
        position.setClosedQuantity((position.getClosedQuantity() != null ? position.getClosedQuantity() : BigDecimal.ZERO).add(exitQty));
        position.setQuantity(BigDecimal.ZERO);
        if (position.getAvgEntryPrice() != null && flatten.fillPrice() != null) {
            // adapter.getSymbolRules() below is wrapped in try/catch like every other
            // fee/ledger lookup in this codebase. Since this runs BEFORE positionRepo.save at
            // the bottom of this method, an unhandled exception here would abort the ENTIRE
            // method -- meaning the position would never even be saved as
            // NAKED_FLATTENED/CLOSED at all,
            // even though position.setStatus("NAKED_FLATTENED") and setQuantity(ZERO) already
            // ran in memory above, and the actual sell already succeeded on the exchange (this
            // method is only ever called with an already-confirmed successful flatten). The
            // database would have kept showing the position as still open indefinitely. Wrapped
            // now so a failure here still lets the position's own already-true closed state
            // reach the database -- with the same halt+incident escalation as the partial-flatten
            // path's own identical gap, since "closed with zero ledger/P&L recording even
            // attempted" is worse, not better, than a recording that was attempted and failed.
            try {
                String quoteAsset = position.getSymbol() != null
                    ? adapter.getSymbolRules(position.getSymbol(), credential.getMode()).quoteAsset() : null;
                BigDecimal exitFee = sumCommissionInQuoteAsset(flatten.fills(), quoteAsset);
                // flatten.fills() is the market sell's own per-fill data, already fetched for
                // the exitFee computation right above — reused here, not a new API call.
                // position.getId() is the consistent cross-reference used across every fill type.
                try {
                    var recorded = fillLedgerService.recordFills(flatten.brokerOrderId(), position.getId(), position.getUserId(), position.getCredentialId(),
                        position.getSymbol(), "SELL", quoteAsset, flatten.fills(), exitQty, flatten.fillPrice());
                    // ledgerRecordingIncomplete is only ever set to true, never reset, matching
                    // the same semantics used elsewhere for this field (see Position.ledgerRecordingIncomplete).
                    int expectedFillRecords = (flatten.fills() != null && !flatten.fills().isEmpty()) ? flatten.fills().size()
                        : (exitQty != null && exitQty.signum() > 0 ? 1 : 0);
                    if (recorded.size() < expectedFillRecords) {
                        position.setLedgerRecordingIncomplete(true);
                        // The flatten fill genuinely happened on the exchange, so the position's
                        // own closure still proceeds normally below even though this write
                        // failed; what changes is that further automated trading halts until a
                        // human investigates why the ledger write failed.
                        String reason = "Fill ledger recording failed for an emergency flatten on " + position.getSymbol()
                            + " (expected " + expectedFillRecords + " fill record(s), got " + recorded.size() + ") — the position's "
                            + "own closure still proceeded since the flatten genuinely happened on the exchange, but automated "
                            + "trading is halted until this is manually investigated and resolved.";
                        if (profile != null) {
                            profile.setTradingHalted(true);
                            profile.setHaltReason(reason);
                            // Targeted atomic $set, touching only the two fields this halt
                            // actually changes, so a stale in-memory profile can't clobber a
                            // concurrent write to an unrelated field.
                            mongoTemplate.updateFirst(
                                new org.springframework.data.mongodb.core.query.Query(
                                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                                new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", reason),
                                RiskProfile.class);
                        }
                        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                            "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
                        incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), flatten.brokerOrderId(),
                            position.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
                    }
                    // An emergency flatten is still an exit fill, so its own commission gets
                    // the same historical-conversion backfill treatment as any other exit.
                    for (var fillRecord : recorded) {
                        try {
                            fillLedgerService.backfillHistoricalCommissionConversion(fillRecord, quoteAsset, adapter, credential.getMode());
                        } catch (Exception e2) {
                            log.warn("Historical commission backfill failed for flatten fill {} (non-fatal): {}", fillRecord.getId(), e2.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Fill ledger recording failed for emergency flatten on position {} (non-fatal, additive record only): {}", position.getId(), e.getMessage());
                }

                // This call site treats exitQty as a FULL close regardless of whether it happens
                // to equal the position's own true pre-close quantity (a capped flatten can
                // genuinely differ), deducting the WHOLE entryFeeQuote rather than a prorated
                // share. Passing exitQty as both the exitQty AND currentQuantity arguments
                // achieves that: the ratio is mathematically forced to 1.0 (entryFeeQuote *
                // exitQty / exitQty == entryFeeQuote) regardless of the position's real
                // quantity.
                var pnlResult = realizedPnlService.calculate(position.getAvgEntryPrice(), flatten.fillPrice(), exitQty, exitQty, position.getEntryFeeQuote(), exitFee);
                BigDecimal pnl = pnlResult.realizedPnl();

                // A prior partial leg may have already recorded its own exitFeeQuote via
                // recordPartialFlattenPnl — accumulate, don't replace, the same rule the
                // partial path already uses, so an earlier leg's fee is never lost from the total.
                if (exitFee != null) {
                    BigDecimal priorExitFee = position.getExitFeeQuote() != null ? position.getExitFeeQuote() : BigDecimal.ZERO;
                    position.setExitFeeQuote(priorExitFee.add(exitFee));
                }

                position.setRealizedPnlQuote((position.getRealizedPnlQuote() != null ? position.getRealizedPnlQuote() : BigDecimal.ZERO).add(pnl));
                if (pnl.signum() < 0 && profile != null) {
                    riskEngine.recordRealizedLoss(profile, pnl.abs());
                }
                // Called unconditionally on pnl's sign (unlike recordRealizedLoss above, which
                // only cares about losses) — a win here needs to reset the consecutive-loss
                // streak, not just a loss extend it.
                if (profile != null) {
                    riskEngine.recordAutoTradeOutcome(profile, position.getTriggerSource(), pnl.signum() < 0);
                }
            } catch (Exception e) {
                // getSymbolRules() itself failed -- the position still closes below (its own
                // exchange-side closure already happened, unconditionally true regardless of
                // this failure), but with zero fee/P&L/ledger data recorded for this leg at all.
                position.setLedgerRecordingIncomplete(true);
                String reason = "Could not establish fee/ledger write parameters for an emergency flatten on " + position.getSymbol()
                    + " (getSymbolRules() itself failed: " + e.getMessage() + ") — the position's own closure still proceeded "
                    + "since the flatten genuinely happened on the exchange, but no fee, P&L, or ledger data was recorded for "
                    + "this leg at all, and automated trading is halted until this is manually investigated and resolved.";
                if (profile != null) {
                    profile.setTradingHalted(true);
                    profile.setHaltReason(reason);
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                        new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", reason),
                        RiskProfile.class);
                }
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                    "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
                incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), flatten.brokerOrderId(),
                    position.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
            }
        }
        // Emergency-flatten's full close is a terminal state transition, applied as an atomic
        // conditional update on status="FLATTENING" (not "OPEN" -- attemptFlatten's earlier
        // atomic OPEN->FLATTENING transition already moved it there): if another process
        // already changed this position's status first, its own side effects already ran, and
        // this method's own side effects below (slot/exposure release) must not double-apply
        // on top of them.
        org.springframework.data.mongodb.core.query.Update flattenCloseUpdate = new org.springframework.data.mongodb.core.query.Update()
            .set("status", position.getStatus())
            .set("exitPrice", position.getExitPrice())
            .set("closeReason", position.getCloseReason())
            .set("closedAt", position.getClosedAt())
            .set("closedQuantity", position.getClosedQuantity())
            .set("quantity", position.getQuantity())
            .set("ledgerRecordingIncomplete", position.isLedgerRecordingIncomplete())
            .set("exitFeeQuote", position.getExitFeeQuote())
            .set("realizedPnlQuote", position.getRealizedPnlQuote());
        var flattenCloseResult = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
            flattenCloseUpdate, Position.class);
        if (flattenCloseResult.getModifiedCount() == 0) {
            log.info("Position {} on {} was already modified by another process before this emergency-flatten close could apply — skipping duplicate side effects.",
                position.getId(), position.getSymbol());
            return;
        }
        // Releases the exact reservation(s) by id when this position has them -- only
        // positions that predate these fields fall back to the old, key-based release.
        if (position.getSlotReservationId() != null) {
            slotReservationService.release(position.getSlotReservationId());
        } else {
            slotReservationService.releaseByKey(position.getCredentialId());
        }
        if (position.getPlanSlotReservationId() != null) {
            slotReservationService.release(position.getPlanSlotReservationId());
        }
        executionContextService.recordClosedByPositionId(position.getId());
        // Releases the exact exposure reservation by id when this position has one -- only
        // positions that predate this field fall back to the old, amount-based approximation
        // (position.getClosedQuantity()*avgEntryPrice, a recomputed value that can legitimately
        // differ from the original reservation).
        if (position.getExposureReservationId() != null) {
            exposureReservationService.release(position.getExposureReservationId());
        } else if (position.getAvgEntryPrice() != null && !position.isAvgEntryPriceUnverified()
                && position.getClosedQuantity() != null && position.getClosedQuantity().signum() > 0) {
            exposureReservationService.release(position.getCredentialId(), position.getSymbol(),
                position.getClosedQuantity().multiply(position.getAvgEntryPrice()));
        }
        if (position.getSignalId() != null) {
            callRepo.findById(position.getSignalId()).ifPresent(call -> {
                var outcome = call.getOutcome() != null ? call.getOutcome() : new com.tradevision.model.TradeOutcome();
                outcome.setResult("EMERGENCY_FLATTEN");
                if (flatten.fillPrice() != null) outcome.setExitPrice(flatten.fillPrice().doubleValue());
                outcome.setResolvedAt(LocalDateTime.now());
                call.setOutcome(outcome);
                callRepo.save(call);
            });
        }
        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "EMERGENCY_FLATTEN_SUCCESS",
            "Naked position on " + position.getSymbol() + " emergency-closed at " + flatten.fillPrice() + " (confirmed qty " + exitQty + ").");
        // A fully-closed position's entire fill history should net to approximately zero.
        // haltProfile() just below already halts trading unconditionally for every emergency
        // flatten regardless of this check -- what a genuine mismatch adds here is a SPECIFIC,
        // additional incident naming the actual ledger discrepancy, rather than only the
        // generic "protection failure" haltProfile() itself raises -- real, more actionable
        // diagnostic information for whoever investigates, not a second halt (that would be
        // redundant with the one below).
        //
        // Without the base asset, a fully-closed position whose entry paid ANY base-asset
        // commission nets to that commission amount, not zero -- a false mismatch on an
        // ordinary, correct flatten, not a genuine discrepancy. Resolved best-effort; a failure
        // here falls back to the old, commission-unaware comparison rather than skipping the
        // check entirely.
        String baseAssetForClosedCheck = null;
        try {
            baseAssetForClosedCheck = adapter.getSymbolRules(position.getSymbol(), credential.getMode()).baseAsset();
        } catch (Exception e) {
            log.debug("Could not resolve base asset for post-flatten ledger reconciliation on {} ({}) -- falling back to the "
                + "commission-unaware comparison.", position.getSymbol(), e.getMessage());
        }
        try {
            var reconcileResult = positionLedgerService.reconcilePositionAgainstLedger(position.getId(), BigDecimal.ZERO, baseAssetForClosedCheck);
            if (!reconcileResult.matches()) {
                // Needs its own explicit follow-up save since the position was already saved
                // above with the (possibly wrong) locally-computed quantity (see ReconcileResult.resolvedQuantity).
                BigDecimal resolvedQty = reconcileResult.resolvedQuantity(position.isLedgerRecordingIncomplete());
                if (resolvedQty.compareTo(position.getQuantity()) != 0) {
                    position.setQuantity(resolvedQty);
                    // Deliberate id-only guard (no status=OPEN condition), since the position
                    // was already saved as CLOSED above -- this differs from every other atomic
                    // position update in this codebase, which does guard on status.
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId())),
                        new org.springframework.data.mongodb.core.query.Update().set("quantity", resolvedQty),
                        Position.class);
                }
                String reason = "Position ledger mismatch on emergency-flatten close for " + position.getSymbol() + ": the fill "
                    + "ledger's full history for this position nets to " + reconcileResult.ledgerQuantity() + ", not the expected "
                    + "~0 for a fully closed position.";
                incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), flatten.brokerOrderId(),
                    position.getSymbol(), "POSITION_LEDGER_MISMATCH", reason);
            }
        } catch (Exception e) {
            log.warn("Position ledger reconciliation check failed for position {} (non-fatal, observability only): {}", position.getId(), e.getMessage());
        }
        // haltOnSuccess is false only for routine, planned exits (max-hold-time, end-of-session,
        // signal-reversal) that are strategy-configured behavior, not failures, via the
        // exitPosition() entry point -- and only reaches this line at all when the close was
        // fully clean (no ledger mismatch, no fee/P&L recording failure -- both of those paths
        // above already halted unconditionally on their own, regardless of haltOnSuccess, since
        // they ARE genuine failures independent of why the flatten was triggered).
        if (haltOnSuccess) {
            haltProfile(profile, position);
        } else {
            log.info("Position {} on {} closed cleanly via a routine exit -- not halting the profile (haltOnSuccess=false).",
                position.getId(), position.getSymbol());
        }
    }

    /**
     * Records P&L and fee accounting for a partial emergency-flatten leg, using the same
     * prorate-and-reduce pattern used for partial OCO exits: allocates this leg's share of the
     * entry fee (based on the ORIGINAL quantity — this runs before position.setQuantity(remaining),
     * so position.getQuantity() here is still the pre-reduction value), deducts it, and reduces
     * the position's remaining entryFeeQuote so the next leg only bears what's actually left.
     * Also deducts this leg's own real exit fee.
     */
    private void recordPartialFlattenPnl(BrokerCredential credential, BrokerAdapter adapter, Position position,
                                          OrderResult flatten, BigDecimal exitQty, RiskProfile profile) {
        if (position.getAvgEntryPrice() == null || flatten.fillPrice() == null) return;

        BigDecimal exitFee = null;
        String quoteAsset = null;
        try {
            var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
            quoteAsset = rules.quoteAsset();
            exitFee = sumCommissionInQuoteAsset(flatten.fills(), quoteAsset);
        } catch (Exception e) {
            // Fee for this specific leg unknown — leave unallocated rather than guess at it.
        }

        // Records this leg's fills in the fill ledger, using the same pattern as every other
        // exit-recording site in this codebase (finalizeFlatten's full close, and
        // PositionMonitorService's OCO exit path).
        if (quoteAsset != null) {
            var recorded = fillLedgerService.recordFills(flatten.brokerOrderId(), position.getId(), position.getUserId(), position.getCredentialId(),
                position.getSymbol(), "SELL", quoteAsset, flatten.fills(), exitQty, flatten.fillPrice());
            int expectedFillRecords = (flatten.fills() != null && !flatten.fills().isEmpty()) ? flatten.fills().size()
                : (exitQty != null && exitQty.signum() > 0 ? 1 : 0);
            if (recorded.size() < expectedFillRecords) {
                position.setLedgerRecordingIncomplete(true);
                // The partial-flatten leg's own P&L/quantity mutation still proceeds below
                // regardless -- the fill genuinely happened on the exchange -- but further
                // automated trading halts until a human investigates.
                String reason = "Fill ledger recording failed for a partial emergency flatten on " + position.getSymbol()
                    + " (expected " + expectedFillRecords + " fill record(s), got " + recorded.size() + ") — this leg's own "
                    + "P&L and quantity reduction still proceeded since the exit genuinely happened on the exchange, but "
                    + "automated trading is halted until this is manually investigated and resolved.";
                if (profile != null) {
                    profile.setTradingHalted(true);
                    profile.setHaltReason(reason);
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                        new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", reason),
                        RiskProfile.class);
                }
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                    "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
                incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), flatten.brokerOrderId(),
                    position.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
            }
            // A partial emergency flatten is still an exit fill, so its commission gets the
            // same historical-conversion backfill as any other exit.
            for (var fillRecord : recorded) {
                try {
                    fillLedgerService.backfillHistoricalCommissionConversion(fillRecord, quoteAsset, adapter, credential.getMode());
                } catch (Exception e) {
                    log.warn("Historical commission backfill failed for partial-flatten fill {} (non-fatal): {}", fillRecord.getId(), e.getMessage());
                }
            }
        } else {
            // "Can't even establish the ledger write parameters at all" (getSymbolRules()
            // itself failed) is the same class of gap as "the write itself failed" -- arguably
            // worse, since it means this leg's fill was never even attempted to be recorded,
            // not just recorded incompletely. Same escalation as the sibling branch above, not
            // a quieter one just because the failure happened one step earlier.
            position.setLedgerRecordingIncomplete(true);
            String reason = "Could not establish fill-ledger write parameters for a partial emergency flatten on "
                + position.getSymbol() + " (getSymbolRules() itself failed, so the quote asset is unknown) — this "
                + "leg's own P&L and quantity reduction still proceeded since the exit genuinely happened on the "
                + "exchange, but no ledger write was even attempted for it, and automated trading is halted until "
                + "this is manually investigated and resolved.";
            if (profile != null) {
                profile.setTradingHalted(true);
                profile.setHaltReason(reason);
                mongoTemplate.updateFirst(
                    new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                    new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", reason),
                    RiskProfile.class);
            }
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
            incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), flatten.brokerOrderId(),
                position.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
        }

        // The same consolidated formula used by every exit path. position.getQuantity() here
        // is still the pre-reduction value (this runs before the caller reduces it).
        var pnlResult = realizedPnlService.calculate(position.getAvgEntryPrice(), flatten.fillPrice(), exitQty,
            position.getQuantity(), position.getEntryFeeQuote(), exitFee);
        BigDecimal pnl = pnlResult.realizedPnl();
        position.setEntryFeeQuote(pnlResult.remainingEntryFeeQuote());

        if (exitFee != null) {
            BigDecimal priorExitFee = position.getExitFeeQuote() != null ? position.getExitFeeQuote() : BigDecimal.ZERO;
            position.setExitFeeQuote(priorExitFee.add(exitFee));
        }

        position.setRealizedPnlQuote((position.getRealizedPnlQuote() != null ? position.getRealizedPnlQuote() : BigDecimal.ZERO).add(pnl));

        // Records this leg's loss against the risk engine's daily-loss counter immediately,
        // per leg, same as the final close does -- rather than waiting for a later leg to
        // fully close the position. If the retry then also fails and the position halts still
        // partially open, a loss recorded only on final close would sit in the database but
        // never actually count against the daily loss limit.
        if (pnl.signum() < 0 && profile != null) {
            riskEngine.recordRealizedLoss(profile, pnl.abs());
        }
        if (profile != null) {
            riskEngine.recordAutoTradeOutcome(profile, position.getTriggerSource(), pnl.signum() < 0);
        }
    }

    private void haltProfile(RiskProfile profile, Position position) {
        haltProfile(profile, position, "Auto-halted after a protection failure on " + position.getSymbol() + " — review required.", "PROTECTION_FAILED");
    }

    // Takes a custom reason and incident type, so a specific halt (like an OCO-collision
    // avoidance halt) reads as what it actually is, not a generic "protection failure" message.
    private void haltProfile(RiskProfile profile, Position position, String haltReason, String incidentType) {
        if (profile == null) return;
        // This is the shared choke point every emergency-flatten-failure path in this file
        // routes through, so it applies a targeted atomic $set, touching only the two fields
        // this method actually changes (tradingHalted, haltReason), rather than a plain
        // full-object save -- a stale in-memory profile object could otherwise silently
        // overwrite a genuinely concurrent write to an unrelated field (dailyRealizedLossQuote,
        // consecutiveOrderFailures, peakEquityQuote, liveAutoTradeAuthorized, autoTradeHalted).
        profile.setTradingHalted(true);
        profile.setHaltReason(haltReason);
        profile.setUpdatedAt(LocalDateTime.now());
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("tradingHalted", true).set("haltReason", haltReason).set("updatedAt", profile.getUpdatedAt()),
            RiskProfile.class);
        // This is the single choke point every emergency-flatten-failure path in this file
        // routes through — the natural place to raise the incident and alert the account
        // holder, rather than duplicating this call at every individual failure site above.
        // See IncidentService's own javadoc.
        incidentService.raiseCritical(position.getUserId(), position.getCredentialId(), position.getId(),
            position.getEntryOrderId(), position.getSymbol(), incidentType, haltReason);
    }

    /**
     * Sums commission only when every fill paid its fee in the quote asset — mixed/foreign-asset
     * fees are left unknown, not guessed.
     *
     * Converting a non-quote-asset (e.g. base-asset or BNB) commission into quote terms would
     * need the commission asset's price at the fill's own timestamp, but the adapter only
     * exposes getCurrentPrice() (live, right now), not a historical price at an arbitrary past
     * timestamp. Using current price as a stand-in for a fill that happened minutes or hours ago
     * would be an approximation, not a real conversion — for a volatile commission asset (BNB
     * is the common case) that approximation could be meaningfully wrong, which is a worse
     * failure mode than the current one: a P&L figure that looks precise but silently isn't,
     * versus a fee that's honestly left null. Left null rather than traded for a misleading
     * estimate.
     */
    public BigDecimal sumCommissionInQuoteAsset(List<Fill> fills, String quoteAsset) {
        if (fills == null || fills.isEmpty() || quoteAsset == null) return null;
        boolean anyOtherAsset = fills.stream().anyMatch(f -> f.commissionAsset() != null && !f.commissionAsset().equalsIgnoreCase(quoteAsset));
        if (anyOtherAsset) return null;
        return fills.stream().map(Fill::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Computes the real net base-asset quantity after deducting any base-asset commission from
     * a set of fills. The base asset is the exchange's default commission asset for a BUY order
     * unless a fee-discount asset (e.g. BNB) is enabled, so this is not a rare edge case — any
     * path that records a position's quantity from fills needs this same deduction. Extracted
     * here so every caller (entry, partial-fill quantity correction, late-discovered-fill
     * recovery) shares one implementation instead of several copies that could silently drift
     * apart.
     */
    public record FillAccountingResult(BigDecimal netBaseQty, BigDecimal baseAssetCommission) {}

    public FillAccountingResult computeNetQuantity(BigDecimal grossQty, List<Fill> fills, String baseAsset) {
        if (fills == null || fills.isEmpty() || baseAsset == null || grossQty == null) {
            return new FillAccountingResult(grossQty, BigDecimal.ZERO);
        }
        BigDecimal baseCommission = fills.stream()
            .filter(f -> f.commissionAsset() != null && f.commissionAsset().equalsIgnoreCase(baseAsset))
            .map(Fill::commission)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = baseCommission.signum() > 0 ? grossQty.subtract(baseCommission) : grossQty;
        return new FillAccountingResult(net, baseCommission);
    }
}
