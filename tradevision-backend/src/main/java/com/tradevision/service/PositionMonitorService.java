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
 * Review items #4 and #6, addressed together since they're really the same problem: this used
 * to infer order state from "is it still in the open-orders list" and never fed realized P&L
 * back into the daily-loss counter at all. Now:
 *
 *  - Entry orders still marked NEW get their real status via getOrderStatus(brokerOrderId) —
 *    ground truth by id, not a symbol-matching guess.
 *  - Open Positions with a protective OCO get polled via getOcoStatus(orderListId); once the
 *    list reports done, the position is closed with a real exit price and the P&L is computed
 *    and pushed into RiskEngineService.recordRealizedLoss, which is what actually drives the
 *    daily-loss kill switch.
 *
 * Still a poller, still documented as a stand-in for a proper websocket user-data-stream
 * (review item #30) — that's a real architecture change, not something to fake here.
 */
@Service
@RequiredArgsConstructor
public class PositionMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PositionMonitorService.class);
    /**
     * Review finding ("Some repository queries return unlimited lists" -- external review,
     * thirty-eighth pass, P2, full context in ProtectionAttemptRepository's own updated method
     * javadoc): the per-cycle cap for recoverStuckProtectionAttempts and recoverOrphanedOcos.
     */
    private static final int RECOVERY_BATCH_SIZE = 200;
    // Audit fix (P0-3 follow-up, full context in AutoTradeService.stopLossLimitGapPercent's own
    // field javadoc): the same configurable stop-limit gap used when AutoTradeService first
    // places an exit OCO, reused here (resize, late-fill-discovery, and entry-order-remainder
    // OCO placement all independently recompute this same stopLimit price from the order's own
    // stopLossTriggerPrice) so widening the gap via application.properties takes effect
    // consistently everywhere this codebase ever derives a stop-limit price, not just at initial
    // placement.
    @org.springframework.beans.factory.annotation.Value("${app.trading.stop-loss-limit-gap-percent:0.005}")
    private BigDecimal stopLossLimitGapPercent;

    private final BrokerCredentialRepository credentialRepo;
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // reconcileEntryOrders's own updated javadoc): confirmed genuinely unused after this pass's
    // own migration -- every actual method call moved to omsOrderRepo below. Removed rather
    // than left as dead weight.
    private final com.tradevision.repository.OrderRepository omsOrderRepo;
    /**
     * Review finding ("Emergency flatten still allows an exchange sell without durable
     * pre-submission intent" -- external review, twenty-sixth pass, P1, full context in
     * FlattenAttempt's own class javadoc): needed for the actual recovery fallback.
     */
    private final com.tradevision.repository.FlattenAttemptRepository flattenAttemptRepo;
    private final com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    /**
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): needed
     * to create the pre-submission durable record at every real OCO placement call site below.
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
    /**
     * User's own explicit architectural request, full context in ExecutionContext's own class
     * javadoc.
     */
    private final ExecutionContextService executionContextService;
    private final com.tradevision.config.StartupState startupState;
    private final com.tradevision.config.TradingHeartbeatService heartbeatService;
    private final FillLedgerService fillLedgerService;
    // Review finding (P1 — "Startup reconciliation is good, but startup trading should remain
    // disabled until reconciliation completes" — full context in StartupState's own javadoc):
    // reset to 0 immediately before the startup pass specifically (see reconcileOnStartup), and
    // read once that pass completes to decide TRADING_ENABLED vs RECONCILIATION_FAILED. Also
    // incremented by any LATER periodic cycle's own per-credential failures (the catch block
    // above is shared code), which is harmless — nothing reads this counter again after the
    // startup pass has already consumed it once.
    private final java.util.concurrent.atomic.AtomicInteger reconciliationFailureCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final PositionSafetyService positionSafetyService;
    private final RiskProfileRepository riskProfileRepo;
    private final com.tradevision.repository.TradeCallRepository callRepo;
    // Review finding (P1 #5 -- "Global ML weights can be poisoned by unverified, client-supplied
    // trade outcomes"): full context in writeRealOutcomeBackToSignal's own updated comment --
    // needed to feed the ML learner from this method's own real, broker-confirmed fill outcomes
    // instead of leaving it to learn exclusively from CallResultUpdater's guesses.
    private final MLWeightService mlWeightService;
    private final BrokerCredentialService credentialService;
    private final RiskEngineService riskEngine;
    private final List<BrokerAdapter> adapters;
    // Review finding ("Position P&L architecture is still scattered" -- full context in
    // RealizedPnlService's own javadoc): this class's own OCO/TP/SL/partial-exit P&L formula
    // now calls the single, verified, consolidated implementation.
    private final RealizedPnlService realizedPnlService;
    // Review finding ("Position Ledger is still not authoritative" -- full context in
    // PositionLedgerService's own javadoc): needed to cross-check a closed position's full fill
    // history against the expected ~0 net, escalating a genuine mismatch to a halt.
    private final PositionLedgerService positionLedgerService;
    // Review finding ("OMS not actually authoritative" -- P0, full context in OrderService's own
    // top-level javadoc): needed now that every OCO placement site in this class (resize,
    // late-fill protection, remainder re-protection) gets a real OMS Order record.
    private final OrderService orderService;

    private Map<com.tradevision.model.BrokerType, BrokerAdapter> adapterMap;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> reconciliationLocks
        = new java.util.concurrent.ConcurrentHashMap<>();
    // Review finding ("Single-instance assumption for autonomous trading/reconciliation -- no
    // distributed lock, unsafe to scale replicas" -- P0, full context in
    // DistributedLockService's own javadoc): needed for the real, cross-process reconciliation
    // lock. instanceId is generated once per JVM at startup -- stable for this process's whole
    // lifetime, distinct from every other instance's own id, which is exactly what a lock
    // "owner" identifier needs to be.
    private final DistributedLockService distributedLockService;
    private final String instanceId = java.util.UUID.randomUUID().toString();

    // CI-review fix ("Position recovery after a filled order" -- real CI run, GitHub Actions log
    // archive downloaded and inspected directly: PositionPersistenceRecoveryIntegrationTest
    // expected status "OPEN" but got "CLOSED_UNVERIFIED_PNL", even after an earlier fix already
    // gave the test order a real SL/TP and a stubbed OCO placement): the actual remaining cause,
    // confirmed by direct inspection -- createPositionForLateDiscoveredFill calls
    // distributedLockService.renew(credential.getId(), instanceId, lockGeneration, ...)
    // immediately before placing the protective OCO (see that call site's own comment), and
    // emergency-flattens the brand-new position if that renewal fails. The test calls
    // createPositionForLateDiscoveredFill directly, with no matching ReconciliationLock ever
    // acquired for its test credential -- renew() correctly finds zero matching documents
    // (instanceId is generated once per JVM, privately, and was not exposed for a test to target
    // even if it tried) and returns false, exactly as it should for a real caller with no lock,
    // which is why the position was emergency-flattened instead of staying OPEN. Not a production
    // bug -- a missing test fixture, same category as the earlier SL/TP fix in this same test --
    // but the earlier fix alone wasn't the whole gap. This getter is the minimal, harmless
    // addition that lets a test actually acquire a real lock under this exact instanceId before
    // calling a method that renews against it; nothing else in this class is changed.
    public String getInstanceId() {
        return instanceId;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000, scheduler = "reconciliationScheduler")
    public void reconcile() {
        doReconcile();
    }

    /**
     * Review item #29 (partial): reconcile against real broker state as soon as the app is up,
     * not on the scheduler's initial 30s delay. This does NOT make the system crash-survivable
     * in the full sense the review means (there's no persisted event log, no replay of missed
     * signals) — it closes the specific, cheap gap where open positions sit unverified for up
     * to 90 seconds after every restart.
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
     * Audit item P0-3 fix. Full context in SchedulingConfig.watchdogScheduler's own javadoc --
     * this is NOT new detection logic. reconcileOcoProtectedPosition (and the
     * handleStopTriggeredButUnfilled check it calls whenever the OCO list isn't ALL_DONE) already
     * correctly detects a stop-limit leg that's triggered-but-unfilled and emergency-flattens
     * it -- the only real gap was that it only ran once every 60 seconds, via the main
     * reconciliationScheduler cadence. This re-runs that exact same method, every 10 seconds,
     * scoped to ONLY the OPEN positions that actually have a live OCO (ocoOrderListId set) --
     * deliberately not the full reconcileCredentialLocked chain (entry-order reconciliation,
     * orphaned-OCO recovery, stuck-protection-attempt recovery, max-hold/end-of-session/risk-exit
     * enforcement), which stay on the 60s cadence since re-running all of that every 10 seconds
     * would multiply broker API load for no safety benefit -- none of those other steps are time-
     * critical in the way a fast-moving stop-loss is.
     *
     * Reuses the exact same per-credential locking this class's own main reconciliation path
     * uses (the JVM-local reconciliationLocks entry, then the real distributed lock) so the two
     * can never mutate the same credential's positions concurrently -- whichever one is already
     * running for a given credential, the other simply skips that credential for this cycle and
     * retries on its own next tick, the same non-blocking "skip, don't queue" pattern already
     * established throughout this class.
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
     * Review finding ("P0 #2" — "Your balance-based position detection is not reliable enough"):
     * confirmed real. The old check compared a position's own quantity against the account's
     * TOTAL free balance for that asset — which conflates three genuinely different things: (1)
     * coins the user holds completely unrelated to this position (the review's own example: user
     * owns 1.00 BTC personally, a 0.10 BTC TradeVision position closes externally, the check
     * still sees 1.00 BTC free and wrongly concludes the 0.10 BTC position is still held), (2)
     * coins locked in ANY open order (using free alone UNDERSTATES real holdings — the review's
     * "reverse problem"), and (3) coins belonging to this credential's OTHER open positions in
     * the same symbol, which also compete for the same balance pool.
     *
     * This closes (2) and (3) directly and verifiably: free+locked (not free alone) is the
     * account's actual total holding, and subtracting what this credential's OTHER open
     * positions in the SAME symbol should account for isolates what SHOULD remain for the
     * position actually being checked.
     *
     * HONEST LIMITATION, not fixed here: (1) is NOT solved, and cannot be solved by any balance
     * comparison alone, no matter how it's computed — spot balances are fungible; there is no
     * exchange-side way to tag "these specific coins belong to this specific bot position."
     * The review's own suggested production solution — a full per-position asset ledger tracking
     * entryNetQty/entryFills/exitFills/externalBalanceDelta per position — is the only way to
     * fully close this, and is real further scope: a new model, and threading it through every
     * entry, exit, and reconciliation path in this codebase. Not something to fake as "fixed"
     * with a better balance formula alone.
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

    /** free+locked, not free alone — see expectedMinimumBalanceForPosition's javadoc for why. */
    private BigDecimal totalHeldBalance(List<com.tradevision.service.broker.dto.AssetBalance> balances, String asset) {
        return balances.stream()
            .filter(b -> b.asset().equalsIgnoreCase(asset))
            .map(b -> (b.free() != null ? b.free() : BigDecimal.ZERO).add(b.locked() != null ? b.locked() : BigDecimal.ZERO))
            .findFirst().orElse(BigDecimal.ZERO);
    }

    /**
     * Review finding ("P0 #3" — full context in ExposureReservation's javadoc): exposure must
     * stay reserved for a position's entire OPEN lifetime, not just the brief order-placement
     * window — released here, at every point a position is genuinely closed, mirroring exactly
     * how slotReservationService.release() already works. Uses closedQuantity (not quantity,
     * already zeroed by the time this runs at every call site) and avgEntryPrice as the best
     * available estimate of what this position's reservation actually represented — a position
     * with an unverified/unknown entry price releases nothing here (there's no honest dollar
     * figure to release), and the periodic reconcile() call self-heals any resulting drift, same
     * as it already does for the slot count.
     */
    private void releaseExposureForClosedPosition(Position position) {
        // Review finding ("Generic exposure release() has the same ownership problem" --
        // external review, twenty-sixth pass, P0, full context in ExposureReservationRecord's
        // own class javadoc): this used to always release quantity*avgEntryPrice -- a
        // recomputed value the review itself shows can legitimately differ from the original
        // quantity*livePrice reservation. Now releases the EXACT reservation by id when this
        // position has one; only positions that predate this field fall back to the old,
        // amount-based approximation.
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
     * Review finding ("Position slot reservations still don't have ownership IDs" -- external
     * review, twenty-eighth pass, P0, full context in PositionSlotReservationRecord's own class
     * javadoc): the same fallback pattern as releaseExposureForClosedPosition just above,
     * applied to both slot-reservation tiers. Centralized here since this method is called from
     * 5 separate close-time sites in this file.
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
        // User's own explicit architectural request, full context in ExecutionContext's own
        // class javadoc: this helper is already called from every real close path in this
        // class (confirmed by direct inspection, not assumed -- it's the shared release point
        // this session's own earlier P0-2 fix centralized specifically so a slot release
        // couldn't be missed at any of the 5 original call sites), making it the one place that
        // reliably marks an execution's own lifecycle CLOSED regardless of which specific close
        // path actually triggered it.
        executionContextService.recordClosedByPositionId(position.getId());
    }

    private void doReconcile() {
        // Review finding (P1 #29 — "Shutdown is improved but still not a true trading
        // shutdown"): stops a NEW reconciliation cycle from starting once shutdown has begun —
        // see ShutdownState's own javadoc for exact scope (doesn't interrupt work already in
        // progress, only prevents new work from starting).
        if (shutdownState.isShuttingDown()) {
            log.info("Shutdown in progress — skipping this reconciliation cycle.");
            return;
        }
        if (adapterMap == null) {
            adapterMap = adapters.stream().collect(Collectors.toMap(BrokerAdapter::getType, Function.identity()));
        }
        for (BrokerCredential credential : credentialRepo.findAll()) {
            // P1-17 fix ("Emergency revoke / credential deactivation stops all monitoring of
            // live positions" -- confirmed real): this used to skip EVERY inactive credential
            // unconditionally, which also meant open positions/OCOs on a just-deactivated
            // credential stopped being reconciled at all -- they keep existing and changing
            // state on the real exchange regardless of this application's own active flag, and
            // this application's only view into that (an OCO filling, a stop triggering, a
            // position needing re-protection after a resize) is this exact reconciliation pass.
            // credential.isActive()==false is still respected for its actual, intended purpose
            // (AutoTradeService.evaluateForProfile/AutonomousScannerService gate NEW autonomous
            // entries on this same flag, entirely unaffected by this fix) -- what changes is
            // that deactivation no longer ALSO silently abandons monitoring of whatever this
            // credential already had open the moment it was deactivated. Every remedial step
            // reconcileCredential itself may take (re-protecting a naked position, an
            // exit-policy-driven flatten) is the same safety machinery this application already
            // runs unconditionally for every ACTIVE credential's own open positions -- there is
            // no separate "new" behavior being introduced here, only the same existing
            // machinery no longer being skipped for a credential that still needs it. Reuses
            // the exact status set BrokerCredentialService.refuseIfCredentialHasOpenWork and
            // RiskProfileService.emergencyRevokeAll's own affectedOpenPositions count already
            // use for "this application is still actively responsible for this position".
            if (!credential.isActive() && !positionRepo.existsByCredentialIdAndStatusIn(credential.getId(),
                    java.util.Set.of("OPEN", "FLATTENING", "NAKED_FLATTENED", "CLOSED_UNVERIFIED_PNL"))) {
                continue;
            }
            if (shutdownState.isShuttingDown()) return; // stop starting new per-credential work mid-loop too
            reconcileCredential(credential);
        }
        // Review finding ("#10 — External Watchdog"): recorded once the cycle actually
        // completes (or ran out of credentials to process) — same "a cycle with no work is
        // still a healthy cycle" reasoning as AutonomousScannerService's own heartbeat.
        heartbeatService.recordReconciliationCompleted();
    }

    /**
     * Review item #3: exposed so BinanceUserDataStreamService can trigger an immediate targeted
     * reconciliation the moment a real-time executionReport/listStatus event arrives, instead of
     * waiting up to 60s for the next scheduled pass. Deliberately reuses this exact method rather
     * than having the WebSocket service duplicate P&L/fee computation independently — one source
     * of truth for how a close is priced, whichever path noticed it first.
     */
    /**
     * Review finding ("P0 #2" — "reconciliation is not concurrency-safe"): confirmed real —
     * this is called from three independent triggers (the 60s scheduled poll, the startup
     * listener, and now the WebSocket event listener), and nothing prevented two of them running
     * for the same credential at once. A tryLock-and-skip guard: if a reconciliation is already
     * in progress for this credential, this trigger is simply dropped rather than run
     * concurrently — the position/order state didn't go anywhere, so whichever trigger fires
     * next (the WS listener again, or the 60s poll) picks up the same work safely. Blocking
     * instead of skipping was deliberately avoided — a WebSocket event thread shouldn't sit
     * waiting behind a REST reconciliation pass that could take a few seconds.
     *
     * UPDATE ("Single-instance assumption for autonomous trading/reconciliation -- no
     * distributed lock, unsafe to scale replicas" -- P0, now closed, full context in
     * DistributedLockService's own javadoc): this JVM-local lock is still checked first (cheap,
     * fast, avoids a database round-trip for the overwhelmingly common same-process contention
     * case), but a genuine distributed lock backed by MongoDB's own unique _id constraint is now
     * also acquired before reconciliation actually proceeds -- real inter-process mutual
     * exclusion, not just an in-process approximation of it. Safe to scale beyond replicas: 1
     * now, not silently assumed to be.
     */
    public void reconcileCredential(BrokerCredential credential) {
        // Review finding ("Graceful shutdown does not stop @Scheduled work or WebSocket
        // listeners from starting new work" -- external review, nineteenth pass, P1, confirmed
        // real by direct inspection before this fix: doReconcile()'s own shutdown check above
        // only ever gated the @Scheduled entry point -- BinanceUserDataStreamService's own
        // onText() calls this exact public method directly on every executionReport/listStatus/
        // outboundAccountPosition event, entirely bypassing that check. A real fill notification
        // arriving during shutdown could still trigger a full reconciliation pass -- including
        // OCO placement or emergency-flattening, genuine money-moving actions -- with nothing in
        // this specific call path ever having looked at shutdown state at all): the fix, added
        // here specifically so BOTH callers (the scheduled sweep and the WebSocket listener) are
        // covered by the same one check, rather than duplicating it in BinanceUserDataStreamService.
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
            // Review finding ("DistributedLockService has a subtle generation race" -- external
            // review, twenty-ninth pass, P1, full context in DistributedLockService.LockLease's
            // own javadoc): switched from the plain boolean tryAcquire() + a separate
            // currentGeneration() query to tryAcquireWithDiagnosis() directly, carrying the
            // exact generation this acquisition's own insert wrote, with no window for it to
            // have changed underneath this call.
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
     * Review finding ("Position close has atomic protection; not every position mutation does"
     * -- P1, full context in this file's own earlier partial-exit conversion comment): a shared
     * helper for the "OCO successfully (re-)placed" field set (ocoOrderListId, ocoPlacedAt, and
     * optionally protectedQuantity if the caller already resolved one) -- this exact pattern
     * repeats at several call sites in this file (resize, late-fill, remainder), each of which
     * used to end in its own separate plain positionRepo.save(position). protectedQuantity is
     * nullable here specifically because not every caller has a resolved value at the point
     * this is called (see OcoOrderResult.actualProtectedQuantity's own javadoc) -- passing null
     * means this update leaves that field alone rather than clobbering a previously-set value
     * with nothing.
     */
    /**
     * Review finding ("atomicSetOcoPlaced() does not verify update success" -- external review,
     * confirmed real by direct inspection before any fix was attempted): the return value of
     * mongoTemplate.updateFirst() used to be discarded entirely -- if the OCO genuinely
     * succeeded on the exchange (Binance now has it ACTIVE) but this update matched zero
     * documents (e.g. the position was already closed by a concurrent process between this
     * caller's own read and this write), the code proceeded as if nothing were wrong: the
     * in-memory position object was still mutated (ocoOrderListId set, ocoPlacedAt recorded) and
     * every caller kept going, believing protection was durably recorded when it was NOT. This
     * is exactly the dangerous asymmetry the review named: the exchange operation succeeded, so
     * this must be treated as an uncertain, escalation-worthy local state, not silently folded
     * into the normal success path. Now raises a critical incident when the update matches
     * nothing, rather than silently returning as if it had.
     *
     * Review finding ("OCO persistence failure still creates a difficult crash window" --
     * external review, fifth pass, P1/operational-hardening, confirmed real by direct inspection
     * before any fix was attempted): the review's own named remaining gap -- the OrphanedOco
     * record used to only get created AFTER discovering modifiedCount==0, meaning a crash
     * between the exchange confirming success and this specific method being reached at all
     * (e.g. mid-way through a caller's own OMS recording step, which runs before this method is
     * invoked at every call site) left NO trace whatsoever: no orphan record, no incident,
     * nothing -- Binance has a real, active OCO this application has completely lost track of.
     * The review's own recommended architecture: "the orphan record guaranteed even if the
     * position update fails." Restructured to match -- the orphan record is now created
     * UNCONDITIONALLY, as the very first action in this method, before the position update is
     * even attempted, so every caller that reaches this method at all leaves a durable trace
     * immediately, regardless of what happens next. If the position update then succeeds, the
     * orphan record is deleted again (redundant at that point -- the position itself durably
     * tracks the OCO). This narrows, but does not fully close, the crash window: a crash
     * strictly BEFORE this method is ever called (between the exchange call returning and a
     * caller's own OMS-recording step reaching this line) is still outside what any single
     * method can guarantee -- see this file's own callers for why moving this call earlier at
     * every site, immediately after the exchange response, was judged a larger restructuring
     * than this pass, matching the review's own "not a reason to rewrite the system" framing.
     */
    /**
     * Review finding ("OCO placement success + local persistence failure still has a residual
     * crash window" -- external review, eighteenth pass, P0, full context in
     * atomicSetOcoPlaced's own updated javadoc below): the actual fix -- every real call site
     * that places a genuine OCO on the exchange now calls this method FIRST, immediately after
     * the exchange response, BEFORE any other processing (OMS recording, position field updates,
     * anything) that could itself throw and abandon this whole operation with zero durable trace.
     * Returns the new orphan's own id (to hand to atomicSetOcoPlaced below), or null if even this
     * save itself failed -- callers proceed regardless either way, same "never let bookkeeping
     * block the real, already-executed exchange operation" principle this codebase applies
     * everywhere else.
     */
    /**
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): the
     * actual pre-submission fix -- called BEFORE adapter.placeExitOco() at every real call site,
     * with the SAME listClientOrderId about to be sent to the exchange. Returns the saved
     * attempt's id (or null if even this save failed -- non-fatal, same "never let bookkeeping
     * block the real, about-to-happen exchange operation" principle as everywhere else).
     */
    // Review finding ("LIVE entry OCO still has no pre-submission ProtectionAttempt" -- external
    // review, twenty-fourth pass, P0, confirmed real by direct inspection before this fix:
    // AutoTradeService's own initial entry-OCO placement (placeExitOcoOrEmergencyFlatten) had no
    // equivalent to this exact mechanism at all -- confirmed via a direct grep for
    // createProtectionAttempt in that file, which returned nothing): this method, along with
    // resolveProtectionAttempt/createOrphanForOco/atomicSetOcoPlaced/
    // haltForProtectionAttemptPersistenceFailure below, is now package-private specifically so
    // AutoTradeService (same package) can reuse this exact, already-hardened sequence rather
    // than duplicating it a second time with the drift risk that implies.
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
     * Review finding, same context as createProtectionAttempt's own javadoc above: marks the
     * pre-submission record resolved once the exchange has actually responded, one way or the
     * other. A null attemptId (the create above failed) is a no-op -- nothing to resolve.
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
            // Review finding ("OCO persistence has a second crash window" -- external review,
            // twenty-second pass, P1, confirmed real by direct inspection before this fix:
            // unlike P0-3's own ProtectionAttempt fix, the exchange OCO call has ALREADY
            // succeeded by the time this method ever runs -- there is no remaining call to
            // refuse or skip, the money-moving action already happened. The only thing left to
            // do is make certain a human learns immediately that this specific position may now
            // have no durable local trace of its own protection, rather than relying solely on
            // atomicSetOcoPlaced's own later position-update succeeding as the only remaining
            // safety net): for LIVE specifically, escalate loudly and halt further NEW trading
            // on this credential -- the existing position itself is left exactly as-is (this
            // does NOT flatten it, since the OCO may well still be genuinely active on the
            // exchange; flattening blind here risks colliding with a real, live order). Looks up
            // the credential by id here rather than threading it through as a new parameter --
            // this method (and atomicSetOcoPlaced, its own caller) has multiple call sites of
            // its own, several nested deep in other methods; a lookup here avoids a wider,
            // riskier cascade through all of them just for this one, already-rare failure path.
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
     * Review finding ("atomicSetOcoPlaced() does not verify update success" -- external review,
     * confirmed real by direct inspection before any fix was attempted): the return value of
     * mongoTemplate.updateFirst() used to be discarded entirely -- if the OCO genuinely
     * succeeded on the exchange (Binance now has it ACTIVE) but this update matched zero
     * documents (e.g. the position was already closed by a concurrent process between this
     * caller's own read and this write), the code proceeded as if nothing were wrong: the
     * in-memory position object was still mutated (ocoOrderListId set, ocoPlacedAt recorded) and
     * every caller kept going, believing protection was durably recorded when it was NOT. This
     * is exactly the dangerous asymmetry the review named: the exchange operation succeeded, so
     * this must be treated as an uncertain, escalation-worthy local state, not silently folded
     * into the normal success path. Now raises a critical incident when the update matches
     * nothing, rather than silently returning as if it had.
     *
     * Review finding ("OCO persistence failure still creates a difficult crash window" --
     * external review, fifth pass, P1/operational-hardening, confirmed real by direct inspection
     * before any fix was attempted): the review's own named remaining gap -- the OrphanedOco
     * record used to only get created AFTER discovering modifiedCount==0, meaning a crash
     * between the exchange confirming success and this specific method being reached at all
     * (e.g. mid-way through a caller's own OMS recording step, which runs before this method is
     * invoked at every call site) left NO trace whatsoever: no orphan record, no incident,
     * nothing -- Binance has a real, active OCO this application has completely lost track of.
     *
     * Review finding ("OCO placement success + local persistence failure still has a residual
     * crash window" -- external review, eighteenth pass, P0, confirmed real by direct inspection
     * before any fix was attempted: this method's own earlier fix narrowed but did not fully
     * close the gap, since the orphan was still only created HERE, inside this method, still
     * after every caller's own OMS-recording step had already run and could have thrown): the
     * actual, final fix -- orphanId is now a parameter, the id of an orphan
     * createOrphanForOco() already created and persisted at every real call site, IMMEDIATELY
     * after the exchange response, before any OMS recording or other processing that could
     * throw. This method no longer creates its own orphan at all in the normal case -- it only
     * uses the id it's given to delete the now-redundant record on success, or leaves it in
     * place (already durable, already queryable by recoverOrphanedOcos) on failure. A null
     * orphanId (a caller that couldn't be updated, or the orphan's own save above failed) falls
     * back to creating one here as a last resort, narrower than before this fix but not zero.
     */
    /**
     * Review finding ("Orphan recovery can mark an unresolved OCO as RESOLVED" -- external
     * review, thirty-fifth pass, P0, confirmed real by direct inspection before this fix: this
     * method used to return void and make its own internal decision (raise an incident, or
     * delete the now-redundant orphan record) with no way for a caller to know which outcome
     * actually happened. At least one real call site relied on that outcome without checking it
     * -- calling markOrphanResolved(orphan, ...) unconditionally right after this method
     * returned, regardless of whether the position update genuinely succeeded. The review's own
     * named race: the position closes between this method's own read and its update, the update
     * matches zero documents, an incident is correctly raised -- but the caller still marks the
     * orphan resolved=true anyway, meaning a genuinely still-active exchange OCO permanently
     * disappears from every future recovery pass, since those all query resolved=false):
     * returns true only when the position update genuinely modified a document. Every caller
     * that takes a further action predicated on success (marking an orphan resolved, for
     * instance) must now check this return value first.
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
        // User's own explicit architectural request, full context in ExecutionContext's own
        // class javadoc: recorded regardless of the local database update's own outcome just
        // below -- the real OCO genuinely exists on the exchange either way, which is the fact
        // this traceability layer exists to reflect.
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
            // call, before this method even ran) IS the structured, queryable record
            // recoverOrphanedOcos() needs -- nothing further to create here now.
        } else if (orphanId != null) {
            // Review finding ("OCO persistence failure still creates a difficult crash window"
            // -- same context as this method's own updated javadoc): the position update
            // succeeded, so the safety-net orphan record is now redundant -- the position itself
            // durably tracks this OCO going forward. Deleted so recoverOrphanedOcos never wastes
            // a pass re-checking something that was never actually orphaned.
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
     * Review finding ("Position close has atomic protection; not every position mutation does"
     * -- P1, full context in this file's own earlier partial-exit conversion comment): shared
     * helper for the "close with unverified P&L" field set (status, closedAt, closedQuantity,
     * quantity=0), which appears identically at two call sites in reconcileOcoProtectedPosition
     * (unresolvable exit price, and unresolvable exit quantity) -- both used to end in their
     * own separate plain positionRepo.save(position).
     *
     * Reads position.getQuantity() itself and applies BOTH the atomic database update and the
     * in-memory mutation, specifically so this must be called BEFORE any other code mutates
     * position.quantity -- callers no longer set closedQuantity/quantity themselves, closing off
     * the exact ordering trap that would silently record closedQuantity=0 if a caller mutated
     * quantity to zero first and only then called this helper.
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
     * P1-15 fix ("Risk accounting ignores unverified closes; drawdown equity is wrong" --
     * confirmed real, this is the first of the audit item's two named gaps). Every call site
     * that closes a position as CLOSED_UNVERIFIED_PNL (this method's own four callers) used to
     * stop at atomicCloseUnverifiedPnl and never call riskEngine.recordRealizedLoss/
     * recordAutoTradeOutcome at all — meaning a real, unverified LOSS contributed literally
     * ZERO to this credential's own daily loss total and consecutive-loss streak, the exact
     * circuit breakers this application relies on to stop autonomous trading after a run of bad
     * outcomes. An unverified close is not a rare edge case (a normal-sized market move that
     * happens to land an OCO leg on an unparsable/missing price field, or a position that closes
     * by some means outside this backend's own order flow, are both realistic, non-exotic
     * events for a live account) — silently exempting every one of them from risk accounting
     * left a real gap an adversarial or simply unlucky sequence of trades could exploit.
     *
     * This closes that gap directly and honestly, without pretending to solve the audit item's
     * SECOND, materially larger gap (full account valuation / deposit-withdrawal detection for
     * checkDrawdown's own equity calculation) in the same pass — see checkDrawdown's own updated
     * comment for that part, and this method's own knownExitPrice-fallback design below for why
     * that separation is deliberate, not an oversight.
     *
     * Resolution order, matching the audit's own suggested fix ("resolve P&L from myTrades for
     * those closes; fallback: count worst-case at SL"):
     *   1. A genuinely known exit price (an OCO leg's own confirmed fill price, when only the
     *      QUANTITY was unresolved) is used directly — this is real, not an estimate.
     *   2. Otherwise, this looks up the position's own most recent OCO_EXIT order (the OMS
     *      record recordOcoPlacementResult creates — see its own javadoc) for its
     *      stopLossTriggerPrice: a real, already-persisted number this application itself chose
     *      as the worst acceptable loss on this exact position, used here as a conservative,
     *      clearly-labeled worst-case floor for risk-accounting purposes only.
     *   3. If neither is available (no OCO ever existed for this position, or its OMS record was
     *      never persisted), this makes NO estimate and logs why, rather than guessing — the
     *      same "an honestly-disclosed gap beats a silently wrong number" discipline this
     *      codebase already applies everywhere else (see, e.g., atomicCloseUnverifiedPnl's own
     *      callers never defaulting to entry price).
     * Position.realizedPnlQuote is deliberately left untouched either way — this only feeds the
     * RiskProfile-level counters, never fabricates the position's own authoritative P&L record,
     * which still genuinely needs manual reconciliation exactly as each caller's own audit
     * message already says.
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
     * Review finding ("Several services still perform full RiskProfile.save()" -- external
     * review, confirmed real by direct inspection before any fix was attempted): a shared
     * helper for the "halt with a reason" field pair (tradingHalted, haltReason), which appears
     * identically at 8 separate call sites throughout this file's own many failure-escalation
     * branches -- each used to independently duplicate the same plain, full-object
     * riskProfileRepo.save() call. Consolidated here rather than converted individually at each
     * site, both to close the actual atomicity gap (a stale in-memory profile silently
     * overwriting a genuinely concurrent write to dailyRealizedLossQuote,
     * consecutiveOrderFailures, peakEquityQuote, liveAutoTradeAuthorized, or autoTradeHalted)
     * and because 8 independent inline copies of the same fix is itself a real maintenance risk
     * -- a future change to this exact pattern would need to be applied correctly 8 times.
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
        // Review finding (P1-1 -- "Paper mode is routed to the real Binance adapter (testnet)
        // during reconciliation and exits"): this used to resolve the adapter by BrokerType alone
        // via the local adapterMap, which only ever holds real, Spring-discovered adapters
        // (BinanceBrokerAdapter etc.) and has no concept of BrokerMode.PAPER at all. A PAPER
        // credential was therefore reconciled with the real Binance adapter against its TESTNET
        // base URL -- paper OCOs (Mongo-generated ids) were looked up on real testnet and always
        // failed, so paper positions could never close via reconcile, and any reversal/max-hold
        // flatten for a "paper" credential sent REAL testnet orders. adapterForCredential is the
        // one place that correctly special-cases PAPER credentials to the simulated adapter --
        // every adapter lookup for a specific credential must go through it, never adapterMap.
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        if (adapter == null) return;
        try {
            // Review finding ("Reconciliation lock renewal failure handling remains
            // inconsistent" -- external review, confirmed real by direct inspection: a genuine
            // gap this session's own earlier fix for this exact area left open): the two
            // existing renewal checks further down in this same method only guarded the steps
            // AFTER reconcileEntryOrders -- that call itself performs real mutations (order
            // status updates, and potentially position corrections via
            // syncPositionQuantityIfMismatched) with NO renewal check at all before it. The
            // lock was acquired fresh by this method's own caller moments ago, so this is
            // primarily defensive against unexpected delay between acquisition and this point
            // (slow adapterMap initialization, JVM scheduling) -- but the review's own stated
            // invariant is unconditional ("NO MORE POSITION/ORDER MUTATIONS... unless a new
            // lock is successfully acquired"), and this closes the one real gap where that
            // wasn't yet enforced on every mutation-capable step, not just most of them.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before this pass's own first mutation-capable step "
                    + "(reconcileEntryOrders) -- stopping before any mutation in this pass runs at all.", credential.getId());
                return;
            }
            reconcileEntryOrders(credential, adapter, lockGeneration);
            // Review finding ("Position still has no FLATTENING state" -- external review,
            // second pass, full context in Position.status's own updated field javadoc): the
            // other half of the FLATTENING fix -- a position can be left stuck in this state by
            // a process crash mid-flatten, and nothing was watching for that at all until this
            // step. Same renewal-check discipline as every other mutation-capable step in this
            // method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before recoverStuckFlattening -- stopping this pass.",
                    credential.getId());
                return;
            }
            recoverStuckFlattening(credential, adapter, lockGeneration);
            // Review finding ("OCO Persistence Failure Has No Reconciliation Path" -- external
            // review, third pass, full context in recoverOrphanedOcos's own javadoc): the same
            // renewal-check discipline as every other mutation-capable step in this method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before recoverOrphanedOcos -- stopping this pass.",
                    credential.getId());
                return;
            }
            recoverOrphanedOcos(credential, adapter, lockGeneration);
            // Review finding ("OCO persistence still has an unavoidable crash window" --
            // external review, nineteenth pass, P1, full context in ProtectionAttempt's own
            // class javadoc): same renewal-check discipline as every other mutation-capable step
            // in this method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before recoverStuckProtectionAttempts -- stopping this pass.",
                    credential.getId());
                return;
            }
            recoverStuckProtectionAttempts(credential, adapter, lockGeneration);
            // User's own explicit multi-strategy-plan design ("15m LONG ... 4-hour maximum
            // holding reached? -> EXIT"), full context in enforceMaxHoldTime's own javadoc:
            // same renewal-check discipline as every other mutation-capable step in this method.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before enforceMaxHoldTime -- stopping this pass.",
                    credential.getId());
                return;
            }
            enforceMaxHoldTime(credential, adapter, lockGeneration);
            // User's own explicit multi-strategy-plan design ("Risk Emergency Exit" as one of
            // the checkboxes in the plan's own Exit Policy), full context in
            // enforceRiskEmergencyExit's own javadoc: same renewal-check discipline.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before enforceRiskEmergencyExit -- stopping this pass.",
                    credential.getId());
                return;
            }
            enforceRiskEmergencyExit(credential, adapter, lockGeneration);
            // User's own explicit design ("End-of-session must be scoped to the Strategy Plan
            // ... It must NOT globally flatten the account"), full context in
            // enforceEndOfSession's own javadoc: same renewal-check discipline.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} before enforceEndOfSession -- stopping this pass.",
                    credential.getId());
                return;
            }
            enforceEndOfSession(credential, adapter, lockGeneration);
            // Review finding ("Reconciliation lock renewal failure currently continues anyway"
            // -- external review): confirmed real, and confirmed the review's own reasoning is
            // stronger than this comment's original one. The original design here reasoned that
            // "aborting partway through could itself leave inconsistent state" -- but that
            // weighs the wrong risk: most of this pass's own individual mutations are already
            // atomic conditional updates (see this file's own extensive P1-6 conversion work),
            // so stopping early just leaves the REMAINING positions unreconciled for this one
            // pass -- safe, since the next scheduled pass 60 seconds later picks them back up.
            // Continuing after a genuinely lost lease, in contrast, risks a SECOND instance that
            // now believes it owns this lock actively mutating the same positions concurrently
            // with this one, at the same time. That's the worse failure mode, not the safer one.
            // Now stops the pass rather than continuing through it.
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
            // Review items #13/#14: self-heal the distributed slot-reservation counter against
            // the real Position count, catching any missed release() from an exception elsewhere.
            List<Position> openPositionsForCredential = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");
            slotReservationService.reconcile(credential.getId(), openPositionsForCredential.size());

            // Review finding ("P0 #3"): same self-healing for the exposure reservation — computed
            // from real OPEN positions with a known, verified entry price (matching the same
            // exclusion releaseExposureForClosedPosition already applies), catching any drift
            // from rounding, a missed release, or an exception elsewhere.
            BigDecimal actualTotalExposure = BigDecimal.ZERO;
            java.util.Map<String, BigDecimal> actualSymbolExposure = new java.util.HashMap<>();
            for (Position p : openPositionsForCredential) {
                if (p.getAvgEntryPrice() == null || p.isAvgEntryPriceUnverified() || p.getQuantity() == null) continue;
                BigDecimal value = p.getQuantity().multiply(p.getAvgEntryPrice());
                actualTotalExposure = actualTotalExposure.add(value);
                actualSymbolExposure.merge(p.getSymbol(), value, BigDecimal::add);
            }
            // Review finding ("Risk" — "atomic correlation reservations"): reconcile() only
            // self-healed total/symbol exposure — group exposure was never included, meaning a
            // release() failure (or any other drift) on a group reservation had no self-healing
            // safety net at all, unlike total/symbol. Computed the same way symbol exposure
            // already is, from the same real OPEN positions.
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
            // Review finding (P1 #7 -- "One failing credential at startup disables autonomous
            // trading for ALL users until restart"): full context in StartupState.
            // isCredentialTradingEnabled's own javadoc -- this credential's own reconciliation
            // attempt just genuinely ran to completion, so it's cleared from the per-credential
            // block list here, regardless of whether it was ever on it. This is also the real
            // "retry" the review asked for: this same method runs again every periodic
            // reconciliation cycle (60s) for every active credential, so a credential that failed
            // at startup (a revoked key, say) recovers automatically, with no restart, the moment
            // its own next reconciliation attempt succeeds -- fixed the key, next cycle clears it.
            startupState.markCredentialReconciled(credential.getId(), true);
        } catch (Exception e) {
            log.warn("Reconciliation failed for credential {}: {}", credential.getId(), e.getMessage());
            reconciliationFailureCount.incrementAndGet();
            // Review finding (P1 #7, full context in StartupState.isCredentialTradingEnabled's
            // own javadoc): THIS credential, and only this credential, is blocked from
            // autonomous trading until a later reconciliation attempt for it succeeds -- every
            // other credential (this user's other credentials, and every other user's) is
            // entirely unaffected by this one failure.
            startupState.markCredentialReconciled(credential.getId(), false);
        }
    }

    /**
     * Review finding ("Position still has no FLATTENING state" -- external review, second pass,
     * full context in Position.status's own updated field javadoc): recovers a position left
     * stuck in FLATTENING by a process crash mid-flatten -- PositionSafetyService.attemptFlatten
     * transitions OPEN->FLATTENING atomically before its own first real exchange call, but a
     * crash between that transition and the flatten's own real outcome being recorded would
     * otherwise leave the position silently stuck forever, with no code path watching for it.
     * A normal, successful flatten resolves synchronously within the same request, in seconds --
     * this reconciliation pass runs every 60 seconds, so ANY position still found in FLATTENING
     * here is already a strong, sufficient signal something went wrong; no separate time
     * threshold is needed on top of that.
     *
     * Deliberately does NOT automatically retry the sell itself. Re-deriving "what actually
     * happened" and safely re-entering the sell flow from a FLATTENING starting state (rather
     * than attemptFlatten's own OPEN starting assumption) would mean either duplicating
     * meaningful parts of that method's own logic with a different entry precondition, or
     * calling it directly and having its own atomic OPEN->FLATTENING transition fail outright
     * (the position is already FLATTENING, not OPEN) -- aborting before ever reaching the real
     * sell. An automated retry built on top of an ambiguous, already-once-failed state is a
     * real place to introduce a NEW bug, not a safe default. Instead: checks the real exchange
     * balance to determine the actual likely outcome, and either resolves the position to the
     * correct terminal state (the sell demonstrably went through) or escalates for manual
     * attention (it demonstrably didn't) -- never guesses.
     */
    /**
     * Review finding ("Stuck FLATTENING recovery uses balance as evidence that the flatten
     * succeeded" -- external review, third pass, confirmed real by direct inspection before any
     * fix was attempted): the previous version reasoned "FLATTENING + exchange free balance <
     * minQty = flatten definitely succeeded." That's not necessarily true -- the review's own
     * named alternative causes (manual exchange activity, another process, another order, an
     * asset transfer, unrelated application activity) are all real, plausible reasons the
     * balance could independently be low. Now finds THIS position's own most recent flatten
     * attempt (see OrderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc's own
     * javadoc) and queries the exchange for THAT SPECIFIC order's own real, definitive status by
     * clientOrderId (see BrokerAdapter.getOrderStatusByClientOrderId's own javadoc) -- ground
     * truth about this exact order, not an inference from an account-wide balance that could
     * have moved for entirely unrelated reasons. Balance is now the FALLBACK, used only when no
     * order record can be found at all (the OMS setup itself failed) or the exchange-side
     * lookup itself fails -- exactly the review's own stated target: "Balance becomes
     * supporting evidence, not the primary proof."
     */
    private void recoverStuckFlattening(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        List<Position> stuck = positionRepo.findByCredentialIdAndStatus(credential.getId(), "FLATTENING");
        if (stuck.isEmpty()) return;
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        for (Position position : stuck) {
            // Review finding ("Reconciliation lease can still expire during one long mutation
            // step" -- external review, third pass, full context in reconcileOpenPositions's
            // own identical fix): the same real vulnerability, in this method's own loop too --
            // each stuck position here can involve up to two real, network-bound exchange calls
            // (the order-status lookup, and the balance fallback), with no renewal at all once
            // this method itself started.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} mid-loop in recoverStuckFlattening (before position {}) "
                    + "-- another instance may now own this lock. Stopping the rest of this loop rather than continuing to mutate shared "
                    + "state without exclusive ownership confirmed.", credential.getId(), position.getId());
                return;
            }
            try {
                List<Order> flattenOrders = omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "FLATTEN");
                Boolean confirmedFilled = null; // null = no definitive order-level answer, fall back to balance
                // Review finding ("Emergency flatten still allows an exchange sell without
                // durable pre-submission intent" -- external review, twenty-sixth pass, P1,
                // full context in FlattenAttempt's own class javadoc): the actual fix -- when no
                // OMS Order record exists at all for this position's flatten (the OMS setup
                // itself failed at submission time, per attemptFlatten's own known, accepted
                // non-fatal behavior), fall back to the minimal, durable FlattenAttempt record
                // instead of skipping straight to the weaker balance heuristic. This gives the
                // exact same definitive, order-status-level answer the OMS-Order path already
                // provides, just sourced from the lighter-weight record when the fuller one
                // never got created.
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
                // Review finding ("Emergency Flatten's durable intent is STILL best-effort" --
                // external review, twenty-eighth pass, P1, confirmed real by direct inspection
                // before this fix: attemptFlatten's own FlattenAttempt insert is itself
                // best-effort (deliberately, per this session's own earlier P1 fix -- a naked
                // position is more dangerous than an unrecorded emergency sell), so BOTH the
                // OMS Order and the FlattenAttempt record can genuinely be missing if Mongo was
                // unavailable at exactly the wrong moment while the real Binance sell still
                // succeeded): the actual fix the review names -- attemptFlatten's own
                // clientOrderId is deterministic (generateClientOrderId("tv-flat", positionId +
                // ":FLATTEN:" + flattenEpisode + ":" + attempt)), and attemptFlatten only ever
                // uses attempt 0 or 1 (see PositionSafetyService's own two call sites) --
                // recovery can independently re-derive both and query Binance directly by each,
                // without needing either durable record to have survived at all.
                //
                // P2-8 fix, full context in Position.flattenEpisode's own field javadoc: the
                // clientOrderId basis now also includes flattenEpisode, since this exact same
                // position can legitimately go through more than one flatten episode over its
                // lifetime (a recovered partial flatten can put it back to OPEN). No guessing is
                // needed here though -- this stuck position is already loaded with its own
                // current, durably-stamped flattenEpisode value, which is exactly the one
                // attemptFlatten used for this (the most recent, still-unresolved) episode.
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
                        // Review finding ("stuck-FLATTENING recovery can still falsely close a
                        // capped order" -- external review, thirty-first pass, P0, confirmed
                        // real by direct inspection before this fix: this branch used to set
                        // confirmedFilled=true unconditionally on a broker-reported FILLED
                        // status, with no comparison against the real position size at all --
                        // the exact same bug this session already fixed for the LIVE flatten
                        // path (attemptFlatten), just still present in the crash-recovery path.
                        // The review's own key distinction: "Order FILLED = the requested order
                        // filled. Position CLOSED = the entire position was sold. Those are two
                        // different facts." A flatten SELL capped below the real position size
                        // (free balance < internal quantity) can genuinely, fully FILL its own
                        // smaller target while the real position still has a real remainder):
                        // the actual fix -- a FILLED order whose own executedQty is less than
                        // the real position size is routed through the exact same
                        // partial-handling path just below (originally built for a
                        // broker-reported PARTIALLY_FILLED status), reusing its already-correct
                        // remaining-quantity math rather than duplicating it. Only a FILLED
                        // order whose executedQty genuinely covers the whole real position (or
                        // whose executedQty came back null, in which case there's no safer
                        // fallback than the prior behavior) is treated as confirmedFilled.
                        // Review finding ("FILLED + zero executedQty can still close the
                        // position" -- external review, thirty-second pass, P1, confirmed real
                        // by direct inspection before this fix: the else branch below used to
                        // catch every case that wasn't a genuine, positive, sub-position
                        // executedQty -- which included executedQty==0 and executedQty==null,
                        // both silently treated as confirmedFilled=true, a full close. The
                        // review's own root cause: BinanceBrokerAdapter.getOrderStatusByClientOrderId
                        // parses executedQty via asText("0") -- a malformed or field-missing
                        // response (status:"FILLED" with no executedQty at all) genuinely
                        // produces status=FILLED, executedQty=0 from this codebase's own
                        // adapter, and the old else branch read that combination as "the whole
                        // position sold." This is a real fail-OPEN in code that otherwise
                        // deliberately fails closed everywhere else in this exact method): the
                        // actual fix -- confirmedFilled is now ONLY ever set true when
                        // executedQty is genuinely positive AND covers the real position size.
                        // Every other FILLED case (executedQty null, zero, or negative) falls
                        // through with confirmedFilled left null, which this method's own
                        // existing "no order-level truth at all" branch further below already
                        // treats as STUCK_FLATTENING_UNKNOWN + halt -- not a new code path, the
                        // correct existing one this case was wrongly routed around before.
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
                    // Review finding ("partial recovery overwrites previous closedQuantity" --
                    // external review, thirty-second pass, confirmed real by direct inspection
                    // before this fix: this used to write partialExecutedQty straight into
                    // closedQuantity, discarding whatever an EARLIER flatten attempt on this
                    // same position had already confirmed-sold. Multiple emergency-flatten
                    // attempts across separate crashes/restarts is a real scenario this
                    // recovery method itself exists to handle -- attempt 1 sells 0.4 (recorded),
                    // the process crashes again mid-attempt-2, attempt 2's own recovery sells
                    // another 0.2 -- the review's own point: closedQuantity must end up 0.6, the
                    // real cumulative total, not 0.2, this leg's own amount alone): the actual
                    // fix, Option A from the review's own two named choices -- cumulative,
                    // chosen over building a separate FlattenExecution ledger (the review's own
                    // Option B, genuinely cleaner long-term but a materially larger, separate
                    // undertaking than this specific accounting fix warrants) since every
                    // downstream consumer of Position.closedQuantity already expects a single,
                    // running total for the position, not a per-leg log.
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
                // Review finding ("Stuck FLATTENING fallback can still use balance when
                // order-level truth is unavailable" -- external review, fifth pass, P1,
                // confirmed real by direct inspection before any fix was attempted): balance is
                // still fetched here, but ONLY as supporting context for whoever investigates
                // manually -- the review's own point is that "balance < minQty" cannot
                // mathematically prove this exact flatten order filled (manual trading, a
                // transfer, another application, another worker, or any other unrelated balance
                // movement could produce the identical observation), so it must never again
                // drive the resolvedFilled decision itself.
                if (confirmedFilled == null && rules.baseAsset() != null) {
                    var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
                    freeBalance = balances.stream()
                        .filter(b -> b.asset().equalsIgnoreCase(rules.baseAsset()))
                        .map(com.tradevision.service.broker.dto.AssetBalance::free)
                        .findFirst().orElse(BigDecimal.ZERO);
                }
                // Review finding, same context: the actual policy change the review recommends
                // -- "NO ORDER ID + NO EXCHANGE ORDER VERIFICATION should preferably become:
                // UNKNOWN + HALT + MANUAL RECONCILIATION rather than automatically closing."
                // resolvedFilled is now NEVER inferred from balance -- only a genuine,
                // order-level FILLED confirmation can close a position from this recovery path.
                // No order-level truth at all is treated the same as a confirmed non-fill: never
                // auto-close, always escalate. This eliminates the final inference-based closure
                // path the review named.
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
                    // Review finding ("recovered full flatten does not release its
                    // reservations" -- external review, thirty-fourth pass, P1, the review's
                    // own explicit ordering, confirmed real by direct inspection before this
                    // fix: this branch closed the position but never called either release
                    // helper the normal OCO/close paths already use, and never marked
                    // ExecutionContext closed either -- leaving both reservation records
                    // durably ACTIVE against a position that no longer exists, and the
                    // execution's own trace stuck at whatever stage it last reached. Not an
                    // over-exposure risk on its own, since aggregate reconciliation eventually
                    // overwrites the counters regardless -- but the reservation RECORDS
                    // themselves, and the traceability this session's own ExecutionContext work
                    // exists to provide, would otherwise never reflect the real, closed truth):
                    // the actual fix, in the review's own explicit order --
                    // 1. Exchange truth confirmed (resolvedFilled, above).
                    // 2. Atomically close Position (the updateFirst below).
                    // 3. Release exact reservation IDs -- both release*() helpers are already
                    //    idempotent (see their own class javadocs), so this is safe even if some
                    //    other reconciliation path already released either one.
                    // 4. Mark ExecutionContext CLOSED.
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
     * Review finding ("OCO Persistence Failure Has No Reconciliation Path" -- external review,
     * third pass, P1, full context in OrphanedOco's own javadoc): the actual reconciliation
     * path the review's own text says was missing entirely. For each unresolved orphan on this
     * credential: if the position it was originally meant for still exists, is still OPEN, and
     * has since ended up with NO oco recorded, the original failure really was just a transient
     * race (the review's own named cause -- "the position may have already been closed by a
     * concurrent process" turned out not to be true after all) -- re-attempt the same atomic
     * set now, which should succeed this time. Otherwise the position genuinely can't take this
     * OCO back (closed, deleted, or already protected by a different one since), so the only
     * remaining question is the exchange's own truth about this specific orphaned OCO: still
     * active and genuinely dangling (escalated, since a live, unattached order affecting this
     * account's real balance is exactly the review's own named risk) or already resolved on its
     * own (no further action -- Binance's own OCO legs are mutually exclusive, so a naturally
     * filled/canceled leg needs nothing further from this application).
     */
    /**
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): the
     * recovery half of the pre-submission fix -- finds any attempt genuinely stuck in
     * SUBMITTING (the exchange call itself never got a chance to resolve it, most likely because
     * this process crashed somewhere between creating this record and receiving the exchange's
     * response) and asks Binance directly, by the deterministic client id this record has always
     * had, what actually happened.
     *
     * HONEST SCOPE: if the exchange confirms an OCO genuinely exists and is still active for
     * this client id, but this application's own position has no matching ocoOrderListId
     * recorded (the crash happened before that could ever be set), this escalates for manual
     * investigation rather than attempting full automated re-attachment -- doing that safely
     * would need the real orderListId, which OcoStatusInfo does not currently expose as its own
     * field (only inside rawResponse's raw JSON). Escalating with the exact listClientOrderId is
     * still a genuine, new capability: before this fix, this scenario had no durable trace
     * anywhere in this application at all, so there was nothing to escalate FROM.
     */
    private void recoverStuckProtectionAttempts(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        var cutoff = LocalDateTime.now().minusMinutes(2);
        // Review finding ("Some repository queries return unlimited lists" -- external review,
        // thirty-eighth pass, P2, full context in the repository's own updated method javadoc):
        // bounded to RECOVERY_BATCH_SIZE per reconciliation cycle, oldest-first -- the most
        // overdue attempts get processed first, and a genuinely large backlog on one credential
        // no longer risks one reconciliation cycle processing every stuck record across all of
        // history in a single pass. Any remainder is simply picked up on the next scheduled pass.
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
                // Review finding ("Stuck ProtectionAttempt considers 'ANY OCO' sufficient" --
                // external review, thirty-fifth pass, P0, confirmed real by direct inspection
                // before this fix: this used to treat position.getOcoOrderListId() != null as
                // sufficient proof THIS attempt succeeded -- but that field only proves the
                // position has SOME OCO recorded, not that it's the one this attempt placed, or
                // even that it's still active. The review's own exact scenario: an OLD OCO
                // finishes (ALL_DONE), a NEW ProtectionAttempt is created for a fresh OCO, a
                // crash happens before the new OCO gets recorded -- recovery would see the
                // STALE old orderListId still sitting on the position, incorrectly conclude
                // "protected," and resolve the new attempt as successful while the position is
                // actually unprotected right now): the actual fix -- when the position has a
                // recorded OCO, verify it's GENUINELY STILL ACTIVE on the exchange before
                // trusting it as proof of protection. A stale, already-finished OCO now falls
                // through to this method's own existing identity check below (this attempt's
                // own listClientOrderId), rather than being trusted on the strength of merely
                // being present.
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
                // Review finding ("Active orphan OCO still requires manual action" -- external
                // review, thirty-sixth pass, P0, the review's own explicit required flow: "GET
                // OCO by origClientOrderId -> OCO found? -> get ID -> attach -> verify ACTIVE...
                // If the OCO cannot safely be attached: attempt safe cancel -> verify cancel ->
                // if impossible -> safe flatten if provably safe -> otherwise HARD HALT + PAGE.
                // Human intervention should be the final branch, not the normal recovery
                // branch"): the actual fix -- this method used to escalate immediately and
                // unconditionally the moment it confirmed a real OCO exists, even though
                // OcoStatusInfo now genuinely carries the exchange-assigned orderListId needed
                // to auto-attach it (see OcoStatusInfo's own updated class javadoc for why it
                // didn't before). Auto-attach is now attempted FIRST, auto-cancel SECOND, and
                // escalation to a human is now the LAST resort, not the first response.
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
                    // Review finding ("Recovery auto-attach needs quantity verification" --
                    // external review, thirty-eighth pass, P1, confirmed real by direct
                    // inspection before this fix: this used to pass position.getQuantity() as
                    // the protected amount -- but the exchange OCO's own real SELL leg quantity
                    // can genuinely differ from that, due to base-asset fees, step-size
                    // rounding, or exchange quantity normalization, exactly as this codebase's
                    // own normal (non-recovery) OCO path already accounts for via
                    // OcoOrderResult.actualProtectedQuantity. Recovery auto-attach was the one
                    // place still using the position's own figure instead of the exchange's real
                    // one -- inconsistent with every other OCO-placement path in this class):
                    // the actual fix -- derive the real protected quantity from the recovered
                    // OCO's own SELL leg(s) directly, the same real number the normal path
                    // already trusts, rather than assuming the position's own recorded quantity
                    // is what the exchange actually protects.
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
                // could be safely confirmed -- exactly the review's own required "HARD HALT +
                // PAGE" as the last resort, not the default response.
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
                // Review finding ("OrphanedOco recovery still has an identity limitation" --
                // external review, thirty-eighth pass, P1, the review's own explicit required
                // checks: "OCO symbol == position.symbol, OCO side == SELL, OCO quantity <=
                // actual position, OCO belongs to credential, OCO active -- before attachment.
                // This is defense-in-depth, but for real money it's worthwhile"): the actual
                // fix -- the exchange's own live OCO status is now fetched BEFORE deciding
                // whether to auto-attach, not only in the other branch below. Every one of the
                // review's own named checks is verified against that live status before trusting
                // it enough to attach: "OCO belongs to credential" is already structurally
                // guaranteed here (this adapter/apiKey/apiSecret are already scoped to this one
                // credential -- there is no code path where a query through them could return
                // another credential's own OCO), so the remaining, genuinely new checks are
                // symbol, side, quantity, and active status.
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
                        // Review finding ("Orphan recovery can mark an unresolved OCO as
                        // RESOLVED" -- external review, thirty-fifth pass, P0, full context in
                        // atomicSetOcoPlaced's own updated javadoc): the actual fix -- the
                        // update genuinely failed (the position closed out from under this call
                        // between the read above and the update itself, or some other race),
                        // atomicSetOcoPlaced already raised OCO_PLACED_BUT_NOT_RECORDED and its
                        // own new orphan record for it. This original orphan record is left
                        // unresolved, not marked resolved=true -- so the still-genuinely-active
                        // exchange OCO cannot silently disappear from every future recovery
                        // pass's own resolved=false query.
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
                    // Review finding ("An active orphan OCO is marked 'resolved' even though the
                    // exchange order remains active" -- external review, fourth pass, P1, full
                    // context in OrphanedOco.escalated's own field javadoc): stays resolved=false
                    // so this exact orphan keeps being found and re-checked by every future pass
                    // until the exchange itself confirms it's actually done -- "resolved" here
                    // now means what it says everywhere else in this codebase: the dangerous
                    // condition is gone, not merely "this application has finished looking at it
                    // once." Deduplicates re-alerting: a fresh critical incident only fires on
                    // first escalation or once escalatedAt is over an hour stale, not on every
                    // single reconciliation pass for the same still-open, already-known problem.
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
     * User's own explicit multi-strategy-plan design: "The strategy timeframe and maximum
     * holding time must be separate... Timeframe = 15m, Max Hold = 4h means: Look for new
     * opportunities every 15-minute candle, but don't necessarily hold a position longer than 4
     * hours." This is the actual enforcement of that rule -- a position whose plan sets
     * maxHoldMinutes, still OPEN past its own entry time plus that duration, gets emergency-
     * flattened with an honest, specific reason (MAX_HOLD_TIME), not silently left open. Runs
     * every reconciliation pass (currently 60 seconds, see this class's own scheduled entry
     * point), so a hold-time breach is caught within one pass interval, not exactly on the dot.
     *
     * A position with no planId (pre-multi-plan, or a manually-triggered position) or whose plan
     * has no maxHoldMinutes set (null or <= 0) is left alone entirely -- this rule is opt-in per
     * plan, matching every other multi-plan feature this session built, never a silent behavior
     * change for a position that never asked for it.
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
                // Review finding (P1-2): max-hold-time is a routine, plan-configured exit, not a
                // protection failure -- uses exitPosition() so a clean close doesn't halt the
                // whole profile or raise a CRITICAL incident.
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
     * User's own explicit multi-strategy-plan design: "Don't make TP/SL the only way out...
     * Risk Emergency Exit" as one of the plan's own Exit Policy checkboxes. This is the actual
     * enforcement -- opt-in per plan (exitOnRiskEmergency, default true, matching this
     * codebase's own established "keep a new plan at least as safe as the account-level system
     * already is" default). Genuinely new, more aggressive behavior than the existing account-
     * level kill switch: halt()/tradingHalted only ever stops NEW trades from being claimed
     * (confirmed by direct inspection -- RiskProfileService.halt() itself never calls
     * emergencyFlatten), it does not touch positions already open. A plan with this flag set
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
     * User's own explicit design: "End-of-session must be scoped to the Strategy Plan... At
     * 15:00: Plan A positions -> CLOSE, Plan B positions -> KEEP OPEN. It must NOT globally
     * flatten the account." Every check here is per-position, keyed off that exact position's
     * own plan -- there is no account-wide flatten anywhere in this method, only ever
     * per-position decisions using that position's own plan's own session configuration.
     *
     * Uses StrategyPlanService.isWithinSession -- the SAME method the scanner uses to decide
     * whether to stop new entries -- so the two can never disagree about where the session
     * boundary actually is. A plan with endOfSessionAction=KEEP_OPEN (or sessionMode=ALWAYS_ON,
     * the default) is never touched by this method at all.
     *
     * Reuses emergencyFlatten -- the same already-hardened mechanism enforceMaxHoldTime and
     * enforceRiskEmergencyExit already use -- which is also the direct answer to the user's own
     * named safety concern: "PARTIAL FILL -> remaining position -> UNPROTECTED/RECOVERY ->
     * retry/reconcile -> DO NOT falsely mark closed." That exact behavior (never inferring a
     * full close from a partial fill, escalating rather than guessing) is already built into
     * attemptFlatten/recoverStuckFlattening from earlier in this session -- reusing the same
     * mechanism here means end-of-session inherits that safety property for free, rather than
     * needing its own separate, newly-risked implementation of it.
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
            // Review finding ("misconfigured session fails OPEN" -- external review, sixth pass,
            // P1, full context in StrategyPlanService.isSessionConfigValid's own javadoc): the
            // review's own explicit instruction -- "Existing positions should not be blindly
            // flattened merely because the configuration is malformed; require a deliberate
            // recovery policy." A broken session config means this position is left exactly as
            // it is (neither flattened nor assumed-safe), with a critical incident raised so a
            // human notices and fixes the plan's own configuration, rather than this method
            // guessing in either direction.
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
                // Review finding (P1-2): end-of-session is a routine, plan-configured exit, not
                // a protection failure -- uses exitPosition() so a clean close doesn't halt the
                // whole profile or raise a CRITICAL incident.
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
     * Review finding ("Reconciliation lease can still expire during one long mutation step" --
     * external review, third pass, full context in reconcileOpenPositions's own identical fix):
     * the same real vulnerability, in this method's own loop instead -- one real, network-bound
     * getOrderStatus call per pending entry order, with no renewal at all once this method
     * itself started. Renewed before every order in this loop too, same reasoning as
     * reconcileOpenPositions's own updated javadoc.
     */
    private void reconcileEntryOrders(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        // Review finding (P1 #16 — "Reconciliation queries are not scalable"): confirmed real —
        // this used to load the user's ENTIRE order history via findByUserIdOrderByPlacedAtDesc
        // and filter down to one credential/two statuses in Java. Fine at a handful of orders,
        // a real problem once a user has thousands. MongoDB does the filtering now, via a proper
        // compound index (see IndexInitializer).
        //
        // UPDATE ("OMS/ExecutedOrder full unification" -- P1, full context in
        // OrderRepository.findByCredentialIdAndStatusInOrderByCreatedAtAsc's own javadoc):
        // migrated off ExecutedOrderRepository's own identical method onto the real Order (OMS)
        // repository. "NEW"/"PARTIALLY_FILLED" (ExecutedOrder's own raw broker status strings)
        // map to ACKNOWLEDGED/PARTIALLY_FILLED in this OMS's own formal state machine.
        // Review finding (P1-13 -- "reconcileEntryOrders polls OCO list IDs as order IDs"): the
        // query above has no role filter, so an OCO_EXIT order's OMS record -- which stores the
        // broker's orderListId in this same brokerOrderId field once recordOcoPlacementResult()
        // acknowledges it (see that method's own javadoc) -- used to be picked up here right
        // alongside real ENTRY orders and polled via adapter.getOrderStatus(...,
        // order.getBrokerOrderId()), i.e. GET /api/v3/order?orderId=<orderListId>. That endpoint
        // expects a real order id, not a list id, so every pass either errored (wasted request
        // weight) or, worse, could resolve to a completely unrelated order that happens to share
        // that numeric id. Excluding "OCO_EXIT" here is a Java-level filter rather than a new
        // derived-query method/index because orderRole is nullable on records written before
        // this field existed (see Order.orderRole's own field comment) -- those legacy null-role
        // records are still real entry orders and must keep being reconciled, so this only
        // excludes the one role value that is now known to hold a list id, not everything except
        // a literal "ENTRY" string. OCO legs get their own dedicated reconciliation elsewhere in
        // this class (recordOcoFillResult/recordOcoCancelResult, driven off Position's own
        // ocoOrderListId and the user-data-stream/reconcileOpenPositions paths) -- they were
        // never meant to be polled by this method at all.
        List<Order> pendingEntries = omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(
                credential.getId(), List.of(OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED)).stream()
            .filter(o -> o.getBrokerOrderId() != null)
            .filter(o -> !"OCO_EXIT".equals(o.getOrderRole()))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        // Review finding, full context in OrderRepository.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc's
        // own updated javadoc: P0 fix -- this query now REQUIRES side == "BUY" at the database
        // level. It used to fetch ANY recently-FILLED order regardless of side, which let a
        // genuine emergency-flatten SELL be rediscovered here as an unresolved "entry," creating
        // an infinite phantom-position loop (confirmed in production). Bounded to the last 2
        // hours specifically so this never turns into re-scanning this credential's entire order
        // history on every single reconciliation pass -- an order still missing a Position after
        // this window has closed needs a human, not an indefinite hot-path retry.
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
                // Review finding, same context: Order.status is this OMS's own formal
                // OrderStatus enum, not the raw Binance status string ExecutedOrder.status used
                // to hold directly -- mapped via this method's own new, explicit helper (see
                // mapBrokerStatusToOrderStatus's own javadoc) rather than assigning the raw
                // string, which wouldn't even compile against the new field type.
                // Review finding (P1 #12 -- "Order state written by whole-document save() with
                // no optimistic locking"): this used to mutate order.status directly and call a
                // plain, whole-document omsOrderRepo.save(order) -- bypassing this OMS's own
                // transition-legality check and atomic conditional update entirely (full context
                // in OrderService.reconcileStatusFromBroker's own javadoc). Routed through that
                // method instead -- a lost race or a genuinely illegal broker-reported status is
                // caught below and logged, exactly like every other place in this codebase that
                // treats a status-transition failure as non-fatal/retry-next-cycle rather than
                // crashing this entire reconciliation pass over one order.
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

                // Review finding (this doc, "partial fills still not fully handled" — corrected
                // scope: Spot MARKET orders resolve synchronously and don't accumulate further
                // fills later, but the recovery path (review item #9, verify-before-declaring-
                // failure after a lost response) CAN query a status snapshot that turns out to
                // differ from what the Position was originally sized with. This closes that real,
                // narrower gap: if the confirmed executedQty doesn't match the linked Position's
                // quantity, correct the Position and resize its protection — don't just update
                // the order row and leave the position/OCO silently wrong.
                // Real bug, confirmed by the user's own live test run: a manual test order (placed
                // via OrderExecutionService.placeTestOrder, clientOrderId prefixed "manual-") has no
                // linked Position by design -- but this reconciliation loop couldn't tell that apart
                // from a genuinely orphaned BOT entry, so it adopted every filled manual order as a
                // late-discovered bot position and then flattened it. isManualTestOrder() lets this
                // loop skip manual orders entirely; bot entries (clientOrderId "tv-s-...") are
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
     * Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
     * reconcileEntryOrders's own updated javadoc): a real, deliberate mapping, not a rename --
     * ExecutedOrder.status used to hold Binance's own raw order-status string directly; this
     * OMS's own Order.status is a formal enum. Binance's documented status values (confirmed by
     * reading BinanceBrokerAdapter.getOrderStatus's own real response parsing, not assumed):
     * NEW, PARTIALLY_FILLED, FILLED, CANCELED, PENDING_CANCEL, REJECTED, EXPIRED, plus this
     * codebase's own adapter-level "UNKNOWN" fallback when the field is missing from the
     * response entirely. Genuinely unrecognized values map to UNKNOWN rather than silently
     * defaulting to something that could be misread as a normal, resolved state.
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
    // same reasoning as handleOcoAllDoneWithNoFill: money-consequential logic deserves a focused
    // test without standing up the whole reconcileCredential() chain. Added for the review's
    // own explicitly requested test #5 ("base commission... through: reconciliation").
    void syncPositionQuantityIfMismatched(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                                    Order order, BigDecimal confirmedExecutedQty, long lockGeneration) {
        // P0-5 fix ("Global unique indexes on exchange order IDs collide across
        // symbols/credentials/testnet" -- full context in Position's own @CompoundIndex
        // javadoc): scoped by credentialId/symbol (both already on the Order this reconciliation
        // pass is processing) so a same-numbered entry order id belonging to a DIFFERENT
        // credential or symbol can never be matched to the wrong Position here.
        Optional<Position> positionOpt = positionRepo.findByCredentialIdAndSymbolAndEntryOrderId(
            order.getCredentialId(), order.getSymbol(), order.getBrokerOrderId());
        if (positionOpt.isEmpty()) {
            // Review finding ("P0 #7" — "a recovered MARKET order can potentially become an
            // orphan position"): confirmed real. AutoTradeService correctly refuses to create a
            // Position when executedQty comes back 0/null at order-placement time — but the
            // ExecutedOrder row IS saved regardless (with status="NEW"), and if the order
            // subsequently fills on Binance's side, this reconciliation loop would previously
            // just update that row's status/fillPrice and stop, since this method required an
            // existing Position to "correct". Nothing ever created one. Real coins could then
            // sit on the exchange with no Position, no protection, and nothing watching them.
            createPositionForLateDiscoveredFill(credential, adapter, apiKey, apiSecret, order, confirmedExecutedQty, lockGeneration);
            return;
        }
        Position position = positionOpt.get();
        if (!"OPEN".equals(position.getStatus())) return;
        if (position.getQuantity().compareTo(confirmedExecutedQty) == 0) return; // already correct, nothing to do

        BigDecimal oldQty = position.getQuantity();
        BigDecimal oldAvgEntry = position.getAvgEntryPrice();

        // Review finding (this doc, "partial-fill accounting is still mathematically incorrect"):
        // this used to update quantity alone and leave avgEntryPrice at whatever the FIRST
        // partial fill priced it at — every P&L, exposure, and risk calculation downstream of
        // this position would then be silently wrong. Pull the real fills for this order and
        // compute the actual quantity-weighted average price and total commission, the same way
        // a broker statement would.
        BigDecimal newAvgEntry = oldAvgEntry;
        BigDecimal newEntryFee = position.getEntryFeeQuote();
        boolean priceVerified = false;
        // Review finding (P1/P0-depending — "Late-fill recovery still doesn't deduct base-asset
        // commission" — this method has the SAME gap createPositionForLateDiscoveredFill did):
        // confirmedExecutedQty here is the GROSS quantity from order status, same as the other
        // method — needs the same net-of-commission treatment before being trusted as the actual
        // position size, or the re-placed OCO below sizes itself against coins the wallet
        // doesn't actually have.
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

        // Review finding ("#15"): the quantity is independently confirmed (order status, not
        // fills) and always safe to apply. The PRICE is only "corrected" when fills were
        // actually retrieved — if that lookup failed, say so honestly instead of quietly
        // reusing the old value while claiming success in the audit trail.
        position.setQuantity(netQuantity);
        position.setAvgEntryPrice(newAvgEntry);
        position.setEntryFeeQuote(newEntryFee);
        position.setAvgEntryPriceUnverified(!priceVerified);
        // Review finding ("Position close has atomic protection; not every position mutation
        // does" -- P1): continuing this session's own established one-path-at-a-time conversion
        // of this codebase's remaining plain positionRepo.save(position) calls -- see this
        // file's own earlier comment on the partial-exit conversion for the full reasoning.
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

        // Review finding ("Reconciliation lease can still expire during one long mutation step"
        // -- external review, third pass, P1-2 remainder): the quantity-correction mutation
        // above has already run. Renewed immediately before cancelOco -- the first real
        // exchange-mutating call in this method.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before cancelOco in "
                + "syncPositionQuantityIfMismatched (position {}) -- another instance may now own this lock. Aborting before mutating.",
                credential.getId(), position.getId());
            return;
        }
        // The existing OCO protects the OLD (wrong) quantity — it must be replaced, not left mismatched.
        OcoOrderResult cancelResult = adapter.cancelOco(apiKey, apiSecret, credential.getMode(), position.getSymbol(), position.getOcoOrderListId());
        // Review finding ("OMS not actually authoritative" -- P0, full context in
        // OrderService.recordOcoCancelResult's own javadoc): the cancel now updates the OCO's
        // own OMS Order record too, not just placement. Non-fatal by design, matching every
        // other OMS call this session -- a bug here must never affect the real cancel/re-place
        // flow this method is actually responsible for.
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
            // Review finding ("P1 — OCO cancellation failure during position quantity
            // correction"): this used to only audit-log the failure and return — nothing
            // actually stopped further autonomous trading on this credential while the
            // protection quantity stayed mismatched against the real position. Halting (not
            // flattening — same reasoning as above still applies) closes that gap: new
            // positions stop being opened while the existing, still-live OCO is left alone for
            // manual reconciliation.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_FAILED",
                "CRITICAL: could not cancel mis-sized OCO " + position.getOcoOrderListId() + " on " + position.getSymbol()
                    + " after a quantity correction (" + oldQty + " -> " + confirmedExecutedQty + "): " + cancelResult.errorMessage()
                    + " — MANUAL INTERVENTION REQUIRED, protection quantity does not match actual position.");
            haltForResizeFailure(credential, position);
            return;
        }

        if (order.getStopLossTriggerPrice() == null || order.getTakeProfitPrice() == null) {
            // Review finding (this doc, "protection resize failure doesn't immediately
            // emergency-flatten"): the old OCO is now cancelled and there's no price data to
            // re-place it with — this position IS genuinely naked. No more "mark unprotected and
            // hope"; flatten it.
            credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "PROTECTION_RESIZE_INCOMPLETE",
                "Mis-sized OCO on " + position.getSymbol() + " was cancelled but no SL/TP price was recorded to re-place it with — "
                    + "emergency-flattening rather than leaving it naked.");
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "No SL/TP price recorded to resize protection with after a partial-fill quantity correction.");
            return;
        }

        BigDecimal stopLimit = order.getStopLossTriggerPrice().multiply(BigDecimal.ONE.subtract(stopLossLimitGapPercent));
        // Review finding ("OCO client IDs are STILL TOO LONG" -- P0, full context in
        // AutoTradeService's own identical fix): confirmed real and fixed. Kept the timestamp
        // in the basis string (unlike the entry OCO's own deterministic fix) -- a resize can
        // genuinely be attempted more than once for the same position (a retry after a prior
        // resize failure), and each attempt needs its own distinct id rather than colliding
        // with a previous attempt's id at Binance as a false duplicate.
        String newListClientOrderId = OrderService.generateClientOrderId("tv-r", position.getId() + ":RESIZE:" + System.currentTimeMillis());

        // Review finding ("OMS not actually authoritative" -- P0, full context in OrderService's
        // own recordOcoPlacementResult javadoc): the resize's own OCO placement gets a real OMS
        // Order record too, same three-phase, non-fatal setup as every other OCO placement site.
        com.tradevision.model.Order resizeOmsOrder;
        try {
            resizeOmsOrder = orderService.create(order.getUserId(), credential.getId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(),
                order.getTakeProfitPrice(), newListClientOrderId);
            // Review finding ("OCO's OMS record doesn't store separate TP/SL prices, orderRole,
            // parentOrderId" -- P1, full context in Order.orderRole's own field comment): same
            // stamping as every other OCO placement site this session.
            resizeOmsOrder.setOrderRole("OCO_EXIT");
            resizeOmsOrder.setTakeProfitPrice(order.getTakeProfitPrice());
            resizeOmsOrder.setStopLossTriggerPrice(order.getStopLossTriggerPrice());
            resizeOmsOrder.setStopLossLimitPrice(stopLimit);
            orderService.markRiskAccepted(resizeOmsOrder);
            orderService.markSubmitting(resizeOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for OCO resize failed (non-fatal, additive record only): {}", e.getMessage());
            resizeOmsOrder = null;
        }

        // Same fix as the late-fill-recovery OCO placement — position.getQuantity() is now
        // netQuantity (set above), not the gross confirmedExecutedQty this used to size against.
        // Review finding ("Reconciliation lease can still expire during one long mutation step"
        // -- external review, third pass, P1-2 remainder): cancelOco and the OMS setup above
        // have both already run since this method's own earlier renewal check. A lost lease
        // here is genuinely different from a simple abort (same reasoning as
        // createPositionForLateDiscoveredFill's own identical fix): cancelOco above already
        // consumed the OLD OCO, so this position now has NO protection at all, and
        // reconcileUnprotectedPosition's own next-pass check never re-attempts protection for a
        // still-held position -- only closes one whose balance is already gone. Emergency-
        // flatten instead of a plain early return, matching this method's own "protect or
        // flatten, never strand" principle -- safe even with the reconciliation lease gone,
        // since emergencyFlatten acquires its own separate, position-specific lock.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before placeExitOco in "
                + "syncPositionQuantityIfMismatched (position {}) -- another instance may now own this lock. Emergency-flattening rather "
                + "than leaving this position naked (its old OCO was already cancelled above).", credential.getId(), position.getId());
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Lost the reconciliation lock immediately after cancelling this position's old, mismatched-quantity OCO, before a "
                    + "correctly-sized replacement could be placed.");
            return;
        }
        // Review finding ("OCO persistence still has an unavoidable crash window" -- external
        // review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc):
        // the actual pre-submission fix -- created with the SAME listClientOrderId about to be
        // sent, BEFORE the exchange call itself, closing the gap createOrphanForOco below still
        // leaves (that one only runs AFTER the exchange call succeeds).
        String protectionAttemptId1 = createProtectionAttempt(position, newListClientOrderId, position.getQuantity(),
            order.getTakeProfitPrice(), order.getStopLossTriggerPrice());
        // Review finding ("Protective OCO recovery still has a path with no durable recovery
        // record" -- external review, twenty-first pass, P0, confirmed real by direct
        // inspection before this fix: this method used to proceed to the real OCO exchange call
        // regardless of whether createProtectionAttempt's own persistence succeeded -- if it
        // failed AND the process then crashed before the position's own OCO fields were ever
        // recorded, a real, active exchange-side OCO could exist with genuinely no durable local
        // trace of it at all): the actual fix, scoped to LIVE specifically -- for TESTNET/PAPER,
        // no real money is at risk, so the OCO call still proceeds even without this record.
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
        // Review finding ("Recovery after exchange submission still needs a stronger state
        // boundary" -- external review, twentieth pass, P1, full context in
        // Order.exchangeCallStartedAt's own field javadoc): stamped here, immediately before
        // the real network call, same discipline as AutoTradeService's own entry-order call site.
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
        // Review finding ("OCO placement success + local persistence failure still has a
        // residual crash window" -- external review, eighteenth pass, P0, full context in
        // createOrphanForOco's own javadoc): the actual fix -- this is now the VERY FIRST thing
        // that happens after the exchange call succeeds, before OMS recording or any other
        // processing that could throw and abandon this operation with zero durable trace.
        String orphanId = (newOco.success() && newOco.ocoOrderListId() != null)
            ? createOrphanForOco(position, newOco.ocoOrderListId(), newOco.actualProtectedQuantity())
            : null;
        // Review finding ("OCO quantity can be smaller than the actual position because of
        // base-asset fees" -- P0, full context in Position.protectedQuantity's own field
        // javadoc): same fix as every other OCO placement site this session.
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
            // Review finding (P1 #4 — same fix as AutoTradeService's placeExitOcoOrEmergencyFlatten):
            // a "failed" placement can still carry a real ocoOrderListId from recovery — record
            // it on the position before flattening so emergencyFlatten's own OCO-aware state
            // machine (P0 #1) verifies actual fill state rather than this path discarding it.
            if (newOco.ocoOrderListId() != null) {
                atomicSetOcoPlaced(position, newOco.ocoOrderListId(), null, orphanId);
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Could not re-place resized protection after a partial-fill quantity correction: " + newOco.errorMessage());
        }
    }

    /**
     * Review finding ("P0 #7"): creates and protects a Position for an order that AutoTradeService
     * correctly refused to open a position for at placement time (executedQty was 0/null then),
     * but which reconciliation has now confirmed actually filled. Reuses the same fills-based
     * weighted-average pricing and unverified-price handling (status="OPEN" +
     * avgEntryPriceUnverified, matching "P0 #3"'s fix) as everywhere else — this is not a special
     * case with its own rules, it's the same position-creation logic AutoTradeService itself uses,
     * just triggered from a later discovery point.
     */
    // Package-private (not private) so PositionMonitorServiceTest can exercise this directly —
    // same reasoning as every other money-consequential method in this file that got the same
    // treatment. Never had any test coverage before this atomic-correlation-reservation change.
    public void createPositionForLateDiscoveredFill(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                                       Order order, BigDecimal confirmedExecutedQty, long lockGeneration) {
        // P0 fix (production incident, full context in OrderRepository.
        // findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc's own javadoc):
        // a SECOND, independent guard against the exact same failure that query-level fix
        // closes -- this method must never create a Position from a SELL order, regardless of
        // which upstream query or caller handed it one. This was the actual, immediate cause of
        // the production incident: this method used to hardcode "BUY" unconditionally when
        // recording the fill and constructing the Position below, with no check on the real
        // order.getSide() at all -- so a genuine emergency-flatten SELL, once rediscovered by
        // the (now-fixed) query above, was recorded and priced as if it were a brand-new BUY
        // entry, creating a phantom Position whose "entry price" was literally the previous
        // position's own exit price. That phantom position's own emergency-flatten then placed
        // ANOTHER real SELL, rediscovered the same way next cycle -- a self-sustaining infinite
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
        // Review finding (P1 #4 — "Late-fill recovery still bypasses the slot reservation"):
        // confirmed real — this used to create a Position with no slot/exposure reservation at
        // all, since the original order's own reservation had already been released when its
        // executedQty first came back 0/null. A late-discovered fill is real coins regardless of
        // whether a slot is available now; reserve first, and if the cap is already full,
        // emergency-flatten rather than silently exceeding maxConcurrentTrades/exposure caps.
        var profileOpt = riskProfileRepo.findByCredentialId(credential.getId());
        int maxConcurrent = profileOpt.map(RiskProfile::getMaxConcurrentTrades).orElse(1);
        var slotResult = slotReservationService.reserve(credential.getId(), maxConcurrent, null, credential.getMode() == BrokerMode.LIVE);
        boolean slotReserved = slotResult.reserved();
        String slotReservationId = slotResult.reservationId();

        BigDecimal avgEntry = null;
        BigDecimal entryFee = null;
        boolean priceVerified = false;
        // Review finding ("Fill Ledger" review — "Late-discovered fills still don't enter
        // FillLedger" / "FillRecord needs positionId"): the position object itself isn't
        // constructed until later in this method (after fill fetching, net-quantity
        // computation, and the slot/exposure over-limit check below) — same reasoning as
        // AutoTradeService's own entry-path fix: pre-assign the id now so the fill-ledger
        // recording below (added alongside this fix, right where the fills are already fetched)
        // can reference the real, final position id immediately, and the eventual Position
        // object just reuses this same id when it's constructed.
        String positionId = java.util.UUID.randomUUID().toString();
        // Review finding ("Position created before ledger is guaranteed"): declared here,
        // alongside positionId, for the same reason — needs to be visible both where the fill
        // ledger recording happens (below) and later where the Position/flattenTarget objects
        // are actually constructed and this flag gets applied to whichever one is used.
        boolean ledgerRecordingIncomplete = false;
        // Review finding (P1/P0-depending — "Late-fill recovery still doesn't deduct base-asset
        // commission" — full context in PositionSafetyService.computeNetQuantity's own javadoc):
        // netQuantity now flows through everything below — the flatten-target quantity if limits
        // are exceeded, the exposure reservation amount, and the final position quantity — all
        // previously used the GROSS confirmedExecutedQty, meaning exposure itself was also being
        // over-counted by exactly the commission amount, not just the eventual OCO sizing.
        BigDecimal netQuantity = confirmedExecutedQty;
        // CI-review fix ("PositionPersistenceRecoveryIntegrationTest: Fill ledger mismatch" --
        // full context in PositionLedgerService.reconstructPosition(String, String)'s own
        // updated javadoc): the reconcilePositionAgainstLedger call further below needs the same
        // base asset computeNetQuantity already uses, but `rules` itself is a local declared
        // inside the `if (totalQty.signum() > 0)` block just below -- out of scope by the time
        // this method reaches that reconcile call. Captured into this outer-scoped holder the
        // same way netQuantity itself already is, right where rules is actually resolved.
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
                    // Review finding ("Fill Ledger" review — "Late-discovered fills still don't
                    // enter FillLedger"): confirmed real and fixed — this is the exact same
                    // fill data AutoTradeService's own entry path already records, just
                    // discovered later. Uses the pre-generated positionId (this method's own
                    // top-of-method comment) since the Position object itself doesn't exist yet.
                    var recorded = fillLedgerService.recordFills(order.getBrokerOrderId(), positionId, order.getUserId(), credential.getId(),
                        order.getSymbol(), "BUY", rules.quoteAsset(), fills, confirmedExecutedQty, avgEntry);
                    // Review finding ("Position created before ledger is guaranteed" — full
                    // reasoning in Position.ledgerRecordingIncomplete's own javadoc): the
                    // Position object doesn't exist yet here either — same pre-generated-id
                    // pattern this method already uses, applied to this flag too.
                    int expectedFillRecords = (fills != null && !fills.isEmpty()) ? fills.size()
                        : (confirmedExecutedQty != null && confirmedExecutedQty.signum() > 0 ? 1 : 0);
                    ledgerRecordingIncomplete = recorded.size() < expectedFillRecords;
                    if (ledgerRecordingIncomplete) {
                        // Review finding ("Fill Ledger can still fail without stopping financial
                        // state changes" -- full context in AutoTradeService's own entry-path
                        // fix for this same review item): the same escalation, applied here too
                        // -- a late-discovered fill is exactly as much "a brand-new position's
                        // first ledger write failing" as a normal entry is, and deserves the
                        // same treatment, not a quieter one just because it was found late.
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
                    // Review finding ("Exit/OCO commission backfill is still NOT wired" --
                    // external review, fourteenth pass, P1, full context in
                    // FillLedgerService.backfillHistoricalCommissionConversion's own javadoc):
                    // same wiring as AutoTradeService's own entry path -- a late-discovered fill
                    // is still an ENTRY, and its own commission deserves the same accounting
                    // treatment.
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

        // Exposure reservation — same reasoning as the slot above, best-effort using whatever
        // price we could verify (skipped entirely if unverified, matching how exposure tracking
        // already excludes unverified-price positions everywhere else — see
        // releaseExposureForClosedPosition).
        //
        // Review finding ("Risk" — "atomic correlation reservations"): now also atomically
        // reserves any matching correlation group, same mechanism as AutoTradeService's own
        // entry-reservation call site.
        boolean exposureReserved = true;
        BigDecimal exposureAmount = null;
        // Review finding ("Exposure reservation rollback can steal another trade's reservation"
        // -- external review, twenty-sixth pass, P0, full context in
        // ExposureReservationRecord's own class javadoc): declared at this wider scope
        // specifically so the rollback below can release the EXACT reservation by id, not a
        // recomputed amount.
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
            // Review finding ("Fill Ledger" review — "FillRecord needs positionId"): reuses the
            // same pre-generated id the fillLedgerService.recordFills() call above already
            // referenced, so the ledger record correctly points at whichever Position object
            // actually ends up persisted, regardless of which branch this method takes.
            flattenTarget.setId(positionId);
            // Review finding ("Position created before ledger is guaranteed"): applied here too
            // — this branch (over-limit, emergency-flattening the late discovery immediately)
            // still creates a real Position, so the same visibility matters regardless of which
            // branch this method takes.
            flattenTarget.setLedgerRecordingIncomplete(ledgerRecordingIncomplete);
            flattenTarget.setUserId(order.getUserId());
            flattenTarget.setCredentialId(credential.getId());
            flattenTarget.setBroker(credential.getBroker());
            flattenTarget.setMode(credential.getMode());
            flattenTarget.setSymbol(order.getSymbol());
            flattenTarget.setQuantity(netQuantity);
            flattenTarget.setEntryOrderId(order.getBrokerOrderId());
            flattenTarget.setStatus("OPEN");
            // P0 fix, same reasoning as the normal (under-limit) branch's own identical fix a
            // few lines below in this method: this branch never set triggerSource at all before
            // this fix, and a position created here was discovered by reconciliation, not a
            // live placement decision -- "LATE_FILL_DISCOVERED" is this codebase's own existing
            // domain term for exactly that, reused here for consistency with the other branch.
            flattenTarget.setTriggerSource("LATE_FILL_DISCOVERED");
            flattenTarget.setAvgEntryPriceUnverified(!priceVerified);
            if (priceVerified) flattenTarget.setAvgEntryPrice(avgEntry);
            positionRepo.save(flattenTarget);
            if (slotReserved) slotReservationService.release(slotReservationId); // give back whichever one WAS reserved
            // Review finding ("Exposure reservation rollback can steal another trade's
            // reservation" -- external review, twenty-sixth pass, P0, full context in
            // ExposureReservationRecord's own class javadoc): releases the EXACT reservation by
            // id now, not a recomputed amount.
            if (exposureReserved && exposureReservationId != null) exposureReservationService.release(exposureReservationId);
            // Review finding ("Late-fill path still mutates Position directly" -- P0, full
            // context in the sibling check just above in this method's own normal branch): same
            // reconciliation, arguably more valuable here specifically -- emergencyFlatten below
            // uses flattenTarget.getQuantity() to decide how much to sell, so a genuine mismatch
            // here means the flatten itself is about to act on a quantity the ledger disagrees
            // with. Runs before that call, not after, for exactly that reason.
            try {
                var reconcileResult = positionLedgerService.reconcilePositionAgainstLedger(positionId, netQuantity, baseAssetForLedgerReconcile);
                if (!reconcileResult.matches()) {
                    // Review finding ("Position Ledger is still not authoritative" -- P0, full
                    // context in ReconcileResult.resolvedQuantity's own javadoc): actual
                    // derivation, applied here specifically because it matters most here --
                    // flattenTarget is about to be handed to emergencyFlatten() below, so
                    // correcting its quantity BEFORE that call means the flatten itself acts on
                    // the ledger's own figure, not a locally-computed one it disagreed with.
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
        // Review finding ("Fill Ledger" review — "FillRecord needs positionId"): same reasoning
        // as flattenTarget above — reuses the id the fill-ledger recording already referenced.
        position.setId(positionId);
        // Review finding ("Position created before ledger is guaranteed"): same reasoning as
        // flattenTarget above.
        position.setLedgerRecordingIncomplete(ledgerRecordingIncomplete);
        position.setUserId(order.getUserId());
        position.setCredentialId(credential.getId());
        position.setBroker(credential.getBroker());
        position.setMode(credential.getMode());
        position.setSymbol(order.getSymbol());
        position.setQuantity(netQuantity);
        position.setEntryOrderId(order.getBrokerOrderId());
        position.setSignalId(order.getSignalId());
        // P0 fix (production incident: this used to copy order.getTriggerSource() verbatim,
        // which meant a phantom position created from a misclassified flatten SELL inherited
        // the ORIGINAL manual order's own "MANUAL" value, mislabeling every position in the
        // resulting chain as user-initiated when only the very first one genuinely was). This
        // position was discovered here, by reconciliation, not by a live placement decision —
        // "LATE_FILL_DISCOVERED" is this codebase's own existing domain term for exactly that
        // (already used as the audit-event type this same method raises a few lines below),
        // reused here rather than inventing a new one.
        position.setTriggerSource("LATE_FILL_DISCOVERED");
        position.setOpenedAt(LocalDateTime.now()); // approximate — the real fill time isn't available from here, only that it's confirmed now
        position.setEntryFeeQuote(entryFee);
        position.setStatus("OPEN");
        // Review finding ("Position slot reservations still don't have ownership IDs" --
        // external review, twenty-eighth pass, P0, full context in
        // PositionSlotReservationRecord's own class javadoc): sets slotReservationId here.
        // Also closes a genuine, separate gap found while fixing this: this specific
        // late-discovered-fill Position creation never set exposureReservationId either, even
        // after this session's own earlier P0 fix for it in AutoTradeService's own Position
        // creation -- a real miss, not something this fix is inventing a reason to touch.
        position.setSlotReservationId(slotReservationId);
        position.setExposureReservationId(exposureReservationId);

        // Review finding ("Late-fill path still mutates Position directly" -- P0, full context
        // in PositionLedgerService's own javadoc): position.setQuantity(netQuantity) above is
        // exactly the direct-mutation pattern the review names -- but netQuantity is derived
        // from the SAME fills already recorded to the Fill Ledger a few lines above in this same
        // method (fillLedgerService.recordFills(...)), so the ledger's own independent
        // reconstruction can genuinely cross-check this specific quantity, the same real
        // escalation already wired at entry, OCO-close, and flatten-close. Runs once here,
        // before whichever branch below actually persists the position, since the quantity
        // itself doesn't differ between them.
        try {
            var reconcileResult = positionLedgerService.reconcilePositionAgainstLedger(positionId, netQuantity, baseAssetForLedgerReconcile);
            if (!reconcileResult.matches()) {
                // Review finding ("Position Ledger is still not authoritative" -- P0, full
                // context in ReconcileResult.resolvedQuantity's own javadoc): actual derivation,
                // applied before position is first saved below.
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

        BigDecimal stopLimit = order.getStopLossTriggerPrice().multiply(BigDecimal.ONE.subtract(stopLossLimitGapPercent));
        // Review finding ("OCO client IDs are STILL TOO LONG" -- P0, full context in
        // AutoTradeService's own identical fix): confirmed real and fixed. Timestamp kept in
        // the basis for the same reason as the resize site's own fix -- a retry of this method
        // for the same underlying order gets its own fresh position id already (generated at
        // the top of this method), but the timestamp is extra, harmless insurance against any
        // scenario where that isn't true.
        String listClientOrderId = OrderService.generateClientOrderId("tv-l", position.getId() + ":LATEFILL:" + System.currentTimeMillis());

        // Review finding ("OMS not actually authoritative" -- P0, full context in OrderService's
        // own recordOcoPlacementResult javadoc): same wiring as every other OCO placement site.
        com.tradevision.model.Order lateFillOmsOrder;
        try {
            lateFillOmsOrder = orderService.create(order.getUserId(), credential.getId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(),
                order.getTakeProfitPrice(), listClientOrderId);
            // Review finding ("OCO's OMS record doesn't store separate TP/SL prices, orderRole,
            // parentOrderId" -- P1, full context in Order.orderRole's own field comment): same
            // stamping as every other OCO placement site this session.
            lateFillOmsOrder.setOrderRole("OCO_EXIT");
            lateFillOmsOrder.setTakeProfitPrice(order.getTakeProfitPrice());
            lateFillOmsOrder.setStopLossTriggerPrice(order.getStopLossTriggerPrice());
            lateFillOmsOrder.setStopLossLimitPrice(stopLimit);
            orderService.markRiskAccepted(lateFillOmsOrder);
            orderService.markSubmitting(lateFillOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for late-fill OCO placement failed (non-fatal, additive record only): {}", e.getMessage());
            lateFillOmsOrder = null;
        }

        // Review finding (P1/P0-depending — "Late-fill recovery still doesn't deduct base-asset
        // commission"): this was the acute part of the bug — sizing the exit OCO off the GROSS
        // confirmedExecutedQty rather than netQuantity means Binance would reject the OCO
        // outright (selling more than the account actually holds), or worse, partially fill it
        // unpredictably. position.getQuantity() is netQuantity here (set above at position
        // creation), so using it directly keeps this consistent with what's actually recorded.
        // Review finding ("Reconciliation lease can still expire during one long mutation step"
        // -- external review, third pass, P1-2 remainder): this whole method has real work
        // above (position creation, slot/exposure reservation, OMS setup) with no renewal check
        // at all until now. A lost lease here is genuinely different from every other renewal
        // check added this pass: this position was JUST created by this same method call, has
        // no existing OCO to fall back on, and reconcileUnprotectedPosition's own next-pass
        // check only ever CLOSES an unprotected position whose balance is already gone -- it
        // never re-attempts protection for one that's still genuinely held. A plain early return
        // here would leave this specific position open and completely naked indefinitely, which
        // is a real safety regression relative to every other branch in this method (all of
        // which either protect or emergency-flatten, never silently leave a position unhandled).
        // Emergency-flatten instead, matching this method's own "protect or flatten, never
        // strand" principle -- and safe to do even with the reconciliation lease gone, since
        // emergencyFlatten acquires its own separate, position-specific lock
        // ("flatten:" + position.getId()), independent of this credential-level lease.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before placeExitOco in "
                + "createPositionForLateDiscoveredFill (position {}) -- another instance may now own this lock. Emergency-flattening "
                + "rather than leaving this newly-created position naked.", credential.getId(), position.getId());
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Lost the reconciliation lock immediately before this late-discovered fill could be protected with an OCO.");
            return;
        }
        // Review finding ("OCO persistence still has an unavoidable crash window" -- external
        // review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc):
        // same pre-submission fix as the resize-OCO call site above.
        String protectionAttemptId2 = createProtectionAttempt(position, listClientOrderId, position.getQuantity(),
            order.getTakeProfitPrice(), order.getStopLossTriggerPrice());
        // Review finding ("Protective OCO recovery still has a path with no durable recovery
        // record" -- external review, twenty-first pass, P0, full context in
        // haltForProtectionAttemptPersistenceFailure's own javadoc): same fix as the resize-OCO
        // call site above.
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
        // Review finding ("Recovery after exchange submission still needs a stronger state
        // boundary" -- external review, twentieth pass, P1, full context in
        // Order.exchangeCallStartedAt's own field javadoc): same fix as the resize-OCO call site above.
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
        // Review finding ("OCO placement success + local persistence failure still has a
        // residual crash window" -- external review, eighteenth pass, P0, full context in
        // createOrphanForOco's own javadoc): same fix as the resize-OCO call site above -- the
        // very first thing after the exchange call succeeds, before any other processing.
        String orphanId2 = (oco.success() && oco.ocoOrderListId() != null)
            ? createOrphanForOco(position, oco.ocoOrderListId(), oco.actualProtectedQuantity())
            : null;
        // Review finding ("OCO quantity can be smaller than the actual position because of
        // base-asset fees" -- P0, full context in Position.protectedQuantity's own field
        // javadoc): same fix as every other OCO placement site this session.
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
            // Review finding (P1 #4 — same fix as the other two placeExitOco failure branches):
            // record a known-but-not-active OCO before flattening, letting emergencyFlatten's
            // own state machine (P0 #1) verify fill state rather than discarding it here.
            if (oco.ocoOrderListId() != null) {
                atomicSetOcoPlaced(position, oco.ocoOrderListId(), null, orphanId2);
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Could not place protection for a late-discovered fill: " + oco.errorMessage());
        }
    }

    /**
     * Review finding ("Reconciliation lease can still expire during one long mutation step" --
     * external review, third pass, confirmed real by direct inspection before any fix was
     * attempted): confirmed real -- this method's own loop makes one real, network-bound
     * exchange call PER position (getOcoStatus, or getOrderStatus for an unprotected one), and
     * until this fix, nothing renewed the lease at all once this specific method started. A
     * credential with many open positions, or even a handful of genuinely slow exchange
     * responses, could exhaust the 90-second lease entirely mid-loop -- at which point a second
     * instance could acquire what it believes is a fresh lock on the same credential while this
     * one is still actively working through the rest of the list, both reconciling
     * simultaneously. Renewed before EVERY position, not periodically -- the dominant per-
     * iteration cost is already the real exchange call (hundreds of milliseconds to seconds);
     * an additional cheap, local Mongo update before each one is a rounding error against that,
     * so there was no real reason to trade off safety for a coarser renewal interval here. A
     * lost renewal stops the REST of this loop -- the same "stop rather than continue mutating
     * without exclusive ownership confirmed" principle already established for every other
     * mutation-capable step in this class's own reconciliation pass, applied here at the
     * per-position granularity this specific loop's own risk profile actually needs.
     */
    private void reconcileOpenPositions(BrokerCredential credential, BrokerAdapter adapter, long lockGeneration) {
        // Review item #6 (fixed): reconcile EVERY open position, not just ones with an OCO.
        // A position that has no OCO — either intentionally unprotected, or the survivor of a
        // failed emergency-flatten — is exactly the one that most needs checking, and the
        // previous filter excluded it entirely.
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
    // same reasoning as handleOcoAllDoneWithNoFill and syncPositionQuantityIfMismatched: money-
    // consequential logic (TP/SL/partial-exit P&L and fill-ledger recording) deserves a focused
    // test without standing up the whole reconcileCredential() chain.
    /**
     * P0-3 fix. Called whenever the exit OCO list is not (yet) ALL_DONE -- the overwhelmingly
     * common reason is completely normal: neither leg has triggered, price is between TP and SL,
     * nothing to do. This only takes action for the one specific abnormal case: the STOP_LOSS_LIMIT
     * leg has genuinely triggered (current market price is at or below the recorded stop trigger)
     * but the leg itself is still sitting NEW/PARTIALLY_FILLED, never FILLED -- meaning the resting
     * SELL LIMIT (placed at stopTrigger x (1 - the configurable stopLossLimitGapPercent gap, 0.5%
     * by default -- see AutoTradeService.placeExitOcoOrEmergencyFlatten)
     * is above a market that kept moving down through it and simply isn't filling. Binance's OCO
     * leg status has no separate "triggered" state to read directly (a triggered stop-limit still
     * reports NEW, same as before it triggered) -- comparing live price against the recorded
     * trigger is the same detection approach the review's own suggested fix uses, and the only
     * signal actually available without extra API surface.
     *
     * The fix is exactly emergencyFlatten's own already-existing, already-tested behavior: it
     * checks whether the OCO is still active, cancels it if so, then market-sells whatever is
     * actually held. Nothing new needed there -- the gap was purely that this method never called
     * it for this condition, leaving the position to sit here every 60s re-reading the same stuck
     * EXECUTING status forever.
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
        // P0-3 fix ("STOP_LOSS_LIMIT with 0.5% buffer and no gap handling -> stop can fail
        // silently, position never exits"): confirmed real by direct inspection -- this used to
        // just `return` here whenever the list wasn't ALL_DONE, which is also exactly the state
        // a triggered-but-unfilled stop leg sits in (a fast move through stopLimit leaves the
        // resting SELL LIMIT above the market, never filling, list stuck EXECUTING). That read
        // as "still protected" forever, with nothing ever cancelling the stuck leg or exiting the
        // position while it kept falling. Detect that specific condition and act on it instead of
        // silently returning.
        if (!"ALL_DONE".equalsIgnoreCase(ocoStatus.listStatus())) {
            handleStopTriggeredButUnfilled(credential, adapter, apiKey, apiSecret, position, ocoStatus, lockGeneration);
            return;
        }

        // Review finding (P0-3 fix, item 2 of the review's own required fix: "Treat
        // PARTIALLY_FILLED legs as fills"): confirmed real -- an ALL_DONE list whose stop leg
        // sits PARTIALLY_FILLED (e.g. it expired mid-fill) used to fall all the way through to
        // handleOcoAllDoneWithNoFill as if NOTHING sold, even though part of the position
        // genuinely did. Widened to treat PARTIALLY_FILLED the same as FILLED here -- the
        // existing exitQty-based partial-close logic a few lines below (filledLeg.executedQty())
        // already handles "sold less than the full position" correctly; it was only ever reached
        // for a leg reported as fully FILLED before this fix.
        OcoStatusInfo.Leg filledLeg = ocoStatus.legs().stream()
            .filter(l -> "FILLED".equalsIgnoreCase(l.status()) || "PARTIALLY_FILLED".equalsIgnoreCase(l.status()))
            .findFirst().orElse(null);

        // Review finding ("P0 #1" — "OCO ALL_DONE with NO filled leg can falsely close a real
        // position"): confirmed real, and worse than first reported — this same code path can
        // misread our OWN cancelOco() calls from syncPositionQuantityIfMismatched/reprotection,
        // not just an external actor. ALL_DONE only means the order LIST finished — both legs
        // could be CANCELED with nothing ever sold. That is fundamentally different from "a leg
        // filled but its price couldn't be verified" (below), which really did sell something.
        // Never assume closed here — verify against the actual base-asset balance first, the
        // same check reconcileUnprotectedPosition already uses.
        if (filledLeg == null) {
            handleOcoAllDoneWithNoFill(credential, adapter, apiKey, apiSecret, position, lockGeneration);
            return;
        }

        if (filledLeg.price() == null || filledLeg.price().signum() <= 0) {
            // Review item #5 (fixed): do NOT default to entry price here — that fabricates a
            // P&L of exactly 0 for a position that may well have lost money. A leg genuinely
            // WAS reported FILLED here (unlike the null-filledLeg case above, which is now
            // handled separately) — something really did sell, we just can't price it. Marking
            // this closed-unverified is correct: the position IS gone, only the P&L is unknown.
            // P1-15 fix (full context in recordUnverifiedCloseRiskImpact's own javadoc): this
            // close's real P&L impact used to vanish entirely from the risk engine's own daily
            // loss total and loss streak — captured BEFORE atomicCloseUnverifiedPnl mutates
            // position.quantity to zero, so the estimate is against the real, pre-close size.
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

        // Review finding (this doc, "BLOCKER #3" — OCO partial-exit can falsely close the whole
        // position): this used to multiply P&L by position.getQuantity() (the FULL position
        // size) regardless of how much the exit leg actually sold. If Binance only filled part
        // of the exit order, this would mark the position CLOSED while real coins remained on
        // the exchange. Always use the leg's own confirmed executedQty, and only fully close
        // when it actually covers the whole position.
        BigDecimal exitQty = filledLeg.executedQty();
        if (exitQty == null || exitQty.signum() <= 0) {
            // P1-15 fix, same context as this method's own price-unresolved branch just above:
            // price IS known here (filledLeg.price(), already validated above the closeReason
            // line), only the quantity is unresolved -- captured before mutation, same reason.
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
            // Review finding ("#6 — Complete Fill Ledger" — "Need every fill: ENTRY, TP, SL,
            // PARTIAL EXIT, EMERGENCY FLATTEN, MANUAL"): this one method already handles TP, SL,
            // and partial exits together — the natural single point to record all three. Uses
            // the broker's own order id (filledLeg.orderId()) as the ledger reference, not an
            // OMS Order.id — this path isn't OMS-driven (see OrderService's own javadoc for the
            // disclosed scope boundary: OCO/exit paths remain outside OMS in this pass), so
            // there's no OMS order to reference here. A real, meaningful identifier, just not
            // the same kind the entry-order path uses — stated plainly, not silently mixed.
            // position.getId() is now the CONSISTENT cross-reference across every fill type
            // (see FillRecord's own javadoc for why orderId alone was genuinely inconsistent).
            var recorded = fillLedgerService.recordFills(filledLeg.orderId(), position.getId(), position.getUserId(), position.getCredentialId(),
                position.getSymbol(), "SELL", rules.quoteAsset(), exitFills, exitQty, filledLeg.price());
            // Review finding ("Position created before ledger is guaranteed" — full reasoning in
            // Position.ledgerRecordingIncomplete's own javadoc): only ever set to true, never
            // back to false — a historical gap doesn't get retroactively fixed by a later,
            // successful recording.
            int expectedFillRecords = (exitFills != null && !exitFills.isEmpty()) ? exitFills.size()
                : (exitQty != null && exitQty.signum() > 0 ? 1 : 0);
            if (recorded.size() < expectedFillRecords) {
                position.setLedgerRecordingIncomplete(true);
                // Review finding ("Fill Ledger can still fail without stopping financial state
                // changes" -- full context in AutoTradeService's own entry-path fix for this
                // same review item, now extended to the exit/OCO path too, per the review's own
                // "entry first, then exits" phasing): same escalation, same reasoning -- the
                // exit fill genuinely happened on the exchange (exitQty/filledLeg.price() are
                // already confirmed), so the position's own close/reduction still proceeds
                // normally below; what changes is that further automated trading halts until a
                // human investigates why this write failed.
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
            // Review finding ("Exit/OCO commission backfill is still NOT wired" -- external
            // review, fourteenth pass, P1, confirmed real by direct inspection before any fix
            // was attempted: entry fills already got this treatment, exit fills didn't -- a BNB
            // commission on the TP/SL leg stayed genuinely unconvertible, understating realized
            // P&L on exits specifically): the actual fix -- same wiring as the entry path.
            for (var fillRecord : recorded) {
                try {
                    fillLedgerService.backfillHistoricalCommissionConversion(fillRecord, rules.quoteAsset(), adapter, credential.getMode());
                } catch (Exception e) {
                    log.warn("Historical commission backfill failed for exit fill {} (non-fatal): {}", fillRecord.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // Review finding ("Fill Ledger can still fail after a confirmed fill" -- P0, now
            // closed): confirmed real -- this catch used to only log.warn() and let the position
            // proceed to close/reduce below with exitFee left null and NO fill-ledger write even
            // attempted, regardless of WHICH line inside the try above actually failed
            // (getSymbolRules(), getFillsForOrder() itself, or recordFills()). The escalation a
            // few lines above only fires when recordFills() successfully returns fewer records
            // than expected -- it never ran at all if an earlier line in this same try threw
            // first. Same halt+incident escalation as every other confirmed-fill-but-ledger-gap
            // case in this codebase now, not a quieter one just because the failure happened
            // earlier in the sequence.
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

        // Review finding ("Position P&L architecture is still scattered" -- full context in
        // RealizedPnlService's own javadoc): the same consolidated formula as every other exit
        // path now. position.getQuantity() here is still the pre-reduction value (this runs
        // before position.setQuantity(...) below), matching what the original inline formula
        // also relied on.
        var pnlResult = realizedPnlService.calculate(position.getAvgEntryPrice(), filledLeg.price(), exitQty,
            position.getQuantity(), position.getEntryFeeQuote(), exitFee);
        BigDecimal pnl = pnlResult.realizedPnl();

        boolean fullyClosed = exitQty.compareTo(position.getQuantity()) >= 0;

        // Review finding ("OCO OMS still doesn't become FILLED when a leg fills" -- P0, full
        // context in OrderService.recordOcoFillResult's own javadoc): the actual fix -- the
        // OCO's own OMS Order record now transitions to FILLED or PARTIALLY_FILLED here,
        // matching this exact leg's own real outcome, instead of staying at ACKNOWLEDGED
        // forever while the position itself moves on. Non-fatal, additive, same design as every
        // other OMS bookkeeping call this session -- never allowed to block or delay the real
        // position mutation that follows.
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
            // Review finding (P1 — "normal OCO exit fee accumulation is still wrong"): this fee
            // was already correctly subtracted from pnl above, but the dedicated exitFeeQuote
            // field itself was never updated in this partial branch at all — only the final
            // branch touched it, and only with an overwrite. Accumulate here too, same pattern
            // already used in PositionSafetyService's partial-flatten accounting.
            if (exitFee != null) {
                BigDecimal priorExitFee = position.getExitFeeQuote() != null ? position.getExitFeeQuote() : BigDecimal.ZERO;
                position.setExitFeeQuote(priorExitFee.add(exitFee));
            }
            position.setOcoOrderListId(null); // the OCO list is ALL_DONE — its protection is consumed, remainder needs fresh protection

            // Review finding ("Position close has atomic protection; not every position
            // mutation does" -- P1): UPDATE -- this comment originally disclosed 23 plain
            // positionRepo.save(position) calls as a deliberately-phased, one-path-at-a-time
            // conversion, converting only this method's own partial-exit case first rather than
            // rewriting all 23 in one unverifiable pass. That phased conversion is now complete
            // -- every remaining site across this class and PositionSafetyService has been
            // converted to a targeted atomic update, each verified individually rather than in
            // one large batch. The 4 sites that were genuinely never at risk (the first-ever
            // save of a freshly-constructed position, with nothing yet in the database to race
            // against -- two in AutoTradeService, two in this class's own late-fill recovery
            // path) were correctly left as plain saves rather than converted for their own sake.
            // Review finding ("Reconciliation lease can still expire during one long mutation
            // step" -- external review, third pass, P1-2 remainder): by this point in this
            // method, getOcoStatus, getSymbolRules, and getFillsForOrder have all already run --
            // real network round-trips that, on a slow connection, could collectively approach
            // the lease. Renewed immediately before this position mutation, the actual
            // consequential write this whole method exists to make correctly.
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
        // Review finding (P1 — "normal OCO exit fee accumulation is still wrong"): accumulate,
        // don't overwrite — a prior partial leg's exitFeeQuote (now correctly recorded per the
        // fix just above) must not be discarded when this final leg closes the position.
        if (exitFee != null) {
            BigDecimal priorExitFee = position.getExitFeeQuote() != null ? position.getExitFeeQuote() : BigDecimal.ZERO;
            position.setExitFeeQuote(priorExitFee.add(exitFee));
        }
        position.setRealizedPnlQuote((position.getRealizedPnlQuote() != null ? position.getRealizedPnlQuote() : BigDecimal.ZERO).add(pnl));
        position.setClosedQuantity(position.getQuantity());
        position.setQuantity(BigDecimal.ZERO);

        // Review finding (P1 — "PositionMonitorService has a similar state-transition race"):
        // the reconciliation lock added for "P0 #2" prevents two reconciliation passes running
        // concurrently WITHIN one instance, but a plain load-modify-save here is still not
        // itself atomic — a second layer of protection matters for anything beyond a single
        // instance. Converted the highest-volume, most consequential close transition (this one)
        // to a conditional atomic update: only actually applies if the document is still
        // "OPEN" at the moment of the write, and only then are the money-consequential side
        // effects (P&L recording, slot release, signal outcome) allowed to run. Honest scope:
        // this pattern is NOT applied to every terminal-status write in this file — that would
        // be its own dedicated pass; this is the one that matters most, done for real.
        // Review finding ("Reconciliation lease can still expire during one long mutation step"
        // -- external review, third pass, P1-2 remainder, same reasoning as this method's own
        // partial-exit renewal check above): the full-close path is the other money-
        // consequential write in this method, reached after the same set of network calls.
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
        releaseSlotForClosedPosition(position); // review items #13/#14: free the slot now this position is genuinely closed
        releaseExposureForClosedPosition(position);

        credentialService.audit(position.getUserId(), credential.getId(), credential.getBroker(), "POSITION_CLOSED",
            "Position on " + position.getSymbol() + " closed via " + closeReason + " at " + filledLeg.price()
                + ", realized P&L " + pnl);

        // Review finding ("Position Ledger is still not authoritative" -- full context in
        // PositionLedgerService's own javadoc): a fully-closed position's entire fill history
        // should net to approximately zero -- everything bought was eventually sold. A genuine
        // mismatch here means the ledger and this position disagree about whether it's actually
        // fully closed, which is exactly the kind of thing worth halting over rather than
        // silently trusting either side. Runs after the position is already closed (its own
        // exchange-side closure is real regardless of what the ledger says) -- what changes on
        // a mismatch is that further automated trading halts until a human investigates.
        //
        // CI-review fix (full context in PositionLedgerService.reconstructPosition(String,
        // String)'s own updated javadoc): without the base asset, a fully-closed position whose
        // entry paid ANY base-asset commission would net to that commission amount, not zero --
        // a false mismatch on ordinary, correct closes, not just this specific test's own
        // scenario. Resolved best-effort; a failure here falls back to the old, commission-
        // unaware comparison rather than skipping the check entirely.
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
                // Review finding ("Position Ledger is still not authoritative" -- P0, full
                // context in ReconcileResult.resolvedQuantity's own javadoc): actual derivation.
                // The atomic close-update just above already committed quantity=0 to the
                // database, so correcting it now (when the ledger disagrees and is
                // known-complete) needs its own explicit follow-up save -- this does NOT reverse
                // the position's CLOSED status, or the slot/exposure release already performed
                // above: those are a bigger operational decision than "what quantity value is
                // honest", and the halt + incident below ensures a human makes that call rather
                // than this code silently re-opening a position mid-flight.
                BigDecimal resolvedQty = reconcileResult.resolvedQuantity(position.isLedgerRecordingIncomplete());
                if (resolvedQty.compareTo(position.getQuantity()) != 0) {
                    position.setQuantity(resolvedQty);
                    // Review finding ("Position close has atomic protection; not every position
                    // mutation does" -- P1, full context in this file's own earlier partial-exit
                    // conversion comment): this specific site's own guard is deliberately just
                    // "this exact position by id," not the usual "and status=OPEN" -- by this
                    // point the atomic close-update above already committed the CLOSED status,
                    // so an OPEN guard would never match here. The concern this atomicity
                    // protects against is different too: not "did someone else close/reopen it
                    // concurrently" (already resolved), but "don't silently discard this
                    // ledger-driven quantity correction to a plain save() that could race with
                    // some other unrelated write to this same now-closed document."
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
     * Review finding ("P0 #1" — full context in the caller). ALL_DONE with no filled leg found
     * means the OCO list finished WITHOUT anything actually selling — both legs canceled is the
     * common real-world cause, and that includes our own cancelOco() calls from a resize. Verify
     * against real balance before concluding anything: still held → re-protect it (the old OCO
     * is consumed either way, whatever the reason), confirmed gone → THEN it's genuinely closed
     * by some means outside this backend's own order flow.
     */
    // Package-private (not private) specifically so PositionMonitorServiceTest can exercise this
    // directly — same reasoning as checkDrawdown: this is money-consequential logic (P0 #1) that
    // deserves its own focused test without standing up the whole reconcileCredential() chain.
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
            // Review finding ("Position close has atomic protection; not every position
            // mutation does" -- P1, full context in this file's own earlier partial-exit
            // conversion comment): same one-path-at-a-time conversion.
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
        // P1-15 fix (full context in recordUnverifiedCloseRiskImpact's own javadoc): captured
        // before atomicCloseUnverifiedPnl mutates position.quantity to zero. profileOpt isn't a
        // parameter of this method (its own signature is exercised directly by several existing
        // tests, deliberately not widened for this) -- looked up here the same way
        // haltForResizeFailure/haltForProtectionAttemptPersistenceFailure already do elsewhere
        // in this class.
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
    /**
     * Review finding ("Reconciliation lease can still expire during one long mutation step" --
     * external review, third pass, P1-2 remainder: per-mutation fencing inside a single long
     * step, not just between loop iterations): this method's own real exchange-mutating call
     * (placeExitOco below) can be preceded by a real network round-trip (getSymbolRules) and
     * several local OMS writes -- individually cheap, but on a slow network any one of them
     * could be the thing that pushes past the lease. lockGeneration is now threaded through so
     * the renewal check immediately before placeExitOco (the actual point of no return -- once
     * that call lands, this application no longer has the option not to have placed the order)
     * can confirm this instance still holds the lock right up to that moment, not just at the
     * top of the loop iteration that led here.
     */
    private void reprotectRemainder(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret, Position position,
                                     long lockGeneration) {
        // Review finding ("Fee edge cases" — "dust" handling): confirmed real and fixed — a
        // partial exit can leave a remaining quantity below the symbol's own minQty, which
        // neither a re-protection OCO nor an emergency-flatten market sell could ever actually
        // place (Binance rejects both below minQty/minNotional). Without this check, the OLD
        // behavior was to attempt the OCO anyway, let it fail, then attempt emergencyFlatten,
        // let THAT fail too, and the position would sit open forever with an amount that can
        // never be sold through this bot — checked here, before either doomed attempt, rather
        // than discovered only after both failed.
        var symbolRules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
        if (symbolRules != null && symbolRules.minQty() != null && position.getQuantity().compareTo(symbolRules.minQty()) < 0) {
            position.setStatus("CLOSED");
            position.setCloseReason("DUST_REMAINING");
            position.setClosedAt(LocalDateTime.now());
            // Review finding ("Position close has atomic protection; not every position
            // mutation does" -- P1, full context in this file's own earlier partial-exit
            // conversion comment): same one-path-at-a-time conversion.
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
        BigDecimal stopLimit = entryOrder.getStopLossTriggerPrice().multiply(BigDecimal.ONE.subtract(stopLossLimitGapPercent));
        // Review finding ("OCO client IDs are STILL TOO LONG" -- P0, full context in
        // AutoTradeService's own identical fix): confirmed real and fixed. Timestamp kept for
        // the same reason as the resize site's own fix -- re-protecting the remainder can
        // genuinely be attempted more than once for the same position.
        String listClientOrderId = OrderService.generateClientOrderId("tv-rem", position.getId() + ":REMAINDER:" + System.currentTimeMillis());

        // Review finding ("OMS not actually authoritative" -- P0, full context in OrderService's
        // own recordOcoPlacementResult javadoc): same wiring as every other OCO placement site.
        com.tradevision.model.Order remainderOmsOrder;
        try {
            remainderOmsOrder = orderService.create(position.getUserId(), credential.getId(), position.getId(),
                position.getSignalId(), position.getSymbol(), "SELL", "OCO", position.getQuantity(),
                entryOrder.getTakeProfitPrice(), listClientOrderId);
            // Review finding ("OCO's OMS record doesn't store separate TP/SL prices, orderRole,
            // parentOrderId" -- P1, full context in Order.orderRole's own field comment): same
            // stamping as every other OCO placement site this session.
            remainderOmsOrder.setOrderRole("OCO_EXIT");
            remainderOmsOrder.setTakeProfitPrice(entryOrder.getTakeProfitPrice());
            remainderOmsOrder.setStopLossTriggerPrice(entryOrder.getStopLossTriggerPrice());
            remainderOmsOrder.setStopLossLimitPrice(stopLimit);
            orderService.markRiskAccepted(remainderOmsOrder);
            orderService.markSubmitting(remainderOmsOrder);
        } catch (Exception e) {
            log.warn("OMS setup for remainder OCO re-protection failed (non-fatal, additive record only): {}", e.getMessage());
            remainderOmsOrder = null;
        }

        // Review finding ("Reconciliation lease can still expire during one long mutation step"
        // -- external review, third pass, P1-2 remainder, full context in this method's own
        // updated javadoc): the real point of no return -- once placeExitOco lands, this
        // application no longer has the option not to have placed the order. A lost lease here
        // is genuinely different from a simple abort, though (same reasoning as
        // createPositionForLateDiscoveredFill's own identical fix): this position has already
        // had its old OCO consumed/cleared by this point, and reconcileUnprotectedPosition's own
        // next-pass check only ever CLOSES an unprotected position whose balance is gone -- it
        // never re-attempts protection for one still genuinely held. A plain early return here
        // would leave this position open and naked indefinitely. Emergency-flatten instead,
        // matching this method's own "protect or flatten, never strand" principle everywhere
        // else -- safe even with the reconciliation lease gone, since emergencyFlatten acquires
        // its own separate, position-specific lock.
        if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
            log.warn("Could not renew reconciliation lock for credential {} immediately before placeExitOco in reprotectRemainder "
                + "(position {}) -- another instance may now own this lock. Emergency-flattening rather than leaving this position naked.",
                credential.getId(), position.getId());
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Lost the reconciliation lock immediately before the remainder after a partial exit could be re-protected with an OCO.");
            return;
        }
        // Review finding ("OCO persistence still has an unavoidable crash window" -- external
        // review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc):
        // same pre-submission fix as every other OCO placement site above.
        String protectionAttemptId3 = createProtectionAttempt(position, listClientOrderId, position.getQuantity(),
            entryOrder.getTakeProfitPrice(), entryOrder.getStopLossTriggerPrice());
        // Review finding ("Protective OCO recovery still has a path with no durable recovery
        // record" -- external review, twenty-first pass, P0, full context in
        // haltForProtectionAttemptPersistenceFailure's own javadoc): same fix as every other OCO
        // call site above.
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
        // Review finding ("Recovery after exchange submission still needs a stronger state
        // boundary" -- external review, twentieth pass, P1, full context in
        // Order.exchangeCallStartedAt's own field javadoc): same fix as every other OCO call site above.
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
        // Review finding ("OCO placement success + local persistence failure still has a
        // residual crash window" -- external review, eighteenth pass, P0, full context in
        // createOrphanForOco's own javadoc): same fix as every other OCO placement site above --
        // the very first thing after the exchange call succeeds, before any other processing.
        String orphanId3 = (oco.success() && oco.ocoOrderListId() != null)
            ? createOrphanForOco(position, oco.ocoOrderListId(), oco.actualProtectedQuantity())
            : null;
        // Review finding ("OCO quantity can be smaller than the actual position because of
        // base-asset fees" -- P0, full context in Position.protectedQuantity's own field
        // javadoc): same fix as every other OCO placement site this session.
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
            // Review finding (P1 #4 — same fix as the other three placeExitOco failure branches):
            // record a known-but-not-active OCO before flattening, letting emergencyFlatten's
            // own state machine (P0 #1) verify fill state rather than discarding it here.
            if (oco.ocoOrderListId() != null) {
                atomicSetOcoPlaced(position, oco.ocoOrderListId(), null, orphanId3);
            }
            positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position,
                "Could not re-place protection for the remainder after a partial OCO exit: " + oco.errorMessage());
        }
    }

    /**
     * Review items #20 / #2 (this doc's numbering): the real outcome of an auto-traded position
     * must be reflected on its originating TradeCallRecord — otherwise CallResultUpdater's
     * theoretical ticker-crossing check has nothing to stop it from later guessing a different
     * (fake) result for the same call, and the user's trade history shows a fabricated outcome
     * instead of what actually happened.
     */
    /**
     * Review finding ("P0 #3" — "Real trade P&L percentage has a definite bug"): confirmed real,
     * and confirmed 100% reproducible, not a rare edge case — this is the ONLY call site, and by
     * the time it runs, position.getQuantity() is ALWAYS zero (set a few lines earlier in the
     * same close transition, before closedQuantity captured the real value). Every single real
     * position close was dividing by zero here, writing Infinity/NaN into pnlPct on production
     * trade accounting that this codebase's own analytics (the P1 #25 segmented win-rate stats)
     * read directly. Fixed by requiring the caller to pass the actual closed quantity explicitly
     * — impossible to accidentally read the already-zeroed live field again — and using
     * BigDecimal division instead of raw double, with an explicit finite check before ever
     * persisting the result.
     */
    // Package-private (not private) specifically so PositionMonitorServiceTest can exercise this
    // directly — review's own explicitly requested test #4 ("Real P&L callback... doesn't
    // produce Infinity/NaN") needs to call this in isolation without driving the entire
    // reconciliation chain just to reach it.
    void writeRealOutcomeBackToSignal(Position position, java.math.BigDecimal exitPrice, String closeReason,
                                               java.math.BigDecimal pnl, java.math.BigDecimal closedQuantity) {
        if (position.getSignalId() == null) return;
        callRepo.findById(position.getSignalId()).ifPresent(call -> {
            var outcome = call.getOutcome() != null ? call.getOutcome() : new com.tradevision.model.TradeOutcome();
            outcome.setResult(closeReason); // STOP_LOSS / TAKE_PROFIT / EMERGENCY_FLATTEN — real, not a guessed HIT_T1/T2/T3
            outcome.setExitPrice(exitPrice.doubleValue());
            outcome.setResolvedAt(LocalDateTime.now());
            // Review finding ("Financial values still mix double and BigDecimal" -- external
            // review, twenty-fourth pass, P2, full context in TradeCallRecord's own updated
            // field comment): call.getEntryPrice() is BigDecimal now -- this actually simplifies
            // what used to be a BigDecimal.valueOf(double) wrapper conversion.
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

            // Review finding ("Global ML weights can be poisoned by unverified, client-supplied
            // trade outcomes" -- P1 #5): this is the ONE place in the codebase where a signal's
            // outcome is backed by a real, broker-confirmed fill (this method only runs from a
            // genuine position-close, driven by filledLeg/pnl this reconciliation pass just
            // verified against the exchange) rather than a guess. mlWeightService.recordOutcome
            // used to be called ONLY from CallResultUpdater's theoretical ticker-crossing checks
            // (both the TP/SL-hit path and the 30-day-EXPIRED sweep) -- which apply equally to a
            // client-submitted call via POST /api/calls/save (arbitrary RSI/MACD/pattern
            // features, never executed) and a real auto-traded signal, with nothing to tell them
            // apart. That let anyone poison the GLOBAL, unscoped per-symbol weights every other
            // user's live signal-scoring reads from, just by saving fabricated calls. Wiring the
            // write side in HERE instead -- keyed off this method's own real, verified pnl sign
            // rather than the close reason string (a clean MAX_HOLD_TIME/END_OF_SESSION exit
            // near breakeven should count as neither a win nor a loss, exactly like the
            // pre-existing EXPIRED case already does) -- means only real, executed outcomes ever
            // reach the learner. CallResultUpdater's own recordOutcome calls are removed in the
            // same pass (see its own updated comments) since neither of its paths is ever a real
            // fill.
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
     * Review item #6: positions with no OCO can't be checked via order-list status — there's no
     * order list. The best available signal without a user-data-stream WebSocket (review item #3,
     * not built this pass) is: has the base-asset balance dropped below what this position
     * should still hold? If so, something closed it outside our own order flow (manual exchange
     * action, a liquidation, anything) — flag it, don't silently leave it OPEN forever, but also
     * don't fabricate an exit price or P&L we don't actually know.
     */
    private void reconcileUnprotectedPosition(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                               Position position, long lockGeneration) {
        var rules = adapter.getSymbolRules(position.getSymbol(), credential.getMode());
        if (rules.baseAsset() == null) return;

        var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
        BigDecimal totalHeld = totalHeldBalance(balances, rules.baseAsset());
        BigDecimal expectedMinimum = expectedMinimumBalanceForPosition(credential.getId(), position);

        if (totalHeld.compareTo(expectedMinimum.multiply(new BigDecimal("0.98"))) < 0) {
            // Review finding ("Reconciliation lease can still expire during one long mutation
            // step" -- external review, third pass, P1-2 remainder): getSymbolRules and
            // getBalance above are both real network round-trips. Renewed immediately before
            // this position's own consequential close.
            if (!distributedLockService.renew(credential.getId(), instanceId, lockGeneration, java.time.Duration.ofSeconds(90))) {
                log.warn("Could not renew reconciliation lock for credential {} immediately before closing unprotected position {} -- "
                    + "another instance may now own this lock. Aborting before mutating.", credential.getId(), position.getId());
                return;
            }
            // P1-15 fix (full context in recordUnverifiedCloseRiskImpact's own javadoc):
            // captured before atomicCloseUnverifiedPnl mutates position.quantity to zero. This
            // is a position with NO OCO at all (this method's own javadoc), so there is no
            // OCO_EXIT order to fall back to for a worst-case price either -- the helper simply
            // makes no estimate and logs why in that case, same as every other genuinely-unknown
            // case in this codebase, rather than guessing.
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

        // P0-4 fix ("Unprotected OPEN positions are never re-protected or exited"): confirmed
        // real by direct inspection -- everything above only ever checked whether the balance
        // had DISAPPEARED. When the coins are genuinely still here (this branch), the OLD code
        // did nothing at all -- no protection placed, no SL comparison, no flatten -- and just
        // left the position OPEN with unlimited downside until the next pass ran the exact same
        // no-op check again. Every reconcile pass now actually resolves this: either re-protect
        // with a fresh OCO from the entry order's own recorded SL/TP, or exit immediately if that
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
            // left, same reasoning as the sibling stuck-triggered-stop fix in
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
     * Review finding ("#17" — "drawdown still isn't mark-to-market"): this used to be free
     * quote-asset balance only — an underwater open position wouldn't move the drawdown number
     * at all until it actually closed, which is exactly backwards for a risk breaker meant to
     * catch trouble early. Equity is now free balance + the current market value of every open
     * position on this credential.
     *
     * HONEST SCOPE, stated plainly rather than glossed over: this assumes every open position's
     * symbol quotes in the SAME asset as drawdownQuoteAsset (e.g. every enabled symbol is a
     * *USDT pair when drawdownQuoteAsset is USDT) — a position quoted in a different asset
     * (e.g. ETHBTC while tracking USDT) would need a currency conversion this doesn't do. This
     * is the same implicit assumption RiskEngineService's exposure caps already make when they
     * sum quantity × avgEntryPrice across positions without checking quote-asset consistency —
     * not a new limitation introduced here, but one that's now also true of this calculation.
     * If ANY open position's current price can't be fetched, the whole check is skipped for
     * this cycle rather than computing equity from a partial position list — a partial number
     * could understate OR overstate drawdown depending on which position got excluded, and
     * guessing which direction is wrong is worse than waiting for the next cycle.
     */
    // Package-private (not private) specifically so PositionMonitorServiceTest can exercise this
    // directly, without standing up the whole reconcileCredential() call chain (order/OCO
    // reconciliation, symbol rules, etc.) just to test the drawdown math in isolation.
    /**
     * P1-15 fix -- second half of the audit item, deliberately scoped down. Fixed in this pass:
     * quote-asset equity now includes LOCKED balance, not free only (see this method's own
     * updated comment at the quoteBalance calculation for the full reasoning). Deliberately NOT
     * attempted in this same pass, matching this codebase's own established discipline of
     * disclosing a scoped-down fix rather than silently leaving a gap unmentioned (see, e.g.,
     * AutoTradeService.sizePosition's own disclosed LIMIT-IOC scope-out for the precedent this
     * follows): full account valuation (non-bot holdings on this same credential, any other
     * asset genuinely part of this account's real net worth) and deposit/withdrawal detection.
     * Both would require classifying every balance change over time as either trading P&L or an
     * external capital flow -- something this application has no existing infrastructure for at
     * all (no balance-history ledger, no deposit/withdrawal webhook or polling from Binance) --
     * a genuinely separate, materially larger undertaking than the two bounded, real fixes this
     * pass actually ships (this method's own locked-balance fix, and
     * recordUnverifiedCloseRiskImpact's own daily-loss/loss-streak fix elsewhere in this class).
     * Concretely, this means: a deposit into this account still raises peakEquityQuote as if it
     * were trading profit, a withdrawal can still trip the drawdown halt as if it were a trading
     * loss, and peakEquityQuote is still never reset (per-session or per-day, as the audit's own
     * suggested fix mentions) -- all three remain real, open gaps a future pass should address
     * with the capital-flow tracking this one does not attempt to build.
     */
    void checkDrawdown(BrokerCredential credential, BrokerAdapter adapter) {
        Optional<RiskProfile> profileOpt = riskProfileRepo.findByCredentialId(credential.getId());
        if (profileOpt.isEmpty()) return;
        RiskProfile profile = profileOpt.get();
        if (profile.getMaxDrawdownPercent() <= 0 || profile.getDrawdownQuoteAsset() == null) return;

        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
        // P1-15 fix ("Drawdown ignores locked USDT" -- the first, narrower half of the audit
        // item's second named gap this pass actually closes; see recordUnverifiedCloseRiskImpact's
        // own javadoc for the first gap, and this method's own updated comment further below for
        // what is deliberately NOT attempted here): confirmed real -- this used to read ONLY
        // AssetBalance.free(), so quote-asset locked in a resting LIMIT order, in the margin/
        // collateral leg of any pending operation, or simply reserved by the exchange for any
        // other reason was invisible to this equity calculation entirely. That quote is not
        // gone -- it is still this account's own money, just not currently free to spend -- and
        // treating it as zero could both fabricate a drawdown breach that never really happened
        // (funds "vanish" from equity the moment they're locked, "reappear" when unlocked) and
        // mask a genuine one (real losses hiding behind funds this calculation never counted in
        // the peak to begin with). free+locked is the correct, minimal fix for this specific,
        // narrow gap -- it does not require any new capital-flow tracking infrastructure, unlike
        // the deposit/withdrawal and non-bot-holdings part of this same audit item.
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
                // Review finding ("Drawdown can silently stop checking when pricing fails" --
                // P1): confirmed real -- this used to log a warning and return, silently
                // skipping the ENTIRE drawdown check cycle whenever even one open position's
                // price couldn't be fetched. Drawdown protection could be completely inactive
                // during exactly the kind of market-data disruption (an exchange outage, a
                // network partition) that also makes real losses more likely, with nothing
                // surfacing this except a log line nobody may ever read. Fixed to treat
                // inability to calculate equity as a risk event, not a free pass, matching the
                // review's own explicit guidance -- halts new trading and raises an incident,
                // while deliberately leaving existing exchange-side protection (OCO stop-loss/
                // take-profit orders already placed on the exchange) completely untouched, since
                // those don't depend on this application being able to price anything right now.
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

        // Review finding (P1 #8 — "Risk failure counters still have races"): peakEquityQuote had
        // the same read-modify-write shape as consecutiveOrderFailures/dailyRealizedLossQuote —
        // MongoDB's own $max operator is actually the cleanest fix here, since "update only if
        // the new value is greater" is exactly what it does atomically in one step, with no
        // conditional query needed at all.
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

    /** Review finding ("P1 — OCO cancellation failure during position quantity correction"):
     *  halts new autonomous trading on this credential without touching the existing (still-live)
     *  OCO — deliberately not calling PositionSafetyService.emergencyFlatten here, since the old
     *  OCO cancellation just failed and may still be active; flattening blind risks colliding
     *  with a live order the way the surrounding code already reasons about. */
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

    /** Review finding, same context as haltForResizeFailure's own javadoc above: the same
     *  atomic halt mechanism, for a different failure this session's own P0-3 fix specifically
     *  needs -- a LIVE credential whose pre-submission ProtectionAttempt record could not be
     *  persisted, meaning the OCO exchange call for it must not be made at all (see
     *  createProtectionAttempt's own callers for the actual skip logic this halt accompanies). */
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
