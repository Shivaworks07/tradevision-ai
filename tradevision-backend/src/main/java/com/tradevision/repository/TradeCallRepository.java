package com.tradevision.repository;

import com.tradevision.model.TradeCallRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface TradeCallRepository extends MongoRepository<TradeCallRecord, String> {
    List<TradeCallRecord> findByUserIdAndSymbolOrderByCalledAtDesc(String userId, String symbol, Pageable p);
    List<TradeCallRecord> findByUserIdOrderByCalledAtDesc(String userId, Pageable p);
    /**
     * Returns a user's trade calls as a true Page (with total count and hasNext), so
     * callers can paginate through the complete history rather than being limited to
     * whatever a single fixed-size fetch returns. Kept as a separate method from
     * findByUserIdOrderByCalledAtDesc, which returns List<T> and already has its own
     * callers that depend on that return type.
     */
    org.springframework.data.domain.Page<TradeCallRecord> findAllByUserIdOrderByCalledAtDesc(String userId, Pageable p);
    // For auto-updater - find pending calls across all users
    List<TradeCallRecord> findByOutcome_ResultOrderByCalledAtDesc(String result, Pageable p);
    /**
     * Backs CallResultUpdater's periodic sweep, ordered oldest-first so a backlog of
     * pending calls actually drains over successive runs: each pass resolves (or expires)
     * the calls that have been waiting longest, freeing room for the next-oldest ones,
     * instead of repeatedly re-fetching only the most recent page.
     */
    List<TradeCallRecord> findByOutcome_ResultOrderByCalledAtAsc(String result, Pageable p);
    // ── Admin analytics ───────────────────────────────────────
    long countByCalledAtAfter(java.time.LocalDateTime since);
    long countByOutcome_ResultStartingWith(String prefix);
    List<TradeCallRecord> findByCalledAtAfterOrderByCalledAtDesc(java.time.LocalDateTime since, Pageable p);

    // Dataset export: resolved calls only — an unresolved call has no label.
    List<TradeCallRecord> findByOutcome_ResultNotOrderByCalledAtDesc(String result, Pageable p);

    // Backs AutoTradeRecoveryService's periodic sweep for signals stuck in PENDING (never
    // claimed at all — calledAt is the correct signal here, since a PENDING record never had
    // an evaluation attempt begin).
    /**
     * Finds PENDING auto-trade signals older than the cutoff that are still eligible for
     * re-dispatch. A signal the user explicitly cancelled, or one that genuinely expired,
     * is excluded here rather than left to the downstream atomic claim in AutoTradeService
     * to reject — avoiding wasted work re-dispatching something that would immediately
     * fail to claim.
     */
    List<TradeCallRecord> findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(
        String status, java.time.LocalDateTime cutoff, java.util.Collection<com.tradevision.model.SignalStatus> excludedSignalStatuses);

    // The EVALUATING case needs a different staleness signal than PENDING — calledAt (when
    // the signal was created) doesn't measure how long the current evaluation attempt has
    // been running; autoTradeEvalStartedAt (set atomically the moment the claim succeeded)
    // does. A signal that sat queued for minutes before being claimed, then evaluated quickly,
    // would otherwise look stuck by calledAt alone.
    //
    // Reclaiming by a real lease expiry rather than a fixed elapsed-time window avoids
    // stealing a signal from a worker that is simply slow but still alive — it only reclaims
    // once the granted lease has actually expired.
    List<TradeCallRecord> findByAutoTradeEvalStatusAndEvaluationLeaseUntilBefore(String status, java.time.LocalDateTime cutoff);
    /**
     * Finds EVALUATING signals whose lease has expired, excluding any signal whose
     * signalStatus shows it has already crossed the point of no return. A signal at
     * ORDER_PENDING or EXECUTED must never be reset back to PENDING and handed to a second
     * worker, however expired its evaluation lease looks, since autoTradeEvalStatus stays
     * "EVALUATING" for the entire duration of evaluateSignal — including while the exchange
     * call to place the order is still in flight.
     */
    List<TradeCallRecord> findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn(
        String status, java.time.LocalDateTime cutoff, java.util.Collection<com.tradevision.model.SignalStatus> excludedSignalStatuses);
    // Catches a record claimed before evaluationLeaseUntil existed as a field: it has
    // autoTradeEvalStartedAt set but evaluationLeaseUntil null. Mongo's $lt against a missing
    // field never matches, so the lease query above can't catch it, and
    // AutoTradeEvalStartedAtIsNull doesn't either since that field is set — this query is the
    // only path that reclaims it.
    List<TradeCallRecord> findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull(String status);
    // Fallback for a record whose autoTradeEvalStartedAt was never set (only possible for a
    // record stuck in EVALUATING from before this field existed), since Mongo's $lt against a
    // null/missing field doesn't match the way a real timestamp comparison would.
    List<TradeCallRecord> findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull(String status);

    // Backs the EXPIRED sweep — a signal sitting unprogressed (GENERATED/VALIDATING) for
    // longer than a reasonable window is stale, not still meaningfully "in progress".
    List<TradeCallRecord> findBySignalStatusInAndCalledAtBefore(List<com.tradevision.model.SignalStatus> statuses, java.time.LocalDateTime cutoff);
    /**
     * Finds APPROVED signals sitting unprogressed past the timeout that haven't already had
     * an incident raised for them, so the periodic check doesn't re-raise for the same signal
     * on every run.
     */
    List<TradeCallRecord> findBySignalStatusAndCalledAtBeforeAndStaleApprovedIncidentRaisedFalse(
        com.tradevision.model.SignalStatus signalStatus, java.time.LocalDateTime cutoff);

    /**
     * Durable backstop for the signal-cooldown dedup check. The in-memory lastSignalAt map
     * is the fast common-case path while the process runs continuously, but a restart wipes
     * it; without this a signal emitted just before a restart could be re-emitted immediately
     * after, defeating the configured cooldown. Every emitted signal is already a persisted
     * TradeCallRecord with a real calledAt timestamp, so no separate collection is needed.
     * Scoped to userId+planId+symbol since planId already uniquely determines its owning
     * credential.
     */
    java.util.Optional<TradeCallRecord> findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc(
        String userId, String planId, String symbol);
}

// ── Admin analytics queries ───────────────────────────────
