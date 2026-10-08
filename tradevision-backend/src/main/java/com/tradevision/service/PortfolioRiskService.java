package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.service.broker.BrokerAdapter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates exposure, P&L, drawdown, correlation and limit-utilization into a single
 * portfolio-level risk view. This is purely a read/aggregation layer: every number is
 * computed from data this codebase already tracks authoritatively — open/closed
 * {@link Position} records, {@link RiskProfile} limits and peak-equity tracking, and
 * live broker balance/price lookups. It introduces no new source of truth.
 *
 * Scope and behavior:
 * - Exposure (gross/net/per-symbol/correlation-group) is computed directly from OPEN
 *   Position records rather than from any reservation bookkeeping — positions are the
 *   authoritative state for a portfolio view, independent of concurrency-safety
 *   mechanisms used elsewhere.
 * - Gross and net exposure are currently identical because this system is spot-only,
 *   long-only (no short positions to net against). Both fields are kept so the model
 *   already supports netting once shorting is introduced.
 * - Unrealized P&L requires a live price per open position's symbol. If a price lookup
 *   fails for a position, that position is excluded from the total and listed in
 *   unpricedSymbols instead of being treated as zero, which would understate real
 *   exposure and risk.
 * - riskState (GREEN/YELLOW/RED) is a simple, disclosed threshold heuristic rather than
 *   a predictive model. GREEN is below 50% of any limit, YELLOW is 50-89%, and RED is
 *   90%+ of any single limit (drawdown, total exposure, or concurrent positions).
 *   Whichever limit is closest to breach determines the color, and the reason is always
 *   reported alongside it.
 */
