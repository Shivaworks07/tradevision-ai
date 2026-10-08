package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.config.TradingHeartbeatService;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.RiskProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Aggregates the system's live operational state — heartbeats, startup/shutdown phase, and each
 * credential's halt/authorization state — into a single authenticated view, so answering "is the
 * bot alive and allowed to trade" doesn't require separately checking liveness and digging
 * through each risk profile.
 *
 * This is a read-only status view, not a control surface — AdminController's halt/resume/
 * halt-all endpoints remain the place actions happen; this only reports state. It's scoped to
 * one authenticated user's own credentials, following the same per-user authorization every
 * other controller in this codebase uses, rather than a separate operator privilege tier.
 */
@Service
@RequiredArgsConstructor
public class OpsStatusService {

    private final BrokerCredentialRepository credentialRepo;
    private final RiskProfileRepository riskProfileRepo;
    private final TradingHeartbeatService heartbeatService;
    private final com.tradevision.config.StartupState startupState;
    private final com.tradevision.config.ShutdownState shutdownState;
    // Broker connectivity health is folded into this same status view rather than left as a
    // separate place to look, since this is the natural single place to check "is the bot alive
    // and allowed to trade".
    private final ExchangeHealthService exchangeHealthService;
    private final BrokerCredentialService credentialService;

    public record CredentialOpsStatus(
        String credentialId,
        String broker,
        String mode,
        boolean tradingHalted,
        String haltReason,
        boolean autoTradeHalted,
        String autoTradeHaltReason,
        boolean liveAutoTradeAuthorized,
        int consecutiveAutoTradeLosses,
        boolean hasRiskProfile,
        ExchangeHealthService.CompositeHealthStatus brokerHealth
    ) {}

    public record OpsStatus(
        String startupPhase,
        boolean tradingEnabled,
        boolean shuttingDown,
        Instant lastScanCompletedAt,
        Instant lastReconciliationCompletedAt,
        List<CredentialOpsStatus> credentials
    ) {}

    public OpsStatus getStatus(String userId) {
        List<BrokerCredential> creds = credentialRepo.findByUserIdAndActiveTrue(userId);
        List<CredentialOpsStatus> credentialStatuses = creds.stream()
            .map(c -> {
                // Computed regardless of whether a risk profile exists — broker connectivity is
                // a fact about the credential itself, not about whether auto-trade happens to be
                // configured for it.
                var brokerHealth = brokerHealthFor(userId, c);
                var profileOpt = riskProfileRepo.findByUserIdAndCredentialId(userId, c.getId());
                if (profileOpt.isEmpty()) {
                    // A credential can exist with no risk profile set up yet, meaning auto-trade
                    // has never been configured for it. Reported as-is, rather than defaulted to
                    // "everything disabled" or skipped.
                    return new CredentialOpsStatus(c.getId(), c.getBroker().name(), c.getMode().name(),
                        false, null, false, null, false, 0, false, brokerHealth);
                }
                var p = profileOpt.get();
                return new CredentialOpsStatus(c.getId(), c.getBroker().name(), c.getMode().name(),
                    p.isTradingHalted(), p.getHaltReason(),
                    p.isAutoTradeHalted(), p.getAutoTradeHaltReason(),
                    p.isLiveAutoTradeAuthorized(), p.getConsecutiveAutoTradeLosses(), true, brokerHealth);
            })
            .toList();

        return new OpsStatus(
            startupState.getPhase().name(),
            startupState.isTradingEnabled(),
            shutdownState.isShuttingDown(),
            heartbeatService.getLastScanCompletedAt(),
            heartbeatService.getLastReconciliationCompletedAt(),
            credentialStatuses
        );
    }

    /**
     * Looks up broker connectivity health for one credential. Failures here must never block or
     * corrupt the primary status report, so a decryption or lookup failure is reported as a
     * "couldn't determine" status rather than omitted or defaulted to healthy.
     */
    private ExchangeHealthService.CompositeHealthStatus brokerHealthFor(String userId, BrokerCredential c) {
        try {
            String apiKey = credentialService.decrypt(c, true);
            return exchangeHealthService.checkComposite(apiKey, c.getId());
        } catch (Exception e) {
            return new ExchangeHealthService.CompositeHealthStatus(false,
                List.of("Could not determine broker health: " + e.getMessage()), null, null, null);
        }
    }
}
