package com.tradevision.config;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.User;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * P1-1 fix ("No startup check that an alert channel is configured before LIVE trading is
 * possible" -- full context in AlertChannelStartupGuard's own class javadoc). Same
 * ReflectionTestUtils pattern as DevSecretStartupGuardTest for the @Value field.
 *
 * Audit fix (P1-1 follow-up -- external review, second pass: "Require the alert channel to
 * cover the live user, and check it when a LIVE credential is connected"): rewritten against the
 * new per-user coverage check (findByMode + userHasAlertChannelCoverage), replacing the old
 * countByMode/existsByAlertWebhookUrlIsNotNull system-wide-existence checks the reviewer
 * correctly flagged as not actually verifying the LIVE user specifically is reachable. Also adds
 * coverage for requireAlertChannelCoverage, the new runtime half of this fix.
 */
@ExtendWith(MockitoExtension.class)
class AlertChannelStartupGuardTest {

    @Mock BrokerCredentialRepository credentialRepo;
    @Mock UserRepository userRepo;

    private AlertChannelStartupGuard guard;

    @BeforeEach
    void setup() {
        guard = new AlertChannelStartupGuard(credentialRepo, userRepo);
        ReflectionTestUtils.setField(guard, "mailEnabled", false);
    }

    private BrokerCredential liveCredential(String userId) {
        BrokerCredential c = new BrokerCredential();
        c.setUserId(userId);
        c.setMode(BrokerMode.LIVE);
        return c;
    }

    private User userWithEmail(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private User userWithWebhook(String id, String webhookUrl) {
        User u = new User();
        u.setId(id);
        u.setAlertWebhookUrl(webhookUrl);
        return u;
    }

    @Test
    @DisplayName("no LIVE credentials and no alert channel -- logs the risk but does NOT refuse to start")
    void noLiveCredentials_noAlertChannel_doesNotThrow() {
        when(credentialRepo.findByMode(BrokerMode.LIVE)).thenReturn(List.of());

        guard.checkAlertChannelConfiguredAgainstLiveCredentials();

        verify(userRepo, never()).findById(any());
    }

    @Test
    @DisplayName("the one user holding a LIVE credential has NO alert channel of their own -- REFUSES TO START, even though this is the only user in the system")
    void liveCredentialOwnerHasNoAlertChannel_refusesToStart() {
        when(credentialRepo.findByMode(BrokerMode.LIVE)).thenReturn(List.of(liveCredential("user1")));
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmail("user1", null)));

        assertThatThrownBy(() -> guard.checkAlertChannelConfiguredAgainstLiveCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("user1");
    }

    /**
     * The exact reviewer scenario: a webhook exists SOMEWHERE in the system, but not for the
     * user who actually holds the LIVE credential. The old check (existsByAlertWebhookUrlIsNotNull)
     * would have let this one through; the per-user check must not.
     */
    @Test
    @DisplayName("a DIFFERENT user (not the LIVE credential holder) has a webhook configured -- still REFUSES TO START for the actual live user")
    void webhookConfiguredForUnrelatedUser_stillRefusesToStart() {
        when(credentialRepo.findByMode(BrokerMode.LIVE)).thenReturn(List.of(liveCredential("live-user")));
        when(userRepo.findById("live-user")).thenReturn(Optional.of(userWithEmail("live-user", null)));
        // "other-user" having a webhook is simply never looked at -- findById is only ever
        // called for users who actually hold a LIVE credential.

        assertThatThrownBy(() -> guard.checkAlertChannelConfiguredAgainstLiveCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("live-user");
        verify(userRepo, never()).findById("other-user");
    }

    @Test
    @DisplayName("LIVE credential's own owner has mail.enabled=true and a real email on file -- does not throw")
    void liveCredentialOwner_mailEnabledWithEmail_doesNotThrow() {
        ReflectionTestUtils.setField(guard, "mailEnabled", true);
        when(credentialRepo.findByMode(BrokerMode.LIVE)).thenReturn(List.of(liveCredential("user1")));
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmail("user1", "trader@example.com")));

        guard.checkAlertChannelConfiguredAgainstLiveCredentials();
    }

    @Test
    @DisplayName("LIVE credential's own owner has configured their own webhook -- does not throw, even with mail disabled")
    void liveCredentialOwner_ownWebhookConfigured_doesNotThrow() {
        when(credentialRepo.findByMode(BrokerMode.LIVE)).thenReturn(List.of(liveCredential("user1")));
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithWebhook("user1", "https://hooks.example.com/abc")));

        guard.checkAlertChannelConfiguredAgainstLiveCredentials();
    }

    @Test
    @DisplayName("multiple LIVE credential owners -- only the uncovered one blocks startup, and is named in the message")
    void multipleLiveOwners_onlyUncoveredOneBlocksStartup() {
        when(credentialRepo.findByMode(BrokerMode.LIVE)).thenReturn(
            List.of(liveCredential("covered-user"), liveCredential("uncovered-user")));
        when(userRepo.findById("covered-user")).thenReturn(Optional.of(userWithWebhook("covered-user", "https://hooks.example.com/ok")));
        when(userRepo.findById("uncovered-user")).thenReturn(Optional.of(userWithEmail("uncovered-user", null)));

        assertThatThrownBy(() -> guard.checkAlertChannelConfiguredAgainstLiveCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("uncovered-user")
            .hasMessageNotContaining("[covered-user]"); // the covered user isn't listed among the uncovered ones
    }

    // ── userHasAlertChannelCoverage / requireAlertChannelCoverage (runtime check) ──────────

    @Test
    @DisplayName("userHasAlertChannelCoverage: false when the user doesn't exist at all")
    void userHasAlertChannelCoverage_userNotFound_false() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThat(guard.userHasAlertChannelCoverage("ghost")).isFalse();
    }

    @Test
    @DisplayName("userHasAlertChannelCoverage: mail.enabled=true but this user has no email on file -- false")
    void userHasAlertChannelCoverage_mailEnabledNoEmail_false() {
        ReflectionTestUtils.setField(guard, "mailEnabled", true);
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmail("user1", null)));

        assertThat(guard.userHasAlertChannelCoverage("user1")).isFalse();
    }

    @Test
    @DisplayName("requireAlertChannelCoverage: throws for a user with no alert channel, naming the connect-a-LIVE-credential context")
    void requireAlertChannelCoverage_uncovered_throws() {
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmail("user1", null)));

        assertThatThrownBy(() -> guard.requireAlertChannelCoverage("user1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LIVE");
    }

    @Test
    @DisplayName("requireAlertChannelCoverage: does not throw once the user has their own webhook configured")
    void requireAlertChannelCoverage_covered_doesNotThrow() {
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithWebhook("user1", "https://hooks.example.com/abc")));

        guard.requireAlertChannelCoverage("user1");
    }
}