@Service
@RequiredArgsConstructor
public class PortfolioRiskService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskService.class);

    private final PositionRepository positionRepo;
    private final RiskProfileRepository riskProfileRepo;

    public record PortfolioSnapshot(
        BigDecimal equity,
        BigDecimal freeBalance,
        BigDecimal openPositionsMarketValue,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        double drawdownPercent,
        BigDecimal grossExposure,
        BigDecimal netExposure,
        Map<String, BigDecimal> exposureBySymbol,
        Map<String, BigDecimal> exposureByCorrelationGroup,
        int openPositionCount,
        int maxConcurrentPositions,
        List<String> unpricedSymbols,
        String riskState,
        String riskStateReason
    ) {}

    public PortfolioSnapshot snapshot(BrokerCredential credential, BrokerAdapter adapter, String apiKey, String apiSecret,
                                       int realizedPnlLookbackDays) {
        RiskProfile profile = riskProfileRepo.findByCredentialId(credential.getId()).orElse(null);
        List<Position> openPositions = positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN");

        // Balance/equity is measured in profile.drawdownQuoteAsset rather than a hardcoded
        // asset, since every enabled symbol is assumed to quote in this same asset
        // (consistent with PositionMonitorService.checkDrawdown).
        String quoteAsset = profile != null && profile.getDrawdownQuoteAsset() != null ? profile.getDrawdownQuoteAsset() : "USDT";
        BigDecimal freeBalance = BigDecimal.ZERO;
        try {
            var balances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
            freeBalance = balances.stream()
                .filter(b -> b.asset().equalsIgnoreCase(quoteAsset))
                .map(com.tradevision.service.broker.dto.AssetBalance::free)
                .findFirst().orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("Portfolio snapshot: could not fetch balance for credential {}: {}", credential.getId(), e.getMessage());
        }

        BigDecimal grossExposure = BigDecimal.ZERO;
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        Map<String, BigDecimal> exposureBySymbol = new HashMap<>();
        List<String> unpricedSymbols = new java.util.ArrayList<>();

        for (Position p : openPositions) {
            if (p.getQuantity() == null || p.getAvgEntryPrice() == null || p.isAvgEntryPriceUnverified()) {
                unpricedSymbols.add(p.getSymbol() + " (unverified entry price)");
                continue;
            }
            BigDecimal currentPrice;
            try {
                currentPrice = adapter.getCurrentPrice(p.getSymbol(), credential.getMode());
            } catch (Exception e) {
                unpricedSymbols.add(p.getSymbol() + " (live price unavailable)");
                continue;
            }
            if (currentPrice == null || currentPrice.signum() <= 0) {
                unpricedSymbols.add(p.getSymbol() + " (live price unavailable)");
                continue;
            }
            BigDecimal marketValue = p.getQuantity().multiply(currentPrice);
            BigDecimal costBasis = p.getQuantity().multiply(p.getAvgEntryPrice());
            grossExposure = grossExposure.add(marketValue);
            unrealizedPnl = unrealizedPnl.add(marketValue.subtract(costBasis));
            exposureBySymbol.merge(p.getSymbol(), marketValue, BigDecimal::add);
        }
        // Net exposure equals gross exposure here since this system is spot-only, long-only.
        BigDecimal netExposure = grossExposure;

        Map<String, BigDecimal> exposureByCorrelationGroup = new HashMap<>();
        if (profile != null && profile.getCorrelationGroups() != null) {
            for (var entry : profile.getCorrelationGroups().entrySet()) {
                BigDecimal groupExposure = exposureBySymbol.entrySet().stream()
                    .filter(e -> entry.getValue() != null && entry.getValue().contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (groupExposure.signum() > 0) exposureByCorrelationGroup.put(entry.getKey(), groupExposure);
            }
        }

        BigDecimal realizedPnl = BigDecimal.ZERO;
        List<Position> recentlyClosed = positionRepo.findByCredentialIdAndClosedAtAfter(credential.getId(),
            LocalDateTime.now().minusDays(realizedPnlLookbackDays));
        for (Position p : recentlyClosed) {
            if (p.getRealizedPnlQuote() != null) realizedPnl = realizedPnl.add(p.getRealizedPnlQuote());
        }

        BigDecimal equity = freeBalance.add(grossExposure);
        double drawdownPercent = 0;
        if (profile != null && profile.getPeakEquityQuote() != null && profile.getPeakEquityQuote().signum() > 0
                && equity.compareTo(profile.getPeakEquityQuote()) < 0) {
            drawdownPercent = profile.getPeakEquityQuote().subtract(equity)
                .divide(profile.getPeakEquityQuote(), 6, java.math.RoundingMode.HALF_UP).doubleValue() * 100.0;
        }

        int maxConcurrent = profile != null ? profile.getMaxConcurrentTrades() : 0;
        var riskState = computeRiskState(profile, drawdownPercent, grossExposure, openPositions.size(), maxConcurrent);

        return new PortfolioSnapshot(equity, freeBalance, grossExposure, realizedPnl, unrealizedPnl, drawdownPercent,
            grossExposure, netExposure, exposureBySymbol, exposureByCorrelationGroup,
            openPositions.size(), maxConcurrent, unpricedSymbols, riskState.state(), riskState.reason());
    }

    private record RiskState(String state, String reason) {}

    private RiskState computeRiskState(RiskProfile profile, double drawdownPercent, BigDecimal grossExposure,
                                        int openCount, int maxConcurrent) {
        if (profile == null) return new RiskState("UNKNOWN", "No risk profile configured for this credential.");

        double worst = 0;
        String worstReason = "All tracked limits are well within bounds.";

        if (profile.getMaxDrawdownPercent() > 0) {
            double ratio = drawdownPercent / profile.getMaxDrawdownPercent();
            if (ratio > worst) { worst = ratio; worstReason = String.format("Drawdown %.1f%% of %.1f%% limit", drawdownPercent, profile.getMaxDrawdownPercent()); }
        }
        if (profile.getMaxTotalExposureQuote() != null && profile.getMaxTotalExposureQuote().signum() > 0) {
            double ratio = grossExposure.divide(profile.getMaxTotalExposureQuote(), 6, java.math.RoundingMode.HALF_UP).doubleValue();
            if (ratio > worst) { worst = ratio; worstReason = String.format("Total exposure %s of %s limit", grossExposure, profile.getMaxTotalExposureQuote()); }
        }
        if (maxConcurrent > 0) {
            double ratio = (double) openCount / maxConcurrent;
            if (ratio > worst) { worst = ratio; worstReason = openCount + " of " + maxConcurrent + " concurrent position slots in use"; }
        }

        if (worst >= 0.90) return new RiskState("RED", worstReason);
        if (worst >= 0.50) return new RiskState("YELLOW", worstReason);
        return new RiskState("GREEN", worstReason);
    }
}
