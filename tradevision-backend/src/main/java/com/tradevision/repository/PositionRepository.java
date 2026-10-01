package com.tradevision.repository;

import com.tradevision.model.Position;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PositionRepository extends MongoRepository<Position, String> {
    List<Position> findByUserIdAndCredentialIdAndStatus(String userId, String credentialId, String status);
    /**
     * Review finding ("Emergency credential revocation can intentionally disable the very
     * monitoring needed by existing positions" -- external review, twenty-fourth pass, P1, full
     * context in RiskProfileService.emergencyRevokeAll's own updated javadoc): needed so this
     * genuinely destructive action can tell the user, concretely, how many positions it's about
     * to stop monitoring -- across every credential for this user, not just one.
     */
    long countByUserIdAndStatusIn(String userId, java.util.Collection<String> statuses);
    List<Position> findByCredentialIdAndStatus(String credentialId, String status);
    // Review finding ("Deactivating a broker credential can abandon live positions" -- external
    // review, twenty-first pass, P0, full context in BrokerCredentialService.delete's own
    // updated javadoc): the exact lookup that fix needs -- every position for this credential
    // still in any status that means "this application is still actively responsible for it",
    // not just a single status.
    List<Position> findByCredentialIdAndStatusIn(String credentialId, java.util.Collection<String> statuses);
    // P1-17 fix ("Emergency revoke / credential deactivation stops all monitoring of live
    // positions" -- full context in PositionMonitorService.doReconcile's own updated comment):
    // an efficient existence check for the same question -- no need to fetch and hold the whole
    // position list just to know whether a deactivated credential still has anything this
    // application is responsible for monitoring.
    boolean existsByCredentialIdAndStatusIn(String credentialId, java.util.Collection<String> statuses);
    List<Position> findByStatus(String status);
    // Review finding (unbounded metrics queries -- P2, full context in OrderRepository's own
    // identical addition): LatencyMetricsService used to call findByStatus("CLOSED") above (all
    // closed positions ever, across every user) and findAll() (literally every position in the
    // entire database) and filter down to a lookback window in memory. Both bounded at the
    // database level instead.
    List<Position> findByStatusAndClosedAtAfter(String status, java.time.LocalDateTime cutoff);
    List<Position> findByOpenedAtAfter(java.time.LocalDateTime cutoff);
    java.util.Optional<Position> findByEntryOrderId(String entryOrderId);
    // P0-5 fix ("Global unique indexes on exchange order IDs collide across
    // symbols/credentials/testnet" -- full context in Position's own @CompoundIndex javadoc):
    // the real, scoped lookup matching the new compound unique index -- entryOrderId alone is
    // only unique per (credentialId, symbol) in the real world. findByEntryOrderId above is kept
    // only for any caller that genuinely cannot supply credentialId/symbol; new and migrated call
    // sites use this one so a same-numbered order id from a DIFFERENT credential or symbol can
    // never be matched in.
    java.util.Optional<Position> findByCredentialIdAndSymbolAndEntryOrderId(String credentialId, String symbol, String entryOrderId);
    // Review finding ("no PositionController / position API"): backs the new dashboard listing —
    // every position for a credential regardless of status, most recent first.
    List<Position> findByUserIdAndCredentialIdOrderByOpenedAtDesc(String userId, String credentialId);
    // Review finding ("Pagination for order history/positions/metrics" -- P2, full context in
    // PositionDashboardService.listPositions's own updated javadoc): the bounded query this
    // endpoint now uses -- with a Pageable parameter, Spring Data takes the sort from the
    // Pageable itself (the caller must pass PageRequest.of(page, size, Sort...)), not from the
    // method name the way the unbounded version above does. Kept alongside the old method
    // rather than replacing it, since removing it could break something outside this specific
    // dashboard call site that this session hasn't audited.
    List<Position> findByUserIdAndCredentialId(String userId, String credentialId, org.springframework.data.domain.Pageable pageable);
    // Review finding ("#7 — Unified Portfolio Risk", agreed sequencing 4 -> 6 -> 5 -> 9 -> 7):
    // backs realized P&L over a window, for PortfolioRiskService — closedAt, not openedAt, is
    // the right field: a position opened last month but closed today belongs in TODAY's window.
    List<Position> findByCredentialIdAndClosedAtAfter(String credentialId, java.time.LocalDateTime cutoff);
    // User's own explicit multi-strategy-plan design ("15m strategy changes strongly bearish? ->
    // EXIT"), full context in AutonomousScannerService.checkSignalReversalExit's own javadoc:
    // the exact lookup that method needs -- an OPEN position for this specific symbol under
    // this specific plan, so a reversal signal only closes the position that plan itself opened,
    // never a different plan's own position on the same symbol.
    List<Position> findByPlanIdAndSymbolAndStatus(String planId, String symbol, String status);
    // User's own explicit design ("Deleting a plan with open positions is still dangerous...
    // DELETE plan + open positions -> REJECT"), full context in StrategyPlanService.delete's own
    // updated javadoc: an efficient existence check (no need to fetch the full position list)
    // for whether this plan still has anything open that depends on it for exit-rule management.
    boolean existsByPlanIdAndStatus(String planId, String status);
}
