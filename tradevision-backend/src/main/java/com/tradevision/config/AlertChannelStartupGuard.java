package com.tradevision.config;

import com.tradevision.model.BrokerMode;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

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
 * connected real money yet (loud logging only for that case), and it is satisfied by EITHER
 * channel being configured (global {@code mail.enabled=true}, or at least one account holder
 * having set their own webhook URL) since {@code IncidentService} already treats the two as
 * independent, either-is-sufficient delivery paths.
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
        long liveCredentialCount = credentialRepo.countByMode(BrokerMode.LIVE);
        if (liveCredentialCount == 0) {
            if (!mailEnabled) {
                log.warn("No alert channel is configured yet (mail.enabled=false and no account holder has set an alert webhook URL). "
                    + "Startup is NOT being refused only because no LIVE-mode broker credential exists yet -- this MUST be fixed "
                    + "(set MAIL_ENABLED=true with a real Brevo API key, and/or have the account holder configure an alert webhook "
                    + "URL) before any real, LIVE broker credential is ever connected, or this application will refuse to start the "
                    + "moment one exists.");
            }
            return;
        }
        if (mailEnabled || userRepo.existsByAlertWebhookUrlIsNotNull()) {
            return;
        }
        // Refuses to start entirely -- see this class's own javadoc for why @PostConstruct (not a
        // later, non-fatal ApplicationReadyEvent check) is the deliberate choice here.
        throw new IllegalStateException("REFUSING TO START: this deployment already holds " + liveCredentialCount + " LIVE-mode "
            + "broker credential(s), but no alert channel is configured at all -- mail.enabled is false (or MAIL_ENABLED/BREVO_API_KEY "
            + "were never set) AND no account holder has configured an alert webhook URL. A CRITICAL incident (a failed emergency "
            + "flatten, an unprotected position, a halted profile) would be durably recorded but delivered to nobody. Set "
            + "MAIL_ENABLED=true with a real BREVO_API_KEY/BREVO_FROM_EMAIL (see application-prod.properties and SETUP.md), and/or "
            + "have at least one account holder configure an alert webhook URL, before starting this application again.");
    }
}
