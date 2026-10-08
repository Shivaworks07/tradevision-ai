package com.tradevision.service;

import com.tradevision.config.ShutdownState;
import com.tradevision.model.TradingIncident;
import com.tradevision.repository.TradingIncidentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers IncidentRetryService's retry step for notification delivery, so that critical alerting
 * is not purely best-effort.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentRetryServiceTest {

    @Mock TradingIncidentRepository incidentRepo;
    @Mock IncidentService incidentService;
    @Mock ShutdownState shutdownState;

    @InjectMocks IncidentRetryService service;

    @Test
    @DisplayName("retryPendingDeliveries: shutdown already in progress -- skips the entire pass, never even queries for pending incidents")
    void retryPendingDeliveries_shutdownInProgress_skipsEntirely() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.retryPendingDeliveries();

        verify(incidentRepo, never()).findByNotificationStatusIn(any());
    }

    @Test
    @DisplayName("retryPendingDeliveries: no pending/retrying incidents -- a real no-op, never calls attemptDelivery")
    void retryPendingDeliveries_nothingPending_noOp() {
        when(shutdownState.isShuttingDown()).thenReturn(false);
        when(incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"))).thenReturn(List.of());

        service.retryPendingDeliveries();

        verify(incidentService, never()).attemptDelivery(any());
    }

    @Test
    @DisplayName("retryPendingDeliveries: an incident whose last attempt was recent (within the backoff window) is skipped this pass -- not retried on every single scheduled cycle")
    void retryPendingDeliveries_recentAttempt_skipsWithinBackoffWindow() {
        when(shutdownState.isShuttingDown()).thenReturn(false);
        var incident = new TradingIncident();
        incident.setId("incident1");
        incident.setLastNotificationAttemptAt(LocalDateTime.now().minusSeconds(30)); // well within the 3-minute backoff
        when(incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"))).thenReturn(List.of(incident));

        service.retryPendingDeliveries();

        verify(incidentService, never()).attemptDelivery(any());
    }

    @Test
    @DisplayName("retryPendingDeliveries: an incident whose last attempt was long enough ago is genuinely retried")
    void retryPendingDeliveries_pastBackoffWindow_retriesDelivery() {
        when(shutdownState.isShuttingDown()).thenReturn(false);
        var incident = new TradingIncident();
        incident.setId("incident1");
        incident.setLastNotificationAttemptAt(LocalDateTime.now().minusMinutes(10)); // well past the 3-minute backoff
        when(incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"))).thenReturn(List.of(incident));

        service.retryPendingDeliveries();

        verify(incidentService).attemptDelivery(incident);
    }

    @Test
    @DisplayName("retryPendingDeliveries: an incident that has never been attempted at all (lastNotificationAttemptAt is null) is retried immediately -- null must not be misread as 'recently attempted'")
    void retryPendingDeliveries_neverAttempted_retriesImmediately() {
        when(shutdownState.isShuttingDown()).thenReturn(false);
        var incident = new TradingIncident();
        incident.setId("incident1");
        incident.setLastNotificationAttemptAt(null);
        when(incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"))).thenReturn(List.of(incident));

        service.retryPendingDeliveries();

        verify(incidentService).attemptDelivery(incident);
    }

    @Test
    @DisplayName("retryPendingDeliveries: one incident's own retry throws an unexpected exception -- the rest of the pass still processes the other incidents, never stops the whole loop")
    void retryPendingDeliveries_oneIncidentThrows_restOfPassStillProcessed() {
        when(shutdownState.isShuttingDown()).thenReturn(false);
        var failing = new TradingIncident();
        failing.setId("incident1"); failing.setLastNotificationAttemptAt(LocalDateTime.now().minusMinutes(10));
        var succeeding = new TradingIncident();
        succeeding.setId("incident2"); succeeding.setLastNotificationAttemptAt(LocalDateTime.now().minusMinutes(10));
        when(incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"))).thenReturn(List.of(failing, succeeding));
        doThrow(new RuntimeException("simulated database error")).when(incidentService).attemptDelivery(failing);

        service.retryPendingDeliveries();

        verify(incidentService).attemptDelivery(succeeding); // still reached despite the first one throwing
    }

    @Test
    @DisplayName("retryPendingDeliveries: shutdown begins mid-loop -- stops processing the remaining incidents rather than continuing to mutate state after shutdown started")
    void retryPendingDeliveries_shutdownMidLoop_stopsProcessingRemaining() {
        var first = new TradingIncident();
        first.setId("incident1"); first.setLastNotificationAttemptAt(LocalDateTime.now().minusMinutes(10));
        var second = new TradingIncident();
        second.setId("incident2"); second.setLastNotificationAttemptAt(LocalDateTime.now().minusMinutes(10));
        when(incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"))).thenReturn(List.of(first, second));
        // Not shutting down for the initial check, but shutdown begins by the time the loop reaches its first iteration's own check.
        when(shutdownState.isShuttingDown()).thenReturn(false, true);

        service.retryPendingDeliveries();

        verify(incidentService, never()).attemptDelivery(any());
    }
}
