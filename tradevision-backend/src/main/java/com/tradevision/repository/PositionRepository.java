package com.tradevision.repository;

import com.tradevision.model.Position;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PositionRepository extends MongoRepository<Position, String> {
    List<Position> findByUserIdAndCredentialIdAndStatus(String userId, String credentialId, String status);
    /**
     * Counts open positions across every credential for a user, so a destructive action
     * like emergency credential revocation can tell the user concretely how many positions
     * it's about to stop monitoring.
     */
    long countByUserIdAndStatusIn(String userId, java.util.Collection<String> statuses);
    List<Position> findByCredentialIdAndStatus(String credentialId, String status);
    // Returns every position for a credential still in any status that means "this
    // application is still actively responsible for it," not just a single status —
    // used before deactivating a broker credential, so live positions aren't abandoned.
    List<Position> findByCredentialIdAndStatusIn(String credentialId, java.util.Collection<String> statuses);
    // Efficient existence check for the same question as the lookup above — no need to
    // fetch and hold the whole position list just to know whether a deactivated credential
    // still has anything this application is responsible for monitoring.
    boolean existsByCredentialIdAndStatusIn(String credentialId, java.util.Collection<String> statuses);
    List<Position> findByStatus(String status);
    // Bounded alternative to findByStatus("CLOSED") above for metrics services that only
    // need a lookback window, rather than fetching every closed position ever and filtering
    // in memory.
    List<Position> findByStatusAndClosedAtAfter(String status, java.time.LocalDateTime cutoff);
    List<Position> findByOpenedAtAfter(java.time.LocalDateTime cutoff);
    java.util.Optional<Position> findByEntryOrderId(String entryOrderId);
    // The scoped lookup matching the compound unique index on (credentialId, symbol,
    // entryOrderId) — entryOrderId alone is only unique per credential and symbol.
    // findByEntryOrderId above is kept only for callers that genuinely cannot supply
    // credentialId/symbol; this one ensures a same-numbered order id from a different
    // credential or symbol can never be matched in.
    java.util.Optional<Position> findByCredentialIdAndSymbolAndEntryOrderId(String credentialId, String symbol, String entryOrderId);
    // Backs the positions dashboard listing — every position for a credential regardless
    // of status, most recent first.
    List<Position> findByUserIdAndCredentialIdOrderByOpenedAtDesc(String userId, String credentialId);
    // Paginated variant of the listing above. With a Pageable parameter, Spring Data takes
    // the sort from the Pageable itself (the caller passes PageRequest.of(page, size,
    // Sort...)) rather than from the method name. Kept alongside the unpaginated method
    // since other call sites still depend on that one's return shape.
    List<Position> findByUserIdAndCredentialId(String userId, String credentialId, org.springframework.data.domain.Pageable pageable);
    // Backs realized P&L over a window for PortfolioRiskService — closedAt, not openedAt,
    // is the right field: a position opened last month but closed today belongs in today's
    // window.
    List<Position> findByCredentialIdAndClosedAtAfter(String credentialId, java.time.LocalDateTime cutoff);
    // Finds the OPEN position for a specific symbol under a specific plan, so a reversal
    // signal only closes the position that plan itself opened, never a different plan's
    // own position on the same symbol.
    List<Position> findByPlanIdAndSymbolAndStatus(String planId, String symbol, String status);
    // Efficient existence check (no need to fetch the full position list) for whether a
    // plan still has anything open that depends on it for exit-rule management — blocks
    // deleting a plan with open positions.
    boolean existsByPlanIdAndStatus(String planId, String status);
}
