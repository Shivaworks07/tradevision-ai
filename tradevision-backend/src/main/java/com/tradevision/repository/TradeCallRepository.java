package com.tradevision.repository;

import com.tradevision.model.TradeCallRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface TradeCallRepository extends MongoRepository<TradeCallRecord, String> {
    List<TradeCallRecord> findByUserIdAndSymbolOrderByCalledAtDesc(String userId, String symbol, Pageable p);
    List<TradeCallRecord> findByUserIdOrderByCalledAtDesc(String userId, Pageable p);
    /**
     * Review finding ("Some analytics are deliberately bounded rather than truly paginated" --
     * external review, thirty-sixth pass, P2, confirmed real by direct inspection before this
     * fix: exportCSV always fetched exactly PageRequest.of(0, 1000) -- a user with more than
     * 1000 resolved trade calls had no way to reach the older ones at all, and no way to even
     * know they were being silently excluded): the actual query real pagination needs --
     * `findAllBy` (a valid Spring Data synonym for `findBy`) returning Page<T> instead of
     * List<T>, since Page carries getTotalElements()/hasNext() the plain List-returning method
     * above doesn't. Deliberately a NEW method, not a change to the existing one above: several
     * other callers already depend on that method's own List<T> return type, and Java can't
     * overload by return type alone.
     */
    org.springframework.data.domain.Page<TradeCallRecord> findAllByUserIdOrderByCalledAtDesc(String userId, Pageable p);
    // For auto-updater - find pending calls across all users
    List<TradeCallRecord> findByOutcome_ResultOrderByCalledAtDesc(String result, Pageable p);
    /**
     * P2-19 fix ("CallResultUpdater: ... newest 50 only" -- external review, confirmed real):
     * CallResultUpdater.updatePendingCalls used the DESC-ordered method above with a fixed
     * PageRequest.of(0, 50) -- if more than 50 calls were ever PENDING at once, the OLDEST ones
     * (the ones closest to the 30-day expiry that same method enforces, and the ones a user is
     * most likely to actually be checking on) were permanently starved: every 5-minute run kept
     * re-fetching only the 50 NEWEST pending calls, so a backlog beyond 50 never drained -- newer
     * PENDING calls keep pushing the same older ones back out of that top-50 window forever.
     * Ordering ASCENDING instead means each run processes the OLDEST pending calls first, so the
     * backlog actually drains over successive runs: any call that resolves (or ages past 30 days
     * into EXPIRED) frees a slot for the next-oldest one, rather than never being reached at all.
     */
    List<TradeCallRecord> findByOutcome_ResultOrderByCalledAtAsc(String result, Pageable p);
    // ── Admin analytics ───────────────────────────────────────
    long countByCalledAtAfter(java.time.LocalDateTime since);
    long countByOutcome_ResultStartingWith(String prefix);
    List<TradeCallRecord> findByCalledAtAfterOrderByCalledAtDesc(java.time.LocalDateTime since, Pageable p);

    // Review item #17 (dataset export): resolved calls only — an unresolved call has no label.
    List<TradeCallRecord> findByOutcome_ResultNotOrderByCalledAtDesc(String result, Pageable p);

    // Review finding (P1 #5 — "Auto-trading is not durable"): backs AutoTradeRecoveryService's
    // periodic sweep for signals stuck in PENDING (never claimed at all — calledAt is the
    // correct signal here, since a PENDING record never had an evaluation attempt begin).
    /**
     * Review finding ("CANCELLED / EXPIRED signals can still be recovered and traded" --
     * external review, seventeenth pass, P0, full context in AutoTradeService's own atomic-claim
     * comment): the PENDING-recovery counterpart to that same fix -- a signal the user
     * explicitly cancelled, or one that genuinely expired, must never even be considered
     * "stuck and worth re-dispatching" in the first place. The downstream atomic claim in
     * AutoTradeService already refuses these regardless (defense in depth, not the only
     * safeguard), but querying for them here at all is wasted work re-dispatching something
     * that will immediately fail to claim. Supersedes the plain findByAutoTradeEvalStatus
     * AndCalledAtBefore this replaced -- that older method had no callers left once this one
     * existed, so it was removed rather than left as dead code.
     */
    List<TradeCallRecord> findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(
        String status, java.time.LocalDateTime cutoff, java.util.Collection<com.tradevision.model.SignalStatus> excludedSignalStatuses);

    // Review finding (P1 — "Auto-trade recovery has a duplicate-evaluation edge"): the
    // EVALUATING case needs a DIFFERENT staleness signal than PENDING — calledAt (when the
    // signal was created) is the wrong measure for "how long has THIS evaluation attempt been
    // running"; autoTradeEvalStartedAt (set atomically the moment the claim succeeded) is the
    // correct one. Using calledAt here was the exact bug: a signal that sat queued for 8 minutes
    // before being claimed, then legitimately evaluating for only 2 more, would incorrectly look
    // stuck by calledAt alone.
    // Review finding ("Auto-trade recovery still needs a lease"): supersedes
    // findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtBefore for the EVALUATING reclaim
    // decision specifically — a real lease expiry, not a fixed elapsed-time assumption. That
    // older method's javadoc explains exactly why calledAt alone was wrong for EVALUATING; this
    // is the next correction in the same direction: even autoTradeEvalStartedAt + a fixed
    // duration can steal a signal from a worker that's simply slow but genuinely still alive —
    // this only reclaims once the granted lease has actually expired.
    List<TradeCallRecord> findByAutoTradeEvalStatusAndEvaluationLeaseUntilBefore(String status, java.time.LocalDateTime cutoff);
    /**
     * Review finding ("Recovery can still race a slow evaluator around the exchange boundary" --
     * external review, fifteenth pass, P0, confirmed real by direct inspection before any fix
     * was attempted: autoTradeEvalStatus stays "EVALUATING" for the ENTIRE duration of
     * evaluateSignal, including while adapter.placeOrder() is actually in flight and
     * signalStatus has already advanced past ORDER_PENDING -- meaning the lease-expiry query
     * above could match and reset a signal that's already mid-exchange-call, not merely stuck):
     * the actual fix -- excludes any signal whose signalStatus shows it has already crossed the
     * point of no return. A signal at ORDER_PENDING or EXECUTED must never be reset back to
     * PENDING and handed to a second worker, however expired its evaluation lease looks; the
     * original worker reaching the exchange (or having already reached it) is the one truth that
     * matters here, and this codebase has no way to safely un-know that a real order may already
     * be in flight.
     */
    List<TradeCallRecord> findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn(
        String status, java.time.LocalDateTime cutoff, java.util.Collection<com.tradevision.model.SignalStatus> excludedSignalStatuses);
    // Review finding ("Auto-trade recovery still needs a lease" — the same transitional-gap
    // pattern findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull already exists for):
    // a record claimed by the OLD claim logic (before evaluationLeaseUntil existed) would have
    // autoTradeEvalStartedAt set but evaluationLeaseUntil null — caught by NEITHER the lease
    // query above (Mongo's $lt against a missing field doesn't match) NOR the
    // AutoTradeEvalStartedAtIsNull fallback (that field IS set for this record) — stuck in
    // EVALUATING forever without this.
    List<TradeCallRecord> findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull(String status);
    // Review finding ("Auto-trade recovery has a duplicate-evaluation edge"): backs the
    // fallback for a record whose autoTradeEvalStartedAt was never set — a transitional case
    // (only possible for a record stuck in EVALUATING from before this field existed) that a
    // date-comparison query can't reliably catch, since Mongo's $lt against a null/missing field
    // doesn't match the way a real timestamp comparison would.
    List<TradeCallRecord> findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull(String status);

    // Review finding ("Signal lifecycle is still partial" — "EXPIRED, CANCELLED are still
    // unused"): backs the EXPIRED sweep — a signal sitting unprogressed (GENERATED/VALIDATING)
    // for longer than a reasonable window is stale, not still meaningfully "in progress".
    List<TradeCallRecord> findBySignalStatusInAndCalledAtBefore(List<com.tradevision.model.SignalStatus> statuses, java.time.LocalDateTime cutoff);
    /**
     * Review finding ("Evaluation recovery now excludes APPROVED, but APPROVED can become
     * permanently abandoned" -- external review, twenty-sixth pass, P1, full context in
     * AutoTradeRecoveryService.detectStaleApprovedSignals's own javadoc): the actual query --
     * APPROVED signals sitting unprogressed past the timeout, that haven't already had an
     * incident raised for them (so the hourly check doesn't re-raise for the same signal every
     * run).
     */
    List<TradeCallRecord> findBySignalStatusAndCalledAtBeforeAndStaleApprovedIncidentRaisedFalse(
        com.tradevision.model.SignalStatus signalStatus, java.time.LocalDateTime cutoff);

    /**
     * P3-3 fix ("AutonomousScannerService.lastSignalAt -- in-memory cooldown lost on restart --
     * persist" -- external review): the durable backstop for the signal-cooldown dedup check.
     * lastSignalAt itself stays an in-memory ConcurrentHashMap (the fast, common-case path — no
     * DB round trip needed while this process has been running continuously), but a restart
     * wipes it, and without this a signal emitted at 10:59 followed by a restart at 11:00 would
     * let the very next scan re-emit an identical signal at 11:01, defeating the plan's own
     * configured cooldown entirely. Every emitted signal is already a persisted TradeCallRecord
     * with a real calledAt timestamp (tradeCallService.saveCall, right where lastSignalAt itself
     * is populated) -- no new collection needed, matching the ScannedCandle precedent of reusing
     * an already-durable record as the cross-restart source of truth rather than inventing a new
     * one. Scoped to userId+planId+symbol: planId already uniquely determines its owning
     * credential (a plan belongs to exactly one credential), so this is the same dedup identity
     * as lastSignalAt's own key, minus credentialId, which planId already implies.
     */
    java.util.Optional<TradeCallRecord> findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc(
        String userId, String planId, String symbol);
}

// ── Admin analytics queries ───────────────────────────────
