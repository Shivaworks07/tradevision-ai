package com.tradevision.config;

import com.tradevision.model.BrokerMode;
import com.tradevision.repository.BrokerCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;

/**
 * P1-16 fix ("Committed secrets + 'local' as default profile" -- second, independent layer of
 * defense; the first is application.properties' own default profile now being "prod", not
 * "local" -- see that file's own updated comment for the full reasoning). This class exists
 * specifically for the case the profile-default fix alone cannot catch: a deployment that
 * genuinely, deliberately (or by copy-paste mistake) ends up running with one of
 * application-local.properties' own well-known, publicly-committed secret values -- whether
 * because SPRING_PROFILES_ACTIVE=local was explicitly set in an environment that also holds
 * real broker credentials, or because a real deployment's own env vars were accidentally set to
 * these exact same values (they are sitting in this repo's own git history in plain text, so
 * "accidentally" is a real risk, not a hypothetical one).
 *
 * The check itself is narrow and specific on purpose: it does NOT refuse to start merely because
 * a known dev secret is active (that would make ordinary local development, which legitimately
 * uses these exact values every single day, impossible) -- it refuses to start when EITHER of
 * two conditions holds:
 * <ul>
 *   <li>the active Spring profile is "prod" -- a production deployment running with a
 *       publicly-committed dev secret is never legitimate, full stop, independent of whether a
 *       LIVE broker credential happens to exist in the database yet. This closes the gap the
 *       audit identified in the original version of this guard: application-prod.properties'
 *       own placeholders (e.g. {@code app.jwt.secret=${JWT_SECRET}}, no default) already make it
 *       hard to reach this class with a known dev secret under "prod" by *omission* (Spring
 *       itself throws on the unresolved placeholder first) -- but nothing previously stopped
 *       someone from reaching it by *commission*: explicitly setting JWT_SECRET/APP_ENCRYPTION_
 *       KEY/ADMIN_BOOTSTRAP_SECRET/OTP_HMAC_SECRET to one of these exact, publicly-known values
 *       (they sit in this repo's own git history in plain text), which satisfied the placeholder
 *       and let a "prod"-profile deployment start normally as long as no LIVE credential had
 *       been connected yet. That window is now closed: "prod" + a known dev secret refuses to
 *       start regardless of LIVE-credential state.</li>
 *   <li>(local/other non-"prod" profiles only) a known dev secret is active AND at least one
 *       LIVE-mode broker credential already exists in this deployment's own database. That
 *       combination is never legitimate: a LIVE credential represents real money at a real
 *       broker, and running with a publicly-known encryption key/JWT secret/admin bootstrap
 *       secret/OTP HMAC key while real credentials exist means anyone who has ever seen this
 *       repository can decrypt those credentials, forge admin JWTs, or bypass the admin
 *       bootstrap ceremony entirely.</li>
 * </ul>
 * Outside both of those, TESTNET/PAPER-only deployments on a known dev secret under a non-"prod"
 * profile are left alone (loud logging only, not a hard failure) -- there is no real money at
 * stake for those yet, and this deliberately doesn't block a fresh local install that hasn't
 * connected a real broker credential yet.
 *
 * @PostConstruct (not an ApplicationReadyEvent listener, unlike IndexInitializer/
 * PositionMonitorService's own startup hooks) is deliberate: this runs during bean
 * initialization, before the application has finished starting and before it could ever begin
 * serving a single request or accepting a single WebSocket connection -- throwing here aborts
 * Spring Boot's own startup sequence outright (matching the audit's own required "refuses to
 * start" behavior), rather than merely disabling a flag after the app is already up and
 * reachable, which is what an ApplicationReadyEvent listener would mean.
 */
