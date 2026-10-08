package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.OcoOrderResult;
import com.tradevision.service.broker.dto.OcoStatusInfo;
import com.tradevision.service.broker.dto.OrderStatusInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls broker state to keep local order/position records and risk accounting in sync with
 * what actually happened on the exchange:
 *
 *  - Entry orders still marked NEW get their real status via getOrderStatus(brokerOrderId) —
 *    ground truth by id, not a symbol-matching guess.
 *  - Open Positions with a protective OCO get polled via getOcoStatus(orderListId); once the
 *    list reports done, the position is closed with a real exit price and the P&L is computed
 *    and pushed into RiskEngineService.recordRealizedLoss, which is what actually drives the
 *    daily-loss kill switch.
 *
 * This is a polling-based implementation rather than a broker websocket user-data stream;
 * switching to a push-based feed would be a larger architectural change and is left for later.
 */
@Service
@RequiredArgsConstructor
public class PositionMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PositionMonitorService.class);
    /** Caps how many records recoverStuckProtectionAttempts and recoverOrphanedOcos process per cycle, so a large backlog can't make one recovery pass unbounded. */
    private static final int RECOVERY_BATCH_SIZE = 200;
    // Configurable stop-limit gap used wherever this codebase derives a stop-limit price from a
    // stop-trigger price (initial placement, resize, late-fill discovery, entry-order-remainder
    // OCO placement) so widening the gap via application.properties takes effect consistently
    // everywhere, not just at initial placement.
    @org.springframework.beans.factory.annotation.Value("${app.trading.stop-loss-limit-gap-percent:0.005}")
    private BigDecimal stopLossLimitGapPercent;

    /**
     * Resolves the stop-limit gap to use for a given order. Prefers the gap stamped on the
     * order record at its original placement time over the live config value, so that a config
     * change made while a position is open doesn't cause a later resize or re-placement to use
     * a different gap than the position's original OCO. Falls back to the live config value
     * only for a pre-existing record written before this field existed.
     */
    private BigDecimal resolveStopLossLimitGapPercent(com.tradevision.model.Order order) {
        return order.getStopLossLimitGapPercent() != null ? order.getStopLossLimitGapPercent() : stopLossLimitGapPercent;
    }

    private final BrokerCredentialRepository credentialRepo;
    // Repository for the unified OMS Order model, used for entry-order reconciliation.
    private final com.tradevision.repository.OrderRepository omsOrderRepo;
    /**
     * Durable record of an emergency-flatten intent, written before submitting the exchange
     * sell so a crash between submission and confirmation can still be recovered correctly.
     */
    private final com.tradevision.repository.FlattenAttemptRepository flattenAttemptRepo;
    private final com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    /**
     * Durable pre-submission record for an OCO placement, written before the exchange call so
     * that a crash between submitting the OCO and persisting its result locally can still be
     * recovered rather than leaving the position's protection state ambiguous.
     */
    private final com.tradevision.repository.ProtectionAttemptRepository protectionAttemptRepo;
    private final com.tradevision.repository.StrategyPlanRepository strategyPlanRepo;
    private final StrategyPlanService strategyPlanService;
    private final PositionRepository positionRepo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final PositionSlotReservationService slotReservationService;
    private final ExposureReservationService exposureReservationService;
    private final com.tradevision.config.ShutdownState shutdownState;
    private final IncidentService incidentService;
    /** Provides request/operation-scoped execution context, full context in ExecutionContext's own class javadoc. */
    private final ExecutionContextService executionContextService;
    private final com.tradevision.config.StartupState startupState;
    private final com.tradevision.config.TradingHeartbeatService heartbeatService;
    private final FillLedgerService fillLedgerService;
    // Counts per-credential reconciliation failures. Reset to 0 immediately before the startup
    // reconciliation pass (see reconcileOnStartup) and read once that pass completes to decide
    // TRADING_ENABLED vs RECONCILIATION_FAILED, so trading stays disabled until startup
    // reconciliation has actually succeeded. Also incremented by later periodic cycles sharing
    // the same catch block, which is harmless since nothing reads the counter again after the
    // startup pass has already consumed it.
    private final java.util.concurrent.atomic.AtomicInteger reconciliationFailureCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final PositionSafetyService positionSafetyService;
    private final RiskProfileRepository riskProfileRepo;
    private final com.tradevision.repository.TradeCallRepository callRepo;
    // Feeds the ML learner from this service's own real, broker-confirmed fill outcomes (see
    // writeRealOutcomeBackToSignal), rather than relying solely on inferred outcomes elsewhere.
    private final MLWeightService mlWeightService;
    private final BrokerCredentialService credentialService;
    private final RiskEngineService riskEngine;
    private final List<BrokerAdapter> adapters;
    // Single, consolidated implementation of OCO/TP/SL/partial-exit P&L calculations, used so
    // every realized-P&L computation in this class shares the same verified formula.
    private final RealizedPnlService realizedPnlService;
    // Cross-checks a closed position's full fill history against the expected net-zero
    // reconciliation, escalating a genuine mismatch to a halt rather than letting it pass silently.
    private final PositionLedgerService positionLedgerService;
    // Creates and tracks the real OMS Order record for every OCO placement site in this class
    // (resize, late-fill protection, remainder re-protection), keeping the OMS authoritative.
    private final OrderService orderService;

    private Map<com.tradevision.model.BrokerType, BrokerAdapter> adapterMap;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> reconciliationLocks
        = new java.util.concurrent.ConcurrentHashMap<>();
    // Provides the real, cross-process reconciliation lock needed to run this service safely
    // across multiple instances. instanceId is generated once per JVM at startup — stable for
    // this process's whole lifetime and distinct from every other instance's id, which is
    // exactly what a lock "owner" identifier needs to be.
    private final DistributedLockService distributedLockService;
    private final String instanceId = java.util.UUID.randomUUID().toString();

    // Exposes this instance's lock-owner id so a test or external caller can acquire a real
    // distributed lock under the same identity this service uses internally (e.g. before
    // calling a method that renews against that lock).
    public String getInstanceId() {
        return instanceId;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000, scheduler = "reconciliationScheduler")
    public void reconcile() {
        doReconcile();
    }

    /**
     * Reconciles against real broker state as soon as the app is up, rather than waiting for
     * the scheduler's initial delay. This does not provide full crash-survivability (there is
     * no persisted event log or replay of missed signals) — it just closes the specific, cheap
     * gap where open positions would otherwise sit unverified for a while after every restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("Running immediate position/order reconciliation on startup.");
        startupState.markReconciling();
        reconciliationFailureCount.set(0);
        doReconcile();
        startupState.markComplete(reconciliationFailureCount.get() == 0);
    }

    /**
     * Fast watchdog pass that re-checks OCO-protected positions for a triggered-but-unfilled
     * stop-limit leg every 10 seconds, rather than waiting for the main 60-second reconciliation
     * cadence. It calls the same detection logic as the main reconciliation path
     * (reconcileOcoProtectedPosition / handleStopTriggeredButUnfilled), scoped to only the OPEN
     * positions that actually have a live OCO — the rest of full reconciliation (entry-order
     * reconciliation, orphaned-OCO recovery, stuck-protection-attempt recovery, max-hold/
     * end-of-session/risk-exit enforcement) stays on the slower cadence since running all of
     * that every 10 seconds would multiply broker API load for no safety benefit; none of those
     * other steps are as time-critical as a fast-moving stop-loss.
     *
     * Uses the same per-credential locking as the main reconciliation path (the JVM-local
     * reconciliationLocks entry, then the distributed lock) so the two can never mutate the
     * same credential's positions concurrently — whichever is already running for a given
     * credential, the other simply skips that credential for this cycle and retries on its own
     * next tick.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000, scheduler = "watchdogScheduler")
    public void watchExitProtection() {
        if (shutdownState.isShuttingDown()) return;
        for (BrokerCredential credential : credentialRepo.findAll()) {
            if (!credential.isActive() && !positionRepo.existsByCredentialIdAndStatusIn(credential.getId(), java.util.Set.of("OPEN"))) {
                continue;
            }
            if (shutdownState.isShuttingDown()) return;
            try {
                watchExitProtectionForCredential(credential);
            } catch (Exception e) {
                log.warn("Exit-protection watchdog pass failed for credential {} (non-fatal -- the next 10s tick retries, and the "
                    + "60s main reconciliation pass remains the authoritative backstop regardless): {}", credential.getId(), e.getMessage());
            }
        }
    }

    private void watchExitProtectionForCredential(BrokerCredential credential) {
        List<Position> ocoProtectedOpen = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN").stream()
            .filter(p -> p.getOcoOrderListId() != null)
            .toList();
        if (ocoProtectedOpen.isEmpty()) return; // nothing for this fast pass to check -- avoid acquiring a lock or decrypting credentials for no reason

        java.util.concurrent.locks.ReentrantLock lock = reconciliationLocks.computeIfAbsent(
            credential.getId(), k -> new java.util.concurrent.locks.ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Main reconciliation (or another watchdog pass) already in progress for credential {} -- skipping this "
                + "watchdog tick, the next one in 10s will retry.", credential.getId());
            return;
        }
        try {
            var acquireLease = distributedLockService.tryAcquireWithDiagnosis(credential.getId(), instanceId, java.time.Duration.ofSeconds(30));
            if (!acquireLease.acquired()) {
                log.debug("Another application instance currently holds the reconciliation lock for credential {} -- skipping this "
                    + "watchdog tick.", credential.getId());
                return;
            }
            long generation = acquireLease.generation();
            try {
                BrokerAdapter adapter = credentialService.adapterForCredential(credential);
                if (adapter == null) return;
                String apiKey = credentialService.decrypt(credential, true);
                String apiSecret = credentialService.decrypt(credential, false);
                Optional<RiskProfile> profileOpt = riskProfileRepo.findByCredentialId(credential.getId());
                for (Position position : ocoProtectedOpen) {
                    try {
                        reconcileOcoProtectedPosition(credential, adapter, apiKey, apiSecret, position, profileOpt, generation);
                    } catch (Exception e) {
                        log.warn("Exit-protection watchdog check failed for position {} ({}) (non-fatal -- the next 10s tick "
                            + "retries, and the 60s main reconciliation pass remains the authoritative backstop regardless): {}",
                            position.getId(), position.getSymbol(), e.getMessage());
                    }
                }
            } finally {
                distributedLockService.release(credential.getId(), instanceId);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Estimates the minimum account balance that should still be held if a position is
     * genuinely still open. Comparing a position's quantity against raw free balance alone
     * conflates several distinct things: coins locked in other open orders (free alone
     * understates real holdings), and coins belonging to this credential's other open
     * positions in the same symbol, which compete for the same balance pool. This computation
     * uses free+locked as the account's actual total holding, and subtracts what this
     * credential's other open positions in the same symbol should account for, isolating what
     * should remain for the position actually being checked.
     *
     * Known limitation: this cannot distinguish coins the user holds for reasons unrelated to
     * this bot (e.g. personal holdings in the same asset) from the bot's own position, since
     * spot balances are fungible and the exchange has no way to tag which coins belong to which
     * bot position. A full per-position asset ledger would be needed to close that gap
     * completely; this balance-based check is a practical approximation, not a full solution.
     */
    private BigDecimal expectedMinimumBalanceForPosition(String credentialId, Position position) {
        List<Position> otherOpenPositionsSameSymbol = positionRepo.findByCredentialIdAndStatus(credentialId, "OPEN").stream()
            .filter(p -> !p.getId().equals(position.getId()))
            .filter(p -> p.getSymbol().equalsIgnoreCase(position.getSymbol()))
            .toList();
        BigDecimal othersQuantity = otherOpenPositionsSameSymbol.stream()
            .map(Position::getQuantity)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return position.getQuantity().add(othersQuantity);
    }

    /** Total account holding (free+locked) for an asset, used instead of free alone — see expectedMinimumBalanceForPosition's javadoc. */
    private BigDecimal totalHeldBalance(List<com.tradevision.service.broker.dto.AssetBalance> balances, String asset) {
        return balances.stream()
            .filter(b -> b.asset().equalsIgnoreCase(asset))
            .map(b -> (b.free() != null ? b.free() : BigDecimal.ZERO).add(b.locked() != null ? b.locked() : BigDecimal.ZERO))
            .findFirst().orElse(BigDecimal.ZERO);
    }

    /**
     * Releases a position's exposure reservation once it is genuinely closed. Exposure stays
     * reserved for a position's entire OPEN lifetime, not just the brief order-placement
     * window, mirroring how slotReservationService.release() works. Uses closedQuantity (not
     * quantity, which is already zeroed by the time this runs) and avgEntryPrice as the best
     * available estimate of what the reservation represented. A position with an
     * unverified/unknown entry price releases nothing here, since there is no reliable dollar
     * figure to release; the periodic reconcile() call self-heals any resulting drift, the same
     * way it already does for the slot count.
     */
    private void releaseExposureForClosedPosition(Position position) {
        // Prefer releasing the exact reservation by id when the position has one, since the
        // recomputed quantity*avgEntryPrice value can legitimately differ from the original
        // quantity*livePrice reservation. Positions that predate the reservation-id field fall
        // back to the amount-based approximation below.
        if (position.getExposureReservationId() != null) {
            exposureReservationService.release(position.getExposureReservationId());
            return;
        }
        if (position.getAvgEntryPrice() == null || position.isAvgEntryPriceUnverified()) return;
        BigDecimal qty = position.getClosedQuantity() != null ? position.getClosedQuantity() : position.getQuantity();
        if (qty == null || qty.signum() <= 0) return;
        exposureReservationService.release(position.getCredentialId(), position.getSymbol(), qty.multiply(position.getAvgEntryPrice()));
    }

    /**
     * Releases a position's slot reservation(s) once it is genuinely closed, using the same
     * id-first/fallback pattern as releaseExposureForClosedPosition for both slot-reservation
     * tiers. Centralized here since this method is called from every close-time site in this
     * class, so a slot release can't be missed at any individual call site.
     */
    private void releaseSlotForClosedPosition(Position position) {
        if (position.getSlotReservationId() != null) {
            slotReservationService.release(position.getSlotReservationId());
        } else {
            slotReservationService.releaseByKey(position.getCredentialId());
        }
        if (position.getPlanSlotReservationId() != null) {
            slotReservationService.release(position.getPlanSlotReservationId());
        }
        // This is the shared release point reached from every close path in this class, making
        // it the one place that reliably marks an execution's lifecycle CLOSED regardless of
        // which specific close path triggered it.
        executionContextService.recordClosedByPositionId(position.getId());
    }

    private void doReconcile() {
        // Stops a new reconciliation cycle from starting once shutdown has begun — see
        // ShutdownState's own javadoc for exact scope (doesn't interrupt work already in
        // progress, only prevents new work from starting).
        if (shutdownState.isShuttingDown()) {
            log.info("Shutdown in progress — skipping this reconciliation cycle.");
            return;
        }
        if (adapterMap == null) {
            adapterMap = adapters.stream().collect(Collectors.toMap(BrokerAdapter::getType, Function.identity()));
        }
        for (BrokerCredential credential : credentialRepo.findAll()) {
            // Inactive credentials still skip reconciliation unless they have positions in a
            // state this application is still responsible for (OPEN, FLATTENING,
            // NAKED_FLATTENED, CLOSED_UNVERIFIED_PNL). Deactivating a credential stops new
            // autonomous entries (AutoTradeService.evaluateForProfile / AutonomousScannerService
            // gate on the same active flag) but must not also abandon monitoring of whatever
            // that credential already had open — positions and OCOs keep existing and changing
            // state on the real exchange regardless of this application's active flag, and this
            // reconciliation pass is the only way the application observes that. Any remedial
            // action reconcileCredential takes (re-protecting a naked position, an
            // exit-policy-driven flatten) is the same safety machinery already run
            // unconditionally for active credentials' open positions — nothing special is
            // introduced for inactive ones, it simply isn't skipped when still needed. Reuses
            // the same status set BrokerCredentialService.refuseIfCredentialHasOpenWork and
            // RiskProfileService.emergencyRevokeAll use for "this application is still actively
            // responsible for this position".
            if (!credential.isActive() && !positionRepo.existsByCredentialIdAndStatusIn(credential.getId(),
                    java.util.Set.of("OPEN", "FLATTENING", "NAKED_FLATTENED", "CLOSED_UNVERIFIED_PNL"))) {
                continue;
            }
            if (shutdownState.isShuttingDown()) return; // stop starting new per-credential work mid-loop too
            reconcileCredential(credential);
        }
        // Recorded once the cycle actually completes (or runs out of credentials to process) —
        // a cycle with no work to do still counts as a healthy heartbeat.
        heartbeatService.recordReconciliationCompleted();
    }

    /**
     * Public entry point so BinanceUserDataStreamService can trigger an immediate targeted
     * reconciliation the moment a real-time executionReport/listStatus event arrives, instead of
     * waiting for the next scheduled pass. Reuses this exact method rather than having the
     * WebSocket service duplicate P&L/fee computation independently, so there is one source of
     * truth for how a close is priced regardless of which path noticed it first.
     *
     * This method can be called concurrently from three independent triggers (the scheduled
     * poll, the startup listener, and the WebSocket event listener), so a tryLock-and-skip guard
     * ensures only one reconciliation runs per credential at a time: if one is already in
     * progress, a new trigger is simply dropped rather than run concurrently, since the
     * position/order state isn't going anywhere and whichever trigger fires next picks up the
     * same work safely. Skipping rather than blocking is deliberate — a WebSocket event thread
     * shouldn't sit waiting behind a REST reconciliation pass that could take a few seconds.
     *
     * A JVM-local lock is checked first since it's cheap and avoids a database round-trip for
     * the common same-process contention case, and a distributed lock backed by MongoDB's
     * unique _id constraint is then acquired before reconciliation actually proceeds, giving
     * real inter-process mutual exclusion so this is safe to run across multiple replicas.
     */
    public void reconcileCredential(BrokerCredential credential) {
        // The shutdown check in doReconcile() only gates the @Scheduled entry point;
        // BinanceUserDataStreamService calls this public method directly on every
        // executionReport/listStatus/outboundAccountPosition event, bypassing that check. This
        // check is placed here instead so both callers (the scheduled sweep and the WebSocket
        // listener) are covered by the same one check, rather than duplicating it elsewhere —
        // a fill notification arriving during shutdown should not trigger a full reconciliation
        // pass, including OCO placement or emergency-flattening.
        if (shutdownState.isShuttingDown()) {
            log.debug("Shutdown in progress -- skipping this reconciliation trigger for credential {}.", credential.getId());
            return;
        }
        java.util.concurrent.locks.ReentrantLock lock = reconciliationLocks.computeIfAbsent(
            credential.getId(), k -> new java.util.concurrent.locks.ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Reconciliation already in progress for credential {} — skipping this trigger.", credential.getId());
            return;
        }
        try {
            // tryAcquireWithDiagnosis() returns the exact generation written by this
            // acquisition's own insert, avoiding a separate currentGeneration() query that could
            // race against a concurrent change to the lock's generation.
            var acquireLease = distributedLockService.tryAcquireWithDiagnosis(credential.getId(), instanceId, java.time.Duration.ofSeconds(90));
            if (!acquireLease.acquired()) {
                log.debug("Another application instance currently holds the reconciliation lock for credential {} — skipping this trigger.", credential.getId());
                return;
            }
            long generation = acquireLease.generation();
            try {
                reconcileCredentialLocked(credential, generation);
            } finally {
                distributedLockService.release(credential.getId(), instanceId);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a durable pre-submission record of an about-to-be-placed OCO, with the same
     * listClientOrderId that will be sent to the exchange. Called before adapter.placeExitOco()
     * at every real call site, so that if the exchange call succeeds but this process crashes
     * before the result is recorded, there is still a durable trace of the intent. Returns the
     * saved attempt's id, or null if even this save failed — a failed save here is non-fatal and
     * never blocks the real, about-to-happen exchange operation.
     *
     * This method, along with resolveProtectionAttempt/createOrphanForOco/atomicSetOcoPlaced/
     * haltForProtectionAttemptPersistenceFailure below, is package-private so that
     * AutoTradeService (same package) can reuse this same hardened sequence for its own
     * entry-OCO placement rather than duplicating it with the drift risk that would imply.
     */
    String createProtectionAttempt(Position position, String listClientOrderId, java.math.BigDecimal quantity,
                                            java.math.BigDecimal takeProfitPrice, java.math.BigDecimal stopLossPrice) {
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setUserId(position.getUserId());
        attempt.setCredentialId(position.getCredentialId());
        attempt.setPositionId(position.getId());
        attempt.setSymbol(position.getSymbol());
        attempt.setListClientOrderId(listClientOrderId);
        attempt.setQuantity(quantity);
        attempt.setTakeProfitPrice(takeProfitPrice);
        attempt.setStopLossPrice(stopLossPrice);
        try {
            protectionAttemptRepo.save(attempt);
            return attempt.getId();
        } catch (Exception e) {
            log.error("Could not persist the pre-submission ProtectionAttempt record for listClientOrderId {} on position {} BEFORE "
                + "even calling the exchange (non-fatal -- proceeding regardless -- but this specific crash-recovery mechanism will have "
                + "no record if this process crashes before the exchange call completes): {}",
                listClientOrderId, position.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Marks a pre-submission ProtectionAttempt record resolved once the exchange has actually
     * responded, one way or the other. A null attemptId (the create above failed) is a no-op —
     * nothing to resolve.
     */
    void resolveProtectionAttempt(String attemptId, boolean succeeded) {
        if (attemptId == null) return;
        try {
            var update = new org.springframework.data.mongodb.core.query.Update()
                .set("status", succeeded ? "ACTIVE" : "FAILED").set("resolvedAt", LocalDateTime.now());
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(attemptId)),
                update, com.tradevision.model.ProtectionAttempt.class);
        } catch (Exception e) {
            log.warn("Could not resolve ProtectionAttempt {} to {} (non-fatal -- a stale SUBMITTING record here just means the "
                + "recovery sweep re-checks it against the exchange on its next pass, which is safe and idempotent): {}",
                attemptId, succeeded ? "ACTIVE" : "FAILED", e.getMessage());
        }
    }

    /** position id + OCO id pairs whose orphan-persistence failure has already been escalated in this process. */
    private final java.util.Set<String> orphanPersistenceEscalations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    String createOrphanForOco(Position position, String ocoOrderListId, java.math.BigDecimal protectedQuantity) {
        var orphan = new com.tradevision.model.OrphanedOco();
        orphan.setUserId(position.getUserId());
        orphan.setCredentialId(position.getCredentialId());
        orphan.setPositionId(position.getId());
        orphan.setSymbol(position.getSymbol());
        orphan.setOcoOrderListId(ocoOrderListId);
        orphan.setProtectedQuantity(protectedQuantity);
        try {
            orphanedOcoRepo.save(orphan);
            return orphan.getId();
        } catch (Exception e) {
            log.error("Could not persist the safety-net OrphanedOco record for OCO {} on position {} immediately after the exchange call "
                + "succeeded (non-fatal -- proceeding regardless -- but if this process crashes before atomicSetOcoPlaced's own position "
                + "update below completes, this orphan will have NO durable trace at all): {}",
                ocoOrderListId, position.getId(), e.getMessage());
            // By the time this method runs, the exchange OCO call has already succeeded — there
            // is no remaining exchange call to refuse or skip, since the money-moving action has
            // already happened. The only thing left to do is make sure a human learns
            // immediately that this position may now have no durable local trace of its own
            // protection. For LIVE specifically, escalate loudly and halt further new trading on
            // this credential; the existing position is left exactly as-is rather than
            // flattened, since the OCO may well still be genuinely active on the exchange and
            // flattening blind here risks colliding with a real, live order. The credential is
            // looked up by id here rather than threaded through as a parameter, since this
            // method has multiple call sites nested deep in other methods, and a lookup here
            // avoids a wider cascade through all of them for this rare failure path.
            credentialRepo.findById(position.getCredentialId()).ifPresent(credential -> {
                // atomicSetOcoPlaced() retries this save when it gets no orphan id back, so the same failure
                // can reach here twice -- halt and page once per position+OCO, not once per attempt.
                if (orphanPersistenceEscalations.size() > 1000) orphanPersistenceEscalations.clear();
                if (credential.getMode() == BrokerMode.LIVE
                        && orphanPersistenceEscalations.add(position.getId() + ":" + ocoOrderListId)) {
                    haltForProtectionAttemptPersistenceFailure(credential, position);
                    incidentService.raiseCritical(position.getUserId(), position.getCredentialId(), position.getId(), null, position.getSymbol(),
                        "ORPHANED_OCO_PERSISTENCE_FAILED_LIVE_HALT",
                        "A real OCO (" + ocoOrderListId + ") was successfully placed on the exchange for " + position.getSymbol()
                            + " (position " + position.getId() + "), but this application could not persist the safety-net "
                            + "OrphanedOco record for it immediately afterward. If this process crashes before the position's own OCO "
                            + "fields are recorded, this application may have no durable local trace of a real, active exchange-side "
                            + "OCO at all. This credential has been auto-halted for further NEW trading. Manual verification "
                            + "required: confirm this OCO's real state directly against the exchange.");
                }
            });
            return null;
        }
    }

    /**
     * Atomically records that an OCO was placed for this position, and verifies the update
     * actually matched a document rather than assuming success. If the OCO succeeded on the
     * exchange but this update matches zero documents (e.g. the position was already closed by
     * a concurrent process between the caller's read and this write), that is a dangerous
     * asymmetry — the exchange operation genuinely succeeded, so this raises a critical incident
     * rather than silently proceeding as if local state were in sync.
     *
     * orphanId is the id of an orphan record that createOrphanForOco() already created and
     * persisted at the real call site, immediately after the exchange response and before any
     * OMS recording or other processing that could throw — this keeps a durable trace even if a
     * crash happens between the exchange confirming success and this method running. On success
     * this method deletes the now-redundant orphan record; on failure it leaves the orphan in
     * place, already durable and queryable by recoverOrphanedOcos. A null orphanId (the caller
     * couldn't supply one, or its own save failed) falls back to creating one here as a last
     * resort.
     *
     * Returns true only when the position update genuinely modified a document. Callers that
     * take a further action predicated on success (such as marking an orphan resolved) must
     * check this return value first — otherwise a position that closed concurrently could have
     * its still-active exchange OCO incorrectly marked resolved and dropped from recovery.
     */
    boolean atomicSetOcoPlaced(Position position, String ocoOrderListId, java.math.BigDecimal protectedQuantity, String orphanId) {
        if (orphanId == null) {
            orphanId = createOrphanForOco(position, ocoOrderListId, protectedQuantity);
        }

        var update = new org.springframework.data.mongodb.core.query.Update()
            .set("ocoOrderListId", ocoOrderListId).set("ocoPlacedAt", LocalDateTime.now());
        if (protectedQuantity != null) update.set("protectedQuantity", protectedQuantity);
        var result = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
            update, Position.class);
        position.setOcoOrderListId(ocoOrderListId);
        position.recordOcoPlaced();
        // Recorded regardless of the local database update's outcome just below — the real OCO
        // genuinely exists on the exchange either way, which is the fact this traceability
        // layer exists to reflect. See ExecutionContext's own class javadoc.
        executionContextService.recordProtectedByPositionId(position.getId(), null, ocoOrderListId);
        if (result.getModifiedCount() == 0) {
            log.error("A real OCO ({}) was successfully placed on the exchange for position {} ({}), but the local database update to "
                + "record it matched ZERO documents -- the position may have already been closed/reconciled by a concurrent process. "
                + "Binance now believes this OCO is active; this application's own database does not durably reflect that.",
                ocoOrderListId, position.getId(), position.getSymbol());
            incidentService.raiseCritical(position.getUserId(), position.getCredentialId(), position.getId(), null, position.getSymbol(),
                "OCO_PLACED_BUT_NOT_RECORDED",
                "A real, active OCO (" + ocoOrderListId + ") exists on the exchange for " + position.getSymbol() + " (position "
                    + position.getId() + "), but the local database update to record it matched zero documents -- likely because this "
                    + "position was already closed by a concurrent process. Manual reconciliation required: verify this OCO's real state "
                    + "against the exchange directly, since this application's own database cannot be trusted to reflect it. A durable "
                    + "OrphanedOco record was already created for this before the update was even attempted -- recoverOrphanedOcos will "
                    + "find and process it on the next reconciliation pass.");
            // The orphan record (created by createOrphanForOco, immediately after the exchange
            // call, before this method even ran) is the structured, queryable record
            // recoverOrphanedOcos() needs — nothing further to create here.
        } else if (orphanId != null) {
            // The position update succeeded, so the safety-net orphan record is now redundant —
            // the position itself durably tracks this OCO going forward. Deleted so
            // recoverOrphanedOcos never wastes a pass re-checking something that was never
            // actually orphaned.
            try {
                orphanedOcoRepo.deleteById(orphanId);
            } catch (Exception e) {
                log.warn("Could not delete the now-redundant safety-net OrphanedOco record {} for OCO {} on position {} (non-fatal -- "
                    + "recoverOrphanedOcos will simply find it, see it's already properly attached to an OPEN position with this exact "
                    + "OCO id, and resolve it harmlessly on the next pass): {}",
                    orphanId, ocoOrderListId, position.getId(), e.getMessage());
            }
        }
        return result.getModifiedCount() > 0;
    }

    /**
     * Shared helper that atomically closes a position with unverified P&L (status, closedAt,
     * closedQuantity, quantity=0), used at both call sites in reconcileOcoProtectedPosition
     * where the exit price or exit quantity can't be resolved.
     *
     * Reads position.getQuantity() itself and applies both the atomic database update and the
     * in-memory mutation, so this must be called before any other code mutates
     * position.quantity — callers must not set closedQuantity/quantity themselves first, since
     * doing so would cause this helper to record closedQuantity=0.
     */
    private void atomicCloseUnverifiedPnl(Position position) {
        BigDecimal originalQuantity = position.getQuantity();
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
            new org.springframework.data.mongodb.core.query.Update()
                .set("status", "CLOSED_UNVERIFIED_PNL").set("closedAt", LocalDateTime.now())
                .set("closedQuantity", originalQuantity).set("quantity", BigDecimal.ZERO),
            Position.class);
        position.setStatus("CLOSED_UNVERIFIED_PNL");
        position.setClosedAt(LocalDateTime.now());
        position.setClosedQuantity(originalQuantity);
        position.setQuantity(BigDecimal.ZERO);
    }

    /**
     * Feeds an unverified position close into the risk engine's daily loss total and
     * consecutive-loss streak, which are the circuit breakers that stop autonomous trading after
     * a run of bad outcomes. An unverified close (a market move that lands an OCO leg on an
     * unparsable/missing price field, or a position closed by some means outside this backend's
     * normal order flow) is a realistic, non-exotic event for a live account, so it must still
     * count toward risk accounting rather than silently contributing zero.
     *
     * This does not attempt full account valuation or deposit/withdrawal detection for equity
     * purposes — see checkDrawdown for that, which is a separate, larger concern.
     *
     * Resolution order:
     *   1. A genuinely known exit price (an OCO leg's confirmed fill price, when only the
     *      quantity was unresolved) is used directly — this is real, not an estimate.
     *   2. Otherwise, this looks up the position's most recent OCO_EXIT order for its
     *      stopLossTriggerPrice: a real, already-persisted number this application chose as the
     *      worst acceptable loss on this position, used here as a conservative worst-case floor
     *      for risk-accounting purposes only.
     *   3. If neither is available, this makes no estimate and logs why, rather than guessing —
     *      an honestly-disclosed gap is preferable to a silently wrong number.
     * Position.realizedPnlQuote is deliberately left untouched either way — this only feeds the
     * RiskProfile-level counters, never fabricates the position's own authoritative P&L record,
     * which still needs manual reconciliation.
     */
    private void recordUnverifiedCloseRiskImpact(Position position, Optional<RiskProfile> profileOpt,
                                                   BigDecimal knownExitPrice, BigDecimal exitQty, String context) {
        if (profileOpt.isEmpty() || exitQty == null || exitQty.signum() <= 0 || position.getAvgEntryPrice() == null) return;
        BigDecimal exitPrice = knownExitPrice;
        boolean estimated = false;
        if (exitPrice == null || exitPrice.signum() <= 0) {
            Order ocoExitOrder = omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT").stream()
                .filter(o -> o.getStopLossTriggerPrice() != null && o.getStopLossTriggerPrice().signum() > 0)
                .findFirst().orElse(null);
            if (ocoExitOrder == null) {
                log.warn("Could not record a risk-engine impact for the unverified close of position {} ({}, {}) -- no exit price is "
                    + "known and no OCO_EXIT order with a recorded stopLossTriggerPrice exists for this position either. This close's "
                    + "real P&L impact (if any) is NOT reflected in this credential's own daily loss total or loss streak -- a known, "
                    + "disclosed gap rather than a fabricated number.", position.getId(), position.getSymbol(), context);
                return;
            }
            exitPrice = ocoExitOrder.getStopLossTriggerPrice();
            estimated = true;
        }
        var pnlResult = realizedPnlService.calculate(position.getAvgEntryPrice(), exitPrice, exitQty, exitQty, position.getEntryFeeQuote(), null);
        BigDecimal pnl = pnlResult.realizedPnl();
        log.warn("Unverified close of position {} ({}, {}): a {} realized-P&L impact of {} was recorded against the risk engine's own "
            + "daily loss total/loss streak ONLY (exit price {} @ qty {}) -- Position.realizedPnlQuote itself is deliberately left "
            + "unset; this is a risk-accounting safeguard, not a substitute for the manual reconciliation this close still needs.",
            position.getId(), position.getSymbol(), context, estimated ? "ESTIMATED worst-case (stop-loss price)" : "confirmed", pnl,
            exitPrice, exitQty);
        if (pnl.signum() < 0) {
            riskEngine.recordRealizedLoss(profileOpt.get(), pnl.abs());
        }
        riskEngine.recordAutoTradeOutcome(profileOpt.get(), position.getTriggerSource(), pnl.signum() < 0);
    }

    /**
     * Atomically sets the halt flag and reason on a risk profile, used from the many
     * failure-escalation branches throughout this file. Uses a targeted field update rather than
     * a full-object save, so this can't silently overwrite a concurrent write to other fields on
     * the same profile (dailyRealizedLossQuote, consecutiveOrderFailures, peakEquityQuote,
     * liveAutoTradeAuthorized, autoTradeHalted). Centralized here rather than duplicated at each
     * call site to avoid the maintenance risk of several independent copies of the same pattern.
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

    private void reconcileCredentialLocked(BrokerCredential credential, long lockGeneration) {
        // adapterForCredential is the one place that correctly special-cases PAPER credentials
        // to the simulated adapter — every adapter lookup for a specific credential must go
        // through it rather than the local adapterMap, which only holds real, Spring-discovered
        // adapters and has no concept of BrokerMode.PAPER. Resolving a PAPER credential through
        // adapterMap directly would reconcile it against the real exchange's testnet, where its
        // simulated OCO ids would never be found.
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        if (adapter == null) return;
        try {
            // The lock was acquired fresh by this method's own caller moments ago, so this
            // renewal check is primarily defensive against unexpected delay between acquisition
            // and this point (slow adapterMap initialization, JVM scheduling). The invariant is
            // unconditional: no position/order mutation should proceed unless a current lock is
            // held, and this covers the first mutation-capable step (reconcileEntryOrders)
            // before it runs, matching the renewal checks further down in this method for the
            // steps that follow.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before this pass's own first mutation-capable step "
                    + "(reconcileEntryOrders) -- stopping before any mutation in this pass runs at all.", credential.getId());
                return;
            }
            reconcileEntryOrders(credential, adapter, lockGeneration);
            // Recovers a position left stuck in FLATTENING by a process crash mid-flatten; see
            // recoverStuckFlattening's own javadoc. Same renewal-check discipline as every other
            // mutation-capable step in this method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before recoverStuckFlattening -- stopping this pass.",
                    credential.getId());
                return;
            }
            recoverStuckFlattening(credential, adapter, lockGeneration);
            // Recovers orphaned OCO records; see recoverOrphanedOcos's own javadoc. Same
            // renewal-check discipline as every other mutation-capable step in this method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before recoverOrphanedOcos -- stopping this pass.",
                    credential.getId());
                return;
            }
            recoverOrphanedOcos(credential, adapter, lockGeneration);
            // Recovers stuck ProtectionAttempt records; see ProtectionAttempt's own class
            // javadoc. Same renewal-check discipline as every other mutation-capable step in
            // this method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before recoverStuckProtectionAttempts -- stopping this pass.",
                    credential.getId());
                return;
            }
            recoverStuckProtectionAttempts(credential, adapter, lockGeneration);
            // Enforces each plan's configured max-hold-time exit; see enforceMaxHoldTime's own javadoc.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before enforceMaxHoldTime -- stopping this pass.",
                    credential.getId());
                return;
            }
            enforceMaxHoldTime(credential, adapter, lockGeneration);
            // Enforces the plan's Risk Emergency Exit option; see enforceRiskEmergencyExit's own javadoc.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before enforceRiskEmergencyExit -- stopping this pass.",
                    credential.getId());
                return;
            }
            enforceRiskEmergencyExit(credential, adapter, lockGeneration);
            // Enforces end-of-session exit, scoped per Strategy Plan rather than flattening the whole account; see enforceEndOfSession's own javadoc.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before enforceEndOfSession -- stopping this pass.",
                    credential.getId());
                return;
            }
            enforceEndOfSession(credential, adapter, lockGeneration);
            // Stops this reconciliation pass rather than continuing when the lock can't be
            // renewed, since most of this pass's mutations are already atomic conditional
            // updates — stopping early just leaves the remaining positions unreconciled until
            // the next scheduled pass 60 seconds later, which is safe. Continuing after a
            // genuinely lost lease risks a second instance that now believes it owns the lock
            // mutating the same positions concurrently, which is the worse failure mode.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-pass -- another instance may now believe it owns this "
                    + "lock and could be concurrently mutating the same positions. Stopping this pass rather than continuing to mutate shared "
                    + "state without exclusive ownership confirmed -- the next scheduled pass will pick up whatever this one didn't finish.",
                    credential.getId());
                return;
            }
            reconcileOpenPositions(credential, adapter, lockGeneration);
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-pass (after reconcileOpenPositions) -- stopping this "
                    + "pass for the same reason as the earlier renewal check.", credential.getId());
                return;
            }
            checkDrawdown(credential, adapter);
            // Self-heals the distributed slot-reservation counter against the real Position
            // count, catching any missed release() from an exception elsewhere.
            List<Position> openPositionsForCredential = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");
            slotReservationService.reconcile(credential.getId(), openPositionsForCredential.size());

            // Self-heals the exposure reservation the same way — computed from real OPEN
            // positions with a known, verified entry price (matching the same exclusion
            // releaseExposureForClosedPosition applies), catching any drift from rounding, a
            // missed release, or an exception elsewhere.
            BigDecimal actualTotalExposure = BigDecimal.ZERO;
            java.util.Map<String, BigDecimal> actualSymbolExposure = new java.util.HashMap<>();
            for (Position p : openPositionsForCredential) {
                if (p.getAvgEntryPrice() == null || p.isAvgEntryPriceUnverified() || p.getQuantity() == null) continue;
                BigDecimal value = p.getQuantity().multiply(p.getAvgEntryPrice());
                actualTotalExposure = actualTotalExposure.add(value);
                actualSymbolExposure.merge(p.getSymbol(), value, BigDecimal::add);
            }
            // Group exposure is self-healed the same way as total/symbol exposure, computed
            // from the same real OPEN positions, so a drift on a correlation-group reservation
            // has the same self-healing safety net as total/symbol exposure does.
            java.util.Map<String, BigDecimal> actualGroupExposure = new java.util.HashMap<>();
            var profileForGroups = riskProfileRepo.findByCredentialId(credential.getId());
            if (profileForGroups.isPresent() && profileForGroups.get().getCorrelationGroups() != null) {
                for (var groupEntry : profileForGroups.get().getCorrelationGroups().entrySet()) {
                    BigDecimal groupTotal = actualSymbolExposure.entrySet().stream()
                        .filter(e -> groupEntry.getValue() != null && groupEntry.getValue().contains(e.getKey()))
                        .map(java.util.Map.Entry::getValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                    actualGroupExposure.put(groupEntry.getKey(), groupTotal);
                }
            }
            exposureReservationService.reconcile(credential.getId(), actualTotalExposure, actualSymbolExposure, actualGroupExposure);
            // This credential's reconciliation attempt just ran to completion, so it is cleared
            // from the per-credential autonomous-trading block list, regardless of whether it
            // was previously on it. Since this method runs every periodic reconciliation cycle
            // for every active credential, a credential that previously failed (e.g. a revoked
            // key) recovers automatically, with no restart, the moment its reconciliation
            // succeeds again — see StartupState.isCredentialTradingEnabled's own javadoc.
            startupState.markCredentialReconciled(credential.getId(), true);
        } catch (Exception e) {
            log.warn("Reconciliation failed for credential {}: {}", credential.getId(), e.getMessage());
            reconciliationFailureCount.incrementAndGet();
            // Only this credential is blocked from autonomous trading until a later
            // reconciliation attempt for it succeeds — every other credential (this user's
            // other credentials, and every other user's) is unaffected by this one failure.
            // See StartupState.isCredentialTradingEnabled's own javadoc.
            startupState.markCredentialReconciled(credential.getId(), false);
        }
    }

    /**
     * Recovers a position left stuck in FLATTENING by a process crash mid-flatten.
     * PositionSafetyService.attemptFlatten transitions OPEN->FLATTENING atomically before its
     * first real exchange call, but a crash between that transition and the flatten's real
     * outcome being recorded would otherwise leave the position silently stuck forever, with no
     * code path watching for it. A normal, successful flatten resolves synchronously within the
     * same request, in seconds; this reconciliation pass runs every 60 seconds, so any position
     * still found in FLATTENING here is already a strong, sufficient signal something went
     * wrong, with no separate time threshold needed.
     *
     * Deliberately does not automatically retry the sell itself. Re-entering the sell flow from
     * a FLATTENING starting state (rather than attemptFlatten's OPEN starting assumption) would
     * either duplicate meaningful parts of that method's logic, or fail outright since the
     * position is already FLATTENING, not OPEN. An automated retry on top of an ambiguous,
     * already-once-failed state risks introducing a new bug rather than being a safe default.
     * Instead, this determines the actual outcome from ground truth and either resolves the
     * position to the correct terminal state or escalates for manual attention, never guessing.
     *
     * Resolution looks up this position's most recent flatten attempt order and queries the
     * exchange for that specific order's real status by clientOrderId — ground truth about this
     * exact order, rather than an inference from account-wide balance that could have moved for
     * unrelated reasons (manual exchange activity, another process, an asset transfer). Balance
     * is used only as a fallback, when no order record can be found at all or the exchange-side
     * lookup itself fails.
     */
    private void recoverStuckFlattening(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        List<Position> stuck = positionRepo.findByCredentialIdAndStatus(credential.getId(), "FLATTENING");
        if (stuck.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        for (Position position : stuck) {
            // Each stuck position here can involve up to two network-bound exchange calls (the
            // order-status lookup, and the balance fallback), so the lock is renewed per
            // position in this loop rather than only once at the start of the method — the
            // same discipline reconcileOpenPositions applies for the same reason.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-loop in recoverStuckFlattening (before position {}) "
                    + "-- another instance may now own this lock. Stopping the rest of this loop rather than continuing to mutate shared "
                    + "state without exclusive ownership confirmed.", credential.getId(), position.getId());
                return;
            }
            try {
                List<Order> flattenOrders = omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "FLATTEN");
                Boolean confirmedFilled = null; // null = no definitive order-level answer, fall back to balance
                // When no OMS Order record exists at all for this position's flatten (the OMS
                // setup itself failed at submission time, a known, accepted non-fatal case in
                // attemptFlatten), this falls back to the minimal, durable FlattenAttempt record
                // instead of skipping straight to the weaker balance heuristic — the same
                // definitive, order-status-level answer the OMS-Order path provides, just
                // sourced from the lighter-weight record when the fuller one never got created.
                String mostRecentFlattenClientOrderId = null;
                if (!flattenOrders.isEmpty() && flattenOrders.get(0).getClientOrderId() != null) {
                    mostRecentFlattenClientOrderId = flattenOrders.get(0).getClientOrderId();
                } else {
                    try {
                        var attempts = flattenAttemptRepo.findByPositionIdOrderByCreatedAtDesc(position.getId());
                        if (!attempts.isEmpty()) {
                            mostRecentFlattenClientOrderId = attempts.get(0).getClientOrderId();
                            log.info("No OMS Order record found for stuck-FLATTENING position {} -- using the durable FlattenAttempt "
                                + "record's own clientOrderId ({}) instead of falling straight to the balance heuristic.",
                                position.getId(), mostRecentFlattenClientOrderId);
                        }
                    } catch (Exception e) {
                        log.warn("Could not query FlattenAttempt records for position {}: {}", position.getId(), e.getMessage());
                    }
                }
                // Both the OMS Order and the FlattenAttempt record can be missing if Mongo was
                // unavailable at exactly the wrong moment while the real exchange sell still
                // succeeded, since attemptFlatten's FlattenAttempt insert is itself best-effort
                // (a naked position is considered more dangerous than an unrecorded emergency
                // sell). To recover without relying on either durable record surviving,
                // attemptFlatten's clientOrderId is deterministic
                // (generateClientOrderId("tv-flat", positionId + ":FLATTEN:" + flattenEpisode +
                // ":" + attempt), always attempt 0 or 1), so recovery can independently re-derive
                // it and query the exchange directly. flattenEpisode is included in the basis
                // since the same position can legitimately go through more than one flatten
                // episode over its lifetime (a recovered partial flatten can put it back to
                // OPEN); this stuck position is already loaded with its current, durably-stamped
                // flattenEpisode value, which is the one attemptFlatten used for this attempt.
                if (mostRecentFlattenClientOrderId == null) {
                    for (int attempt = 1; attempt >= 0; attempt--) {
                        String derivedId = com.tradevision.service.OrderService.generateClientOrderId("tv-flat",
                            position.getId() + ":FLATTEN:" + position.getFlattenEpisode() + ":" + attempt);
                        try {
                            var derivedStatus = adapter.getOrderStatusByClientOrderId(apiKey, apiSecret, credential.getMode(),
                                position.getSymbol(), derivedId);
                            if (derivedStatus != null && derivedStatus.status() != null) {
                                mostRecentFlattenClientOrderId = derivedId;
                                log.info("No OMS Order or FlattenAttempt record found for stuck-FLATTENING position {} -- "
                                    + "independently re-derived and confirmed a real order at the deterministic clientOrderId {} "
                                    + "(attempt {}), instead of falling straight to the balance heuristic.",
                                    position.getId(), derivedId, attempt);
                                break;
                            }
                        } catch (Exception e) {
                            // Genuinely no order exists at this derived id (or the query itself
                            // failed) -- try the other attempt number before giving up.
                        }
                    }
                }
                BigDecimal partialExecutedQty = null;
                BigDecimal partialAvgPrice = null;
                if (mostRecentFlattenClientOrderId != null) {
                    try {
                        var orderStatus = adapter.getOrderStatusByClientOrderId(apiKey, apiSecret, credential.getMode(),
                            position.getSymbol(), mostRecentFlattenClientOrderId);
                        // A broker-reported FILLED status means the requested order filled, not
                        // necessarily that the whole position was sold — a flatten sell capped
                        // below the real position size (e.g. free balance < internal quantity)
                        // can fully fill its own smaller target while the real position still
                        // has a genuine remainder. A FILLED order whose executedQty is less than
                        // the real position size is therefore routed through the same
                        // partial-handling path below used for a PARTIALLY_FILLED status, reusing
                        // its remaining-quantity math. confirmedFilled is only ever set true when
                        // executedQty is genuinely positive and covers the whole real position —
                        // a null, zero, or negative executedQty (which can happen on a malformed
                        // or field-missing broker response) falls through with confirmedFilled
                        // left null, which the "no order-level truth" branch further below
                        // correctly treats as STUCK_FLATTENING_UNKNOWN + halt rather than
                        // inferring a full close.
                        if ("FILLED".equalsIgnoreCase(orderStatus.status())) {
                            if (orderStatus.executedQty() != null && orderStatus.executedQty().signum() > 0
                                    && orderStatus.executedQty().compareTo(position.getQuantity()) < 0) {
                                partialExecutedQty = orderStatus.executedQty();
                                partialAvgPrice = orderStatus.avgPrice();
                            } else if (orderStatus.executedQty() != null && orderStatus.executedQty().signum() > 0
                                    && orderStatus.executedQty().compareTo(position.getQuantity()) >= 0) {
                                confirmedFilled = true;
                            }
                            // executedQty null, zero, or negative: confirmedFilled stays null --
                            // genuinely no usable order-level truth, never inferred as a full close.
                        } else if ("CANCELED".equalsIgnoreCase(orderStatus.status()) || "REJECTED".equalsIgnoreCase(orderStatus.status())
                                || "EXPIRED".equalsIgnoreCase(orderStatus.status())) {
                            confirmedFilled = false;
                        } else if ("PARTIALLY_FILLED".equalsIgnoreCase(orderStatus.status())
                                && orderStatus.executedQty() != null && orderStatus.executedQty().signum() > 0) {
                            partialExecutedQty = orderStatus.executedQty();
                            partialAvgPrice = orderStatus.avgPrice();
                        }
                        // Any other real status (NEW, UNKNOWN, or a PARTIALLY_FILLED with no
                        // usable executedQty) is genuinely ambiguous at the order level --
                        // left null, falls through to the balance-based fallback below
                        // rather than guessing.
                    } catch (Exception e) {
                        log.warn("Could not query exchange order status by clientOrderId for stuck-FLATTENING position {} ({}): {} "
                            + "-- falling back to balance as supporting evidence.", position.getId(), position.getSymbol(), e.getMessage());
                    }
                }

                if (partialExecutedQty != null) {
                    BigDecimal remaining = position.getQuantity().subtract(partialExecutedQty);
                    var symbolRules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
                    boolean isDust = symbolRules.minQty() != null && remaining.compareTo(symbolRules.minQty()) < 0;
                    // closedQuantity accumulates across separate flatten attempts rather than
                    // being overwritten by this leg's own amount, since multiple emergency-flatten
                    // attempts across separate crashes/restarts is a real scenario this recovery
                    // method exists to handle: attempt 1 sells 0.4 (recorded), the process
                    // crashes again mid-attempt-2, attempt 2's recovery sells another 0.2 —
                    // closedQuantity must end up 0.6, the real cumulative total, not 0.2.
                    // Position.closedQuantity is a single running total for the position, not a
                    // per-leg log, so every downstream consumer expects it to be cumulative.
                    BigDecimal cumulativeClosedQuantity = (position.getClosedQuantity() != null ? position.getClosedQuantity() : BigDecimal.ZERO)
                        .add(partialExecutedQty);
                    log.warn("Recovered position {} ({}) stuck in FLATTENING -- the exchange's own order status confirms a PARTIAL "
                        + "fill: {} of {} sold, {} remaining{}. Correcting quantity accounting rather than closing the whole position. "
                        + "Cumulative closedQuantity across all flatten attempts on this position: {}.",
                        position.getId(), position.getSymbol(), partialExecutedQty, position.getQuantity(), remaining,
                        isDust ? " (below minQty, treated as dust)" : "", cumulativeClosedQuantity);
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
                        isDust
                            ? new org.springframework.data.mongodb.core.query.Update()
                                .set("status", "CLOSED_UNVERIFIED_PNL").set("closedAt", LocalDateTime.now())
                                .set("closedQuantity", cumulativeClosedQuantity).set("quantity", BigDecimal.ZERO)
                            : new org.springframework.data.mongodb.core.query.Update()
                                .set("status", "OPEN").set("closedQuantity", cumulativeClosedQuantity)
                                .set("quantity", remaining).set("avgEntryPriceUnverified", true),
                        Position.class);
                    credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                        "STUCK_FLATTENING_RECOVERED_PARTIAL",
                        "Position " + position.getSymbol() + " (" + position.getId() + ") was found stuck in FLATTENING with a "
                            + "confirmed PARTIAL fill (" + partialExecutedQty + " of " + position.getQuantity() + " at avg price "
                            + partialAvgPrice + "; cumulative closedQuantity across all flatten attempts on this position: "
                            + cumulativeClosedQuantity + "). " + (isDust
                                ? "Remaining " + remaining + " is below the symbol's own minQty -- marked closed as dust residual."
                                : "Remaining " + remaining + " is still economically significant -- position quantity corrected, left "
                                    + "OPEN and unprotected. This P&L is approximate (order-status-level executedQty/avgPrice, not a full "
                                    + "fill-by-fill fee breakdown) -- needs manual review."));
                    incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), null, position.getSymbol(),
                        "STUCK_FLATTENING_PARTIAL_UNPROTECTED",
                        "Position " + position.getSymbol() + " (" + position.getId() + ") recovered from stuck FLATTENING with a "
                            + "confirmed partial fill -- " + (isDust ? "remaining quantity is dust and was closed automatically."
                                : "remaining " + remaining + " is still open and genuinely unprotected. Manual review required: this "
                                    + "application does not automatically retry a sell from an already-once-failed, ambiguous state."));
                    if (!isDust) {
                        riskProfileRepo.findByCredentialId(credential.getId()).ifPresent(p -> atomicHaltProfile(p,
                            "Position " + position.getSymbol() + " stuck in FLATTENING recovered with an unprotected partial-fill "
                                + "remainder — halted pending manual review."));
                    }
                    continue;
                }

                BigDecimal freeBalance = null;
                var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
                // Balance is fetched here only as supporting context for whoever investigates
                // manually. "Balance < minQty" cannot mathematically prove this exact flatten
                // order filled, since manual trading, a transfer, another application, another
                // worker, or any other unrelated balance movement could produce the identical
                // observation, so it must never drive the resolvedFilled decision itself.
                if (confirmedFilled == null && rules.baseAsset() != null) {
                    var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
                    freeBalance = balances.stream()
                        .filter(b -> b.asset().equalsIgnoreCase(rules.baseAsset()))
                        .map(com.tradevision.service.broker.dto.AssetBalance::free)
                        .findFirst().orElse(BigDecimal.ZERO);
                }
                // resolvedFilled is never inferred from balance — only a genuine, order-level
                // FILLED confirmation can close a position from this recovery path. No
                // order-level truth at all is treated the same as a confirmed non-fill: never
                // auto-close, always escalate.
                boolean resolvedFilled = confirmedFilled != null && confirmedFilled;
                boolean noOrderLevelTruthAtAll = confirmedFilled == null;
                String evidenceBasis = confirmedFilled != null
                    ? "the exchange's own definitive order status for this specific flatten attempt"
                    : "no order-level truth available at all (no flatten order on record, or the exchange lookup itself failed) -- "
                        + "account balance (" + freeBalance + ") is supporting context only, and per this application's own policy is "
                        + "never sufficient on its own to prove this exact flatten order filled";

                if (resolvedFilled) {
                    log.warn("Recovered position {} ({}) stuck in FLATTENING -- {} confirms the sell went through. Marking closed.",
                        position.getId(), position.getSymbol(), evidenceBasis);
                    // Order matters here: confirm exchange truth first (resolvedFilled, above),
                    // then atomically close the Position, then release the exact reservation
                    // IDs (both release*() helpers are idempotent, so this is safe even if
                    // another reconciliation path already released either one), then mark
                    // ExecutionContext CLOSED. Without releasing the reservations and marking
                    // ExecutionContext closed, both reservation records would stay durably
                    // active against a position that no longer exists.
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("FLATTENING")),
                        new org.springframework.data.mongodb.core.query.Update()
                            .set("status", "CLOSED_UNVERIFIED_PNL").set("closedAt", LocalDateTime.now())
                            .set("closedQuantity", position.getQuantity()).set("quantity", BigDecimal.ZERO),
                        Position.class);
                    releaseSlotForClosedPosition(position); // also marks the ExecutionContext CLOSED (shared close point)
                    releaseExposureForClosedPosition(position);
                    credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "STUCK_FLATTENING_RECOVERED_CLOSED",
                        "Position " + position.getSymbol() + " (" + position.getId() + ") was found stuck in FLATTENING by this "
                            + "reconciliation pass -- " + evidenceBasis + ". Marked closed.");
                } else {
                    String incidentType = noOrderLevelTruthAtAll ? "STUCK_FLATTENING_UNKNOWN" : "STUCK_FLATTENING_UNRESOLVED";
                    log.error("Recovered position {} ({}) stuck in FLATTENING -- {}{} This position is genuinely unprotected. Escalating "
                        + "rather than automatically retrying.", position.getId(), position.getSymbol(), evidenceBasis,
                        noOrderLevelTruthAtAll ? "" : " shows the sell did NOT go through.");
                    incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), null, position.getSymbol(),
                        incidentType,
                        "Position " + position.getSymbol() + " (" + position.getId() + ") was found stuck in FLATTENING by this "
                            + "reconciliation pass, and " + evidenceBasis + " -- this position is genuinely unprotected. Manual review "
                            + "required: this application does not automatically retry a sell from an already-once-failed, ambiguous state, "
                            + "and does not infer a fill from account balance alone when order-level truth is unavailable.");
                    riskProfileRepo.findByCredentialId(credential.getId()).ifPresent(p -> atomicHaltProfile(p,
                        "Position " + position.getSymbol() + " stuck in FLATTENING with an unresolved, unprotected sell — halted pending manual review."));
                }
            } catch (Exception e) {
                log.error("Could not verify exchange state for position {} ({}) stuck in FLATTENING: {} -- leaving it stuck for the next "
                    + "reconciliation pass rather than guessing its real state.", position.getId(), position.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * Recovers orphaned OCO records — OCOs that were successfully placed on the exchange but
     * whose local position update may not have been durably recorded. For each unresolved
     * orphan on this credential: if the position it was meant for still exists, is still OPEN,
     * and has no OCO recorded, the original failure was likely just a transient race, so this
     * re-attempts the same atomic set now, which should succeed this time. Otherwise the
     * position genuinely can't take this OCO back (closed, deleted, or already protected by a
     * different one since), so the remaining question is the exchange's own truth about this
     * specific orphaned OCO: still active and genuinely dangling (escalated, since a live,
     * unattached order affecting this account's real balance is a real risk) or already resolved
     * on its own (no further action needed, since Binance's OCO legs are mutually exclusive, so
     * a naturally filled/canceled leg needs nothing further).
     */
    /**
     * Recovery half of the pre-submission ProtectionAttempt mechanism (see its own class
     * javadoc): finds any attempt genuinely stuck in SUBMITTING, meaning the exchange call never
     * got a chance to resolve it locally, most likely because this process crashed somewhere
     * between creating the record and receiving the exchange's response, and asks the exchange
     * directly, by the deterministic client id this record has always had, what actually
     * happened.
     *
     * If the exchange confirms an OCO genuinely exists and is still active for this client id,
     * but this application's position has no matching ocoOrderListId recorded (the crash
     * happened before that could be set), this escalates for manual investigation rather than
     * attempting full automated re-attachment, since that would need the real orderListId, which
     * is not currently exposed as its own field. Escalating with the exact listClientOrderId is
     * still a meaningful capability, since without it this scenario would leave no durable trace
     * anywhere in this application.
     */
    private void recoverStuckProtectionAttempts(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        var cutoff = LocalDateTime.now().minusMinutes(2);
        // Bounded to RECOVERY_BATCH_SIZE per reconciliation cycle, oldest-first, so the most
        // overdue attempts get processed first and a large backlog on one credential can't make
        // one reconciliation cycle process every stuck record across all of history in a single
        // pass. Any remainder is picked up on the next scheduled pass.
        var pageable = org.springframework.data.domain.PageRequest.of(0, RECOVERY_BATCH_SIZE,
            org.springframework.data.domain.Sort.by("createdAt").ascending());
        List<com.tradevision.model.ProtectionAttempt> stuck = protectionAttemptRepo.findByStatusAndCreatedAtBefore("SUBMITTING", cutoff, pageable);
        stuck = stuck.stream().filter(a -> credential.getId().equals(a.getCredentialId())).toList();
        if (stuck.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        for (var attempt : stuck) {
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-loop in recoverStuckProtectionAttempts (before "
                    + "attempt {}) -- another instance may now own this lock. Stopping the rest of this loop.", credential.getId(), attempt.getId());
                return;
            }
            try {
                Position position = positionRepo.findById(attempt.getPositionId()).orElse(null);
                // A non-null position.getOcoOrderListId() only proves the position has some OCO
                // recorded, not that it is the one this attempt placed or that it is still
                // active — an old OCO can finish (ALL_DONE) just as a new ProtectionAttempt is
                // created for a fresh OCO, and a crash before the new OCO is recorded would
                // otherwise leave the stale old orderListId looking like valid proof of
                // protection. So when the position has a recorded OCO, its status is verified as
                // genuinely still active on the exchange before trusting it; a stale,
                // already-finished OCO falls through to the identity check below instead.
                if (position != null && position.getOcoOrderListId() != null) {
                    boolean recordedOcoStillActive;
                    try {
                        var recordedOcoStatus = adapter.getOcoStatus(apiKey, apiSecret, credential.getMode(), position.getOcoOrderListId());
                        recordedOcoStillActive = recordedOcoStatus != null && !"ALL_DONE".equalsIgnoreCase(recordedOcoStatus.listStatus());
                    } catch (Exception e) {
                        log.warn("Could not verify position {}'s own recorded OCO {} against the exchange while resolving stuck "
                            + "ProtectionAttempt {} -- treating it as unverified rather than assuming it's still active.",
                            position.getId(), position.getOcoOrderListId(), attempt.getId(), e.getMessage());
                        recordedOcoStillActive = false;
                    }
                    if (recordedOcoStillActive) {
                        resolveProtectionAttempt(attempt.getId(), true);
                        continue;
                    }
                    log.warn("ProtectionAttempt {} (position {}, {}): the position's own recorded OCO {} is stale (not genuinely active "
                        + "on the exchange) -- not trusting it as proof of protection for this attempt. Checking this attempt's own "
                        + "client id directly instead.", attempt.getId(), position.getId(), attempt.getSymbol(), position.getOcoOrderListId());
                }
                var ocoStatus = adapter.getOcoStatusByClientOrderId(apiKey, apiSecret, credential.getMode(), attempt.getListClientOrderId());
                if ("REJECT".equalsIgnoreCase(ocoStatus.listStatus()) || ocoStatus.legs().isEmpty()) {
                    // Binance has no record of this client id at all -- the exchange call
                    // genuinely never went through (crashed before it could even be sent, or the
                    // request itself failed outright). Nothing to reconcile.
                    resolveProtectionAttempt(attempt.getId(), false);
                    continue;
                }
                // Resolution order: attempt auto-attach first, auto-cancel second, and escalate
                // to a human only as the last resort when neither can be safely confirmed — see
                // OcoStatusInfo's own class javadoc for how the exchange-assigned orderListId
                // needed for auto-attach is obtained.
                if (ocoStatus.orderListId() == null) {
                    // A real OCO exists (legs are present), but its own exchange-assigned id
                    // still couldn't be extracted -- genuinely can't attach or cancel it without
                    // that id. This is the one case that must still escalate immediately: there
                    // is no safe automated action possible without the id itself.
                    log.error("ProtectionAttempt {} (position {}, {}): a real OCO exists for client id {} but its own exchange-assigned "
                        + "orderListId could not be extracted from the exchange's own response -- cannot safely auto-attach or "
                        + "auto-cancel without it. Escalating.", attempt.getId(), attempt.getPositionId(), attempt.getSymbol(), attempt.getListClientOrderId());
                    incidentService.raiseCritical(attempt.getUserId(), attempt.getCredentialId(), attempt.getPositionId(), null, attempt.getSymbol(),
                        "PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO",
                        "A real OCO exists on the exchange under client id " + attempt.getListClientOrderId() + " for " + attempt.getSymbol()
                            + " (position " + attempt.getPositionId() + "), but its own exchange-assigned orderListId could not be "
                            + "extracted from the exchange's own response. Manual reconciliation required: look up this exact client id "
                            + "directly on Binance (GET /api/v3/orderList with origClientOrderId=" + attempt.getListClientOrderId()
                            + ") to find the real orderListId, then attach or cancel it manually.");
                    continue;
                }

                boolean ocoGenuinelyFinished = "ALL_DONE".equalsIgnoreCase(ocoStatus.listStatus());
                boolean positionCanTakeThisOco = position != null && "OPEN".equals(position.getStatus())
                    && position.getOcoOrderListId() == null && !ocoGenuinelyFinished;

                if (positionCanTakeThisOco) {
                    // The protected quantity is derived from the recovered OCO's own SELL
                    // leg(s), the same real number the normal (non-recovery) OCO path trusts via
                    // OcoOrderResult.actualProtectedQuantity, rather than assumed to equal the
                    // position's own recorded quantity — the exchange's real SELL leg quantity
                    // can differ due to base-asset fees, step-size rounding, or exchange
                    // quantity normalization.
                    java.math.BigDecimal recoveredProtectedQuantity = ocoStatus.legs().stream()
                        .filter(leg -> "SELL".equalsIgnoreCase(leg.side()) && leg.origQty() != null && leg.origQty().signum() > 0)
                        .map(com.tradevision.service.broker.dto.OcoStatusInfo.Leg::origQty)
                        .findFirst()
                        .orElse(position.getQuantity()); // no leg carried a usable origQty at all -- fall back to the position's own figure rather than protecting zero
                    if (recoveredProtectedQuantity.compareTo(position.getQuantity()) != 0) {
                        log.warn("ProtectionAttempt {} (position {}, {}): recovered OCO's own real protected quantity ({}) differs from "
                            + "the position's own recorded quantity ({}) -- using the exchange's real figure, consistent with the normal "
                            + "(non-recovery) OCO path's own actualProtectedQuantity handling.",
                            attempt.getId(), attempt.getPositionId(), attempt.getSymbol(), recoveredProtectedQuantity, position.getQuantity());
                    }
                    // Step 1: auto-attach. The position is OPEN, unprotected, and this OCO is a
                    // real, still-active order this exact ProtectionAttempt was submitting --
                    // the safe, correct action is to durably record it, not ask a human to do
                    // this exact lookup-and-attach by hand.
                    boolean attached = atomicSetOcoPlaced(position, ocoStatus.orderListId(), recoveredProtectedQuantity, null);
                    if (attached) {
                        position.setProtectedQuantity(recoveredProtectedQuantity); // consistent with the normal OCO path's own in-memory update
                        log.warn("ProtectionAttempt {} (position {}, {}): auto-attached the real, active OCO (orderListId {}) found on "
                            + "the exchange under this attempt's own client id {} -- the crash-window scenario this mechanism exists to "
                            + "surface, resolved automatically.", attempt.getId(), attempt.getPositionId(), attempt.getSymbol(),
                            ocoStatus.orderListId(), attempt.getListClientOrderId());
                        credentialService.audit(attempt.getUserId(), attempt.getCredentialId(), credential.getBroker(), "PROTECTION_ATTEMPT_AUTO_ATTACHED",
                            "ProtectionAttempt " + attempt.getId() + " on " + attempt.getSymbol() + " (position " + attempt.getPositionId()
                                + "): automatically attached OCO " + ocoStatus.orderListId() + ", found active on the exchange under this "
                                + "attempt's own client id after a crash prevented recording it initially.");
                        resolveProtectionAttempt(attempt.getId(), true);
                        continue;
                    }
                    // atomicSetOcoPlaced's own failure path already raised OCO_PLACED_BUT_NOT_RECORDED
                    // and its own new orphan record -- falls through to the cancel/halt branches
                    // below, since attach did not actually succeed.
                    log.warn("ProtectionAttempt {} (position {}, {}): auto-attach did not actually succeed (the position likely changed "
                        + "concurrently) -- falling through to the auto-cancel branch.", attempt.getId(), attempt.getPositionId(), attempt.getSymbol());
                }

                if (!ocoGenuinelyFinished) {
                    // Step 2: auto-attach was either unsafe (position closed/already protected)
                    // or didn't actually succeed -- the position no longer needs (or can no
                    // longer safely take) this OCO, so the safe action is to cancel the real,
                    // still-active order rather than leave it dangling indefinitely.
                    try {
                        var cancelResult = adapter.cancelOco(apiKey, apiSecret, credential.getMode(), attempt.getSymbol(), ocoStatus.orderListId());
                        var verifyStatus = adapter.getOcoStatus(apiKey, apiSecret, credential.getMode(), ocoStatus.orderListId());
                        if (cancelResult.success() || "ALL_DONE".equalsIgnoreCase(verifyStatus.listStatus())) {
                            log.warn("ProtectionAttempt {} (position {}, {}): auto-cancelled OCO {} -- the position could not safely take "
                                + "it (closed, already protected by a different OCO, or auto-attach failed), and the exchange itself now "
                                + "confirms it's no longer active.", attempt.getId(), attempt.getPositionId(), attempt.getSymbol(), ocoStatus.orderListId());
                            credentialService.audit(attempt.getUserId(), attempt.getCredentialId(), credential.getBroker(), "PROTECTION_ATTEMPT_AUTO_CANCELLED",
                                "ProtectionAttempt " + attempt.getId() + " on " + attempt.getSymbol() + " (position " + attempt.getPositionId()
                                    + "): automatically cancelled OCO " + ocoStatus.orderListId() + " -- the position could not safely "
                                    + "take it, and the exchange confirms it's no longer active.");
                            resolveProtectionAttempt(attempt.getId(), false);
                            continue;
                        }
                        log.error("ProtectionAttempt {} (position {}, {}): attempted to auto-cancel OCO {}, but the exchange still confirms "
                            + "it as active (verify status {}) -- cancellation could not be confirmed. Escalating.",
                            attempt.getId(), attempt.getPositionId(), attempt.getSymbol(), ocoStatus.orderListId(), verifyStatus.listStatus());
                    } catch (Exception e) {
                        log.error("ProtectionAttempt {} (position {}, {}): auto-cancel of OCO {} itself failed: {} -- cannot confirm the "
                            + "order is genuinely gone. Escalating.", attempt.getId(), attempt.getPositionId(), attempt.getSymbol(),
                            ocoStatus.orderListId(), e.getMessage());
                    }
                }

                // Step 3 (final branch, not the first): neither auto-attach nor auto-cancel
                // could be safely confirmed, so this escalates to a hard halt and a critical
                // incident as the last resort, not the default response.
                log.error("ProtectionAttempt {} (position {}, {}) has been stuck in SUBMITTING for over 2 minutes. A real OCO exists "
                    + "under its own client id {} (orderListId {}, list status {}), but neither auto-attach nor auto-cancel could be "
                    + "safely confirmed. This is exactly the crash-window scenario ProtectionAttempt exists to surface.",
                    attempt.getId(), attempt.getPositionId(), attempt.getSymbol(), attempt.getListClientOrderId(), ocoStatus.orderListId(), ocoStatus.listStatus());
                incidentService.raiseCritical(attempt.getUserId(), attempt.getCredentialId(), attempt.getPositionId(), null, attempt.getSymbol(),
                    "PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO",
                    "A real OCO (orderListId " + ocoStatus.orderListId() + ") exists on the exchange under client id "
                        + attempt.getListClientOrderId() + " for " + attempt.getSymbol() + " (position " + attempt.getPositionId()
                        + "), but this application could neither safely auto-attach it to the position nor safely confirm cancelling it. "
                        + "Manual reconciliation required directly on the exchange.");
                // Deliberately NOT marking this resolved -- it stays SUBMITTING so a human
                // fixing it manually (or a future reconciliation pass) can still find it, same
                // "stays unresolved until the dangerous condition is actually gone" principle as
                // OrphanedOco's own escalation branch above.
            } catch (Exception e) {
                log.warn("Could not check stuck ProtectionAttempt {} (position {}, {}) against the exchange: {} -- leaving it in "
                    + "SUBMITTING for the next reconciliation pass rather than guessing.",
                    attempt.getId(), attempt.getPositionId(), attempt.getSymbol(), e.getMessage());
            }
        }
    }

    private void recoverOrphanedOcos(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        var pageable = org.springframework.data.domain.PageRequest.of(0, RECOVERY_BATCH_SIZE,
            org.springframework.data.domain.Sort.by("createdAt").ascending());
        List<com.tradevision.model.OrphanedOco> orphans = orphanedOcoRepo.findByCredentialIdAndResolvedFalse(credential.getId(), pageable);
        if (orphans.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        for (com.tradevision.model.OrphanedOco orphan : orphans) {
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-loop in recoverOrphanedOcos (before orphan {}) -- "
                    + "another instance may now own this lock. Stopping the rest of this loop rather than continuing to mutate shared "
                    + "state without exclusive ownership confirmed.", credential.getId(), orphan.getId());
                return;
            }
            try {
                Position position = positionRepo.findById(orphan.getPositionId()).orElse(null);
                // Before auto-attaching an orphaned OCO to a position, its identity is verified
                // against the exchange's live status: the OCO must still be active, every leg
                // must be a SELL, and the leg quantity must not meaningfully exceed the
                // position's own quantity. "OCO belongs to this credential" is already
                // structurally guaranteed, since this adapter/apiKey/apiSecret are scoped to one
                // credential and no code path here could return another credential's OCO — the
                // remaining checks (symbol, side, quantity, active status) are what actually
                // need verifying before trusting an auto-attach with real money.
                var ocoStatus = adapter.getOcoStatus(apiKey, apiSecret, credential.getMode(), orphan.getOcoOrderListId());
                if (position != null && "OPEN".equals(position.getStatus()) && position.getOcoOrderListId() == null) {
                    java.util.List<String> verificationFailures = new java.util.ArrayList<>();
                    if ("ALL_DONE".equalsIgnoreCase(ocoStatus.listStatus())) {
                        verificationFailures.add("OCO is no longer active on the exchange (listStatus=" + ocoStatus.listStatus() + ")");
                    }
                    boolean anyLegMatchesSymbol = ocoStatus.legs().isEmpty(); // an empty leg list can't be checked either way -- don't block on a symbol this application has no leg data for
                    boolean allLegsAreSell = true;
                    java.math.BigDecimal maxLegQuantity = java.math.BigDecimal.ZERO;
                    for (var leg : ocoStatus.legs()) {
                        if (!"SELL".equalsIgnoreCase(leg.side())) allLegsAreSell = false;
                        if (leg.origQty() != null && leg.origQty().compareTo(maxLegQuantity) > 0) maxLegQuantity = leg.origQty();
                    }
                    if (!ocoStatus.legs().isEmpty() && !allLegsAreSell) {
                        verificationFailures.add("at least one OCO leg is not SELL -- this OCO cannot be a valid exit protection for a long position");
                    }
                    if (position.getQuantity() != null && maxLegQuantity.signum() > 0
                        && maxLegQuantity.compareTo(position.getQuantity().multiply(BigDecimal.valueOf(1.02))) > 0) {
                        // Allow a small (2%) tolerance for legitimate step-size/normalization
                        // differences (see OcoStatusInfo.Leg.origQty's own field javadoc) -- only
                        // a genuinely larger mismatch (this OCO protects meaningfully MORE than
                        // this position actually holds) is treated as a real identity concern.
                        verificationFailures.add("OCO quantity (" + maxLegQuantity + ") exceeds the position's own actual quantity ("
                            + position.getQuantity() + ") by more than a reasonable tolerance");
                    }
                    if (!verificationFailures.isEmpty()) {
                        log.error("Orphaned OCO {} (position {}, {}): auto-attach verification failed -- {}. NOT auto-attaching; "
                            + "escalating for manual review instead.", orphan.getOcoOrderListId(), orphan.getPositionId(), orphan.getSymbol(),
                            String.join("; ", verificationFailures));
                        incidentService.raiseCritical(orphan.getUserId(), orphan.getCredentialId(), orphan.getPositionId(), null, orphan.getSymbol(),
                            "ORPHANED_OCO_AUTO_ATTACH_VERIFICATION_FAILED",
                            "Orphaned OCO " + orphan.getOcoOrderListId() + " for position " + orphan.getPositionId() + " (" + orphan.getSymbol()
                                + ") looked eligible for auto-attach (position OPEN, no OCO recorded), but failed exchange-side identity "
                                + "verification: " + String.join("; ", verificationFailures) + ". NOT auto-attached -- manual review required.");
                        continue;
                    }
                    log.warn("Orphaned OCO {} (position {}, {}): the position is still OPEN and still has no OCO recorded -- the "
                        + "original failure was a transient race, not a genuinely lost update. Re-attempting the same atomic set now.",
                        orphan.getOcoOrderListId(), orphan.getPositionId(), orphan.getSymbol());
                    // Deliberately passes null, not orphan.getId(), even though this orphan
                    // already exists: this call site immediately calls markOrphanResolved(orphan,
                    // ...) right below regardless of outcome, which itself does its own
                    // orphanedOcoRepo.save(orphan) -- reusing this orphan's own id here would mean
                    // atomicSetOcoPlaced's own delete-on-success deletes it, then
                    // markOrphanResolved's save() immediately re-creates the same document, just
                    // to mark it resolved. Not harmful, but wasteful and confusing. This call site
                    // never had the crash-window problem createOrphanForOco's own new callers
                    // exist to close anyway -- it's already re-processing an existing, durable
                    // orphan record, not placing a fresh, previously-untracked OCO.
                    boolean attached = atomicSetOcoPlaced(position, orphan.getOcoOrderListId(), orphan.getProtectedQuantity(), null);
                    if (attached) {
                        markOrphanResolved(orphan, "Re-attached to position " + orphan.getPositionId()
                            + " on a later reconciliation pass -- the position was still OPEN and unprotected.");
                    } else {
                        // If the update genuinely failed (e.g. the position closed concurrently
                        // between the read above and the update), atomicSetOcoPlaced already
                        // raised OCO_PLACED_BUT_NOT_RECORDED and created its own new orphan
                        // record for it. This original orphan record is left unresolved rather
                        // than marked resolved=true, so the still-genuinely-active exchange OCO
                        // doesn't silently disappear from future recovery passes.
                        log.warn("Re-attachment of orphaned OCO {} to position {} did not actually succeed (the position likely closed "
                            + "concurrently) -- leaving this orphan record unresolved so a future reconciliation pass keeps finding it.",
                            orphan.getOcoOrderListId(), orphan.getPositionId());
                    }
                    continue;
                }

                // The position can't take this OCO back (closed, gone, or already protected by
                // a different one) -- the only remaining question is this specific OCO's own
                // real state on the exchange.
                if (!"ALL_DONE".equalsIgnoreCase(ocoStatus.listStatus())) {
                    // Stays resolved=false so this orphan keeps being found and re-checked by
                    // every future pass until the exchange confirms it's actually done —
                    // "resolved" means the dangerous condition is gone, not merely that this
                    // application has looked at it once. Re-alerting is deduplicated: a fresh
                    // critical incident only fires on first escalation or once escalatedAt is
                    // over an hour stale, not on every single pass for the same known problem.
                    boolean shouldAlert = !orphan.isEscalated()
                        || orphan.getEscalatedAt() == null
                        || orphan.getEscalatedAt().isBefore(LocalDateTime.now().minusHours(1));
                    log.error("Orphaned OCO {} (position {}, {}) is STILL ACTIVE on the exchange, and this application's own database "
                        + "has no position to attach it to. This is a real, live order that can still affect this account's own actual "
                        + "balance.{}", orphan.getOcoOrderListId(), orphan.getPositionId(), orphan.getSymbol(),
                        shouldAlert ? " Escalating for manual action." : " Already escalated recently -- not re-alerting.");
                    if (shouldAlert) {
                        incidentService.raiseCritical(orphan.getUserId(), orphan.getCredentialId(), orphan.getPositionId(), null, orphan.getSymbol(),
                            "ORPHANED_OCO_STILL_ACTIVE",
                            "OCO " + orphan.getOcoOrderListId() + " for " + orphan.getSymbol() + " is still active on the exchange (list "
                                + "status " + ocoStatus.listStatus() + "), but this application has no position it can be durably attached "
                                + "to. Manual action required directly on the exchange: this application will not automatically cancel or "
                                + "otherwise act on an order it cannot durably track. This orphan will keep being re-checked on every future "
                                + "reconciliation pass until the exchange itself confirms it's no longer active.");
                        orphan.setEscalated(true);
                        orphan.setEscalatedAt(LocalDateTime.now());
                        orphanedOcoRepo.save(orphan);
                    }
                } else {
                    log.info("Orphaned OCO {} (position {}, {}) is no longer active on the exchange (list status ALL_DONE) -- no further "
                        + "action needed.", orphan.getOcoOrderListId(), orphan.getPositionId(), orphan.getSymbol());
                    markOrphanResolved(orphan, "Confirmed no longer active on the exchange (list status ALL_DONE) -- resolved on its own, "
                        + "no action needed.");
                }
            } catch (Exception e) {
                log.warn("Could not resolve orphaned OCO {} (position {}, {}): {} -- leaving it unresolved for the next reconciliation "
                    + "pass rather than guessing.", orphan.getOcoOrderListId(), orphan.getPositionId(), orphan.getSymbol(), e.getMessage());
            }
        }
    }

    private void markOrphanResolved(com.tradevision.model.OrphanedOco orphan, String resolution) {
        orphan.setResolved(true);
        orphan.setResolvedAt(LocalDateTime.now());
        orphan.setResolution(resolution);
        orphanedOcoRepo.save(orphan);
    }

    /**
     * Enforces a strategy plan's configured maximum hold time, kept separate from the plan's
     * timeframe: e.g. a 15-minute timeframe means new opportunities are evaluated every
     * 15-minute candle, while a 4-hour max hold means a position is not held longer than 4
     * hours regardless of timeframe. A position whose plan sets maxHoldMinutes, still OPEN past
     * its entry time plus that duration, is emergency-flattened with a specific reason
     * (MAX_HOLD_TIME) rather than silently left open. Runs every reconciliation pass (currently
     * 60 seconds), so a hold-time breach is caught within one pass interval, not exactly on the
     * dot.
     *
     * A position with no planId (pre-multi-plan, or manually triggered) or whose plan has no
     * maxHoldMinutes set is left alone entirely — this rule is opt-in per plan, so it never
     * silently changes behavior for a position whose plan never configured it.
     */
    private void enforceMaxHoldTime(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        List<Position> openPositions = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");
        if (openPositions.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        for (Position position : openPositions) {
            if (position.getPlanId() == null || position.getOpenedAt() == null) continue;
            com.tradevision.model.StrategyPlan plan;
            try {
                plan = strategyPlanRepo.findById(position.getPlanId()).orElse(null);
            } catch (Exception e) {
                log.warn("Could not look up strategy plan {} for position {} while enforcing max-hold-time (non-fatal, skipping this "
                    + "position this pass): {}", position.getPlanId(), position.getId(), e.getMessage());
                continue;
            }
            if (plan == null || plan.getMaxHoldMinutes() == null || plan.getMaxHoldMinutes() <= 0) continue;

            LocalDateTime deadline = position.getOpenedAt().plusMinutes(plan.getMaxHoldMinutes());
            if (LocalDateTime.now().isBefore(deadline)) continue;

            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} immediately before max-hold-time flatten (position {}) "
                    + "-- another instance may now own this lock. Stopping the rest of this loop.", credential.getId(), position.getId());
                return;
            }
            log.warn("Position {} ({}) has exceeded its own plan's maxHoldMinutes ({}) -- opened at {}, deadline was {}. "
                + "Emergency-flattening per the plan's own configured exit policy.",
                position.getId(), position.getSymbol(), plan.getMaxHoldMinutes(), position.getOpenedAt(), deadline);
            try {
                // Max-hold-time is a routine, plan-configured exit, not a protection failure —
                // uses exitPosition() so a clean close doesn't halt the whole profile or raise a
                // CRITICAL incident.
                positionSafetyService.exitPosition(credential, adapter, apiKey, apiSecret, position,
                    "MAX_HOLD_TIME: plan \"" + plan.getName() + "\" (" + plan.getId() + ") configured a maximum hold of "
                        + plan.getMaxHoldMinutes() + " minutes; this position opened at " + position.getOpenedAt()
                        + " and was still open past that deadline.");
            } catch (Exception e) {
                log.error("Max-hold-time emergency-flatten failed for position {} ({}): {} -- will be retried on the next reconciliation "
                    + "pass.", position.getId(), position.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * Enforces a plan's "Risk Emergency Exit" option: TP/SL are not the only way out of a
     * position — a plan can opt in (exitOnRiskEmergency, default true) to also flatten its own
     * open positions as soon as the account-level risk engine halts trading. This is more
     * aggressive behavior than the existing account-level kill switch: halt()/tradingHalted only
     * stops new trades from being claimed, it does not touch positions already open
     * (RiskProfileService.halt() itself never calls emergencyFlatten). A plan with this flag set
     * actively closes its own open positions the moment the account is found halted, rather than
     * leaving them open indefinitely under a kill switch that was never designed to flatten
     * anything on its own.
     */
    private void enforceRiskEmergencyExit(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        var profileOpt = riskProfileRepo.findByCredentialId(credential.getId());
        if (profileOpt.isEmpty()) return;
        RiskProfile profile = profileOpt.get();
        if (!profile.isTradingHalted() && !profile.isAutoTradeHalted()) return; // account not halted -- nothing for this check to do

        List<Position> openPositions = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");
        if (openPositions.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        for (Position position : openPositions) {
            if (position.getPlanId() == null) continue; // opt-in per plan -- no plan means no risk-emergency-exit rule to apply
            com.tradevision.model.StrategyPlan plan;
            try {
                plan = strategyPlanRepo.findById(position.getPlanId()).orElse(null);
            } catch (Exception e) {
                log.warn("Could not look up strategy plan {} for position {} while enforcing risk-emergency-exit (non-fatal, skipping "
                    + "this position this pass): {}", position.getPlanId(), position.getId(), e.getMessage());
                continue;
            }
            if (plan == null || !plan.isExitOnRiskEmergency()) continue;

            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} immediately before risk-emergency-exit flatten (position "
                    + "{}) -- another instance may now own this lock. Stopping the rest of this loop.", credential.getId(), position.getId());
                return;
            }
            log.warn("Position {} ({}) belongs to plan \"{}\" with exitOnRiskEmergency enabled, and this account is currently halted "
                + "(tradingHalted={}, autoTradeHalted={}) -- emergency-flattening per the plan's own configured exit policy.",
                position.getId(), position.getSymbol(), plan.getName(), profile.isTradingHalted(), profile.isAutoTradeHalted());
            try {
                positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                    "RISK_EMERGENCY_EXIT: plan \"" + plan.getName() + "\" (" + plan.getId() + ") has exitOnRiskEmergency enabled, and "
                        + "this account is currently halted (tradingHalted=" + profile.isTradingHalted() + ", autoTradeHalted="
                        + profile.isAutoTradeHalted() + ").");
            } catch (Exception e) {
                log.error("Risk-emergency-exit flatten failed for position {} ({}): {} -- will be retried on the next reconciliation "
                    + "pass.", position.getId(), position.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * Enforces end-of-session position closure, scoped per Strategy Plan rather than as an
     * account-wide flatten: e.g. at a plan's configured session end, that plan's positions can
     * close while another plan's positions stay open, depending on each plan's own
     * endOfSessionAction. Every check here is per-position, keyed off that position's own plan.
     *
     * Uses StrategyPlanService.isWithinSession — the same method the scanner uses to decide
     * whether to stop new entries — so the two can never disagree about where the session
     * boundary actually is. A plan with endOfSessionAction=KEEP_OPEN (or sessionMode=ALWAYS_ON,
     * the default) is never touched by this method.
     *
     * Reuses exitPosition/emergencyFlatten, the same hardened mechanism enforceMaxHoldTime and
     * enforceRiskEmergencyExit use, which never infers a full close from a partial fill and
     * instead escalates rather than guessing — reusing it here means end-of-session inherits
     * that safety property rather than needing its own separate implementation of it.
     */
    private void enforceEndOfSession(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        List<Position> openPositions = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");
        if (openPositions.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        for (Position position : openPositions) {
            if (position.getPlanId() == null) continue; // no plan means no session configuration to enforce
            com.tradevision.model.StrategyPlan plan;
            try {
                plan = strategyPlanRepo.findById(position.getPlanId()).orElse(null);
            } catch (Exception e) {
                log.warn("Could not look up strategy plan {} for position {} while enforcing end-of-session (non-fatal, skipping this "
                    + "position this pass): {}", position.getPlanId(), position.getId(), e.getMessage());
                continue;
            }
            if (plan == null || plan.getSessionMode() == com.tradevision.model.SessionMode.ALWAYS_ON) continue;
            if (plan.getEndOfSessionAction() != com.tradevision.model.EndOfSessionAction.CLOSE_POSITIONS) continue;
            // A broken session config means the position is left exactly as it is, neither
            // flattened nor assumed safe, with a critical incident raised so a human notices
            // and fixes the plan's configuration, rather than this method guessing in either
            // direction. See StrategyPlanService.isSessionConfigValid's own javadoc.
            if (!strategyPlanService.isSessionConfigValid(plan)) {
                log.error("Plan \"{}\" ({}) has an invalid/incomplete session configuration (mode={}, start={}, end={}, timezone={}, "
                    + "days={}) -- position {} left untouched rather than guessing whether to close it. Manual review required.",
                    plan.getName(), plan.getId(), plan.getSessionMode(), plan.getSessionStart(), plan.getSessionEnd(),
                    plan.getSessionTimezone(), plan.getSessionDays(), position.getId());
                incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), null, position.getSymbol(),
                    "INVALID_SESSION_CONFIG",
                    "Plan \"" + plan.getName() + "\" (" + plan.getId() + ") has an invalid or incomplete session configuration and "
                        + "cannot be evaluated for end-of-session closure. Position " + position.getId() + " was left untouched -- "
                        + "manual review of this plan's own session settings is required.");
                continue;
            }
            if (strategyPlanService.isWithinSession(plan)) continue; // still inside the session -- nothing to do

            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} immediately before end-of-session flatten (position {}) "
                    + "-- another instance may now own this lock. Stopping the rest of this loop.", credential.getId(), position.getId());
                return;
            }
            log.warn("Position {} ({}) belongs to plan \"{}\" ({}), whose own configured trading session has ended -- "
                + "emergency-flattening per endOfSessionAction=CLOSE_POSITIONS. This is scoped to this exact plan only, not an "
                + "account-wide flatten.", position.getId(), position.getSymbol(), plan.getName(), plan.getId());
            try {
                // End-of-session is a routine, plan-configured exit, not a protection failure —
                // uses exitPosition() so a clean close doesn't halt the whole profile or raise a
                // CRITICAL incident.
                positionSafetyService.exitPosition(credential, adapter, apiKey, apiSecret, position,
                    "END_OF_SESSION: plan \"" + plan.getName() + "\" (" + plan.getId() + ") has endOfSessionAction=CLOSE_POSITIONS, and "
                        + "this plan's own configured trading session (" + plan.getSessionStart() + "-" + plan.getSessionEnd() + " "
                        + plan.getSessionTimezone() + ") has ended.");
            } catch (Exception e) {
                log.error("End-of-session flatten failed for position {} ({}): {} -- will be retried on the next reconciliation pass "
                    + "rather than falsely marking it closed.", position.getId(), position.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * Reconciles pending entry orders against real broker status. Each entry order in this
     * method's loop can involve a real, network-bound getOrderStatus call, so the reconciliation
     * lock is renewed before every order in the loop rather than only once at the start, the
     * same discipline reconcileOpenPositions applies for the same reason.
     */
    private void reconcileEntryOrders(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        // Queries are scoped to this credential and status set at the database level via a
        // compound index, rather than loading the full order history and filtering in Java,
        // which would not scale once a user has accumulated many orders.
        //
        // "NEW"/"PARTIALLY_FILLED" (the broker's raw status strings) map to
        // ACKNOWLEDGED/PARTIALLY_FILLED in this OMS's own formal state machine.
        //
        // The query excludes orderRole "OCO_EXIT": an OCO_EXIT order's OMS record stores the
        // broker's orderListId in this same brokerOrderId field once recordOcoPlacementResult()
        // acknowledges it (see that method's own javadoc), and that id is not a valid argument
        // for adapter.getOrderStatus(..., brokerOrderId) — that call expects a real order id,
        // not a list id. orderRole is nullable on records written before this field existed
        // (see Order.orderRole's own field comment), and those legacy null-role records are
        // still real entry orders that must keep being reconciled, so this only excludes the one
        // role value known to hold a list id rather than requiring an exact "ENTRY" match. OCO
        // legs get their own dedicated reconciliation elsewhere in this class
        // (recordOcoFillResult/recordOcoCancelResult, driven off Position's own ocoOrderListId
        // and the user-data-stream/reconcileOpenPositions paths) — they are never polled by this
        // method.
        List<Order> pendingEntries = omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(
                credential.getId(), List.of(OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED)).stream()
            .filter(o -> o.getBrokerOrderId() != null)
            .filter(o -> !"OCO_EXIT".equals(o.getOrderRole()))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        // This query requires side == "BUY" at the database level, so a flatten/exit SELL order
        // is never rediscovered here as an unresolved "entry" — that would otherwise create a
        // phantom-position loop. Bounded to the last 2 hours so this never turns into
        // re-scanning this credential's entire order history on every reconciliation pass; an
        // order still missing a Position after this window needs a human, not an indefinite
        // hot-path retry.
        pendingEntries.addAll(omsOrderRepo.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                credential.getId(), "BUY", OrderStatus.FILLED, LocalDateTime.now().minusHours(2)).stream()
            .filter(o -> o.getBrokerOrderId() != null)
            .toList());
        if (pendingEntries.isEmpty()) return;

        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        for (Order order : pendingEntries) {
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-loop in reconcileEntryOrders (before order {}) -- "
                    + "another instance may now own this lock. Stopping the rest of this loop rather than continuing to mutate shared "
                    + "state without exclusive ownership confirmed.", credential.getId(), order.getId());
                return;
            }
            try {
                OrderStatusInfo status = adapter.getOrderStatus(apiKey, apiSecret, credential.getMode(), order.getSymbol(), order.getBrokerOrderId());
                // Order.status is this OMS's own formal OrderStatus enum, mapped from the raw
                // broker status string via mapBrokerStatusToOrderStatus. The update is routed
                // through orderService.reconcileStatusFromBroker so the OMS's own
                // transition-legality check and atomic conditional update are respected, rather
                // than mutating order.status directly and saving the whole document. A lost race
                // or an illegal broker-reported status transition is caught below and logged,
                // the same way every other non-fatal, retry-next-cycle failure in this codebase
                // is handled rather than crashing the whole reconciliation pass over one order.
                OrderStatus mappedStatus = mapBrokerStatusToOrderStatus(status.status());
                BigDecimal reconciledAvgPrice = (status.executedQty() != null && status.executedQty().signum() > 0)
                    ? status.avgPrice() : null;
                try {
                    orderService.reconcileStatusFromBroker(order, mappedStatus, reconciledAvgPrice);
                } catch (IllegalStateException e) {
                    log.warn("Could not reconcile order {} ({}) to broker-reported status {} via the OMS ({}) -- leaving its stored "
                            + "status unchanged rather than writing an unvalidated one; will be retried on the next reconciliation pass.",
                        order.getId(), order.getSymbol(), mappedStatus, e.getMessage());
                }

                // Spot MARKET orders resolve synchronously and don't accumulate further fills
                // later, but the recovery path (verify-before-declaring-failure after a lost
                // response) can query a status snapshot that turns out to differ from what the
                // Position was originally sized with. If the confirmed executedQty doesn't match
                // the linked Position's quantity, the Position is corrected and its protection
                // resized, rather than just updating the order row and leaving the
                // position/OCO silently wrong.
                // A manual test order (placed via OrderExecutionService.placeTestOrder,
                // clientOrderId prefixed "manual-") has no linked Position by design, and must
                // be distinguished from a genuinely orphaned bot entry so it isn't adopted as a
                // late-discovered bot position and flattened. isManualTestOrder() lets this loop
                // skip manual orders entirely; bot entries (clientOrderId "tv-s-...") are
                // unaffected and still get recovered normally.
                if (status.executedQty() != null && status.executedQty().signum() > 0 && !isManualTestOrder(order)) {
                    syncPositionQuantityIfMismatched(credential, adapter, apiKey, apiSecret, order, status.executedQty(), lockGeneration);
                }
            } catch (Exception e) {
                log.warn("Could not fetch order status for {} ({}): {}", order.getSymbol(), order.getBrokerOrderId(), e.getMessage());
            }
        }
    }

    /**
     * Maps the broker's raw order-status string to this OMS's formal OrderStatus enum. Covers
     * Binance's documented status values (NEW, PARTIALLY_FILLED, FILLED, CANCELED,
     * PENDING_CANCEL, REJECTED, EXPIRED) plus this adapter's own "UNKNOWN" fallback when the
     * field is missing from the response. A genuinely unrecognized value maps to UNKNOWN rather
     * than silently defaulting to something that could be misread as a normal, resolved state.
     */
    private OrderStatus mapBrokerStatusToOrderStatus(String brokerStatus) {
        if (brokerStatus == null) return OrderStatus.UNKNOWN;
        return switch (brokerStatus) {
            case "NEW" -> OrderStatus.ACKNOWLEDGED;
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED", "CANCELLED" -> OrderStatus.CANCELLED;
            case "PENDING_CANCEL" -> OrderStatus.CANCEL_PENDING;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "EXPIRED" -> OrderStatus.EXPIRED;
            default -> OrderStatus.UNKNOWN;
        };
    }

    /** Manual test orders carry a "manual-" client order id (OrderExecutionService.placeTestOrder); bot entries use "tv-s-...". */
    static boolean isManualTestOrder(Order order) {
        return order != null && order.getClientOrderId() != null && order.getClientOrderId().startsWith("manual-");
    }

    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly —
    // money-consequential logic deserves a focused test without standing up the whole
    // reconcileCredential() chain.
    void syncPositionQuantityIfMismatched(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                                    Order order, BigDecimal confirmedExecutedQty, long lockGeneration) {
        // Scoped by credentialId/symbol (both already on the Order this reconciliation pass is
        // processing) since broker order IDs are only unique within a credential and symbol —
        // a same-numbered entry order id belonging to a different credential or symbol must
        // never be matched to the wrong Position here.
        Optional<Position> positionOpt = positionRepo.findByCredentialIdAndSymbolAndEntryOrderId(
            order.getCredentialId(), order.getSymbol(), order.getBrokerOrderId());
        if (positionOpt.isEmpty()) {
            // AutoTradeService correctly refuses to create a Position when executedQty comes
            // back 0/null at order-placement time, but the order row is still saved. If the
            // order subsequently fills on the exchange's side with no Position ever created,
            // real coins would otherwise sit on the exchange with no Position, no protection,
            // and nothing watching them — so a late-discovered fill with no matching Position
            // creates one here instead of being left unaccounted for.
            createPositionForLateDiscoveredFill(credential, adapter, apiKey, apiSecret, order, confirmedExecutedQty, lockGeneration);
            return;
        }
        Position position = positionOpt.get();
        if (!"OPEN".equals(position.getStatus())) return;
        if (position.getQuantity().compareTo(confirmedExecutedQty) == 0) return; // already correct, nothing to do

        BigDecimal oldQty = position.getQuantity();
        BigDecimal oldAvgEntry = position.getAvgEntryPrice();

        // The real fills for this order are pulled to compute the actual quantity-weighted
        // average entry price and total commission, the same way a broker statement would —
        // updating quantity alone and leaving avgEntryPrice at whatever the first partial fill
        // priced it at would leave every P&L, exposure, and risk calculation downstream of this
        // position silently wrong.
        BigDecimal newAvgEntry = oldAvgEntry;
        BigDecimal newEntryFee = position.getEntryFeeQuote();
        boolean priceVerified = false;
        // confirmedExecutedQty here is the gross quantity from order status and needs the same
        // net-of-commission treatment as createPositionForLateDiscoveredFill before being
        // trusted as the actual position size, or the re-placed OCO below would size itself
        // against coins the wallet doesn't actually have.
        BigDecimal netQuantity = confirmedExecutedQty;
        try {
            List<com.tradevision.service.broker.dto.Fill> fills =
                adapter.getFillsForOrder(apiKey, apiSecret, credential.getMode(), position.getSymbol(), order.getBrokerOrderId());
            if (!fills.isEmpty()) {
                BigDecimal totalQty = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
                for (var f : fills) { totalQty = totalQty.add(f.qty()); totalCost = totalCost.add(f.qty().multiply(f.price())); }
                if (totalQty.signum() > 0) {
                    newAvgEntry = totalCost.divide(totalQty, 8, java.math.RoundingMode.HALF_UP);
                    var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
                    newEntryFee = positionSafetyService.sumCommissionInQuoteAsset(fills, rules.quoteAsset());
                    var netResult = positionSafetyService.computeNetQuantity(confirmedExecutedQty, fills, rules.baseAsset());
                    netQuantity = netResult.netBaseQty();
                    priceVerified = true;
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch fills to recompute avg entry price for position {}: {}", position.getId(), e.getMessage());
        }

        // The quantity is independently confirmed (order status, not fills) and always safe to
        // apply. The price is only "corrected" when fills were actually retrieved — if that
        // lookup failed, this records that honestly instead of quietly reusing the old value
        // while claiming success in the audit trail.
        position.setQuantity(netQuantity);
        position.setAvgEntryPrice(newAvgEntry);
        position.setEntryFeeQuote(newEntryFee);
        position.setAvgEntryPriceUnverified(!priceVerified);
        // Uses an atomic conditional update scoped to status=OPEN rather than a plain
        // whole-document save, consistent with every other position mutation in this class.
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
            new org.springframework.data.mongodb.core.query.Update()
                .set("quantity", netQuantity).set("avgEntryPrice", newAvgEntry)
                .set("entryFeeQuote", newEntryFee).set("avgEntryPriceUnverified", !priceVerified),
            Position.class);
        if (priceVerified) {
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_QUANTITY_CORRECTED",
                "Position " + position.getId() + " on " + position.getSymbol() + " quantity corrected from " + oldQty
                    + " to " + netQuantity + " (net of any base-asset commission), avgEntryPrice " + oldAvgEntry + " -> " + newAvgEntry
                    + " after order status reconciliation.");
        } else {
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_QUANTITY_CORRECTED_PRICE_UNVERIFIED",
                "Position " + position.getId() + " on " + position.getSymbol() + " quantity corrected from " + oldQty
                    + " to " + confirmedExecutedQty + ", but the average entry price could NOT be independently verified "
                    + "(fills lookup failed) — still using the prior value of " + oldAvgEntry + ", flagged unverified. "
                    + "P&L for this position is provisional until reconciled.");
        }

        if (position.getOcoOrderListId() == null) return; // unprotected position — nothing sized wrong to fix

        // Renewed immediately before cancelOco, the first real exchange-mutating call in this
        // method following the quantity-correction mutation above.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before cancelOco in "
                + "syncPositionQuantityIfMismatched (position {}) -- another instance may now own this lock. Aborting before mutating.",
                credential.getId(), position.getId());
            return;
        }
        // The existing OCO protects the OLD (wrong) quantity — it must be replaced, not left mismatched.
        OcoOrderResult cancelResult = adapter.cancelOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(), position.getOcoOrderListId());
        // Updates the OCO's OMS Order record for the cancel too, not just placement. Non-fatal
        // by design, like every other OMS bookkeeping call in this class — a bug here must never
        // affect the real cancel/re-place flow this method is responsible for.
        try {
            orderService.recordOcoCancelResult(position.getCredentialId(), position.getSymbol(), position.getOcoOrderListId(), cancelResult);
        } catch (Exception e) {
            log.warn("OMS recordOcoCancelResult failed for OCO {} (non-fatal, additive record only): {}", position.getOcoOrderListId(), e.getMessage());
        }
        if (!cancelResult.success()) {
            // NOT emergency-flattening here: cancellation failing likely means the old OCO is
            // still active, still protecting SOME quantity (just not the corrected one) — this
            // is under-protected, not naked. Flattening blind could collide with a still-live
            // OCO order. Flagged CRITICAL for manual review rather than guessing at an action.
            //
            // Halts further autonomous trading on this credential (rather than flattening, for
            // the same reason as above) while the protection quantity stays mismatched against
            // the real position: new positions stop being opened while the existing, still-live
            // OCO is left alone for manual reconciliation.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_FAILED",
                "CRITICAL: could not cancel mis-sized OCO " + position.getOcoOrderListId() + " on " + position.getSymbol()
                    + " after a quantity correction (" + oldQty + " -> " + confirmedExecutedQty + "): " + cancelResult.errorMessage()
                    + " — MANUAL INTERVENTION REQUIRED, protection quantity does not match actual position.");
            haltForResizeFailure(credential, position);
            return;
        }

        if (order.getStopLossTriggerPrice() == null || order.getTakeProfitPrice() == null) {
            // The old OCO is now cancelled and there is no price data to re-place it with — this
            // position is genuinely naked, so it is flattened rather than left unprotected.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_INCOMPLETE",
                "Mis-sized OCO on " + position.getSymbol() + " was cancelled but no SL/TP price was recorded to re-place it with — "
                    + "emergency-flattening rather than leaving it naked.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "No SL/TP price recorded to resize protection with after a partial-fill quantity correction.");
            return;
        }

        BigDecimal stopLimit = order.getStopLossTriggerPrice().multiply(BigDecimal.ONE.subtract(resolveStopLossLimitGapPercent(order)));
        // The client id basis includes a timestamp (unlike the entry OCO's deterministic id)
        // since a resize can genuinely be attempted more than once for the same position (a
        // retry after a prior resize failure), and each attempt needs its own distinct id
        // rather than colliding with a previous attempt's id at the exchange as a false
        // duplicate.
        String newListClientOrderId = OrderService.generateClientOrderId("tv-r", position.getId() + ":RESIZE:" + System.currentTimeMillis());

        // The resize's OCO placement gets a real OMS Order record too, the same three-phase,
        // non-fatal setup used at every other OCO placement site.
        com.tradevision.model.Order resizeOmsOrder;
        try {
            resizeOmsOrder = orderService.create(order.getUserId(), credential.getId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(),
                order.getTakeProfitPrice(), newListClientOrderId);
            // Stamps the OMS record with the real TP/SL prices and role, consistent with every
            // other OCO placement site.
            resizeOmsOrder.setOrderRole("OCO_EXIT");
            resizeOmsOrder.setTakeProfitPrice(order.getTakeProfitPrice());
            resizeOmsOrder.setStopLossTriggerPrice(order.getStopLossTriggerPrice());
            resizeOmsOrder.setStopLossLimitPrice(stopLimit);
            resizeOmsOrder.setStopLossLimitGapPercent(resolveStopLossLimitGapPercent(order));
            orderService.markRiskAccepted(resizeOmsOrder);
            orderService.markSubmitting(resizeOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for OCO resize failed (non-fatal, additive record only): {}", e.getMessage());
            resizeOmsOrder = null;
        }

        // position.getQuantity() here is netQuantity (set above), the commission-adjusted
        // figure, not the gross confirmedExecutedQty.
        // A lost lease at this point is handled differently from a simple abort: cancelOco above
        // already consumed the old OCO, so this position now has no protection at all, and the
        // next-pass unprotected-position check never re-attempts protection for a still-held
        // position, only closes one whose balance is already gone. Emergency-flattening instead
        // of a plain early return follows this method's "protect or flatten, never strand"
        // principle, and is safe even with the reconciliation lease gone since emergencyFlatten
        // acquires its own separate, position-specific lock.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before placeExitOco in "
                + "syncPositionQuantityIfMismatched (position {}) -- another instance may now own this lock. Emergency-flattening rather "
                + "than leaving this position naked (its old OCO was already cancelled above).", credential.getId(), position.getId());
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Lost the reconciliation lock immediately after cancelling this position's old, mismatched-quantity OCO, before a "
                    + "correctly-sized replacement could be placed.");
            return;
        }
        // Created with the same listClientOrderId about to be sent, before the exchange call
        // itself, so a crash between this and the exchange call still leaves a durable
        // pre-submission trace (createOrphanForOco below only runs after the exchange call
        // succeeds, so it alone would not cover a crash before that).
        String protectionAttemptId1 = createProtectionAttempt(position, newListClientOrderId, position.getQuantity(),
            order.getTakeProfitPrice(), order.getStopLossTriggerPrice());
        // Scoped to LIVE specifically: if the pre-submission record couldn't be persisted and
        // the process then crashed before the position's OCO fields were recorded, a real,
        // active exchange-side OCO could exist with no durable local trace at all, so the OCO
        // call is not made this cycle for LIVE. For TESTNET/PAPER, no real money is at risk, so
        // the OCO call still proceeds even without this record.
        if (protectionAttemptId1 == null && credential.getMode() == BrokerMode.LIVE) {
            log.error("LIVE credential {} -- could not persist the pre-submission protection record for position {} ({}). The OCO "
                + "exchange call will NOT be made this cycle; halting further autonomous trading on this credential and raising a "
                + "critical incident, since this position is now genuinely unprotected.", credential.getId(), position.getId(), position.getSymbol());
            haltForProtectionAttemptPersistenceFailure(credential, position);
            incidentService.raiseCritical(position.getUserId(), position.getCredentialId(), position.getId(), null, position.getSymbol(),
                "PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT",
                "Could not persist the pre-submission protection record for " + position.getSymbol() + " (position " + position.getId()
                    + ") -- the OCO exchange call was NOT made this cycle. This position is currently UNPROTECTED and this credential "
                    + "has been auto-halted. Manual intervention required: either resolve the underlying database issue and let the "
                    + "next cycle retry, or manually place protection/close this position directly.");
            return;
        }
        // Stamped here, immediately before the real network call, the same discipline as
        // AutoTradeService's entry-order call site — see Order.exchangeCallStartedAt's own
        // field javadoc.
        if (resizeOmsOrder != null) {
            try {
                orderService.markExchangeCallStarted(resizeOmsOrder);
            } catch (Exception e) {
                log.warn("Could not stamp exchangeCallStartedAt for OCO resize order {} (non-fatal, additive record only): {}", resizeOmsOrder.getId(), e.getMessage());
            }
        }
        OcoOrderResult newOco = adapter.placeExitOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(),
            position.getQuantity(), order.getTakeProfitPrice(), order.getStopLossTriggerPrice(), stopLimit, newListClientOrderId);
        resolveProtectionAttempt(protectionAttemptId1, newOco.success());
        // This is the first thing that happens after the exchange call succeeds, before OMS
        // recording or any other processing that could throw and abandon this operation with no
        // durable trace. See createOrphanForOco's own javadoc.
        String orphanId = (newOco.success() && newOco.ocoOrderListId() != null)
            ? createOrphanForOco(position, newOco.ocoOrderListId(), newOco.actualProtectedQuantity())
            : null;
        // The OCO's real protected quantity can be smaller than the position's own quantity due
        // to base-asset fees — see Position.protectedQuantity's own field javadoc.
        if (newOco.success() && newOco.actualProtectedQuantity() != null) {
            position.setProtectedQuantity(newOco.actualProtectedQuantity());
        }
        if (resizeOmsOrder != null) {
            try {
                orderService.recordOcoPlacementResult(resizeOmsOrder, newOco);
            } catch (Exception e) {
                log.warn("OMS recordOcoPlacementResult failed for OCO resize order {} (non-fatal, additive record only): {}", resizeOmsOrder.getId(), e.getMessage());
            }
        }

        if (newOco.success()) {
            atomicSetOcoPlaced(position, newOco.ocoOrderListId(), newOco.actualProtectedQuantity(), orphanId);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZED",
                "OCO on " + position.getSymbol() + " re-placed at corrected quantity " + confirmedExecutedQty + ".");
        } else {
            // Old OCO is cancelled, new one failed — genuinely naked now. Same rule as everywhere else.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_FAILED",
                "Cancelled mis-sized OCO on " + position.getSymbol() + " but could NOT re-place it at the corrected "
                    + "quantity " + confirmedExecutedQty + ": " + newOco.errorMessage() + " — emergency-flattening rather than leaving it naked.");
            // A "failed" placement can still carry a real ocoOrderListId from recovery — this is
            // recorded on the position before flattening so emergencyFlatten's own OCO-aware
            // state machine verifies actual fill state rather than this path discarding it.
            if (newOco.ocoOrderListId() != null) {
                atomicSetOcoPlaced(position, newOco.ocoOrderListId(), null, orphanId);
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Could not re-place resized protection after a partial-fill quantity correction: " + newOco.errorMessage());
        }
    }

    /**
     * Creates and protects a Position for an order that AutoTradeService correctly refused to
     * open a position for at placement time (executedQty was 0/null then), but which
     * reconciliation has now confirmed actually filled. Reuses the same fills-based
     * weighted-average pricing and unverified-price handling (status="OPEN" +
     * avgEntryPriceUnverified) used everywhere else — this is the same position-creation logic
     * AutoTradeService itself uses, just triggered from a later discovery point.
     */
    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly —
    // money-consequential logic deserves a focused test without standing up the whole
    // reconciliation chain.
    public void createPositionForLateDiscoveredFill(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                                       Order order, BigDecimal confirmedExecutedQty, long lockGeneration) {
        // This method must never create a Position from a SELL order, regardless of which
        // upstream query or caller handed it one — an entry Position must only ever be created
        // from a BUY. Without this check, a genuine emergency-flatten SELL rediscovered by the
        // query above could be recorded and priced as if it were a brand-new BUY entry, creating
        // a phantom Position whose "entry price" was literally the previous position's own exit
        // price. That phantom position's own emergency-flatten would then place another real
        // SELL, rediscovered the same way next cycle — a self-sustaining infinite
        // loop. Never silently reinterpret a SELL as a BUY here under any circumstance; a SELL
        // reaching this method at all means something upstream let it through and needs its own
        // investigation, but this method's own job either way is simply to do nothing and
        // return, not guess.
        if (!"BUY".equals(order.getSide())) {
            log.warn("createPositionForLateDiscoveredFill called for order {} on {} whose side is {}, not BUY -- refusing to create "
                + "a Position from it. This order is very likely a genuine exit (an emergency/naked-flatten SELL, for instance) that "
                + "reconcileEntryOrders' own side-filtered query should already exclude; reaching this guard at all means some other "
                + "caller or a future change let a non-BUY order through. No Position created, nothing else done for this order here.",
                order.getBrokerOrderId(), order.getSymbol(), order.getSide());
            return;
        }
        // A late-discovered fill creates real coins regardless of whether a slot is available
        // now, since the original order's own reservation was already released when its
        // executedQty first came back 0/null. A fresh slot/exposure reservation is taken here;
        // if the cap is already full, this emergency-flattens rather than silently exceeding
        // maxConcurrentTrades/exposure caps.
        var profileOpt = riskProfileRepo.findByCredentialId(credential.getId());
        int maxConcurrent = profileOpt.map(RiskProfile::getMaxConcurrentTrades).orElse(1);
        var slotResult = slotReservationService.reserve(credential.getId(), maxConcurrent, null, credential.getMode() == BrokerMode.LIVE);
        boolean slotReserved = slotResult.reserved();
        String slotReservationId = slotResult.reservationId();

        BigDecimal avgEntry = null;
        BigDecimal entryFee = null;
        boolean priceVerified = false;
        // The Position object itself isn't constructed until later in this method (after fill
        // fetching, net-quantity computation, and the slot/exposure over-limit check below), so
        // its id is pre-assigned here. This lets the fill-ledger recording below reference the
        // real, final position id immediately, and the eventual Position object just reuses
        // this same id when it's constructed.
        String positionId = java.util.UUID.randomUUID().toString();
        // Declared here, alongside positionId, for the same reason — needs to be visible both
        // where fill ledger recording happens (below) and later where the Position/flattenTarget
        // objects are actually constructed and this flag gets applied to whichever one is used.
        boolean ledgerRecordingIncomplete = false;
        // netQuantity flows through everything below — the flatten-target quantity if limits are
        // exceeded, the exposure reservation amount, and the final position quantity — rather
        // than the gross confirmedExecutedQty, so exposure is not over-counted by the commission
        // amount on top of the eventual OCO sizing.
        BigDecimal netQuantity = confirmedExecutedQty;
        // The reconcilePositionAgainstLedger call further below needs the same base asset
        // computeNetQuantity uses, but `rules` is a local declared inside the
        // `if (totalQty.signum() > 0)` block just below and out of scope by the time this
        // method reaches that reconcile call. Captured into this outer-scoped holder right
        // where rules is actually resolved.
        String baseAssetForLedgerReconcile = null;
        try {
            List<com.tradevision.service.broker.dto.Fill> fills =
                adapter.getFillsForOrder(apiKey, apiSecret, credential.getMode(), order.getSymbol(), order.getBrokerOrderId());
            if (!fills.isEmpty()) {
                BigDecimal totalQty = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
                for (var f : fills) { totalQty = totalQty.add(f.qty()); totalCost = totalCost.add(f.qty().multiply(f.price())); }
                if (totalQty.signum() > 0) {
                    avgEntry = totalCost.divide(totalQty, 8, java.math.RoundingMode.HALF_UP);
                    var rules = adapter.getSymbolRules(order.getSymbol(), credential.getMode());
                    baseAssetForLedgerReconcile = rules.baseAsset();
                    entryFee = positionSafetyService.sumCommissionInQuoteAsset(fills, rules.quoteAsset());
                    // Records the same fill data AutoTradeService's entry path already records
                    // for a normal entry, just discovered later. Uses the pre-generated
                    // positionId since the Position object itself doesn't exist yet.
                    var recorded = fillLedgerService.recordFills(order.getBrokerOrderId(), positionId, order.getUserId(), credential.getId(),
                        order.getSymbol(), "BUY", rules.quoteAsset(), fills, confirmedExecutedQty, avgEntry);
                    // The Position object doesn't exist yet here either, so this flag is tracked
                    // with the same pre-generated-id pattern and applied once the Position is
                    // constructed — see Position.ledgerRecordingIncomplete's own javadoc.
                    int expectedFillRecords = (fills != null && !fills.isEmpty()) ? fills.size()
                        : (confirmedExecutedQty != null && confirmedExecutedQty.signum() > 0 ? 1 : 0);
                    ledgerRecordingIncomplete = recorded.size() < expectedFillRecords;
                    if (ledgerRecordingIncomplete) {
                        // A late-discovered fill is treated the same as a normal entry's first
                        // ledger write failing — see AutoTradeService's entry-path handling for
                        // this same case.
                        String reason = "Fill ledger recording failed for a late-discovered position on " + order.getSymbol()
                            + " (expected " + expectedFillRecords + " fill record(s), got " + recorded.size() + ") — the position "
                            + "itself was still created since the fill genuinely happened on the exchange, but automated "
                            + "trading is halted until this is manually investigated and resolved.";
                        profileOpt.ifPresent(p -> atomicHaltProfile(p, reason));
                        credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(),
                            "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
                        incidentService.raiseCritical(order.getUserId(), credential.getId(), positionId, order.getBrokerOrderId(),
                            order.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
                    }
                    // Same commission-conversion wiring as AutoTradeService's own entry path — a
                    // late-discovered fill is still an entry, and its commission deserves the
                    // same accounting treatment. See FillLedgerService.backfillHistoricalCommissionConversion's own javadoc.
                    for (var fillRecord : recorded) {
                        try {
                            fillLedgerService.backfillHistoricalCommissionConversion(fillRecord, rules.quoteAsset(), adapter, credential.getMode());
                        } catch (Exception e) {
                            log.warn("Historical commission backfill failed for fill {} (non-fatal): {}", fillRecord.getId(), e.getMessage());
                        }
                    }
                    var netResult = positionSafetyService.computeNetQuantity(confirmedExecutedQty, fills, rules.baseAsset());
                    netQuantity = netResult.netBaseQty();
                    if (netResult.baseAssetCommission().signum() > 0) {
                        credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "BASE_ASSET_COMMISSION_DEDUCTED",
                            "Late-discovered fill for order " + order.getBrokerOrderId() + " on " + order.getSymbol() + " paid "
                                + netResult.baseAssetCommission() + " " + rules.baseAsset() + " in commission — position quantity "
                                + "recorded as " + netQuantity + " (gross fill was " + confirmedExecutedQty + "), not the gross figure.");
                    }
                    priceVerified = true;
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch fills to price a late-discovered fill for order {}: {}", order.getBrokerOrderId(), e.getMessage());
        }

        // Exposure reservation, same reasoning as the slot above: best-effort using whatever
        // price could be verified, skipped entirely if unverified, consistent with how exposure
        // tracking excludes unverified-price positions everywhere else (see
        // releaseExposureForClosedPosition). Also atomically reserves any matching correlation
        // group, the same mechanism as AutoTradeService's own entry-reservation call site.
        boolean exposureReserved = true;
        BigDecimal exposureAmount = null;
        // Declared at this wider scope so the rollback below can release the exact reservation
        // by id, not a recomputed amount.
        String exposureReservationId = null;
        if (priceVerified && profileOpt.isPresent()) {
            exposureAmount = netQuantity.multiply(avgEntry);
            var exposureResult = exposureReservationService.reserve(credential.getId(), order.getSymbol(), exposureAmount,
                profileOpt.get().getMaxTotalExposureQuote(), profileOpt.get().getMaxSymbolExposureQuote(),
                profileOpt.get().getCorrelationGroups(), profileOpt.get().getCorrelationGroupCaps(), null, credential.getMode() == BrokerMode.LIVE);
            exposureReserved = exposureResult.allowed();
            exposureReservationId = exposureResult.reservationId();
        }

        if (!slotReserved || !exposureReserved) {
            credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "LATE_FILL_EXCEEDS_LIMITS",
                "Order " + order.getBrokerOrderId() + " on " + order.getSymbol() + " confirmed filled (" + netQuantity
                    + " net of commission) but " + (!slotReserved ? "the concurrent-trade slot limit" : "the exposure limit")
                    + " was already at capacity when this was discovered — emergency-flattening rather than silently exceeding it.");
            Position flattenTarget = new Position();
            // Reuses the same pre-generated id the fillLedgerService.recordFills() call above
            // already referenced, so the ledger record correctly points at whichever Position
            // object actually ends up persisted, regardless of which branch this method takes.
            flattenTarget.setId(positionId);
            flattenTarget.setLedgerRecordingIncomplete(ledgerRecordingIncomplete);
            flattenTarget.setUserId(order.getUserId());
            flattenTarget.setCredentialId(credential.getId());
            flattenTarget.setBroker(credential.getBroker());
            flattenTarget.setMode(credential.getMode());
            flattenTarget.setSymbol(order.getSymbol());
            flattenTarget.setQuantity(netQuantity);
            flattenTarget.setEntryOrderId(order.getBrokerOrderId());
            flattenTarget.setStatus("OPEN");
            // A position created here was discovered by reconciliation, not a live placement
            // decision — "LATE_FILL_DISCOVERED" is this codebase's domain term for exactly
            // that, matching the normal (under-limit) branch below.
            flattenTarget.setTriggerSource("LATE_FILL_DISCOVERED");
            flattenTarget.setAvgEntryPriceUnverified(!priceVerified);
            if (priceVerified) flattenTarget.setAvgEntryPrice(avgEntry);
            positionRepo.save(flattenTarget);
            if (slotReserved) slotReservationService.release(slotReservationId); // give back whichever one WAS reserved
            // Releases the exact reservation by id, not a recomputed amount.
            if (exposureReserved && exposureReservationId != null) exposureReservationService.release(exposureReservationId);
            // emergencyFlatten below uses flattenTarget.getQuantity() to decide how much to
            // sell, so this reconciliation against the ledger runs before that call — a genuine
            // mismatch here means the flatten is about to act on a quantity the ledger
            // disagrees with, and that should be caught first.
            try {
                var reconcileResult = positionLedgerService.reconcilePositionAgainstLedger(positionId, netQuantity, baseAssetForLedgerReconcile);
                if (!reconcileResult.matches()) {
                    // flattenTarget is about to be handed to emergencyFlatten() below, so
                    // correcting its quantity before that call means the flatten itself acts on
                    // the ledger's figure, not a locally-computed one it disagreed with. See
                    // ReconcileResult.resolvedQuantity's own javadoc.
                    BigDecimal resolvedQty = reconcileResult.resolvedQuantity(flattenTarget.isLedgerRecordingIncomplete());
                    if (resolvedQty.compareTo(flattenTarget.getQuantity()) != 0) {
                        flattenTarget.setQuantity(resolvedQty);
                    }
                    String reason = "Position ledger mismatch on a late-discovered, over-limit fill for " + order.getSymbol()
                        + ": the fill ledger reconstructs " + reconcileResult.ledgerQuantity() + " for this position, but "
                        + netQuantity + " was believed correct (and is about to be used for an emergency flatten) — "
                        + "automated trading is halted until this is manually investigated and resolved.";
                    profileOpt.ifPresent(p -> atomicHaltProfile(p, reason));
                    credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(),
                        "POSITION_LEDGER_MISMATCH_HALT", reason);
                    incidentService.raiseCritical(order.getUserId(), credential.getId(), positionId, order.getBrokerOrderId(),
                        order.getSymbol(), "POSITION_LEDGER_MISMATCH", reason);
                }
            } catch (Exception e) {
                log.warn("Position ledger reconciliation check failed for late-discovered over-limit fill on order {} (non-fatal, observability only): {}", order.getBrokerOrderId(), e.getMessage());
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, flattenTarget,
                "Late-discovered fill exceeds concurrent-trade or exposure limits.");
            return;
        }

        Position position = new Position();
        // Reuses the id the fill-ledger recording already referenced, same as flattenTarget above.
        position.setId(positionId);
        position.setLedgerRecordingIncomplete(ledgerRecordingIncomplete);
        position.setUserId(order.getUserId());
        position.setCredentialId(credential.getId());
        position.setBroker(credential.getBroker());
        position.setMode(credential.getMode());
        position.setSymbol(order.getSymbol());
        position.setQuantity(netQuantity);
        position.setEntryOrderId(order.getBrokerOrderId());
        position.setSignalId(order.getSignalId());
        // This position was discovered here, by reconciliation, not by a live placement
        // decision, so triggerSource is set to the domain term for that rather than copied from
        // order.getTriggerSource() — copying it would mislabel a position as user-initiated
        // whenever the triggering order itself was.
        position.setTriggerSource("LATE_FILL_DISCOVERED");
        position.setOpenedAt(LocalDateTime.now()); // approximate — the real fill time isn't available from here, only that it's confirmed now
        position.setEntryFeeQuote(entryFee);
        position.setStatus("OPEN");
        position.setSlotReservationId(slotReservationId);
        position.setExposureReservationId(exposureReservationId);

        // netQuantity is derived from the same fills already recorded to the Fill Ledger above
        // (fillLedgerService.recordFills(...)), so the ledger's independent reconstruction can
        // cross-check this quantity, the same cross-check already wired at entry, OCO-close, and
        // flatten-close. Runs once here, before whichever branch below actually persists the
        // position, since the quantity itself doesn't differ between them.
        try {
            var reconcileResult = positionLedgerService.reconcilePositionAgainstLedger(positionId, netQuantity, baseAssetForLedgerReconcile);
            if (!reconcileResult.matches()) {
                // Derived before the position is first saved below — see
                // ReconcileResult.resolvedQuantity's own javadoc.
                BigDecimal resolvedQty = reconcileResult.resolvedQuantity(position.isLedgerRecordingIncomplete());
                if (resolvedQty.compareTo(position.getQuantity()) != 0) {
                    position.setQuantity(resolvedQty);
                }
                String reason = "Position ledger mismatch on a late-discovered fill for " + order.getSymbol()
                    + ": the fill ledger reconstructs " + reconcileResult.ledgerQuantity() + " for this position, but "
                    + netQuantity + " was believed correct — automated trading is halted until this is manually "
                    + "investigated and resolved.";
                profileOpt.ifPresent(p -> atomicHaltProfile(p, reason));
                credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(),
                    "POSITION_LEDGER_MISMATCH_HALT", reason);
                incidentService.raiseCritical(order.getUserId(), credential.getId(), positionId, order.getBrokerOrderId(),
                    order.getSymbol(), "POSITION_LEDGER_MISMATCH", reason);
            }
        } catch (Exception e) {
            log.warn("Position ledger reconciliation check failed for late-discovered fill on order {} (non-fatal, observability only): {}", order.getBrokerOrderId(), e.getMessage());
        }

        if (!priceVerified) {
            position.setAvgEntryPriceUnverified(true);
            positionRepo.save(position);
            credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "LATE_FILL_DISCOVERED_UNVERIFIED_PRICE",
                "Order " + order.getBrokerOrderId() + " on " + order.getSymbol() + " confirmed filled (" + confirmedExecutedQty
                    + ") on a later reconciliation pass, but entry price could not be verified — emergency-flattening rather than "
                    + "leaving an unpriced, unprotected position undiscovered any longer.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Late-discovered fill with no verifiable entry price to protect it against.");
            return;
        }

        position.setAvgEntryPrice(avgEntry);
        positionRepo.save(position);
        credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "LATE_FILL_DISCOVERED",
            "Order " + order.getBrokerOrderId() + " on " + order.getSymbol() + " confirmed filled (" + confirmedExecutedQty
                + " @ " + avgEntry + ") on a later reconciliation pass after initially showing no confirmed quantity — "
                + "position created and being protected now.");

        if (order.getStopLossTriggerPrice() == null || order.getTakeProfitPrice() == null) {
            credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_INCOMPLETE",
                "No SL/TP recorded on order " + order.getBrokerOrderId() + " to protect this late-discovered fill with — emergency-flattening.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "No SL/TP price recorded to protect a late-discovered fill.");
            return;
        }

        BigDecimal stopLimit = order.getStopLossTriggerPrice().multiply(BigDecimal.ONE.subtract(resolveStopLossLimitGapPercent(order)));
        // Timestamp kept in the id basis for the same reason as the resize site: a retry of this
        // method for the same underlying order already gets a fresh position id (generated at
        // the top of this method), but the timestamp is extra, harmless insurance against any
        // scenario where that isn't true.
        String listClientOrderId = OrderService.generateClientOrderId("tv-l", position.getId() + ":LATEFILL:" + System.currentTimeMillis());

        // Same OMS wiring as every other OCO placement site.
        com.tradevision.model.Order lateFillOmsOrder;
        try {
            lateFillOmsOrder = orderService.create(order.getUserId(), credential.getId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(),
                order.getTakeProfitPrice(), listClientOrderId);
            // Same TP/SL/role stamping as every other OCO placement site.
            lateFillOmsOrder.setOrderRole("OCO_EXIT");
            lateFillOmsOrder.setTakeProfitPrice(order.getTakeProfitPrice());
            lateFillOmsOrder.setStopLossTriggerPrice(order.getStopLossTriggerPrice());
            lateFillOmsOrder.setStopLossLimitPrice(stopLimit);
            lateFillOmsOrder.setStopLossLimitGapPercent(resolveStopLossLimitGapPercent(order));
            orderService.markRiskAccepted(lateFillOmsOrder);
            orderService.markSubmitting(lateFillOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for late-fill OCO placement failed (non-fatal, additive record only): {}", e.getMessage());
            lateFillOmsOrder = null;
        }

        // Sizing the exit OCO off the gross confirmedExecutedQty rather than netQuantity would
        // make the exchange reject the OCO outright (selling more than the account actually
        // holds), or worse, partially fill it unpredictably. position.getQuantity() is
        // netQuantity here (set above at position creation), so using it directly keeps this
        // consistent with what's actually recorded.
        // A lost lease at this point is handled differently from a simple abort: this position
        // was just created by this same method call, has no existing OCO to fall back on, and
        // the next-pass unprotected-position check only ever closes an unprotected position
        // whose balance is already gone — it never re-attempts protection for one that's still
        // genuinely held. A plain early return here would leave this position open and
        // completely naked indefinitely. Emergency-flattening instead follows this method's
        // "protect or flatten, never strand" principle, and is safe to do even with the
        // reconciliation lease gone since emergencyFlatten acquires its own separate,
        // position-specific lock ("flatten:" + position.getId()), independent of this
        // credential-level lease.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before placeExitOco in "
                + "createPositionForLateDiscoveredFill (position {}) -- another instance may now own this lock. Emergency-flattening "
                + "rather than leaving this newly-created position naked.", credential.getId(), position.getId());
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Lost the reconciliation lock immediately before this late-discovered fill could be protected with an OCO.");
            return;
        }
        // Same pre-submission durability as the resize-OCO call site above.
        String protectionAttemptId2 = createProtectionAttempt(position, listClientOrderId, position.getQuantity(),
            order.getTakeProfitPrice(), order.getStopLossTriggerPrice());
        // Same LIVE-scoped halt-before-exchange-call as the resize-OCO call site above — see
        // haltForProtectionAttemptPersistenceFailure's own javadoc.
        if (protectionAttemptId2 == null && credential.getMode() == BrokerMode.LIVE) {
            log.error("LIVE credential {} -- could not persist the pre-submission protection record for position {} ({}). The OCO "
                + "exchange call will NOT be made this cycle; halting further autonomous trading on this credential and raising a "
                + "critical incident, since this position is now genuinely unprotected.", credential.getId(), position.getId(), position.getSymbol());
            haltForProtectionAttemptPersistenceFailure(credential, position);
            incidentService.raiseCritical(position.getUserId(), position.getCredentialId(), position.getId(), null, position.getSymbol(),
                "PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT",
                "Could not persist the pre-submission protection record for " + position.getSymbol() + " (position " + position.getId()
                    + ") -- the OCO exchange call was NOT made this cycle. This position is currently UNPROTECTED and this credential "
                    + "has been auto-halted. Manual intervention required: either resolve the underlying database issue and let the "
                    + "next cycle retry, or manually place protection/close this position directly.");
            return;
        }
        // Same exchangeCallStartedAt stamping as the resize-OCO call site above.
        if (lateFillOmsOrder != null) {
            try {
                orderService.markExchangeCallStarted(lateFillOmsOrder);
            } catch (Exception e) {
                log.warn("Could not stamp exchangeCallStartedAt for late-fill OCO order {} (non-fatal, additive record only): {}", lateFillOmsOrder.getId(), e.getMessage());
            }
        }
        OcoOrderResult oco = adapter.placeExitOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(),
            position.getQuantity(), order.getTakeProfitPrice(), order.getStopLossTriggerPrice(), stopLimit, listClientOrderId);
        resolveProtectionAttempt(protectionAttemptId2, oco.success());
        // The very first thing after the exchange call succeeds, before any other processing —
        // same as the resize-OCO call site above. See createOrphanForOco's own javadoc.
        String orphanId2 = (oco.success() && oco.ocoOrderListId() != null)
            ? createOrphanForOco(position, oco.ocoOrderListId(), oco.actualProtectedQuantity())
            : null;
        // Same base-asset-fee handling as every other OCO placement site — see
        // Position.protectedQuantity's own field javadoc.
        if (oco.success() && oco.actualProtectedQuantity() != null) {
            position.setProtectedQuantity(oco.actualProtectedQuantity());
        }
        if (lateFillOmsOrder != null) {
            try {
                orderService.recordOcoPlacementResult(lateFillOmsOrder, oco);
            } catch (Exception e) {
                log.warn("OMS recordOcoPlacementResult failed for late-fill OCO order {} (non-fatal, additive record only): {}", lateFillOmsOrder.getId(), e.getMessage());
            }
        }

        if (oco.success()) {
            atomicSetOcoPlaced(position, oco.ocoOrderListId(), oco.actualProtectedQuantity(), orphanId2);
            credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZED",
                "Late-discovered fill on " + position.getSymbol() + " now protected with a fresh OCO.");
        } else {
            credentialService.audit(order.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_FAILED",
                "Could not protect late-discovered fill on " + position.getSymbol() + ": " + oco.errorMessage()
                    + " — emergency-flattening rather than leaving it naked.");
            // Records a known-but-not-active OCO before flattening, the same as the other
            // placeExitOco failure branches, letting emergencyFlatten's own state machine
            // verify fill state rather than discarding it here.
            if (oco.ocoOrderListId() != null) {
                atomicSetOcoPlaced(position, oco.ocoOrderListId(), null, orphanId2);
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Could not place protection for a late-discovered fill: " + oco.errorMessage());
        }
    }

    /**
     * Reconciles every open position for a credential against real broker state. This method's
     * loop makes one network-bound exchange call per position (getOcoStatus, or getOrderStatus
     * for an unprotected one), so the reconciliation lock is renewed before every position
     * rather than only once at the start — a credential with many open positions, or even a
     * handful of slow exchange responses, could otherwise exhaust the lease entirely mid-loop,
     * letting a second instance acquire what it believes is a fresh lock on the same credential
     * while this one is still working through the rest of the list. The per-iteration renewal
     * cost (a cheap, local Mongo update) is negligible against the dominant per-iteration cost
     * (the real exchange call), so there's no real trade-off in renewing this often. A lost
     * renewal stops the rest of this loop, the same "stop rather than continue mutating without
     * exclusive ownership confirmed" principle applied throughout this class's reconciliation
     * pass.
     */
    private void reconcileOpenPositions(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        // Reconciles every open position, not just ones with an OCO — a position with no OCO,
        // either intentionally unprotected or the survivor of a failed emergency-flatten, is
        // exactly the one that most needs checking.
        List<Position> openPositions = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");
        if (openPositions.isEmpty()) return;

        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        Optional<RiskProfile> profileOpt = riskProfileRepo.findByCredentialId(credential.getId());

        for (Position position : openPositions) {
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-loop in reconcileOpenPositions (before position {}) "
                    + "-- another instance may now own this lock. Stopping the rest of this loop rather than continuing to mutate shared "
                    + "state without exclusive ownership confirmed.", credential.getId(), position.getId());
                return;
            }
            try {
                if (position.getOcoOrderListId() != null) {
                    reconcileOcoProtectedPosition(credential, adapter, apiKey, apiSecret, position, profileOpt, lockGeneration);
                } else {
                    reconcileUnprotectedPosition(credential, adapter, apiKey, apiSecret, position, lockGeneration);
                }
            } catch (Exception e) {
                log.warn("Could not reconcile position {}: {}", position.getId(), e.getMessage());
            }
        }
    }

    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly —
    // money-consequential logic (TP/SL/partial-exit P&L and fill-ledger recording) deserves a
    // focused test without standing up the whole reconcileCredential() chain.
    /**
     * Called whenever the exit OCO list is not (yet) ALL_DONE. The overwhelmingly common reason
     * is completely normal: neither leg has triggered, price is between TP and SL, nothing to
     * do. This only takes action for one specific abnormal case: the STOP_LOSS_LIMIT leg has
     * genuinely triggered (current market price is at or below the recorded stop trigger) but
     * the leg itself is still sitting NEW/PARTIALLY_FILLED, never FILLED — meaning the resting
     * SELL LIMIT (placed at stopTrigger x (1 - the configurable stopLossLimitGapPercent gap) is
     * above a market that kept moving down through it and simply isn't filling. The broker's OCO
     * leg status has no separate "triggered" state to read directly (a triggered stop-limit
     * still reports NEW, same as before it triggered), so comparing live price against the
     * recorded trigger is the only signal available without extra API surface.
     *
     * The response is exactly emergencyFlatten's existing behavior: it checks whether the OCO
     * is still active, cancels it if so, then market-sells whatever is actually held, rather
     * than leaving the position sitting here on every pass re-reading the same stuck EXECUTING
     * status indefinitely.
     */
    private void handleStopTriggeredButUnfilled(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                                  Position position, OcoStatusInfo ocoStatus, long lockGeneration) {
        OcoStatusInfo.Leg stopLeg = ocoStatus.legs().stream()
            .filter(l -> l.type() != null && l.type().toUpperCase().contains("STOP"))
            .findFirst().orElse(null);
        if (stopLeg == null || "FILLED".equalsIgnoreCase(stopLeg.status())) return; // can't identify it, or it already filled -- next pass's ALL_DONE branch (or ongoing EXECUTING) handles it correctly either way

        var entryOrderOpt = position.getEntryOrderId() != null ? omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId(position.getCredentialId(), position.getSymbol(), position.getEntryOrderId()) : Optional.<Order>empty();
        if (entryOrderOpt.isEmpty() || entryOrderOpt.get().getStopLossTriggerPrice() == null) return; // nothing recorded to compare against -- can't safely conclude anything here

        BigDecimal currentPrice;
        try {
            currentPrice = adapter.getCurrentPrice(position.getSymbol(), credential.getMode());
        } catch (Exception e) {
            log.warn("Could not fetch current price for {} while checking for a stuck-triggered stop leg on position {} (will retry next pass): {}",
                position.getSymbol(), position.getId(), e.getMessage());
            return;
        }
        BigDecimal stopTrigger = entryOrderOpt.get().getStopLossTriggerPrice();
        if (currentPrice.compareTo(stopTrigger) > 0) return; // price is still above the stop trigger -- hasn't triggered, this is the normal resting-OCO case

        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before flattening a stuck-triggered stop on position {} "
                + "-- another instance may now own this lock. Aborting before mutating.", credential.getId(), position.getId());
            return;
        }
        String reason = "Stop-loss leg on " + position.getSymbol() + " appears triggered (current price " + currentPrice
            + " is at or below the recorded stop trigger " + stopTrigger + ") but has not filled (leg status " + stopLeg.status()
            + ") -- the resting stop-limit order is likely above a market that gapped or moved fast through it. Cancelling the stuck "
            + "OCO and flattening at market rather than leaving this position falling with no real protection.";
        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "OCO_STOP_TRIGGERED_UNFILLED", reason);
        positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position, reason);
    }

    void reconcileOcoProtectedPosition(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                                Position position, Optional<RiskProfile> profileOpt, long lockGeneration) {
        OcoStatusInfo ocoStatus = adapter.getOcoStatus(apiKey, apiSecret, credential.getMode(), position.getOcoOrderListId());
        // A triggered-but-unfilled stop leg sits in the same non-ALL_DONE state as a normal,
        // still-resting OCO (a fast move through stopLimit leaves the resting SELL LIMIT above
        // the market, never filling, list stuck EXECUTING), so this specific condition is
        // detected and acted on here rather than treating every non-ALL_DONE list as "still
        // protected, nothing to do."
        if (!"ALL_DONE".equalsIgnoreCase(ocoStatus.listStatus())) {
            handleStopTriggeredButUnfilled(credential, adapter, apiKey, apiSecret, position, ocoStatus, lockGeneration);
            return;
        }

        // PARTIALLY_FILLED legs are treated the same as FILLED here (e.g. a leg that expired
        // mid-fill), since the existing exitQty-based partial-close logic a few lines below
        // (filledLeg.executedQty()) already handles "sold less than the full position"
        // correctly — an ALL_DONE list whose stop leg is only PARTIALLY_FILLED must not fall
        // through to handleOcoAllDoneWithNoFill as if nothing sold.
        OcoStatusInfo.Leg filledLeg = ocoStatus.legs().stream()
            .filter(l -> "FILLED".equalsIgnoreCase(l.status()) || "PARTIALLY_FILLED".equalsIgnoreCase(l.status()))
            .findFirst().orElse(null);

        // ALL_DONE only means the order list finished — both legs could be CANCELED with
        // nothing ever sold, including as a result of this application's own cancelOco() calls
        // from syncPositionQuantityIfMismatched/reprotection, not just an external actor. That
        // is fundamentally different from "a leg filled but its price couldn't be verified"
        // (below), which really did sell something.
        // Never assume closed here — verify against the actual base-asset balance first, the
        // same check reconcileUnprotectedPosition already uses.
        if (filledLeg == null) {
            handleOcoAllDoneWithNoFill(credential, adapter, apiKey, apiSecret, position, lockGeneration);
            return;
        }

        if (filledLeg.price() == null || filledLeg.price().signum() <= 0) {
            // Does not default to entry price here, since that would fabricate a P&L of exactly
            // 0 for a position that may well have lost money. A leg genuinely was reported
            // FILLED here (unlike the null-filledLeg case above, handled separately), so
            // something really did sell, we just can't price it. Marking this closed-unverified
            // is correct: the position is gone, only the P&L is unknown.
            // Captured before atomicCloseUnverifiedPnl mutates position.quantity to zero, so the
            // risk-impact estimate below is against the real, pre-close size — see
            // recordUnverifiedCloseRiskImpact's own javadoc.
            BigDecimal originalQuantity = position.getQuantity();
            atomicCloseUnverifiedPnl(position);
            releaseSlotForClosedPosition(position); // no longer open — free the slot regardless of P&L uncertainty
            releaseExposureForClosedPosition(position);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSE_UNVERIFIED",
                "OCO " + position.getOcoOrderListId() + " on " + position.getSymbol() + " reports a filled leg but its price "
                    + "could not be resolved — P&L NOT recorded, needs manual reconciliation against Binance's trade history.");
            recordUnverifiedCloseRiskImpact(position, profileOpt, null, originalQuantity, "OCO leg filled, price unresolved");
            return;
        }

        String closeReason = filledLeg.type() != null && filledLeg.type().toUpperCase().contains("STOP") ? "STOP_LOSS" : "TAKE_PROFIT";

        // Always uses the leg's own confirmed executedQty rather than the full position size,
        // and only fully closes the position when that quantity actually covers it all — if the
        // exchange only filled part of the exit order, the position must stay open with the
        // remainder reprotected, not be marked closed while real coins remain on the exchange.
        BigDecimal exitQty = filledLeg.executedQty();
        if (exitQty == null || exitQty.signum() <= 0) {
            // Price is known here (filledLeg.price(), already validated above the closeReason
            // line), only the quantity is unresolved — captured before mutation, same reasoning
            // as the price-unresolved branch above.
            BigDecimal originalQuantity = position.getQuantity();
            atomicCloseUnverifiedPnl(position);
            releaseSlotForClosedPosition(position);
            releaseExposureForClosedPosition(position);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSE_UNVERIFIED",
                "OCO leg on " + position.getSymbol() + " reports FILLED but no confirmed executed quantity — P&L NOT recorded, "
                    + "needs manual reconciliation against Binance's actual trade history.");
            recordUnverifiedCloseRiskImpact(position, profileOpt, filledLeg.price(), originalQuantity, "OCO leg filled, quantity unresolved");
            return;
        }

        BigDecimal exitFee = null;
        try {
            var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
            List<com.tradevision.service.broker.dto.Fill> exitFills = adapter.getFillsForOrder(apiKey, apiSecret, credential.getMode(), position.getSymbol(), filledLeg.orderId());
            exitFee = positionSafetyService.sumCommissionInQuoteAsset(exitFills, rules.quoteAsset());
            // This one method already handles TP, SL, and partial exits together, making it the
            // natural single point to record all three to the fill ledger. Uses the broker's own
            // order id (filledLeg.orderId()) as the ledger reference rather than an OMS Order.id,
            // since this OCO/exit path is not OMS-driven and has no OMS order to reference —
            // still a real, meaningful identifier, just a different kind than the entry-order
            // path uses. position.getId() is the consistent cross-reference across every fill
            // type (see FillRecord's own javadoc).
            var recorded = fillLedgerService.recordFills(filledLeg.orderId(), position.getId(), position.getUserId(), position.getCredentialId(),
                position.getSymbol(), "SELL", rules.quoteAsset(), exitFills, exitQty, filledLeg.price());
            // Only ever set to true, never back to false — a historical gap doesn't get
            // retroactively fixed by a later, successful recording. See
            // Position.ledgerRecordingIncomplete's own javadoc.
            int expectedFillRecords = (exitFills != null && !exitFills.isEmpty()) ? exitFills.size()
                : (exitQty != null && exitQty.signum() > 0 ? 1 : 0);
            if (recorded.size() < expectedFillRecords) {
                position.setLedgerRecordingIncomplete(true);
                // The exit fill genuinely happened on the exchange (exitQty/filledLeg.price()
                // are already confirmed), so the position's close/reduction still proceeds
                // normally below; what changes is that further automated trading halts until a
                // human investigates why this ledger write failed.
                String reason = "Fill ledger recording failed for an OCO exit on " + position.getSymbol()
                    + " (expected " + expectedFillRecords + " fill record(s), got " + recorded.size() + ") — the position's "
                    + "own close/reduction still proceeded since the exit genuinely happened on the exchange, but "
                    + "automated trading is halted until this is manually investigated and resolved.";
                profileOpt.ifPresent(p -> atomicHaltProfile(p, reason));
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                    "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
                incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), filledLeg.orderId(),
                    position.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
            }
            // Same commission-conversion wiring as the entry path, so a non-quote-asset
            // commission on the TP/SL leg doesn't stay unconverted and understate realized P&L
            // on exits.
            for (var fillRecord : recorded) {
                try {
                    fillLedgerService.backfillHistoricalCommissionConversion(fillRecord, rules.quoteAsset(), adapter, credential.getMode());
                } catch (Exception e) {
                    log.warn("Historical commission backfill failed for exit fill {} (non-fatal): {}", fillRecord.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // Whatever line inside the try above failed (getSymbolRules(), getFillsForOrder(),
            // or recordFills()), the position still proceeds to close/reduce below since the
            // exit genuinely happened on the exchange, but this escalates with the same
            // halt+incident treatment as every other confirmed-fill-but-ledger-gap case in this
            // codebase, rather than only logging a warning and silently skipping the ledger write.
            log.warn("Could not fetch exit fill fees for position {}: {}", position.getId(), e.getMessage());
            position.setLedgerRecordingIncomplete(true);
            String reason = "Could not record the fill ledger for an OCO exit on " + position.getSymbol()
                + " (" + e.getMessage() + ") — the position's own close/reduction still proceeded since the exit "
                + "genuinely happened on the exchange, but no fee, or ledger data was recorded for this leg at all, "
                + "and automated trading is halted until this is manually investigated and resolved.";
            profileOpt.ifPresent(p -> atomicHaltProfile(p, reason));
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                "FILL_LEDGER_RECORDING_FAILED_HALT", reason);
            incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), filledLeg.orderId(),
                position.getSymbol(), "FILL_LEDGER_INCOMPLETE", reason);
        }

        // Uses the same consolidated P&L formula as every other exit path (see
        // RealizedPnlService's own javadoc). position.getQuantity() here is still the
        // pre-reduction value, since this runs before position.setQuantity(...) below.
        var pnlResult = realizedPnlService.calculate(position.getAvgEntryPrice(), filledLeg.price(), exitQty,
            position.getQuantity(), position.getEntryFeeQuote(), exitFee);
        BigDecimal pnl = pnlResult.realizedPnl();

        boolean fullyClosed = exitQty.compareTo(position.getQuantity()) >= 0;

        // Transitions the OCO's OMS Order record to FILLED or PARTIALLY_FILLED here, matching
        // this leg's real outcome, instead of leaving it at ACKNOWLEDGED while the position
        // itself moves on. Non-fatal and additive, like every other OMS bookkeeping call in this
        // class — never allowed to block or delay the real position mutation that follows. See
        // OrderService.recordOcoFillResult's own javadoc.
        try {
            orderService.recordOcoFillResult(position.getCredentialId(), position.getSymbol(), position.getOcoOrderListId(), fullyClosed);
        } catch (Exception e) {
            log.warn("OMS recordOcoFillResult failed for OCO {} (non-fatal, additive record only): {}", position.getOcoOrderListId(), e.getMessage());
        }

        if (!fullyClosed) {
            // PARTIAL exit: reduce the position, keep it OPEN, and re-protect the remainder —
            // never silently leave the leftover quantity unprotected or, worse, marked as if it
            // no longer existed.
            BigDecimal remainingQty = position.getQuantity().subtract(exitQty);
            BigDecimal remainingEntryFee = pnlResult.remainingEntryFeeQuote();
            BigDecimal runningPnl = (position.getRealizedPnlQuote() != null ? position.getRealizedPnlQuote() : BigDecimal.ZERO).add(pnl);

            position.setQuantity(remainingQty);
            position.setEntryFeeQuote(remainingEntryFee);
            position.setRealizedPnlQuote(runningPnl);
            // exitFee is already subtracted from pnl above; the dedicated exitFeeQuote field is
            // also accumulated here (not overwritten), the same pattern used in
            // PositionSafetyService's partial-flatten accounting, so a position with multiple
            // partial exits keeps a running total rather than only the last leg's fee.
            if (exitFee != null) {
                BigDecimal priorExitFee = position.getExitFeeQuote() != null ? position.getExitFeeQuote() : BigDecimal.ZERO;
                position.setExitFeeQuote(priorExitFee.add(exitFee));
            }
            position.setOcoOrderListId(null); // the OCO list is ALL_DONE — its protection is consumed, remainder needs fresh protection

            // Position mutations in this class and PositionSafetyService use a targeted atomic
            // conditional update rather than a plain whole-document save, except for the
            // first-ever save of a freshly-constructed position, which has nothing yet in the
            // database to race against.
            // By this point in this method, getOcoStatus, getSymbolRules, and getFillsForOrder
            // have all already run — real network round-trips that, on a slow connection, could
            // collectively approach the lease — so the lock is renewed immediately before this
            // position mutation, the consequential write this method exists to make correctly.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} immediately before the partial-exit mutation in "
                    + "reconcileOcoProtectedPosition (position {}) -- another instance may now own this lock. Aborting before mutating.",
                    credential.getId(), position.getId());
                return;
            }
            org.springframework.data.mongodb.core.query.Update partialExitUpdate = new org.springframework.data.mongodb.core.query.Update()
                .set("quantity", remainingQty)
                .set("entryFeeQuote", remainingEntryFee)
                .set("realizedPnlQuote", runningPnl)
                .set("exitFeeQuote", position.getExitFeeQuote())
                .set("ocoOrderListId", (Object) null);
            var partialExitResult = mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
                partialExitUpdate, Position.class);
            if (partialExitResult.getModifiedCount() == 0) {
                // Lost the race — some other process already changed this position (closed it,
                // or applied a different mutation) first. Its own side effects already ran;
                // applying this partial-exit's side effects on top would double-count P&L and
                // corrupt the quantity. Stop here, matching the full-close atomic update's own
                // established "lost the race, not a failure" handling right above in this class.
                log.info("Position {} on {} was already modified by another process before this partial exit could apply — skipping duplicate side effects.",
                    position.getId(), position.getSymbol());
                return;
            }

            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_PARTIAL_EXIT",
                "Partial " + closeReason + " on " + position.getSymbol() + ": " + exitQty + " exited at " + filledLeg.price()
                    + " (partial P&L " + pnl + "), " + remainingQty + " remains open and needs fresh protection.");

            if (pnl.signum() < 0 && profileOpt.isPresent()) {
                riskEngine.recordRealizedLoss(profileOpt.get(), pnl.abs());
            }
            if (profileOpt.isPresent()) {
                riskEngine.recordAutoTradeOutcome(profileOpt.get(), position.getTriggerSource(), pnl.signum() < 0);
            }

            reprotectRemainder(credential, adapter, apiKey, apiSecret, position, lockGeneration);
            return;
        }

        position.setStatus("CLOSED");
        position.setExitPrice(filledLeg.price());
        position.setCloseReason(closeReason);
        position.setClosedAt(LocalDateTime.now());
        // Accumulates rather than overwrites, so a prior partial leg's exitFeeQuote is not
        // discarded when this final leg closes the position.
        if (exitFee != null) {
            BigDecimal priorExitFee = position.getExitFeeQuote() != null ? position.getExitFeeQuote() : BigDecimal.ZERO;
            position.setExitFeeQuote(priorExitFee.add(exitFee));
        }
        position.setRealizedPnlQuote((position.getRealizedPnlQuote() != null ? position.getRealizedPnlQuote() : BigDecimal.ZERO).add(pnl));
        position.setClosedQuantity(position.getQuantity());
        position.setQuantity(BigDecimal.ZERO);

        // The reconciliation lock prevents two reconciliation passes running concurrently within
        // one instance, but a plain load-modify-save here is still not itself atomic — a second
        // layer of protection matters for anything beyond a single instance. This close
        // transition, the highest-volume, most consequential one, uses a conditional atomic
        // update: it only applies if the document is still "OPEN" at the moment of the write,
        // and only then are the money-consequential side effects (P&L recording, slot release,
        // signal outcome) allowed to run.
        // The full-close path is the other money-consequential write in this method, reached
        // after the same set of network calls as the partial-exit path's renewal check above.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before the full-close mutation in "
                + "reconcileOcoProtectedPosition (position {}) -- another instance may now own this lock. Aborting before mutating.",
                credential.getId(), position.getId());
            return;
        }
        org.springframework.data.mongodb.core.query.Update update = new org.springframework.data.mongodb.core.query.Update()
            .set("status", "CLOSED")
            .set("exitPrice", position.getExitPrice())
            .set("closeReason", closeReason)
            .set("closedAt", position.getClosedAt())
            .set("exitFeeQuote", position.getExitFeeQuote())
            .set("realizedPnlQuote", position.getRealizedPnlQuote())
            .set("closedQuantity", position.getClosedQuantity())
            .set("quantity", BigDecimal.ZERO)
            .set("ocoOrderListId", position.getOcoOrderListId());
        var result = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
            update, Position.class);
        if (result.getModifiedCount() == 0) {
            // Lost the race — some other pass already closed (or otherwise changed) this
            // position first. Its side effects already ran; running them again here would
            // double-count P&L and double-release the slot. Stop here, not a failure.
            log.info("Position {} on {} was already closed by another process — skipping duplicate close side effects.",
                position.getId(), position.getSymbol());
            return;
        }
        // No positionRepo.save(position) here deliberately — the atomic update above already
        // persisted everything needed. A plain save() of the stale in-memory object afterward
        // would risk overwriting anything a concurrent process changed in between, defeating
        // the entire point of doing this atomically in the first place.
        releaseSlotForClosedPosition(position); // free the slot now this position is genuinely closed
        releaseExposureForClosedPosition(position);

        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSED",
            "Position on " + position.getSymbol() + " closed via " + closeReason + " at " + filledLeg.price()
                + ", realized P&L " + pnl);

        // A fully-closed position's entire fill history should net to approximately zero —
        // everything bought was eventually sold. A genuine mismatch means the ledger and this
        // position disagree about whether it's actually fully closed, worth halting over rather
        // than silently trusting either side. Runs after the position is already closed, since
        // its exchange-side closure is real regardless of what the ledger says — what changes on
        // a mismatch is that further automated trading halts until a human investigates.
        //
        // Without the base asset, a fully-closed position whose entry paid any base-asset
        // commission would net to that commission amount, not zero, producing a false mismatch
        // on ordinary, correct closes. Resolved best-effort; a failure here falls back to the
        // commission-unaware comparison rather than skipping the check entirely.
        String baseAssetForClosedCheck = null;
        try {
            baseAssetForClosedCheck = adapter.getSymbolRules(position.getSymbol(), credential.getMode()).baseAsset();
        } catch (Exception e) {
            log.debug("Could not resolve base asset for post-close ledger reconciliation on {} ({}) -- falling back to the "
                + "commission-unaware comparison.", position.getSymbol(), e.getMessage());
        }
        try {
            var reconcileResult = positionLedgerService.reconcilePositionAgainstLedger(position.getId(), BigDecimal.ZERO, baseAssetForClosedCheck);
            if (!reconcileResult.matches()) {
                // The atomic close-update just above already committed quantity=0 to the
                // database, so correcting it now (when the ledger disagrees and is
                // known-complete) needs its own explicit follow-up write. This does not reverse
                // the position's CLOSED status or the slot/exposure release already performed
                // above — that is a bigger operational decision than "what quantity value is
                // honest," and the halt + incident below ensures a human makes that call rather
                // than this code silently re-opening a position mid-flight. See
                // ReconcileResult.resolvedQuantity's own javadoc.
                BigDecimal resolvedQty = reconcileResult.resolvedQuantity(position.isLedgerRecordingIncomplete());
                if (resolvedQty.compareTo(position.getQuantity()) != 0) {
                    position.setQuantity(resolvedQty);
                    // This update's guard is deliberately just "this exact position by id," not
                    // the usual "and status=OPEN" — by this point the atomic close-update above
                    // already committed the CLOSED status, so an OPEN guard would never match
                    // here. What this atomicity protects against here is different too: not
                    // "did someone else close/reopen it concurrently" (already resolved), but
                    // not silently discarding this ledger-driven quantity correction to a plain
                    // save() that could race with some other unrelated write to this same
                    // now-closed document.
                    mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId())),
                        new org.springframework.data.mongodb.core.query.Update().set("quantity", resolvedQty),
                        Position.class);
                }
                String reason = "Position ledger mismatch on close for " + position.getSymbol() + ": the fill ledger's full "
                    + "history for this position nets to " + reconcileResult.ledgerQuantity() + ", not the expected ~0 for a "
                    + "fully closed position — automated trading is halted until this is manually investigated and resolved.";
                profileOpt.ifPresent(p -> atomicHaltProfile(p, reason));
                credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(),
                    "POSITION_LEDGER_MISMATCH_HALT", reason);
                incidentService.raiseCritical(position.getUserId(), credential.getId(), position.getId(), filledLeg.orderId(),
                    position.getSymbol(), "POSITION_LEDGER_MISMATCH", reason);
            }
        } catch (Exception e) {
            log.warn("Position ledger reconciliation check failed for position {} (non-fatal, observability only): {}", position.getId(), e.getMessage());
        }

        writeRealOutcomeBackToSignal(position, filledLeg.price(), closeReason, pnl, position.getClosedQuantity());

        if (pnl.signum() < 0 && profileOpt.isPresent()) {
            riskEngine.recordRealizedLoss(profileOpt.get(), pnl.abs());
        }
        if (profileOpt.isPresent()) {
            riskEngine.recordAutoTradeOutcome(profileOpt.get(), position.getTriggerSource(), pnl.signum() < 0);
        }
    }

    /**
     * Handles an OCO list that reports ALL_DONE with no filled leg found — meaning the list
     * finished without anything actually selling. Both legs being canceled is the common
     * real-world cause, including this application's own cancelOco() calls from a resize.
     * Verifies against real balance before concluding anything: still held means re-protect it
     * (the old OCO is consumed either way, regardless of the reason), confirmed gone means it is
     * genuinely closed by some means outside this backend's own order flow.
     */
    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly —
    // money-consequential logic deserves a focused test without standing up the whole
    // reconcileCredential() chain.
    void handleOcoAllDoneWithNoFill(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret, Position position,
                                     long lockGeneration) {
        var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
        if (rules.baseAsset() == null) {
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSE_UNVERIFIED",
                "OCO " + position.getOcoOrderListId() + " on " + position.getSymbol() + " is ALL_DONE with no filled leg, and the "
                    + "base asset for this symbol is unknown — cannot verify against balance. Needs manual review.");
            return;
        }

        var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
        BigDecimal totalHeld = totalHeldBalance(balances, rules.baseAsset());
        BigDecimal expectedMinimum = expectedMinimumBalanceForPosition(credential.getId(), position);

        if (totalHeld.compareTo(expectedMinimum.multiply(new BigDecimal("0.98"))) >= 0) {
            // Still genuinely held — the OCO that just went ALL_DONE with nothing filled is
            // consumed regardless of why, so this position needs fresh protection now.
            position.setOcoOrderListId(null);
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
                new org.springframework.data.mongodb.core.query.Update().set("ocoOrderListId", (Object) null),
                Position.class);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "OCO_ALL_DONE_NO_FILL_STILL_HELD",
                "OCO " + position.getSymbol() + " went ALL_DONE with no filled leg, and the account's total (free+locked) " + rules.baseAsset()
                    + " holding of " + totalHeld + " still covers this position (" + position.getQuantity() + ") plus any other open "
                    + "positions in the same symbol on this credential — position was NOT closed. Re-protecting rather than assuming it sold.");
            reprotectRemainder(credential, adapter, apiKey, apiSecret, position, lockGeneration);
            return;
        }

        // Balance confirms it's actually gone — genuinely closed by some means this backend
        // didn't directly observe (manual action on the exchange, liquidation, anything).
        // Captured before atomicCloseUnverifiedPnl mutates position.quantity to zero. profileOpt
        // isn't a parameter of this method, so it's looked up here the same way
        // haltForResizeFailure/haltForProtectionAttemptPersistenceFailure do elsewhere in this
        // class.
        BigDecimal originalQuantity = position.getQuantity();
        atomicCloseUnverifiedPnl(position);
        releaseSlotForClosedPosition(position);
        releaseExposureForClosedPosition(position);
        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSE_UNVERIFIED",
            "OCO " + position.getSymbol() + " went ALL_DONE with no filled leg, and the account's total (free+locked) " + rules.baseAsset()
                + " holding (" + totalHeld + ", expected at least " + expectedMinimum + " if still held) confirms the position (qty " + position.getClosedQuantity()
                + ") is actually gone — closed outside this backend's own order flow. P&L NOT recorded, needs manual reconciliation "
                + "against Binance's actual trade history.");
        recordUnverifiedCloseRiskImpact(position, riskProfileRepo.findByCredentialId(credential.getId()), null, originalQuantity,
            "OCO ALL_DONE with no filled leg, closure confirmed by balance only");
    }

    /**
     * Re-places protection for a position whose quantity just shrank due to a partial exit
     * (the OCO that covered the original quantity is already consumed/ALL_DONE). Looks up the
     * original entry order for its recorded SL/TP prices; if it can't re-protect, emergency-
     * flattens the remainder rather than leaving it naked — same rule as everywhere else.
     */
    private void reprotectRemainder(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret, Position position,
                                     long lockGeneration) {
        // A partial exit can leave a remaining quantity below the symbol's own minQty, which
        // neither a re-protection OCO nor an emergency-flatten market sell can actually place
        // (the exchange rejects both below minQty/minNotional). This is checked here, before
        // either attempt, so the position does not sit open forever after both an OCO and an
        // emergency-flatten attempt independently fail on an amount this bot can never sell.
        var symbolRules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
        if (symbolRules != null && symbolRules.minQty() != null && position.getQuantity().compareTo(symbolRules.minQty()) < 0) {
            position.setStatus("CLOSED");
            position.setCloseReason("DUST_REMAINING");
            position.setClosedAt(LocalDateTime.now());
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(position.getId()).and("status").is("OPEN")),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("status", "CLOSED").set("closeReason", "DUST_REMAINING").set("closedAt", position.getClosedAt()),
                Position.class);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "DUST_REMAINING_AFTER_PARTIAL_EXIT",
                "Remaining " + position.getQuantity() + " " + position.getSymbol() + " after a partial exit is below the symbol's own "
                    + "minimum tradeable quantity (" + symbolRules.minQty() + ") -- cannot be re-protected via OCO or sold via emergency-flatten "
                    + "market order, since both require meeting the same minQty. Marked CLOSED rather than left open forever with an amount this "
                    + "bot can never trade. This dust remains in the account and needs manual handling (e.g. Binance's own convert-small-balances "
                    + "feature) outside this bot's scope.");
            return;
        }

        var entryOrderOpt = position.getEntryOrderId() != null ? omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId(position.getCredentialId(), position.getSymbol(), position.getEntryOrderId()) : Optional.<Order>empty();
        if (entryOrderOpt.isEmpty() || entryOrderOpt.get().getStopLossTriggerPrice() == null || entryOrderOpt.get().getTakeProfitPrice() == null) {
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_INCOMPLETE",
                "Remaining " + position.getQuantity() + " on " + position.getSymbol() + " after a partial exit has no recorded "
                    + "SL/TP to re-protect with — emergency-flattening rather than leaving it naked.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "No SL/TP price recorded to protect the remainder after a partial OCO exit.");
            return;
        }
        Order entryOrder = entryOrderOpt.get();
        BigDecimal stopLimit = entryOrder.getStopLossTriggerPrice().multiply(BigDecimal.ONE.subtract(resolveStopLossLimitGapPercent(entryOrder)));
        // Timestamp kept in the id basis for the same reason as the resize site: re-protecting
        // the remainder can genuinely be attempted more than once for the same position.
        String listClientOrderId = OrderService.generateClientOrderId("tv-rem", position.getId() + ":REMAINDER:" + System.currentTimeMillis());

        // Same OMS wiring as every other OCO placement site.
        com.tradevision.model.Order remainderOmsOrder;
        try {
            remainderOmsOrder = orderService.create(position.getUserId(), credential.getId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(),
                entryOrder.getTakeProfitPrice(), listClientOrderId);
            // Same TP/SL/role stamping as every other OCO placement site.
            remainderOmsOrder.setOrderRole("OCO_EXIT");
            remainderOmsOrder.setTakeProfitPrice(entryOrder.getTakeProfitPrice());
            remainderOmsOrder.setStopLossTriggerPrice(entryOrder.getStopLossTriggerPrice());
            remainderOmsOrder.setStopLossLimitPrice(stopLimit);
            remainderOmsOrder.setStopLossLimitGapPercent(resolveStopLossLimitGapPercent(entryOrder));
            orderService.markRiskAccepted(remainderOmsOrder);
            orderService.markSubmitting(remainderOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for remainder OCO re-protection failed (non-fatal, additive record only): {}", e.getMessage());
            remainderOmsOrder = null;
        }

        // This is the real point of no return — once placeExitOco lands, this application no
        // longer has the option not to have placed the order. A lost lease here is handled
        // differently from a simple abort: this position has already had its old OCO
        // consumed/cleared by this point, and the next-pass unprotected-position check only ever
        // closes an unprotected position whose balance is gone, never re-attempting protection
        // for one still genuinely held. A plain early return would leave this position open and
        // naked indefinitely, so emergency-flattening is used instead, matching this method's
        // "protect or flatten, never strand" principle — safe even with the reconciliation lease
        // gone, since emergencyFlatten acquires its own separate, position-specific lock.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before placeExitOco in reprotectRemainder "
                + "(position {}) -- another instance may now own this lock. Emergency-flattening rather than leaving this position naked.",
                credential.getId(), position.getId());
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Lost the reconciliation lock immediately before the remainder after a partial exit could be re-protected with an OCO.");
            return;
        }
        // Same pre-submission durability as every other OCO placement site above.
        String protectionAttemptId3 = createProtectionAttempt(position, listClientOrderId, position.getQuantity(),
            entryOrder.getTakeProfitPrice(), entryOrder.getStopLossTriggerPrice());
        // Same LIVE-scoped halt-before-exchange-call as every other OCO call site above.
        if (protectionAttemptId3 == null && credential.getMode() == BrokerMode.LIVE) {
            log.error("LIVE credential {} -- could not persist the pre-submission protection record for position {} ({}). The OCO "
                + "exchange call will NOT be made this cycle; halting further autonomous trading on this credential and raising a "
                + "critical incident, since this position is now genuinely unprotected.", credential.getId(), position.getId(), position.getSymbol());
            haltForProtectionAttemptPersistenceFailure(credential, position);
            incidentService.raiseCritical(position.getUserId(), position.getCredentialId(), position.getId(), null, position.getSymbol(),
                "PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT",
                "Could not persist the pre-submission protection record for " + position.getSymbol() + " (position " + position.getId()
                    + ") -- the OCO exchange call was NOT made this cycle. This position is currently UNPROTECTED and this credential "
                    + "has been auto-halted. Manual intervention required: either resolve the underlying database issue and let the "
                    + "next cycle retry, or manually place protection/close this position directly.");
            return;
        }
        // Same exchangeCallStartedAt stamping as every other OCO call site above.
        if (remainderOmsOrder != null) {
            try {
                orderService.markExchangeCallStarted(remainderOmsOrder);
            } catch (Exception e) {
                log.warn("Could not stamp exchangeCallStartedAt for remainder OCO order {} (non-fatal, additive record only): {}", remainderOmsOrder.getId(), e.getMessage());
            }
        }
        OcoOrderResult oco = adapter.placeExitOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(),
            position.getQuantity(), entryOrder.getTakeProfitPrice(), entryOrder.getStopLossTriggerPrice(), stopLimit, listClientOrderId);
        resolveProtectionAttempt(protectionAttemptId3, oco.success());
        // The very first thing after the exchange call succeeds, before any other processing —
        // same as every other OCO placement site above. See createOrphanForOco's own javadoc.
        String orphanId3 = (oco.success() && oco.ocoOrderListId() != null)
            ? createOrphanForOco(position, oco.ocoOrderListId(), oco.actualProtectedQuantity())
            : null;
        // Same base-asset-fee handling as every other OCO placement site — see
        // Position.protectedQuantity's own field javadoc.
        if (oco.success() && oco.actualProtectedQuantity() != null) {
            position.setProtectedQuantity(oco.actualProtectedQuantity());
        }
        if (remainderOmsOrder != null) {
            try {
                orderService.recordOcoPlacementResult(remainderOmsOrder, oco);
            } catch (Exception e) {
                log.warn("OMS recordOcoPlacementResult failed for remainder OCO order {} (non-fatal, additive record only): {}", remainderOmsOrder.getId(), e.getMessage());
            }
        }

        if (oco.success()) {
            atomicSetOcoPlaced(position, oco.ocoOrderListId(), oco.actualProtectedQuantity(), orphanId3);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZED",
                "Remaining " + position.getQuantity() + " on " + position.getSymbol() + " re-protected after a partial exit.");
        } else {
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_FAILED",
                "Could not re-protect remaining " + position.getQuantity() + " on " + position.getSymbol()
                    + " after a partial exit: " + oco.errorMessage() + " — emergency-flattening rather than leaving it naked.");
            // Records a known-but-not-active OCO before flattening, the same as the other
            // placeExitOco failure branches, letting emergencyFlatten's own state machine
            // verify fill state rather than discarding it here.
            if (oco.ocoOrderListId() != null) {
                atomicSetOcoPlaced(position, oco.ocoOrderListId(), null, orphanId3);
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Could not re-place protection for the remainder after a partial OCO exit: " + oco.errorMessage());
        }
    }

    /**
     * Writes the real, broker-confirmed outcome of an auto-traded position back onto its
     * originating TradeCallRecord, so the signal's recorded result reflects what actually
     * happened on the exchange rather than a theoretical guess from ticker-crossing checks.
     *
     * closedQuantity must be passed explicitly by the caller rather than read from
     * position.getQuantity(), since by the time this runs that field is always zero (set
     * earlier in the same close transition). Using the caller-supplied value with BigDecimal
     * division, plus an explicit finite check before persisting, avoids ever computing or
     * storing an Infinity/NaN pnlPct on production trade accounting.
     */
    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly, in
    // isolation, without driving the entire reconciliation chain just to reach it.
    void writeRealOutcomeBackToSignal(Position position, java.math.BigDecimal exitPrice, String closeReason,
                                               java.math.BigDecimal pnl, java.math.BigDecimal closedQuantity) {
        if (position.getSignalId() == null) return;
        callRepo.findById(position.getSignalId()).ifPresent(call -> {
            var outcome = call.getOutcome() != null ? call.getOutcome() : new com.tradevision.model.TradeOutcome();
            outcome.setResult(closeReason); // STOP_LOSS / TAKE_PROFIT / EMERGENCY_FLATTEN — real, not a guessed HIT_T1/T2/T3
            outcome.setExitPrice(exitPrice.doubleValue());
            outcome.setResolvedAt(LocalDateTime.now());
            // call.getEntryPrice() is BigDecimal, so this computes the P&L percentage directly
            // without a double-to-BigDecimal wrapper conversion.
            if (call.getEntryPrice().signum() > 0 && closedQuantity != null && closedQuantity.signum() > 0) {
                java.math.BigDecimal notional = call.getEntryPrice().multiply(closedQuantity);
                java.math.BigDecimal pnlPctExact = pnl.divide(notional, 8, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100));
                double pnlPct = pnlPctExact.doubleValue();
                // Explicit finite check before persisting — never trust that upstream math (or a
                // future change to how pnl/notional get computed) can't produce a bad value.
                if (Double.isFinite(pnlPct)) {
                    outcome.setPnlPct(Math.round(pnlPct * 1000.0) / 1000.0);
                } else {
                    log.error("Refusing to persist non-finite pnlPct ({}) for signal {} — pnl={}, notional={}. Leaving pnlPct unset rather than corrupting analytics.",
                        pnlPct, position.getSignalId(), pnl, notional);
                }
            }
            if (call.getCalledAt() != null) {
                long minutes = java.time.Duration.between(call.getCalledAt(), LocalDateTime.now()).toMinutes();
                outcome.setDurationMinutes(minutes);
            }
            call.setOutcome(outcome);
            callRepo.save(call);

            // This is the one place in the codebase where a signal's outcome is backed by a
            // real, broker-confirmed fill — this method only runs from a genuine position close,
            // driven by filledLeg/pnl this reconciliation pass just verified against the
            // exchange. Feeding mlWeightService.recordOutcome from here, keyed off this method's
            // own real, verified pnl sign rather than the close reason string (a clean
            // MAX_HOLD_TIME/END_OF_SESSION exit near breakeven counts as neither a win nor a
            // loss, consistent with the EXPIRED case), means only real, executed outcomes reach
            // the learner feeding the global, per-symbol weights every user's live
            // signal-scoring reads from.
            if (call.getFeatures() != null) {
                try {
                    String mlResult = pnl.signum() > 0 ? "HIT_T1" : (pnl.signum() < 0 ? "HIT_SL" : closeReason);
                    mlWeightService.recordOutcome(call.getMarket(), call.getSymbol(), mlResult,
                        "LONG".equals(call.getDirection()), call.getFeatures().getRsi(), call.getFeatures().isMacdBull(),
                        call.getFeatures().getPatterns(), call.getFeatures().getVolumeRatio());
                } catch (Exception e) {
                    log.warn("ML weight recording failed for real outcome on signal {} (non-fatal, additive only): {}",
                        position.getSignalId(), e.getMessage());
                }
            }
        });
    }

    /**
     * Reconciles a position with no OCO, which can't be checked via order-list status since
     * there is no order list. The best available signal without a user-data-stream websocket is
     * whether the base-asset balance has dropped below what this position should still hold. If
     * so, something closed it outside this application's own order flow (manual exchange action,
     * a liquidation, anything) — this flags it rather than silently leaving it OPEN forever, but
     * does not fabricate an exit price or P&L that isn't actually known.
     */
    private void reconcileUnprotectedPosition(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                               Position position, long lockGeneration) {
        var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
        if (rules.baseAsset() == null) return;

        var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
        BigDecimal totalHeld = totalHeldBalance(balances, rules.baseAsset());
        BigDecimal expectedMinimum = expectedMinimumBalanceForPosition(credential.getId(), position);

        if (totalHeld.compareTo(expectedMinimum.multiply(new BigDecimal("0.98"))) < 0) {
            // getSymbolRules and getBalance above are both real network round-trips, so the
            // lock is renewed immediately before this position's consequential close.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} immediately before closing unprotected position {} -- "
                    + "another instance may now own this lock. Aborting before mutating.", credential.getId(), position.getId());
                return;
            }
            // Captured before atomicCloseUnverifiedPnl mutates position.quantity to zero. This
            // is a position with no OCO at all, so there is no OCO_EXIT order to fall back to
            // for a worst-case price either — recordUnverifiedCloseRiskImpact makes no estimate
            // and logs why in that case, rather than guessing.
            BigDecimal originalQuantity = position.getQuantity();
            atomicCloseUnverifiedPnl(position);
            releaseSlotForClosedPosition(position); // no longer open — free the slot regardless of P&L uncertainty
            releaseExposureForClosedPosition(position);
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSE_UNVERIFIED",
                "Unprotected position on " + position.getSymbol() + " (qty " + position.getClosedQuantity() + ") — the account's total (free+locked) "
                    + rules.baseAsset() + " holding (" + totalHeld + ", expected at least " + expectedMinimum + " if still held, "
                    + "accounting for any other open positions in this symbol on this credential) is now below that, meaning it "
                    + "closed outside this backend's own order flow. P&L NOT recorded — needs manual reconciliation "
                    + "against Binance's actual trade history.");
            recordUnverifiedCloseRiskImpact(position, riskProfileRepo.findByCredentialId(credential.getId()), null, originalQuantity,
                "unprotected position closed, confirmed by balance only");
            return;
        }

        // Everything above only checks whether the balance has disappeared. When the coins are
        // genuinely still here (this branch), the position must not simply stay OPEN with
        // unlimited downside — every reconcile pass resolves this by either re-protecting with
        // a fresh OCO from the entry order's recorded SL/TP, or exiting immediately if that
        // can't be done safely.
        var entryOrderOpt = position.getEntryOrderId() != null ? omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId(position.getCredentialId(), position.getSymbol(), position.getEntryOrderId()) : Optional.<Order>empty();
        if (entryOrderOpt.isEmpty() || entryOrderOpt.get().getStopLossTriggerPrice() == null || entryOrderOpt.get().getTakeProfitPrice() == null) {
            // No recorded SL/TP to re-protect with at all -- "mark unprotected and hope" is not
            // an acceptable state for real money. Flatten now rather than leave it naked forever.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_MISSING_NO_SLTP",
                "Position on " + position.getSymbol() + " has no active OCO and no recorded SL/TP price to re-protect it with — "
                    + "emergency-flattening rather than leaving it open and unprotected.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "No OCO and no recorded SL/TP price for a genuinely still-held position -- cannot safely re-protect.");
            return;
        }

        BigDecimal stopTrigger = entryOrderOpt.get().getStopLossTriggerPrice();
        BigDecimal currentPrice;
        try {
            currentPrice = adapter.getCurrentPrice(position.getSymbol(), credential.getMode());
        } catch (Exception e) {
            log.warn("Could not fetch current price for {} while re-protecting unprotected position {} (will retry next pass): {}",
                position.getSymbol(), position.getId(), e.getMessage());
            return;
        }

        if (currentPrice.compareTo(stopTrigger) <= 0) {
            // Price has already moved through (or is exactly at) the recorded stop trigger --
            // placing a new STOP_LOSS_LIMIT order with that same trigger would be rejected by
            // Binance as "would trigger immediately." Exiting at market is the only safe option
            // left, the same handling used for a stuck triggered stop in
            // reconcileOcoProtectedPosition's own handleStopTriggeredButUnfilled.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_MISSING_PRICE_BELOW_STOP",
                "Position on " + position.getSymbol() + " has no active OCO, and current price " + currentPrice
                    + " is already at or below the recorded stop trigger " + stopTrigger + " — a new stop order at that price would be "
                    + "rejected as already-triggered. Emergency-flattening at market instead of leaving this position falling unprotected.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "No OCO, and current price is already through the recorded stop trigger — flattening at market rather than re-placing a stop that would be rejected.");
            return;
        }

        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before re-protecting unprotected position {} -- "
                + "another instance may now own this lock. Aborting before mutating.", credential.getId(), position.getId());
            return;
        }
        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_MISSING_REPLACING",
            "Position on " + position.getSymbol() + " has no active OCO but is genuinely still held (current price " + currentPrice
                + " is still above the recorded stop trigger " + stopTrigger + ") — re-placing protection from the entry order's own recorded SL/TP.");
        // reprotectRemainder already implements exactly this: look up the entry order's SL/TP,
        // place a fresh OCO for position.getQuantity(), or emergency-flatten if that fails --
        // the same mechanism this class already relies on after a partial exit consumes the old
        // OCO. Reused here rather than duplicated, since the two situations ("no OCO because the
        // old one was just consumed" and "no OCO because reconciliation just discovered one is
        // missing") need identical handling from this point on.
        reprotectRemainder(credential, adapter, apiKey, apiSecret, position, lockGeneration);
    }

    /**
     * Checks mark-to-market drawdown against the configured limit and halts trading if breached.
     * Equity is free+locked quote-asset balance plus the current market value of every open
     * position on this credential — an underwater open position moves the drawdown number
     * immediately rather than only once it closes, which matters for a risk breaker meant to
     * catch trouble early.
     *
     * This assumes every open position's symbol quotes in the same asset as drawdownQuoteAsset
     * (e.g. every enabled symbol is a *USDT pair when drawdownQuoteAsset is USDT) — a position
     * quoted in a different asset would need a currency conversion this does not perform. This
     * is the same implicit assumption RiskEngineService's exposure caps already make when they
     * sum quantity × avgEntryPrice across positions without checking quote-asset consistency.
     * If any open position's current price can't be fetched, the whole check is skipped for
     * this cycle rather than computing equity from a partial position list, since a partial
     * number could understate or overstate drawdown depending on which position was excluded.
     *
     * This does not attempt full account valuation (non-bot holdings on this credential, other
     * assets genuinely part of the account's net worth) or deposit/withdrawal detection — both
     * would require classifying every balance change over time as either trading P&L or an
     * external capital flow, which this application has no infrastructure for (no
     * balance-history ledger, no deposit/withdrawal webhook or polling). Concretely: a deposit
     * into this account still raises peakEquityQuote as if it were trading profit, a withdrawal
     * can still trip the drawdown halt as if it were a trading loss, and peakEquityQuote is
     * never reset — these remain open gaps that would need capital-flow tracking to close.
     */
    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly,
    // without standing up the whole reconcileCredential() call chain (order/OCO reconciliation,
    // symbol rules, etc.) just to test the drawdown math in isolation.
    void checkDrawdown(BrokerCredential credential, BrokerAdapter adapter) {
        Optional<RiskProfile> profileOpt = riskProfileRepo.findByCredentialId(credential.getId());
        if (profileOpt.isEmpty()) return;
        RiskProfile profile = profileOpt.get();
        if (profile.getMaxDrawdownPercent() <= 0 || profile.getDrawdownQuoteAsset() == null) return;

        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
        // free+locked, not free alone: quote-asset locked in a resting LIMIT order, a pending
        // operation's margin/collateral leg, or reserved by the exchange for any other reason is
        // still this account's own money, just not currently free to spend. Treating it as zero
        // could both fabricate a drawdown breach that never really happened (funds "vanish" from
        // equity the moment they're locked, "reappear" when unlocked) and mask a genuine one
        // (real losses hiding behind funds never counted toward the peak to begin with).
        BigDecimal quoteBalance = balances.stream()
            .filter(b -> b.asset().equalsIgnoreCase(profile.getDrawdownQuoteAsset()))
            .map(b -> b.free().add(b.locked() != null ? b.locked() : BigDecimal.ZERO))
            .findFirst().orElse(null);
        if (quoteBalance == null) return;

        List<Position> openPositions = positionRepo.findByUserIdAndCredentialIdAndStatus(
            credential.getUserId(), credential.getId(), "OPEN");
        BigDecimal openPositionsMarketValue = BigDecimal.ZERO;
        for (Position p : openPositions) {
            try {
                BigDecimal currentPrice = adapter.getCurrentPrice(p.getSymbol(), credential.getMode());
                openPositionsMarketValue = openPositionsMarketValue.add(p.getQuantity().multiply(currentPrice));
            } catch (Exception e) {
                // Inability to price an open position is treated as a risk event, not a free
                // pass: halts new trading and raises an incident, rather than silently skipping
                // the entire drawdown check cycle. Drawdown protection must stay active during
                // exactly the kind of market-data disruption (an exchange outage, a network
                // partition) that also makes real losses more likely. Existing exchange-side
                // protection (OCO stop-loss/take-profit orders already placed on the exchange)
                // is left completely untouched, since those don't depend on this application
                // being able to price anything right now.
                log.error("Could not price open position {} ({}) for mark-to-market drawdown -- treating this as a risk event, "
                    + "not skipping the check: {}", p.getId(), p.getSymbol(), e.getMessage());
                String reason = "Drawdown check could not price open position " + p.getSymbol() + " (" + e.getMessage() + ") -- "
                    + "unable to calculate real current equity, so new autonomous trading is halted until pricing is confirmed "
                    + "working again. Existing exchange-side stop-loss/take-profit protection on open positions is unaffected.";
                atomicHaltProfile(profile, reason);
                credentialService.audit(profile.getUserId(), credential.getId(), credential.getBroker(), "DRAWDOWN_PRICING_FAILED_HALT", reason);
                incidentService.raiseCritical(profile.getUserId(), credential.getId(), p.getId(), null,
                    p.getSymbol(), "DRAWDOWN_PRICING_UNAVAILABLE", reason);
                return;
            }
        }
        BigDecimal currentEquity = quoteBalance.add(openPositionsMarketValue);

        // MongoDB's $max operator applies "update only if the new value is greater" atomically
        // in one step, avoiding the read-modify-write race a conditional query would have.
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update().max("peakEquityQuote", currentEquity),
            RiskProfile.class);
        RiskProfile refreshed = mongoTemplate.findOne(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            RiskProfile.class);
        if (refreshed == null) return; // profile deleted concurrently — nothing further to check
        profile.setPeakEquityQuote(refreshed.getPeakEquityQuote());

        if (profile.getPeakEquityQuote() == null || currentEquity.compareTo(profile.getPeakEquityQuote()) >= 0) {
            return; // at or above peak — no drawdown to evaluate
        }

        BigDecimal peak = profile.getPeakEquityQuote();
        if (peak.signum() <= 0) return;
        double drawdownPct = peak.subtract(currentEquity).divide(peak, 6, java.math.RoundingMode.HALF_UP).doubleValue() * 100.0;

        if (drawdownPct >= profile.getMaxDrawdownPercent() && !profile.isTradingHalted()) {
            profile.setTradingHalted(true);
            profile.setHaltReason("Max drawdown reached: " + String.format("%.1f", drawdownPct)
                + "% below peak mark-to-market equity of " + peak + " " + profile.getDrawdownQuoteAsset()
                + " (limit " + profile.getMaxDrawdownPercent() + "%). Current: " + quoteBalance + " free+locked " + profile.getDrawdownQuoteAsset()
                + " + " + openPositionsMarketValue + " in open positions = " + currentEquity + ".");
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                new org.springframework.data.mongodb.core.query.Update().set("tradingHalted", true).set("haltReason", profile.getHaltReason()),
                RiskProfile.class);
            credentialService.audit(credential.getUserId(), credential.getId(), credential.getBroker(),
                "DRAWDOWN_HALT", profile.getHaltReason());
            incidentService.raiseCritical(credential.getUserId(), credential.getId(), null, null,
                null, "RISK_LIMIT_BREACH", profile.getHaltReason());
        }
    }

    /** Halts new autonomous trading on this credential without touching the existing (still-live)
     *  OCO — deliberately not calling PositionSafetyService.emergencyFlatten here, since the old
     *  OCO cancellation just failed and may still be active; flattening blind risks colliding
     *  with a live order. */
    void haltForResizeFailure(BrokerCredential credential, Position position) {
        riskProfileRepo.findByCredentialId(credential.getId()).ifPresent(profile -> {
            if (profile.isTradingHalted()) return;
            String reason = "Auto-halted: could not cancel the mis-sized OCO " + position.getOcoOrderListId() + " on "
                + position.getSymbol() + " after a quantity correction -- the existing OCO may still be active on the exchange, "
                + "so no automatic action (flatten or re-place) was attempted. This position's protection quantity does not "
                + "match its actual quantity and needs manual reconciliation.";
            profile.setTradingHalted(true);
            profile.setHaltReason(reason);
            profile.setUpdatedAt(LocalDateTime.now());
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("tradingHalted", true).set("haltReason", reason).set("updatedAt", profile.getUpdatedAt()),
                RiskProfile.class);
        });
    }

    /** Same atomic halt mechanism as haltForResizeFailure above, for a LIVE credential whose
     *  pre-submission ProtectionAttempt record could not be persisted, meaning the OCO exchange
     *  call for it must not be made at all — see createProtectionAttempt's own callers for the
     *  skip logic this halt accompanies. */
    void haltForProtectionAttemptPersistenceFailure(BrokerCredential credential, Position position) {
        riskProfileRepo.findByCredentialId(credential.getId()).ifPresent(profile -> {
            if (profile.isTradingHalted()) return;
            String reason = "Auto-halted: could not persist the pre-submission protection record for " + position.getSymbol()
                + " -- the OCO exchange call was NOT made, since doing so without a durable local record first would leave this "
                + "application with no way to recover it after a crash. This position is currently UNPROTECTED and needs manual attention.";
            profile.setTradingHalted(true);
            profile.setHaltReason(reason);
            profile.setUpdatedAt(LocalDateTime.now());
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("tradingHalted", true).set("haltReason", reason).set("updatedAt", profile.getUpdatedAt()),
                RiskProfile.class);
        });
    }
}
