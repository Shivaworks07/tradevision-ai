package com.tradevision.service;

import com.tradevision.dto.PositionSummaryDto;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.Order;
import com.tradevision.model.Position;
import com.tradevision.repository.PositionRepository;
import com.tradevision.service.broker.BrokerAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backend support for the position/execution dashboard, including user-facing emergency
 * actions. This is deliberately the only place outside PositionMonitorService/
 * PositionSafetyService themselves that touches position state directly — PositionController
 * stays thin and delegates entirely here, the same pattern used by every other
 * controller/service pair in this codebase. Package-private helpers on BrokerCredentialService
 * (ownedCredential, decrypt, adapterForCredential) are reachable from here because this class
 * lives in the same package; those helpers are intentionally not public so that ownership
 * checks and the LIVE-mode ceremony can't be bypassed by a new controller calling them
 * directly.
 *
 * "Cancel protection" is deliberately not exposed as a standalone action: manually cancelling
 * an OCO without immediately replacing it would leave a real position naked by direct user
 * action, which is exactly the state every other part of this codebase exists to prevent
 * automatically. Emergency Flatten (close the position outright) and Reconcile Now (re-sync
 * against the exchange) are the two actions exposed because both have a well-defined, safe
 * outcome; a bare "remove protection and leave it" does not.
 */
@Service
@RequiredArgsConstructor
public class PositionDashboardService {

    // Used to log non-fatal warnings when a per-symbol rules lookup fails during dashboard assembly.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PositionDashboardService.class);

    private final PositionRepository positionRepo;
    // Repository for the unified Order (OMS) model — the source of entry-order details
    // (stop-loss/take-profit prices) shown on the dashboard.
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final BrokerCredentialService credentialService;
    private final PositionSafetyService positionSafetyService;
    private final PositionMonitorService positionMonitorService;
    private final com.tradevision.repository.TradingIncidentRepository incidentRepo;

