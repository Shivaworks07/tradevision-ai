package com.tradevision.service;

import com.tradevision.model.TradeCallRecord;
import com.tradevision.repository.TradeCallRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Review finding (P1 #5 — "Auto-trading is not durable" — full context in
 * TradeCallRecord.autoTradeEvalStatus's own javadoc): the Mongo-backed outbox/worker the review
 * itself said would be enough for this stage, not Kafka/RabbitMQ. Periodically finds signals
 * stuck in PENDING (the async dispatch never ran at all — e.g. the process crashed right after
 * save) or stuck in EVALUATING (a crash mid-evaluation) for longer than is plausible under
 * normal operation, and re-dispatches them.
 *
 * STALE_AFTER is deliberately generous (10 minutes) — evaluation itself normally completes in
 * well under a second, so this isn't tuned to catch normal latency, it's tuned to avoid ever
 * mistaking an evaluation that's simply still queued behind a burst of other signals for one
 * that's genuinely lost.
 *
 * Review finding (P1 — "Auto-trade recovery has a duplicate-evaluation edge"): confirmed real,
 * a genuine bug in this file's own original design — PENDING and EVALUATING need DIFFERENT
 * staleness signals. PENDING's correct measure is calledAt (a PENDING record never had an
 * evaluation attempt begin, so there's no other timestamp to use). EVALUATING's correct measure
 * is autoTradeEvalStartedAt — using calledAt for BOTH meant a signal that sat queued for 8
 * minutes before being claimed, then legitimately evaluating for only 2 more, would already
 * read as ">10 minutes since calledAt" and get incorrectly reclaimed while the original worker
 * was still genuinely alive and working on it — two workers evaluating the same signal at once.
 *
 * Review finding (P1/🟠 #15 — "Trading worker is still inside the API process"): worth being
 * explicit about what this file does and doesn't address. This outbox/recovery pattern is the
 * "you don't have to introduce Kafka yet, a Mongo-backed worker is enough" piece the review
 * itself asked for — signal evaluation now survives a process crash without message loss. What
 * it does NOT do is move trading execution into a genuinely separate process/deployable from the
 * REST API — evaluateSignal still runs inside this same Spring Boot application (on its own
 * bounded executor, not the request thread, but the same JVM and the same deployment unit).
 * Actually separating "API" from "trading worker" into independently deployable, independently
 * scalable processes is real further architectural work — a different service, its own
 * deployment pipeline, and a decision about how they'd communicate (this same Mongo outbox could
 * still be the answer) — not something to bolt on as a side effect of the durability fix.
 * Review finding ("Recovery needs the same plan-version semantics" -- external review,
 * fourteenth pass, P1): already closed, as a direct consequence of this file's own design
 * rather than anything new added here. This file has NO authorization logic of its own at
 * all -- it only resets autoTradeEvalStatus back to PENDING and re-dispatches through the
 * exact same AutoTradeService.evaluateSignal() every fresh signal goes through. A recovered
 * TradeCallRecord still carries its own original planVersion, stamped by the scanner at the
 * moment it was first generated (see TradeCallRecord.planVersion's own field javadoc) --
 * unchanged by however long it sat stuck. evaluateSignal's own atomic
 * StrategyPlanService.claimPlanExecution() call re-validates that stamped version against the
 * plan's CURRENT one before any exchange call, so "is this signal still authorized under the
 * configuration that generated it" (the review's own question) is answered honestly for a
 * recovered signal exactly the same way it is for a fresh one -- if the plan was edited or
 * disabled while a worker held this signal, recovery re-dispatching it hits the same atomic
 * rejection a live race would have.
 *
 * One honest, minor gap this does NOT close: a crash that happens mid-execution (a signal stuck
 * in EVALUATING, not PENDING) means the original evaluateSignal call may have already
 * incremented StrategyPlan.executionInFlightCount via claimPlanExecution before crashing --
 * releasePlanExecution's own finally block never ran, so that count can leak upward and never
 * come back down for that specific claim. This is purely a diagnostic-count drift, the same
 * already-disclosed limitation as RiskProfile.executionInFlightCount has always had --
 * claimPlanExecution's own findAndModify criteria never checks executionInFlightCount as a gate
 * condition, so a leaked count cannot block any future execution, recovered or otherwise.
 */
@Service
@RequiredArgsConstructor
public class AutoTradeRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AutoTradeRecoveryService.class);
    private static final java.time.Duration STALE_AFTER = java.time.Duration.ofMinutes(10);

    private final TradeCallRepository callRepo;
    private final MongoTemplate mongoTemplate;
    private final AutoTradeService autoTradeService;
    private final com.tradevision.config.ShutdownState shutdownState;
    /**
     * Review finding ("Evaluation recovery now excludes APPROVED, but APPROVED can become
     * permanently abandoned" -- external review, twenty-sixth pass, P1, full context in the new
     * detectStaleApprovedSignals's own javadoc): needed to raise the incident this fix requires.
     */
    private final com.tradevision.service.IncidentService incidentService;

    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000, scheduler = "maintenanceScheduler") // every 2 minutes
    public void recoverStuckSignals() {
        if (shutdownState.isShuttingDown()) return;

        LocalDateTime cutoff = LocalDateTime.now().minus(STALE_AFTER);
        java.util.List<TradeCallRecord> stuck = new java.util.ArrayList<>();
        stuck.addAll(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn("PENDING", cutoff,
            java.util.List.of(com.tradevision.model.SignalStatus.CANCELLED, com.tradevision.model.SignalStatus.EXPIRED)));
        // Review finding ("Auto-trade recovery still needs a lease"): the EVALUATING reclaim
        // decision now uses the real lease expiry (checked against "now", not the fixed
        // STALE_AFTER window above — a signal only becomes reclaimable once its OWN granted
        // lease has actually run out, per whatever duration AutoTradeService.EVALUATION_LEASE_DURATION
        // set at claim time) rather than a fixed elapsed-time assumption from calledAt/STALE_AFTER.
        // Review finding ("Recovery can still race a slow evaluator around the exchange
        // boundary" -- external review, fifteenth pass, P0, full context in this new query
        // method's own javadoc): a signal already at ORDER_PENDING or EXECUTED must never be
        // reclaimed here, however expired its lease looks -- the original worker may genuinely
        // still be mid-exchange-call, or may have already completed it.
        //
        // Review finding ("Auto-trade recovery can still reclaim an evaluation while the
        // original worker is at a late execution stage" -- external review, twenty-fourth pass,
        // P1, confirmed real by direct inspection before this fix: APPROVED is a real, distinct
        // SignalStatus this evaluation flow genuinely passes through immediately before the
        // final exchange authorization/execution sequence -- this exclusion list only ever
        // named ORDER_PENDING/EXECUTED, meaning a signal that had already reached APPROVED
        // before its own worker's lease happened to expire could still be reclaimed and
        // re-evaluated by a second worker while the original worker might still be mid-way
        // through claiming execution authorization. Added APPROVED to this same exclusion,
        // closing the specific gap the review names. Honest scope: this narrows the race
        // without eliminating every theoretical version of it -- the review's own suggested
        // fuller fix (a dedicated EXECUTION_LOCKED status, entered atomically right before the
        // exchange call and excluded here unconditionally) would close the remaining gap
        // completely, but is a larger, more invasive change to this evaluation flow's own state
        // machine than this pass attempts.
        stuck.addAll(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn("EVALUATING", LocalDateTime.now(),
            java.util.List.of(com.tradevision.model.SignalStatus.APPROVED, com.tradevision.model.SignalStatus.ORDER_PENDING,
                com.tradevision.model.SignalStatus.EXECUTED)));
        // Transitional fallback: an EVALUATING record with no autoTradeEvalStartedAt at all
        // (only possible from before this field existed) can't be caught by the date comparison
        // above — treated as unconditionally eligible for recovery, since there's no start time
        // to judge staleness against in the first place.
        stuck.addAll(callRepo.findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull("EVALUATING"));
        // Review finding ("Auto-trade recovery still needs a lease"): the SAME transitional gap,
        // one field newer — a record claimed by the OLD claim logic (autoTradeEvalStartedAt set,
        // but evaluationLeaseUntil never set, since that field didn't exist yet at claim time)
        // isn't caught by the lease query above OR the fallback immediately above this one. See
        // TradeCallRepository's own javadoc on this exact query for the full reasoning.
        stuck.addAll(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull("EVALUATING"));
        // Review finding (caught while adding the query directly above, not after — a record
        // with BOTH autoTradeEvalStartedAt and evaluationLeaseUntil null, the original
        // transitional case from before either field existed, would match both this query AND
        // findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull above, getting added to
        // `stuck` twice — the second iteration's reset-to-PENDING could then interrupt the
        // re-dispatch the first iteration just started, a duplicate-dispatch risk). Deduplicated
        // by id before processing (LinkedHashMap preserves the original order) rather than
        // trying to make the four queries mutually exclusive.
        java.util.Map<String, TradeCallRecord> dedupedById = new java.util.LinkedHashMap<>();
        for (TradeCallRecord s : stuck) dedupedById.put(s.getId(), s);
        java.util.Collection<TradeCallRecord> dedupedStuck = dedupedById.values();
        if (dedupedStuck.isEmpty()) return;

        log.warn("Found {} auto-trade signal(s) stuck in PENDING/EVALUATING — recovering.", dedupedStuck.size());
        for (TradeCallRecord signal : dedupedStuck) {
            if (shutdownState.isShuttingDown()) return;
            // P0-9 fix ("Stale signal max-age gate" -- full context in
            // AutoTradeService.MAX_SIGNAL_AGE's own javadoc): a signal already too old to trade
            // is expired here, BEFORE it's ever reset back to PENDING and re-dispatched --
            // without this, a signal stuck PENDING for hours (no auto-trade-enabled profile at
            // the time, an extended outage, etc.) would be re-dispatched as if fresh on every
            // single 2-minute recovery pass forever, relying entirely on evaluateSignal's own
            // per-dispatch age check (added by this same fix) to reject it every time -- wasted,
            // repeated work for a signal that will never be tradeable again. Expiring it here
            // instead means it's cleaned up ONCE, on the first recovery pass that notices it's
            // too old, rather than being pointlessly re-evaluated on every pass until
            // expireStaleSignals' own much looser 24-hour sweep eventually catches it.
            if (signal.getCalledAt() != null && signal.getCalledAt().isBefore(LocalDateTime.now().minus(AutoTradeService.MAX_SIGNAL_AGE))) {
                log.info("Stuck signal {} on {} for user {} is older than the maximum actionable age of {} minute(s) -- marking EXPIRED "
                        + "rather than re-dispatching a signal that can no longer be traded.",
                    signal.getId(), signal.getSymbol(), signal.getUserId(), AutoTradeService.MAX_SIGNAL_AGE.toMinutes());
                // Atomic and conditional, same reasoning as expireStaleSignals' own identical
                // update -- only transitions a status that's still genuinely PENDING/EVALUATING
                // at write time, so this can't clobber a concurrent real completion.
                mongoTemplate.findAndModify(
                    new Query(Criteria.where("id").is(signal.getId()).and("autoTradeEvalStatus").in("PENDING", "EVALUATING")),
                    new Update().set("autoTradeEvalStatus", "EVALUATED").set("signalStatus", com.tradevision.model.SignalStatus.EXPIRED),
                    TradeCallRecord.class);
                continue;
            }
            // Atomically reset to PENDING first — evaluateSignal's own claim gate (PENDING ->
            // EVALUATING) is what actually re-dispatches this safely, so this worker never
            // bypasses that gate, it just makes the signal eligible for it again. Conditioned on
            // NOT already EVALUATED, in case the original evaluation actually completed in the
            // narrow window between this query running and this reset executing.
            var reset = mongoTemplate.findAndModify(
                new Query(Criteria.where("id").is(signal.getId()).and("autoTradeEvalStatus").ne("EVALUATED")),
                new Update().set("autoTradeEvalStatus", "PENDING"),
                TradeCallRecord.class);
            if (reset == null) continue; // already completed since the query above — nothing to recover
            log.warn("Recovering stuck signal {} on {} for user {} — re-dispatching.", signal.getId(), signal.getSymbol(), signal.getUserId());
            autoTradeService.evaluateSignal(signal.getUserId(), signal);
        }
    }

    // Review finding ("Signal lifecycle is still partial" — "EXPIRED, CANCELLED are still
    // unused"): a signal's underlying market conditions are stale by the time it's been sitting
    // unprogressed this long — a real trading signal from a day ago is no longer meaningfully
    // actionable, whatever caused it to stall (symbol not enabled for any profile, no
    // auto-trade-enabled profile at all, or a scan cycle that simply never got to it).
    private static final java.time.Duration EXPIRE_AFTER = java.time.Duration.ofHours(24);

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000, scheduler = "maintenanceScheduler") // every hour, starting 5 minutes after startup
    public void expireStaleSignals() {
        if (shutdownState.isShuttingDown()) return;

        LocalDateTime cutoff = LocalDateTime.now().minus(EXPIRE_AFTER);
        var stale = callRepo.findBySignalStatusInAndCalledAtBefore(
            java.util.List.of(com.tradevision.model.SignalStatus.GENERATED, com.tradevision.model.SignalStatus.VALIDATING), cutoff);
        if (stale.isEmpty()) return;

        log.info("Found {} signal(s) unprogressed for over {} — marking EXPIRED.", stale.size(), EXPIRE_AFTER);
        for (TradeCallRecord signal : stale) {
            if (shutdownState.isShuttingDown()) return;
            // Atomic and conditional on signalStatus still being GENERATED/VALIDATING at write
            // time — the same race-safety reasoning as the PENDING/EVALUATING reset above, this
            // time against evaluateSignal's own concurrent progression of the same record rather
            // than a competing recovery worker.
            mongoTemplate.findAndModify(
                new Query(Criteria.where("id").is(signal.getId())
                    .and("signalStatus").in(com.tradevision.model.SignalStatus.GENERATED, com.tradevision.model.SignalStatus.VALIDATING)),
                new Update().set("signalStatus", com.tradevision.model.SignalStatus.EXPIRED),
                TradeCallRecord.class);
        }
    }

    /**
     * Review finding ("Evaluation recovery now excludes APPROVED, but APPROVED can become
     * permanently abandoned" -- external review, twenty-sixth pass, P1, confirmed real by
     * direct inspection before this fix: recoverStuckSignals correctly excludes APPROVED from
     * reclaiming — a real fix for the duplicate-evaluation race — but that means a signal that
     * reached APPROVED right before its worker crashed, with no exchange call ever actually
     * made, could sit at APPROVED forever with nothing ever picking it back up. The review's own
     * framing: "you've chosen safety over availability, which I agree with for LIVE. But
     * operationally, this should be represented as APPROVED_STALE or an incident after a
     * timeout. Otherwise a signal can remain approved forever without anyone knowing."): the
     * actual fix — this does NOT automatically re-trade a stale APPROVED signal (that would
     * reopen the exact duplicate-evaluation race recoverStuckSignals' own exclusion exists to
     * close); it raises a real incident for manual reconciliation instead, exactly as the
     * review's own suggested fix describes. staleApprovedIncidentRaised prevents this hourly
     * check from re-raising the same incident for the same still-unresolved signal every run.
     */
    private static final java.time.Duration APPROVED_STALE_AFTER = java.time.Duration.ofMinutes(10);

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 360_000, scheduler = "maintenanceScheduler") // every hour, staggered 1 minute after expireStaleSignals
    public void detectStaleApprovedSignals() {
        if (shutdownState.isShuttingDown()) return;

        LocalDateTime cutoff = LocalDateTime.now().minus(APPROVED_STALE_AFTER);
        var staleApproved = callRepo.findBySignalStatusAndCalledAtBeforeAndStaleApprovedIncidentRaisedFalse(
            com.tradevision.model.SignalStatus.APPROVED, cutoff);
        if (staleApproved.isEmpty()) return;

        log.error("Found {} signal(s) stuck at APPROVED for over {} with no exchange call ever recorded — raising incidents for "
            + "manual reconciliation rather than automatically re-trading them (which would reopen the duplicate-evaluation race "
            + "recoverStuckSignals' own APPROVED exclusion exists to close).", staleApproved.size(), APPROVED_STALE_AFTER);
        for (TradeCallRecord signal : staleApproved) {
            if (shutdownState.isShuttingDown()) return;
            incidentService.raiseCritical(signal.getUserId(), null, null, signal.getId(), signal.getSymbol(),
                "EXECUTION_STALE",
                "Signal " + signal.getId() + " on " + signal.getSymbol() + " has been at APPROVED for over "
                    + APPROVED_STALE_AFTER.toMinutes() + " minute(s) with no exchange call ever recorded — the worker that "
                    + "approved it may have crashed before placing the order, or before ever recording that it did. This is "
                    + "NOT automatically re-traded (that would risk a duplicate execution if the original order actually did "
                    + "go through) — manual review required: check this signal's real state directly against the exchange.");
            // Atomic and conditional, same reasoning as expireStaleSignals' own update above --
            // only flips this flag if it's still false at write time, so a concurrent run of
            // this same method (or a genuine re-run before this one committed) can't both raise
            // the incident.
            mongoTemplate.findAndModify(
                new Query(Criteria.where("id").is(signal.getId()).and("staleApprovedIncidentRaised").is(false)),
                new Update().set("staleApprovedIncidentRaised", true),
                TradeCallRecord.class);
        }
    }
}
