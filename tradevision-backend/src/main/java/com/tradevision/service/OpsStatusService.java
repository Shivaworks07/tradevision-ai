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
 * Review finding ("Ops / monitoring surface" — "/actuator/health + kill endpoints exist. No
 * dedicated authenticated ops status (heartbeats, halt flags, LIVE auth per credential).
 * Operators need one clear place to see 'is the bot alive and allowed to trade?'"): this is
 * that single place — aggregates what already exists (TradingHeartbeatService, StartupState,
 * each credential's RiskProfile halt/authorization state) into one authenticated view, rather
 * than an operator having to separately check /actuator/health for liveness and then dig through
 * each risk profile individually to answer "is it actually allowed to trade right now".
 *
 * HONEST SCOPE: this is a read-only status view, not a control surface — AdminController's own
 * existing halt/resume/halt-all endpoints remain the place actions happen; this only reports
 * state. Scoped to one authenticated user's own credentials (via BrokerCredentialRepository.
 * findByUserIdAndActiveTrue), not a global cross-user operations view — this codebase has no
 * concept of an "operator" role distinct from a regular authenticated user in this pass, so this
 * follows the same per-user authorization every other controller in this codebase already uses,
 * not a new privilege tier.
 */
@Service
@RequiredArgsConstructor
public class OpsStatusService {

    private final BrokerCredentialRepository credentialRepo;
    private final RiskProfileRepository riskProfileRepo;
    private final TradingHeartbeatService heartbeatService;
    private final com.tradevision.config.StartupState startupState;
    private final com.tradevision.config.ShutdownState shutdownState;
    // Review finding ("Broker health improved but not unified" — "A single composite 'broker +
    // market-data + execution + rate-limit' status is still incomplete"): wired in here, since
    // this is the natural single place an operator checks "is the bot alive and allowed to
    // trade" — no reason broker connectivity health should be a second place to look.
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
                // Review finding ("Broker health improved but not unified"): computed
                // regardless of whether a risk profile exists — broker connectivity is a fact
                // about the CREDENTIAL, not about whether auto-trade happens to be configured
                // for it.
                var brokerHealth = brokerHealthFor(userId, c);
                var profileOpt = riskProfileRepo.findByUserIdAndCredentialId(userId, c.getId());
                if (profileOpt.isEmpty()) {
                    // A real, honest state — a credential can exist with no risk profile set up
                    // yet, meaning auto-trade has never been configured for it at all. Reported
                    // as-is, not silently defaulted to "everything disabled" or skipped.
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
     * Non-fatal, additive — same design principle as everywhere else in this codebase where a
     * side-channel observation must never block or corrupt the primary status report. A
     * decryption or lookup failure here is reported as an honest "couldn't determine" rather
     * than silently omitted or defaulted to healthy.
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
