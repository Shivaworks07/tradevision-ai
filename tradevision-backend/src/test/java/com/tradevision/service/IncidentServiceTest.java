package com.tradevision.service;

import com.tradevision.model.TradingIncident;
import com.tradevision.model.User;
import com.tradevision.repository.TradingIncidentRepository;
import com.tradevision.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers IncidentService's alert orchestration logic, which is fully mockable. EmailService and
 * WebhookAlertService both use an inline-initialized RestTemplate, so their own HTTP calls are
 * not unit-testable the same way and are not exercised here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentServiceTest {

    @Mock TradingIncidentRepository incidentRepo;
    @Mock UserRepository userRepo;
    @Mock EmailService emailService;
    @Mock WebhookAlertService webhookAlertService;

    @InjectMocks IncidentService service;

    @BeforeEach
    void setup() {
        // raise() reassigns `incident = incidentRepo.save(incident)` before attemptDelivery(),
        // matching real Spring Data behavior (save returns the persisted entity). An unstubbed
        // mock returns null, which NPEs in attemptDelivery(). Return the argument, like a real save.
        when(incidentRepo.save(any(TradingIncident.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User userWithEmailAndWebhook() {
        User u = new User();
        u.setId("user1");
        u.setEmail("trader@example.com");
        u.setAlertWebhookUrl("https://hooks.example.com/alert");
        return u;
    }

    @Test
    @DisplayName("raiseWarning: always durably records the incident but never alerts -- WARNING is dashboard-only")
    void raiseWarning_recordsButNeverAlerts() {
        service.raiseWarning("user1", "cred1", "pos1", "order1", "BTCUSDT", "SLIPPAGE_HIGH", "Slippage exceeded threshold");

        ArgumentCaptor<TradingIncident> captor = ArgumentCaptor.forClass(TradingIncident.class);
        verify(incidentRepo).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo("WARNING");
        verify(userRepo, never()).findById(any());
        verify(emailService, never()).sendAlert(any(), any(), any());
        verify(webhookAlertService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("raiseCritical: durably records the incident AND alerts via both email and webhook independently")
    void raiseCritical_recordsAndAlertsBothChannels() {
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmailAndWebhook()));
        when(emailService.sendAlert(any(), any(), any())).thenReturn(true);
        when(webhookAlertService.send(any(), any(), any(), any(), any())).thenReturn(true);

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "PROTECTION_FAILED", "OCO placement failed after 3 attempts");

        ArgumentCaptor<TradingIncident> captor = ArgumentCaptor.forClass(TradingIncident.class);
        verify(incidentRepo, times(2)).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo("CRITICAL");
        assertThat(captor.getValue().getType()).isEqualTo("PROTECTION_FAILED");
        assertThat(captor.getValue().getNotificationStatus()).isEqualTo("DELIVERED");
        verify(emailService).sendAlert(eq("trader@example.com"), contains("PROTECTION FAILED"), contains("OCO placement failed"));
        verify(webhookAlertService).send(eq("https://hooks.example.com/alert"), eq("PROTECTION_FAILED"), eq("CRITICAL"), eq("BTCUSDT"), contains("OCO placement failed"));
    }

    @Test
    @DisplayName("raiseCritical: a failed webhook delivery must not suppress email delivery -- each channel is genuinely independent")
    void raiseCritical_webhookFailure_emailStillAttempted() {
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmailAndWebhook()));
        when(webhookAlertService.send(any(), any(), any(), any(), any())).thenReturn(false);
        when(emailService.sendAlert(any(), any(), any())).thenReturn(true);

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "EMERGENCY_FLATTEN_FAILED", "Could not flatten position");

        verify(emailService).sendAlert(any(), any(), any()); // still called despite webhook returning false
        verify(incidentRepo, times(2)).save(any()); // incident durably recorded regardless, plus the final delivery-status update
    }

    @Test
    @DisplayName("raiseCritical: an email delivery failure (returns false, not thrown) never blocks the incident from being durably recorded, and the already-attempted webhook call is unaffected by it")
    void raiseCritical_emailFailure_webhookStillAttemptedAndIncidentRecorded() {
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmailAndWebhook()));
        when(emailService.sendAlert(any(), any(), any())).thenReturn(false);
        when(webhookAlertService.send(any(), any(), any(), any(), any())).thenReturn(true);

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "ORDER_STATE_UNKNOWN", "Broker did not confirm order status");

        verify(webhookAlertService).send(any(), any(), any(), any(), any());
        verify(incidentRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("raiseCritical: both channels fail on the initial attempt -- notificationStatus is RETRYING (not DELIVERY_EXHAUSTED), leaving it for IncidentRetryService's own scheduled pass rather than escalating immediately")
    void raiseCritical_bothChannelsFail_statusIsRetrying() {
        when(userRepo.findById("user1")).thenReturn(Optional.of(userWithEmailAndWebhook()));
        when(emailService.sendAlert(any(), any(), any())).thenReturn(false);
        when(webhookAlertService.send(any(), any(), any(), any(), any())).thenReturn(false);

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "PROTECTION_FAILED", "message");

        ArgumentCaptor<TradingIncident> captor = ArgumentCaptor.forClass(TradingIncident.class);
        verify(incidentRepo, times(2)).save(captor.capture());
        assertThat(captor.getValue().getNotificationStatus()).isEqualTo("RETRYING");
        assertThat(captor.getValue().getNotificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("raiseCritical: no webhook URL configured -- webhook is simply never attempted, email still sent, incident still recorded")
    void raiseCritical_noWebhookConfigured_webhookSkippedEmailStillSent() {
        User user = new User();
        user.setId("user1");
        user.setEmail("trader@example.com");
        // alertWebhookUrl left unset (null)
        when(userRepo.findById("user1")).thenReturn(Optional.of(user));
        when(emailService.sendAlert(any(), any(), any())).thenReturn(true);

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "PROTECTION_FAILED", "message");

        verify(webhookAlertService, never()).send(any(), any(), any(), any(), any());
        verify(emailService).sendAlert(any(), any(), any());
    }

    @Test
    @DisplayName("raiseCritical: no email on file -- email is simply never attempted, but the incident is still durably recorded")
    void raiseCritical_noEmailOnFile_emailSkippedIncidentStillRecorded() {
        User user = new User();
        user.setId("user1");
        // email left unset (null)
        when(userRepo.findById("user1")).thenReturn(Optional.of(user));

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "PROTECTION_FAILED", "message");

        verify(emailService, never()).sendAlert(any(), any(), any());
        verify(incidentRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("raiseCritical: user not found -- alerting is skipped gracefully, but the incident is still durably recorded, never thrown")
    void raiseCritical_userNotFound_incidentStillRecordedNoThrow() {
        when(userRepo.findById("user1")).thenReturn(Optional.empty());

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "PROTECTION_FAILED", "message");

        verify(incidentRepo, times(2)).save(any());
        verify(emailService, never()).sendAlert(any(), any(), any());
        verify(webhookAlertService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("raiseCritical: an unexpected exception during alerting (e.g. a repository lookup failure) is caught and never propagates -- an alert failing to send must never mask or interrupt the safety action that triggered it")
    void raiseCritical_unexpectedException_neverPropagates() {
        when(userRepo.findById("user1")).thenThrow(new RuntimeException("simulated database connectivity issue"));

        service.raiseCritical("user1", "cred1", "pos1", "order1", "BTCUSDT", "PROTECTION_FAILED", "message");
        // must not throw -- the incident record itself (via incidentRepo.save, called before the
        // try block that looks up the user) already succeeded regardless of this failure.

        verify(incidentRepo, times(2)).save(any());
    }
}