    /**
     * Returns one page of positions for a credential, most recently opened first. Pagination
     * bounds the response size for credentials that have been auto-trading for a long time and
     * may have accumulated thousands of closed positions; the sort order is explicitly passed
     * via the Pageable's Sort rather than relied on from the query method's name.
     *
     * Trade-off: statusFilter is applied after pagination, to this page's own results, not
     * pushed into the database query itself. As a result, filtering by a specific status on a
     * page dominated by other statuses can return noticeably fewer than `size` results. A
     * caller that needs a complete, filtered view across all positions (not just the most
     * recent page) should page through and filter client-side, or a dedicated filtered query
     * should be added if that need becomes common.
     */
    public List<PositionSummaryDto> listPositions(String userId, String credentialId, String statusFilter, int page, int size) {
        BrokerCredential credential = credentialService.ownedCredential(userId, credentialId); // ownership check — throws if not this user's credential
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "openedAt"));
        List<Position> positions = positionRepo.findByUserIdAndCredentialId(userId, credentialId, pageable);
        List<Position> filtered = positions.stream()
            .filter(p -> statusFilter == null || statusFilter.isBlank() || statusFilter.equalsIgnoreCase("ALL") || statusFilter.equalsIgnoreCase(p.getStatus()))
            .toList();

        // Entry orders are fetched in one batch query for all positions on this page rather
        // than one query per position, then looked up from an in-memory map in toSummary().
        List<String> entryOrderIds = filtered.stream().map(Position::getEntryOrderId).filter(java.util.Objects::nonNull).distinct().toList();
        // The lookup is scoped by credentialId (every position in `filtered` already comes from
        // this single credential) because broker order IDs are only unique within a credential,
        // not globally — the same numeric order ID can exist on different credentials or
        // testnet/live modes. Positions can still span multiple symbols within one credential,
        // so the map below is keyed by symbol+brokerOrderId rather than brokerOrderId alone,
        // and toSummary looks entries up the same way.
        Map<String, Order> entryOrdersById = entryOrderIds.isEmpty() ? Map.of()
            : orderRepo.findByCredentialIdAndBrokerOrderIdIn(credentialId, entryOrderIds).stream()
                .collect(java.util.stream.Collectors.toMap(o -> o.getSymbol() + "|" + o.getBrokerOrderId(), o -> o, (a, b) -> a));

        // Symbol rules (used for dust classification) are fetched once per unique symbol among
        // this page's OPEN positions, since multiple positions commonly share a symbol. Closed
        // positions never need this and are excluded from the batch. Each fetch is individually
        // wrapped so one bad or slow symbol can't take down the rest of this batch or the whole
        // dashboard response.
        var adapter = credentialService.adapterForCredential(credential);
        Map<String, com.tradevision.service.broker.dto.SymbolRules> rulesBySymbol = new java.util.HashMap<>();
        for (String symbol : filtered.stream().filter(p -> "OPEN".equals(p.getStatus())).map(Position::getSymbol).distinct().toList()) {
            try {
                rulesBySymbol.put(symbol, adapter.getSymbolRules(symbol, credential.getMode()));
            } catch (Exception e) {
                log.warn("Could not fetch symbol rules for {} while classifying dashboard protection status (non-fatal, this symbol's "
                    + "own positions fall back to the safer PARTIAL classification rather than DUST): {}", symbol, e.getMessage());
            }
        }

        return filtered.stream().map(p -> toSummary(p, entryOrdersById, rulesBySymbol)).toList();
    }

    /**
     * Classifies how well an open position's quantity is covered by its OCO protection order:
     * FULL (fully covered), DUST_RESIDUAL (a gap exists but is genuinely below the exchange's
     * minQty, so it's dust rather than meaningful naked exposure), PARTIAL (a gap at or above
     * minQty — a real, meaningful residual that the automated safety path separately escalates
     * and flattens, though this read-only dashboard path can observe the position mid-flight
     * before that escalation completes), UNPROTECTED (no OCO at all), or N/A (not an open
     * position, so protection status doesn't apply). When minQty couldn't be fetched for this
     * symbol, an unexplained gap is classified as PARTIAL rather than DUST_RESIDUAL — without a
     * confirmed minQty, the more serious classification is the safer default.
     */
    private String classifyProtection(Position p, com.tradevision.service.broker.dto.SymbolRules rules) {
        if (!"OPEN".equals(p.getStatus())) return "N/A";
        if (p.getOcoOrderListId() == null || p.getProtectedQuantity() == null) return "UNPROTECTED";
        BigDecimal residual = p.getQuantity().subtract(p.getProtectedQuantity());
        if (residual.signum() <= 0) return "FULL";
        if (rules != null && rules.minQty() != null && residual.compareTo(rules.minQty()) < 0) return "DUST_RESIDUAL";
        return "PARTIAL";
    }

    private PositionSummaryDto toSummary(Position p, Map<String, Order> entryOrdersById,
                                          Map<String, com.tradevision.service.broker.dto.SymbolRules> rulesBySymbol) {
        // Map key is symbol+brokerOrderId, matching how the batch above is keyed.
        Optional<Order> entryOrder = p.getEntryOrderId() != null
            ? Optional.ofNullable(entryOrdersById.get(p.getSymbol() + "|" + p.getEntryOrderId())) : Optional.empty();

        return new PositionSummaryDto(
            p.getId(),
            p.getCredentialId(),
            p.getSymbol(),
            p.getMode() != null ? p.getMode().name() : null,
            p.getStatus(),
            p.getQuantity(),
            p.getClosedQuantity(),
            p.getAvgEntryPrice(),
            p.isAvgEntryPriceUnverified(),
            entryOrder.map(Order::getStopLossTriggerPrice).orElse(null),
            entryOrder.map(Order::getTakeProfitPrice).orElse(null),
            // "Protected" requires protectedQuantity to be present and to actually cover the
            // position's real quantity, not just the presence of an OCO order ID — a position
            // can be only partially protected after exchange step-size rounding, and that case
            // must not be reported as fully protected.
            p.getOcoOrderListId() != null && "OPEN".equals(p.getStatus())
                && p.getProtectedQuantity() != null && p.getProtectedQuantity().compareTo(p.getQuantity()) >= 0,
            p.getProtectedQuantity(),
            classifyProtection(p, rulesBySymbol.get(p.getSymbol())),
            p.getExitPrice(),
            p.getRealizedPnlQuote(),
            p.getEntryFeeQuote(),
            p.getExitFeeQuote(),
            p.getCloseReason(),
            p.getTriggerSource(),
            p.getOpenedAt(),
            p.getClosedAt()
        );
    }

    /**
     * User-initiated emergency flatten on one specific position. Same PositionSafetyService the
     * automated safety net uses — a manual trigger of the real thing, not a separate parallel
     * "manual close" code path with its own rules.
     */
    public void emergencyFlattenPosition(String userId, String positionId) {
        Position position = positionRepo.findById(positionId)
            .orElseThrow(() -> new IllegalArgumentException("Position not found."));
        if (!userId.equals(position.getUserId())) {
            throw new IllegalArgumentException("Position not found."); // same message as "not found" — don't reveal existence of another user's position
        }
        if (!"OPEN".equals(position.getStatus())) {
            throw new IllegalStateException("This position is not open — nothing to flatten.");
        }

        BrokerCredential credential = credentialService.ownedCredential(userId, position.getCredentialId());
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        credentialService.audit(userId, credential.getId(), credential.getBroker(), "MANUAL_EMERGENCY_FLATTEN_REQUESTED",
            "User manually requested emergency flatten for position " + positionId + " on " + position.getSymbol() + ".");
        positionSafetyService.emergencyFlatten(credential, adapter, apiKey, apiSecret, position, "Manually requested by user.");
    }

    /** User-initiated reconciliation — the same reconcileCredential the scheduler and WebSocket listener already trigger, just on demand. */
    public void reconcileNow(String userId, String credentialId) {
        BrokerCredential credential = credentialService.ownedCredential(userId, credentialId);
        credentialService.audit(userId, credentialId, credential.getBroker(), "MANUAL_RECONCILE_REQUESTED",
            "User manually requested reconciliation.");
        positionMonitorService.reconcileCredential(credential);
    }

    /** Returns unresolved trading incidents for a credential, backing the dashboard's "critical" indicator. */
    public java.util.List<com.tradevision.model.TradingIncident> unresolvedIncidents(String userId, String credentialId) {
        credentialService.ownedCredential(userId, credentialId); // ownership check
        return incidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc(credentialId);
    }
}
