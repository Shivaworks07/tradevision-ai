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
 * Review finding ("UI has no real Position/Execution dashboard" / "No user-facing emergency
 * position action"): the backend side of both. This is deliberately the only place outside
 * PositionMonitorService/PositionSafetyService themselves that touches position state directly —
 * PositionController stays thin and delegates entirely here, same pattern as every other
 * controller/service pair in this codebase. Package-private helpers on BrokerCredentialService
 * (ownedCredential, decrypt, adapterForCredential) are reachable from here because this lives in
 * the same package — that's deliberate, not incidental: those helpers are intentionally NOT
 * public, so ownership checks and the LIVE-mode ceremony can't be bypassed by a new controller
 * calling them directly.
 *
 * Honest scope: "Cancel protection" (listed in the review's own mockup) is deliberately NOT
 * exposed as a standalone action. Manually cancelling an OCO without immediately replacing it
 * would leave a real position naked by direct user action — exactly the state every other part
 * of this codebase exists to prevent automatically. Emergency Flatten (close the position
 * outright) and Reconcile Now (re-sync against the exchange) are the two actions exposed because
 * both have a well-defined, safe outcome; a bare "remove protection and leave it" does not.
 */
@Service
@RequiredArgsConstructor
public class PositionDashboardService {

    // Review finding ("OCO protection logic is better, but dust classification needs one more
    // invariant" -- external review, second pass): needed now that listPositions's own new
    // symbol-rules batch fetch logs a non-fatal warning per failed lookup -- this class had no
    // logger declared at all before this fix.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PositionDashboardService.class);

    private final PositionRepository positionRepo;
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in this class's
    // own updated toSummary comment): migrated from ExecutedOrderRepository to the real Order
    // (OMS) repository -- same field name, since this file has no other "orderRepo"-named
    // dependency to collide with.
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final BrokerCredentialService credentialService;
    private final PositionSafetyService positionSafetyService;
    private final PositionMonitorService positionMonitorService;
    private final com.tradevision.repository.TradingIncidentRepository incidentRepo;

    /**
     * Review finding ("Pagination for order history/positions/metrics" -- P2): confirmed real
     * -- this used to query positionRepo.findByUserIdAndCredentialIdOrderByOpenedAtDesc, which
     * returns EVERY position ever opened for this credential in one response. A credential open
     * for months, actively auto-trading, could eventually return thousands of closed positions
     * on a single request -- a real, growing cost with no bound. Now paginated via
     * PageRequest, with the same OpenedAt-descending sort as before (most recent first),
     * explicitly passed via the Pageable's own Sort rather than relied on from the query
     * method's name.
     *
     * HONEST TRADE-OFF, stated plainly: statusFilter is still applied AFTER pagination (to this
     * page's own results), not before -- pushing the filter into the database query itself
     * would need a different repository method per filter combination, more scope than this
     * fix's own bounded goal (capping response size). The practical effect: filtering by a
     * specific status on a page dominated by other statuses can return noticeably fewer than
     * `size` results, unlike the old unbounded version, which always filtered the complete set.
     * A caller that needs a complete, filtered view across all positions (not just the most
     * recent page) isn't fully served by this endpoint as changed -- flagged here rather than
     * left as a silent, undocumented behavior change.
     */
    public List<PositionSummaryDto> listPositions(String userId, String credentialId, String statusFilter, int page, int size) {
        BrokerCredential credential = credentialService.ownedCredential(userId, credentialId); // ownership check — throws if not this user's credential
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "openedAt"));
        List<Position> positions = positionRepo.findByUserIdAndCredentialId(userId, credentialId, pageable);
        List<Position> filtered = positions.stream()
            .filter(p -> statusFilter == null || statusFilter.isBlank() || statusFilter.equalsIgnoreCase("ALL") || statusFilter.equalsIgnoreCase(p.getStatus()))
            .toList();

        // Review finding (P1 #17 — "Position dashboard has an N+1 query problem"): confirmed
        // real — toSummary() used to call orderRepo.findByBrokerOrderId once PER position. One
        // batch query for every entry order these positions actually reference, then an
        // in-memory map lookup per position instead of a database round-trip per position.
        List<String> entryOrderIds = filtered.stream().map(Position::getEntryOrderId).filter(java.util.Objects::nonNull).distinct().toList();
        // Review finding ("OMS/ExecutedOrder full unification" -- P1): migrated off
        // ExecutedOrderRepository's own identical batch-lookup method.
        //
        // P0-5 fix ("Global unique indexes on exchange order IDs collide across
        // symbols/credentials/testnet" -- full context in Order's own @CompoundIndex javadoc):
        // this batch is already scoped to a single credentialId (every position in `filtered`
        // comes from positionRepo.findByUserIdAndCredentialId above), so the DB query itself now
        // filters by credentialId too, eliminating any risk of matching a same-numbered order id
        // from a DIFFERENT credential. These positions CAN still span multiple symbols within
        // this one credential though, so the in-memory map below is keyed by symbol+brokerOrderId
        // (not brokerOrderId alone) to eliminate the same cross-symbol collision risk too, and
        // toSummary looks entries up the same way.
        Map<String, Order> entryOrdersById = entryOrderIds.isEmpty() ? Map.of()
            : orderRepo.findByCredentialIdAndBrokerOrderIdIn(credentialId, entryOrderIds).stream()
                .collect(java.util.stream.Collectors.toMap(o -> o.getSymbol() + "|" + o.getBrokerOrderId(), o -> o, (a, b) -> a));

        // Review finding ("OCO protection logic is better, but dust classification needs one
        // more invariant" -- external review, second pass): same N+1-avoidance principle as the
        // entry-order batch above -- one getSymbolRules() call per UNIQUE symbol among this
        // page's own OPEN positions (not one per position), since multiple positions commonly
        // share a symbol. Closed positions never need this, so they're excluded from the batch
        // entirely. Each fetch is individually wrapped so one bad/slow symbol can't take down
        // the rest of this batch or the whole dashboard response.
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
     * Review finding ("OCO protection logic is better, but dust classification needs one more
     * invariant" -- external review, second pass): the actual classification -- FULL (fully
     * covered), DUST_RESIDUAL (a real gap, but genuinely below the exchange's own minQty --
     * dust, not a meaningful naked exposure), PARTIAL (a gap AT OR ABOVE minQty -- a real,
     * meaningful residual the review's own AutoTradeService-side fix already escalates and
     * emergency-flattens on the money-moving side, but this dashboard read path can still
     * observe the position mid-flight before that escalation completes), UNPROTECTED (no OCO
     * at all), N/A (not OPEN -- protection status is meaningless for a closed position). When
     * this symbol's minQty couldn't be fetched at all (see listPositions's own try/catch above),
     * a genuine gap defaults to PARTIAL, never DUST_RESIDUAL -- this session's own established
     * "never fabricate a reassuring answer when genuinely uncertain" principle: if the real
     * minQty can't be confirmed, treat an unexplained gap as the more serious classification,
     * not the more comfortable one.
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
        // P0-5 fix: map key now symbol+brokerOrderId (see the batch-build site above for why) --
        // looked up the same way here.
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
            // Review finding ("Position protection status is not yet a first-class invariant"
            // -- external review, full context in PositionSummaryDto's own updated header
            // javadoc): confirmed real -- this used to be true whenever ocoOrderListId was
            // merely non-null, regardless of whether the OCO actually covers the position's
            // real quantity. A partially-protected position (a real, known outcome after
            // exchange step-size rounding -- see Position.protectedQuantity's own field
            // javadoc) would still show as fully "protected" here. Now requires
            // protectedQuantity to actually be present and cover the position's real quantity.
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

    /** Review finding (P1 #20): backs the dashboard's "🔴 N CRITICAL" indicator. */
    public java.util.List<com.tradevision.model.TradingIncident> unresolvedIncidents(String userId, String credentialId) {
        credentialService.ownedCredential(userId, credentialId); // ownership check
        return incidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc(credentialId);
    }
}
