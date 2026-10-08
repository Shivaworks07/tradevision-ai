package com.tradevision.config;

import com.tradevision.model.BrokerMode;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * P1-1 fix ("No startup check that an alert channel is configured before LIVE trading is
 * possible" -- full context in AlertChannelStartupGuard's own class javadoc). Same
 * ReflectionTestUtils pattern as DevSecretStartupGuardTest for the @Value field.
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

    @Test
    @DisplayName("no LIVE credentials and no alert channel -- logs the risk but does NOT refuse to start")
    void noLiveCredentials_noAlertChannel_doesNotThrow() {
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(0L);

        guard.checkAlertChannelConfiguredAgainstLiveCredentials();

        verify(userRepo, never()).existsByAlertWebhookUrlIsNotNull();
    }

    @Test
    @DisplayName("LIVE credentials exist, mail.enabled=false, and no account holder has a webhook configured -- REFUSES TO START")
    void liveCredentials_noAlertChannelAtAll_refusesToStart() {
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(1L);
        when(userRepo.existsByAlertWebhookUrlIsNotNull()).thenReturn(false);

        assertThatThrownBy(() -> guard.checkAlertChannelConfiguredAgainstLiveCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("LIVE");
    }

    @Test
    @DisplayName("LIVE credentials exist and mail.enabled=true -- never even checks the webhook repo, does not throw")
    void liveCredentials_mailEnabled_doesNotThrow() {
        ReflectionTestUtils.setField(guard, "mailEnabled", true);
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(3L);

        guard.checkAlertChannelConfiguredAgainstLiveCredentials();

        verify(userRepo, never()).existsByAlertWebhookUrlIsNotNull();
    }

    @Test
    @DisplayName("LIVE credentials exist, mail disabled, but at least one account holder configured a webhook -- does not throw")
    void liveCredentials_webhookConfigured_doesNotThrow() {
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(1L);
        when(userRepo.existsByAlertWebhookUrlIsNotNull()).thenReturn(true);

        guard.checkAlertChannelConfiguredAgainstLiveCredentials();
    }
}
