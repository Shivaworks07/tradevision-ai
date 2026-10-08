package com.tradevision.config;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.User;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ensures that every user holding a LIVE-mode broker credential has at least one alert channel
 * reachable for them specifically, both at startup and at the moment a new LIVE credential is
 * connected. {@link com.tradevision.service.IncidentService#attemptDelivery} already degrades
 * gracefully when every notification channel fails or is unconfigured — the CRITICAL incident is
 * always durably recorded, and after {@code MAX_NOTIFICATION_ATTEMPTS} failed delivery attempts
 * it's marked {@code DELIVERY_EXHAUSTED} with a loud log line — but that only matters once an
 * alert channel exists at all. {@code mail.enabled} defaults to {@code false}, and {@code
 * User.alertWebhookUrl} starts out {@code null} for every account until someone explicitly sets
 * it, so without this guard a LIVE credential could be connected with no way to ever notify its
 * owner of a halted profile, a failed emergency flatten, or an unprotected position.
 *
 * Mirrors {@link DevSecretStartupGuard}'s shape: a {@code @PostConstruct} check, not a later
 * {@code ApplicationReadyEvent} listener, so refusing to start happens before the application
 * could ever accept a single request. It is narrow by design — it never blocks a fresh install
 * or a TESTNET/PAPER-only deployment that hasn't connected real money yet (loud logging only for
 * that case).
 *
 * The coverage check is per-user, not system-wide: {@link #checkAlertChannelConfiguredAgainstLiveCredentials()}
 * resolves every distinct user who holds a LIVE credential and verifies coverage for each one
 * individually (their own email with {@code mail.enabled=true}, and/or their own webhook URL) —
 * one user having a webhook configured does not cover a different user's LIVE credential.
 * {@link #requireAlertChannelCoverage(String)} is the same per-user check, exposed for {@code
 * BrokerCredentialService.confirmLiveConnect} to call synchronously and refuse a new LIVE
 * connection outright if that specific user has no alert channel configured, closing the gap at
 * the moment a credential is created rather than only at the next process restart.
 */
@Component
@RequiredArgsConstructor
public class AlertChannelStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelStartupGuard.class);

    @Value("${mail.enabled:false}")
    private boolean mailEnabled;

    private final BrokerCredentialRepository credentialRepo;
    private final UserRepository userRepo;

    @PostConstruct
    public void checkAlertChannelConfiguredAgainstLiveCredentials() {
        List<BrokerCredential> liveCredentials = credentialRepo.findByMode(BrokerMode.LIVE);
        if (liveCredentials.isEmpty()) {
            if (!mailEnabled) {
                log.warn("No alert channel is configured yet (mail.enabled=false and no account holder has set an alert webhook URL). "
                    + "Startup is NOT being refused only because no LIVE-mode broker credential exists yet -- this MUST be fixed "
                    + "(set MAIL_ENABLED=true with a real Brevo API key, and/or have the account holder configure an alert webhook "
                    + "URL) before any real, LIVE broker credential is ever connected, or this application will refuse to start the "
                    + "moment one exists.");
            }
            return;
        }

        Set<String> liveUserIds = liveCredentials.stream().map(BrokerCredential::getUserId).collect(Collectors.toSet());
        List<String> uncoveredUserIds = liveUserIds.stream().filter(userId -> !userHasAlertChannelCoverage(userId)).toList();
        if (uncoveredUserIds.isEmpty()) {
            return;
        }
        // Refuses to start entirely, before the application can accept any request.
        throw new IllegalStateException("REFUSING TO START: " + uncoveredUserIds.size() + " of " + liveUserIds.size() + " user(s) "
            + "holding a LIVE-mode broker credential have NO alert channel reachable for them specifically (their own email with "
            + "mail.enabled=true, and/or their own alert webhook URL) -- user id(s): " + uncoveredUserIds + ". A CRITICAL incident "
            + "(a failed emergency flatten, an unprotected position, a halted profile) for one of these accounts would be durably "
            + "recorded but delivered to nobody. Either have each of these account holders configure an alert webhook URL, or set "
            + "MAIL_ENABLED=true with a real BREVO_API_KEY/BREVO_FROM_EMAIL (see application-prod.properties and SETUP.md) and "
            + "ensure each has a real email on file, before starting this application again.");
    }

    /**
     * True if this specific user has at least one alert channel actually reachable for them —
     * either their own email on file with the global {@code mail.enabled} flag on, or their own
     * webhook URL configured. Mirrors what {@code IncidentService.attemptDelivery} checks
     * per-user for each channel, so this answers the real question of whether an alert for this
     * user's incident would actually go anywhere.
     */
    public boolean userHasAlertChannelCoverage(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;
        if (mailEnabled && user.getEmail() != null && !user.getEmail().isBlank()) return true;
        return user.getAlertWebhookUrl() != null && !user.getAlertWebhookUrl().isBlank();
    }

    /**
     * Called from {@code BrokerCredentialService.confirmLiveConnect} immediately before a LIVE
     * credential is persisted. Refuses the connection outright, the same fail-before-the-row-
     * exists posture {@code doConnect}'s withdrawal/trading-permission checks use, rather than
     * silently creating a LIVE credential this account has no way of ever being alerted about.
     */
    public void requireAlertChannelCoverage(String userId) {
        if (userHasAlertChannelCoverage(userId)) return;
        throw new IllegalStateException("Cannot connect a LIVE broker credential: your account has no alert channel configured "
            + "(no alert webhook URL, and no email on file with alerting enabled). A failed emergency flatten, an unprotected "
            + "position, or a halted profile would have nobody to notify. Configure an alert webhook URL for your account before "
            + "connecting a LIVE credential.");
    }
}
