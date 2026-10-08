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
 * Audit finding (P1-1 — "No startup check that an alert channel is configured before LIVE
 * trading is possible"): confirmed real by direct inspection. {@link
 * com.tradevision.service.IncidentService#attemptDelivery} already degrades as gracefully as
 * this codebase can manage when every notification channel fails or is unconfigured — the
 * CRITICAL incident itself is always durably recorded, and after {@code MAX_NOTIFICATION_ATTEMPTS}
 * failed delivery attempts it's marked {@code DELIVERY_EXHAUSTED} with a loud log line. But
 * nothing before this fix ever stopped a deployment from reaching that "delivered to nobody,
 * ever" state in the first place: {@code mail.enabled} defaults to {@code false} in both
 * application-prod.properties and application-local.properties, and {@code
 * User.alertWebhookUrl} starts out {@code null} for every account until someone explicitly sets
 * it via {@code AuthService.setAlertWebhookUrl}. A deployment that connects a real, LIVE-mode
 * broker credential without ever touching either of those is one where a halted profile, a
 * failed emergency-flatten, or an unprotected position after a broker outage pages nobody — the
 * account holder finds out only by opening the app and checking the dashboard themselves, which
 * defeats the entire point of an "emergency" alert.
 *
 * Mirrors {@link DevSecretStartupGuard}'s own shape and reasoning exactly, including why this is
 * a {@code @PostConstruct} check rather than a later {@code ApplicationReadyEvent} listener (see
 * that class's javadoc): refusing to start here, before the application could ever accept a
 * single request, is the only way to guarantee this gap is caught before it matters rather than
 * discovered the hard way during a real incident. Just like that guard, this one is narrow on
 * purpose — it never blocks a fresh install or a TESTNET/PAPER-only deployment that hasn't
 * connected real money yet (loud logging only for that case).
 *
 * Audit fix (P1-1 follow-up -- external review, second pass): "Require the alert channel to
 * cover the live user, and check it when a LIVE credential is connected." Confirmed real by
 * direct inspection -- two distinct gaps in the original fix:
 *
 * <ol>
 *   <li>The startup check was satisfied by {@code mail.enabled} OR <b>any</b> account holder in
 *       the whole system having set a webhook — a deployment with 50 users where only user #17
 *       (who holds no broker credential at all) ever configured a webhook, and {@code
 *       mail.enabled=false}, passed this check cleanly even though the actual LIVE trader (say,
 *       user #3) has no alert channel at all. "Some user somewhere is reachable" is not the
 *       property that matters — "the specific user whose money is on LIVE is reachable" is.
 *       {@link #checkAlertChannelConfiguredAgainstLiveCredentials()} below now resolves every
 *       distinct user who actually holds a LIVE credential and verifies coverage for each one of
 *       them individually (own email with {@code mail.enabled=true}, and/or their own webhook
 *       URL) — not a system-wide existence check.</li>
 *   <li>This was a {@code @PostConstruct} check only — it runs once, at process boot, against
 *       whatever LIVE credentials already existed at that moment. A LIVE credential connected
 *       afterward (the overwhelmingly common case: nobody has a LIVE credential at first boot of
 *       a fresh deployment) was never checked against this guard at all until the next restart.
 *       {@link #requireAlertChannelCoverage(String, String)} is the same per-user coverage check,
 *       exposed for {@code BrokerCredentialService.confirmLiveConnect} (the actual moment a LIVE
 *       credential is persisted) to call synchronously and refuse the connection outright if that
 *       specific user has no alert channel configured — closing the gap at the point it is
 *       actually created, not just at the next process restart.</li>
 * </ol>
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
        // Refuses to start entirely -- see this class's own javadoc for why @PostConstruct (not a
        // later, non-fatal ApplicationReadyEvent check) is the deliberate choice here.
        throw new IllegalStateException("REFUSING TO START: " + uncoveredUserIds.size() + " of " + liveUserIds.size() + " user(s) "
            + "holding a LIVE-mode broker credential have NO alert channel reachable for them specifically (their own email with "
            + "mail.enabled=true, and/or their own alert webhook URL) -- user id(s): " + uncoveredUserIds + ". A CRITICAL incident "
            + "(a failed emergency flatten, an unprotected position, a halted profile) for one of these accounts would be durably "
            + "recorded but delivered to nobody. Either have each of these account holders configure an alert webhook URL, or set "
            + "MAIL_ENABLED=true with a real BREVO_API_KEY/BREVO_FROM_EMAIL (see application-prod.properties and SETUP.md) and "
            + "ensure each has a real email on file, before starting this application again.");
    }

    /**
     * True if this specific user has at least one alert channel actually reachable for THEM --
     * either their own email on file with the global {@code mail.enabled} flag on, or their own
     * webhook URL configured. Mirrors exactly what {@code IncidentService.attemptDelivery}
     * actually checks per-user for each channel, so this answers the real question ("will an
     * alert for this user's incident actually go anywhere") rather than a looser proxy for it.
     */
    public boolean userHasAlertChannelCoverage(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;
        if (mailEnabled && user.getEmail() != null && !user.getEmail().isBlank()) return true;
        return user.getAlertWebhookUrl() != null && !user.getAlertWebhookUrl().isBlank();
    }

    /**
     * The runtime half of this fix's "check it when a LIVE credential is connected" requirement.
     * Called from {@code BrokerCredentialService.confirmLiveConnect} immediately before the LIVE
     * credential is actually persisted -- refuses the connection outright (same
     * fail-before-the-row-exists posture {@code doConnect}'s own withdrawal/trading-permission
     * checks already use) rather than silently creating a LIVE credential this account has no
     * way of ever being alerted about.
     */
    public void requireAlertChannelCoverage(String userId) {
        if (userHasAlertChannelCoverage(userId)) return;
        throw new IllegalStateException("Cannot connect a LIVE broker credential: your account has no alert channel configured "
            + "(no alert webhook URL, and no email on file with alerting enabled). A failed emergency flatten, an unprotected "
            + "position, or a halted profile would have nobody to notify. Configure an alert webhook URL for your account before "
            + "connecting a LIVE credential.");
    }
}