@Component
@RequiredArgsConstructor
public class DevSecretStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(DevSecretStartupGuard.class);

    // The exact values committed in application-local.properties -- see that file's own header
    // comment ("Every secret below is a randomly-generated, clearly-fake placeholder committed
    // to this repo on purpose... NEVER reuse any of these values... in any real deployment").
    private static final Set<String> KNOWN_DEV_SECRETS = Set.of(
        "p9b28r5s+b0fz+BhL51ef1y7wfpKZr5W/8tEDHVjxZk=",  // app.jwt.secret
        "FVzfJYk/8RCI4V0V1Jy3vk50Ni7fJg1GspyINhozLLk=",  // app.encryption.key
        "ev0/Nk5FhRBgIx5cNEfDq1iQU+dEcZTIet58FsxJWME=",  // app.admin.bootstrap-secret
        "Qknh9d9kZrg1TsuqJLIW5RF7BtFFByTRGf93EnjwxsQ="   // app.otp.hmac-secret
    );

    @Value("${app.jwt.secret:}")
    private String jwtSecret;
    @Value("${app.encryption.key:}")
    private String encryptionKey;
    @Value("${app.admin.bootstrap-secret:}")
    private String adminBootstrapSecret;
    @Value("${app.otp.hmac-secret:}")
    private String otpHmacSecret;

    private final Environment environment;
    private final BrokerCredentialRepository credentialRepo;

    @PostConstruct
    public void checkForDevSecretsAgainstLiveCredentials() {
        boolean usingKnownDevSecret = KNOWN_DEV_SECRETS.contains(jwtSecret) || KNOWN_DEV_SECRETS.contains(encryptionKey)
            || KNOWN_DEV_SECRETS.contains(adminBootstrapSecret) || KNOWN_DEV_SECRETS.contains(otpHmacSecret);
        if (!usingKnownDevSecret) return;

        // Audit fix ("DevSecretStartupGuard only refuses them when LIVE credentials exist; make
        // it refuse in the prod profile" -- confirmed real, see this class's own updated javadoc
        // for the full "by omission vs by commission" reasoning): a "prod" deployment refuses to
        // start the moment a known dev secret is detected, independent of whether a LIVE broker
        // credential already exists.
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("REFUSING TO START: this deployment is running with the \"prod\" Spring profile active "
                + "AND at least one publicly-committed, known development secret (see application-local.properties) for "
                + "app.jwt.secret, app.encryption.key, app.admin.bootstrap-secret, and/or app.otp.hmac-secret. A production "
                + "deployment must never run with a secret value that is sitting in this repository's own public git history in "
                + "plain text, regardless of whether a LIVE broker credential has been connected yet. Set real, unique values via "
                + "the JWT_SECRET, APP_ENCRYPTION_KEY, ADMIN_BOOTSTRAP_SECRET, and OTP_HMAC_SECRET environment variables (see "
                + "application-prod.properties and SETUP.md) before starting this application again.");
        }

        long liveCredentialCount = credentialRepo.countByMode(BrokerMode.LIVE);
        if (liveCredentialCount > 0) {
            // Refuses to start entirely -- see this class's own javadoc for why @PostConstruct
            // (not a later, non-fatal ApplicationReadyEvent check) is the deliberate choice here.
            throw new IllegalStateException("REFUSING TO START: this deployment is running with at least one publicly-committed, "
                + "known development secret (see application-local.properties) for app.jwt.secret, app.encryption.key, "
                + "app.admin.bootstrap-secret, and/or app.otp.hmac-secret, AND already holds " + liveCredentialCount + " LIVE-mode "
                + "broker credential(s). Anyone who has ever seen this repository's own public source/git history knows these exact "
                + "secret values -- running with them while real broker credentials exist means those credentials, admin access, and "
                + "OTP verification are not actually protected. Set real, unique values via the SPRING_PROFILES_ACTIVE=prod "
                + "environment variables (JWT_SECRET, APP_ENCRYPTION_KEY, ADMIN_BOOTSTRAP_SECRET, OTP_HMAC_SECRET -- see "
                + "application-prod.properties and SETUP.md) before starting this application again.");
        }
        log.error("This deployment is running with at least one publicly-committed, known development secret (see "
            + "application-local.properties) for app.jwt.secret, app.encryption.key, app.admin.bootstrap-secret, and/or "
            + "app.otp.hmac-secret. Startup is NOT being refused only because no LIVE-mode broker credential exists yet -- this MUST "
            + "be fixed with real, unique secret values (see application-prod.properties and SETUP.md) before any real, LIVE broker "
            + "credential is ever connected to this deployment, or this application will refuse to start the moment one exists.");
    }
}
