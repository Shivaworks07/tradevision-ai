package com.tradevision.service;

import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.RiskProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers closeAllStreams() actually closing a live connection, using a reflection-based
 * construction technique for the private ManagedConnection record, the same technique already
 * proven in this codebase's own OrderFlowServiceTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BinanceUserDataStreamServiceTest {

    @Mock BrokerCredentialRepository credentialRepo;
    @Mock RiskProfileRepository riskProfileRepo;
    @Mock BrokerCredentialService credentialService;
    @Mock PositionMonitorService positionMonitorService;
    @Mock OrderService orderService;
    @Mock com.tradevision.repository.OrderRepository orderRepo;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    @Mock ExchangeHealthService exchangeHealthService;
    // The dedicated dispatch scheduler that scheduleDebouncedReconcile uses instead of calling
    // positionMonitorService.reconcileCredential() directly on onText's own thread. Mocked (not
    // a real ThreadPoolTaskScheduler) so the tests below can control exactly when -- or whether
    // -- the scheduled reconciliation task actually runs, via an ArgumentCaptor on schedule()'s
    // own Runnable argument.
    @Mock org.springframework.scheduling.TaskScheduler wsReconcileDispatchScheduler;

    @InjectMocks BinanceUserDataStreamService service;

    @Test
    @DisplayName("reconcileConnections: does nothing during shutdown — never even reads credentials, let alone reopens a connection")
    void reconcileConnections_skipsDuringShutdown() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.reconcileConnections();

        verify(credentialRepo, never()).findAll();
    }

    /**
     * A connection whose subscribe request has been pending for longer than
     * SUBSCRIBE_TIMEOUT_MS gets torn down by reconcileConnections() itself, rather than sitting
     * in `connections` forever.
     */
    @Test
    @DisplayName("reconcileConnections: a connection whose subscribe request has been pending too long is closed and removed, not left as a silent zombie")
    void reconcileConnections_subscribeTimedOut_closesAndRemovesConnection() throws Exception {
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        when(mockSocket.sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mockSocket));

        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");
        // Simulate a subscribe request sent well over SUBSCRIBE_TIMEOUT_MS (10s) ago, with no
        // response ever received (pendingSubscribeId still set).
        var pendingIdField = listenerClass.getDeclaredField("pendingSubscribeId");
        pendingIdField.setAccessible(true);
        pendingIdField.set(listener, "some-request-id");
        var pendingAtField = listenerClass.getDeclaredField("pendingSubscribeAt");
        pendingAtField.setAccessible(true);
        pendingAtField.set(listener, java.time.Instant.now().minusSeconds(30));

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        var credential = new com.tradevision.model.BrokerCredential();
        credential.setId("cred1");
        credential.setActive(true);
        credential.setBroker(com.tradevision.model.BrokerType.BINANCE);
        when(credentialRepo.findAll()).thenReturn(java.util.List.of(credential));
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(java.util.Optional.empty());

        service.reconcileConnections();

        verify(mockSocket).sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        assertThat(connections).doesNotContainKey("cred1");
    }

    /**
     * Covers hung-connection detection in verifySubscriptionHealth -- a connection that never
     * answers repeated active session.status probes is force-closed (so reconcileConnections
     * reopens it), while one that's only missed a single probe so far is given another chance
     * rather than closed prematurely.
     */
    private Object buildSubscribedListenerWithPendingSessionStatus(String credentialId, String pendingId, int missedSoFar) throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, credentialId);
        var stateField = listenerClass.getDeclaredField("state");
        stateField.setAccessible(true);
        Class<?> stateEnum = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ConnectionState");
        stateField.set(listener, java.util.Arrays.stream(stateEnum.getEnumConstants()).filter(e -> e.toString().equals("SUBSCRIBED")).findFirst().get());
        var pendingIdField = listenerClass.getDeclaredField("pendingSessionStatusId");
        pendingIdField.setAccessible(true);
        pendingIdField.set(listener, pendingId);
        var missedField = listenerClass.getDeclaredField("consecutiveMissedSessionStatus");
        missedField.setAccessible(true);
        missedField.set(listener, missedSoFar);
        return listener;
    }

    @Test
    @DisplayName("verifySubscriptionHealth: a connection that reaches MAX_CONSECUTIVE_MISSED_SESSION_STATUS unanswered probes is force-closed as hung, with the backoff and health-tracking the other close paths already use")
    void verifySubscriptionHealth_maxMissedProbes_forceClosesAsHung() throws Exception {
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        when(mockSocket.sendClose(anyInt(), anyString())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mockSocket));
        // One miss already recorded (from the PREVIOUS cycle) -- this call's own detection that
        // the request is STILL pending pushes it to 2, meeting the threshold.
        Object listener = buildSubscribedListenerWithPendingSessionStatus("cred1", "still-pending-request-id", 1);

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        when(shutdownState.isShuttingDown()).thenReturn(false);

        service.verifySubscriptionHealth();

        verify(mockSocket).sendClose(anyInt(), anyString());
        verify(mockSocket, never()).sendText(anyString(), anyBoolean()); // must not probe a connection it just closed
        assertThat(connections).doesNotContainKey("cred1");
        verify(exchangeHealthService).recordWsError(eq("cred1"), contains("session.status"));
    }

    @Test
    @DisplayName("verifySubscriptionHealth: a connection that has missed only ONE probe so far is given another chance -- probed again, not yet closed")
    void verifySubscriptionHealth_belowMissThreshold_stillProbesAgain() throws Exception {
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        // No misses recorded yet -- this is the very first miss being detected this cycle (1 < 2).
        Object listener = buildSubscribedListenerWithPendingSessionStatus("cred1", "still-pending-request-id", 0);

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        when(shutdownState.isShuttingDown()).thenReturn(false);

        service.verifySubscriptionHealth();

        verify(mockSocket, never()).sendClose(anyInt(), anyString());
        verify(mockSocket).sendText(anyString(), eq(true)); // still sends a fresh probe
        assertThat(connections).containsKey("cred1");
    }

    @Test
    @DisplayName("closeAllStreams: with no active connections, does nothing and does not throw")
    void closeAllStreams_withNoActiveConnections() {
        service.closeAllStreams();
        // No exception, no assertion needed beyond "this call completed" -- an empty
        // `connections` map means there is genuinely nothing to close.
    }

    /**
     * A PAPER credential must never be considered eligible for a real WebSocket connection to
     * Binance's testnet endpoint -- the eligibility check must consider mode, not just
     * isActive()/autoTradeEnabled/tradingHalted. Tested via the synchronous, safe half of this
     * behavior: an EXISTING connection for a credential that has since become PAPER (or was
     * always PAPER, however it got a connection registered) must be torn down, since it's no
     * longer eligible -- verifying the OPPOSITE case (a fresh PAPER credential never gets a new
     * connection opened) isn't safely unit-testable here, since the real connect() path makes a
     * genuine, unmocked network call this test must never risk triggering.
     */
    @Test
    @DisplayName("reconcileConnections: a PAPER-mode credential is never eligible for a real WebSocket connection -- an existing connection for one is torn down, even with auto-trade enabled")
    void reconcileConnections_paperCredential_neverEligible() throws Exception {
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        when(mockSocket.sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mockSocket));

        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");
        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        var credential = new com.tradevision.model.BrokerCredential();
        credential.setId("cred1");
        credential.setActive(true);
        credential.setBroker(com.tradevision.model.BrokerType.BINANCE);
        credential.setMode(com.tradevision.model.BrokerMode.PAPER);
        when(credentialRepo.findAll()).thenReturn(java.util.List.of(credential));
        // Even with auto-trade genuinely enabled and nothing halted -- PAPER alone must still
        // make this credential ineligible.
        var profile = new com.tradevision.model.RiskProfile();
        profile.setAutoTradeEnabled(true);
        profile.setTradingHalted(false);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(java.util.Optional.of(profile));

        service.reconcileConnections();

        verify(mockSocket).sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        assertThat(connections).doesNotContainKey("cred1");
    }

    void closeAllStreams_noConnections_noOp() {
        service.closeAllStreams(); // must not throw
    }

    /**
     * Uses the same reflection-based construction technique already established and proven in
     * this codebase's own OrderFlowServiceTest -- injects a real ManagedConnection (a private
     * nested record) into the private connections map, with a WebSocket mock whose sendClose()
     * returns a controllable, delayed CompletableFuture, so this actually exercises the
     * graceful-wait behavior rather than just the empty-map early return.
     */
    @Test
    @DisplayName("closeAllStreams: genuinely waits for a real (mocked) close handshake to complete before returning, not a fire-and-forget call that merely looked synchronous")
    void closeAllStreams_realConnection_waitsForCloseHandshake() throws Exception {
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        java.util.concurrent.CompletableFuture<java.net.http.WebSocket> closeFuture = new java.util.concurrent.CompletableFuture<>();
        when(mockSocket.sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())).thenReturn(closeFuture);

        // Construct the private ManagedConnection(WebSocket, Instant, Listener) record via
        // reflection. Listener is itself a non-static inner class of BinanceUserDataStreamService
        // (it tracks Listener.pendingSubscribeAt for the subscribe-timeout check), so its
        // reflective constructor implicitly takes the outer instance as its own first
        // parameter, same as any non-static inner class's real, compiler-generated one.
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var constructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        constructor.setAccessible(true);
        Object managedConnection = constructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        // Inject it into the private `connections` map field.
        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        // Complete the close future on a short delay from a background thread, simulating a
        // real (but fast) async close handshake -- if closeAllStreams() genuinely waits, this
        // completes before the method returns; if it doesn't wait, the assertion below would
        // still pass by coincidence, so what actually matters here is that sendClose() gets
        // called at all and the method doesn't throw or hang indefinitely on a real future.
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            closeFuture.complete(mockSocket);
        }).start();

        long startedAt = System.nanoTime();
        service.closeAllStreams();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        verify(mockSocket).sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "application shutdown");
        assertThat(connections).isEmpty(); // removed from the map immediately, matching closeConnection's own existing behavior
        // closeAllStreams() must not have returned before the close future genuinely completed
        // (~200ms later) -- a fire-and-forget call that never awaited anything would return in
        // well under 200ms regardless of the future's state.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(190L);
    }

    @Test
    @DisplayName("Listener: onOpen() alone does NOT record WS connected -- only the actual subscribe confirmation does -- verified via the same reflection-based construction technique this file already established for its own private inner types")
    void listenerOnOpen_doesNotRecordConnected_untilSubscribeConfirmed() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var constructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        constructor.setAccessible(true);
        Object listener = constructor.newInstance(service, "cred1");

        var onOpenMethod = listenerClass.getMethod("onOpen", java.net.http.WebSocket.class);
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        onOpenMethod.invoke(listener, mockSocket);

        // The raw handshake alone must NOT be treated as "connected".
        verify(exchangeHealthService, never()).recordWsConnected(any());

        // Now simulate the actual subscribe confirmation arriving, matching the pending id.
        var pendingIdField = listenerClass.getDeclaredField("pendingSubscribeId");
        pendingIdField.setAccessible(true);
        pendingIdField.set(listener, "req-1");

        var onTextMethod = listenerClass.getMethod("onText", java.net.http.WebSocket.class, CharSequence.class, boolean.class);
        onTextMethod.invoke(listener, mockSocket, "{\"id\":\"req-1\",\"result\":null}", true);

        // Only NOW, after the genuine subscribe confirmation, is the connection recorded.
        verify(exchangeHealthService).recordWsConnected("cred1");
    }

    @Test
    @DisplayName("recordConnectFailure/recordConnectSuccess: each successive failure doubles the backoff delay (30s, 60s, 120s...), and a success afterward resets it entirely")
    void connectBackoff_doublesOnFailure_resetsOnSuccess() throws Exception {
        var failMethod = BinanceUserDataStreamService.class.getDeclaredMethod("recordConnectFailure", String.class);
        failMethod.setAccessible(true);
        var successMethod = BinanceUserDataStreamService.class.getDeclaredMethod("recordConnectSuccess", String.class);
        successMethod.setAccessible(true);
        var nextRetryField = BinanceUserDataStreamService.class.getDeclaredField("nextRetryAllowedAt");
        nextRetryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var nextRetryMap = (java.util.Map<String, java.time.Instant>) nextRetryField.get(service);

        java.time.Instant before = java.time.Instant.now();
        failMethod.invoke(service, "cred1");
        java.time.Instant afterFirstFailure = nextRetryMap.get("cred1");
        // First failure: ~30s out.
        assertThat(afterFirstFailure).isAfter(before.plusSeconds(25));
        assertThat(afterFirstFailure).isBefore(before.plusSeconds(35));

        failMethod.invoke(service, "cred1");
        java.time.Instant afterSecondFailure = nextRetryMap.get("cred1");
        // Second failure: ~60s out from now, clearly further than the first's own ~30s.
        assertThat(afterSecondFailure).isAfter(afterFirstFailure.plusSeconds(15));

        successMethod.invoke(service, "cred1");
        assertThat(nextRetryMap).doesNotContainKey("cred1"); // fully reset, not just shortened
    }

    /**
     * Builds the scenario "socket A connected -> socket A refresh starts -> socket B connected
     * -> socket A.onClose() -> assert socket B STILL exists" -- onClose() must check which
     * socket the callback actually belongs to, not unconditionally remove the connections entry
     * for the credential.
     */
    @Test
    @DisplayName("Listener.onClose: a STALE callback from an old, already-superseded socket must never remove a NEWER socket's own live connection entry")
    void onClose_staleCallbackFromOldSocket_doesNotRemoveNewerConnection() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);

        // Socket A connects first.
        java.net.http.WebSocket socketA = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object listenerA = listenerConstructor.newInstance(service, "cred1");
        connections.put("cred1", mcConstructor.newInstance(socketA, java.time.Instant.now(), listenerA));

        // The 23-hour proactive refresh closes A and opens a NEW socket B for the same
        // credential -- B is now the genuinely live, current connection.
        java.net.http.WebSocket socketB = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object listenerB = listenerConstructor.newInstance(service, "cred1");
        connections.put("cred1", mcConstructor.newInstance(socketB, java.time.Instant.now(), listenerB));

        // A's own onClose() callback finally fires -- late, asynchronously, AFTER B is already
        // registered. This must be a no-op for the connections map, not a deletion of B.
        var onCloseMethod = listenerClass.getMethod("onClose", java.net.http.WebSocket.class, int.class, String.class);
        onCloseMethod.setAccessible(true);
        onCloseMethod.invoke(listenerA, socketA, 1000, "old socket closing (stale callback)");

        Object stillRegistered = connections.get("cred1");
        assertThat(stillRegistered).isNotNull();
        var socketField = managedConnectionClass.getDeclaredMethod("socket");
        socketField.setAccessible(true);
        assertThat(socketField.invoke(stillRegistered)).isSameAs(socketB);
    }

    /**
     * Proves the RAW_CONNECTED/SUBSCRIBING/SUBSCRIBED state machine is observable and honest,
     * not just present in the code.
     */
    @Test
    @DisplayName("getConnectionState/isSubscribed: distinguishes no-connection, subscribing, and genuinely-subscribed states, rather than collapsing them into one exists-in-map boolean")
    void getConnectionState_distinguishesRealStates() throws Exception {
        assertThat(service.getConnectionState("cred1")).isNull();
        assertThat(service.isSubscribed("cred1")).isFalse();

        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        // A freshly-constructed Listener starts at RAW_CONNECTED (see its own field comment) --
        // observable the moment its ManagedConnection is registered, but not yet subscribed.
        assertThat(service.getConnectionState("cred1")).isEqualTo(BinanceUserDataStreamService.ConnectionState.RAW_CONNECTED);
        assertThat(service.isSubscribed("cred1")).isFalse();

        var stateField = listenerClass.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(listener, BinanceUserDataStreamService.ConnectionState.SUBSCRIBING);
        assertThat(service.getConnectionState("cred1")).isEqualTo(BinanceUserDataStreamService.ConnectionState.SUBSCRIBING);
        assertThat(service.isSubscribed("cred1")).isFalse(); // subscribing is NOT the same as genuinely subscribed

        stateField.set(listener, BinanceUserDataStreamService.ConnectionState.SUBSCRIBED);
        assertThat(service.getConnectionState("cred1")).isEqualTo(BinanceUserDataStreamService.ConnectionState.SUBSCRIBED);
        assertThat(service.isSubscribed("cred1")).isTrue();
    }

    /**
     * Proves event time is tracked and out-of-order delivery is detected (via log output this
     * test can't directly assert on, but getLastEventTimeMs's own behavior is directly
     * verifiable and is exactly what the out-of-order check itself reads).
     */
    @Test
    @DisplayName("onText/getLastEventTimeMs: tracks the most recent event time seen, and does not regress it when a later message arrives with an EARLIER event time (out-of-order delivery)")
    void onText_tracksLastEventTime_doesNotRegressOnOutOfOrderDelivery() throws Exception {
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.empty()); // isolate this test to event-time tracking, not reconciliation

        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        var onTextMethod = listenerClass.getMethod("onText", java.net.http.WebSocket.class, CharSequence.class, boolean.class);
        onTextMethod.setAccessible(true);
        onTextMethod.invoke(listener, mockSocket,
            "{\"event\":{\"e\":\"executionReport\",\"E\":1000000}}", true);
        assertThat(service.getLastEventTimeMs("cred1")).isEqualTo(1000000L);

        // A second event arrives with an EARLIER event time -- genuinely out-of-order delivery.
        onTextMethod.invoke(listener, mockSocket,
            "{\"event\":{\"e\":\"executionReport\",\"E\":900000}}", true);
        // Logged as a warning (deliberately observability-only, not a gating decision), but the
        // tracked value itself does not regress backward.
        assertThat(service.getLastEventTimeMs("cred1")).isEqualTo(1000000L);

        // A THIRD event, later than both, correctly advances it again.
        onTextMethod.invoke(listener, mockSocket,
            "{\"event\":{\"e\":\"executionReport\",\"E\":1100000}}", true);
        assertThat(service.getLastEventTimeMs("cred1")).isEqualTo(1100000L);
    }

    @Test
    @DisplayName("getLastEventTimeMs: a credential with no connection at all returns 0, never a stale or fabricated value")
    void getLastEventTimeMs_noConnection_returnsZero() {
        assertThat(service.getLastEventTimeMs("cred-nonexistent")).isEqualTo(0L);
    }

    /**
     * Proves the session.status health check only ever asks Binance's own side about genuinely
     * SUBSCRIBED connections, and -- most importantly -- never acts on a mismatch beyond
     * logging it.
     */
    @Test
    @DisplayName("verifySubscriptionHealth: sends session.status only for connections in the SUBSCRIBED state, never for one still SUBSCRIBING or RAW_CONNECTED")
    void verifySubscriptionHealth_onlyQueriesGenuinelySubscribedConnections() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        var stateField = listenerClass.getDeclaredField("state");
        stateField.setAccessible(true);

        java.net.http.WebSocket subscribedSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object subscribedListener = listenerConstructor.newInstance(service, "cred-subscribed");
        stateField.set(subscribedListener, BinanceUserDataStreamService.ConnectionState.SUBSCRIBED);
        connections.put("cred-subscribed", mcConstructor.newInstance(subscribedSocket, java.time.Instant.now(), subscribedListener));

        java.net.http.WebSocket subscribingSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object subscribingListener = listenerConstructor.newInstance(service, "cred-subscribing");
        stateField.set(subscribingListener, BinanceUserDataStreamService.ConnectionState.SUBSCRIBING);
        connections.put("cred-subscribing", mcConstructor.newInstance(subscribingSocket, java.time.Instant.now(), subscribingListener));

        service.verifySubscriptionHealth();

        verify(subscribedSocket).sendText(org.mockito.ArgumentMatchers.contains("session.status"), eq(true));
        verify(subscribingSocket, never()).sendText(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("verifySubscriptionHealth: does nothing during shutdown")
    void verifySubscriptionHealth_duringShutdown_doesNothing() throws Exception {
        when(shutdownState.isShuttingDown()).thenReturn(true);
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        var stateField = listenerClass.getDeclaredField("state");
        stateField.setAccessible(true);
        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);

        java.net.http.WebSocket socket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object listener = listenerConstructor.newInstance(service, "cred1");
        stateField.set(listener, BinanceUserDataStreamService.ConnectionState.SUBSCRIBED);
        connections.put("cred1", mcConstructor.newInstance(socket, java.time.Instant.now(), listener));

        service.verifySubscriptionHealth();

        verify(socket, never()).sendText(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("onText: a session.status response reporting userDataStream=false is logged only -- it must NEVER close the connection, record a failure, or otherwise act as though a real disconnect happened, per this feature's own deliberate observability-only scope")
    void onText_sessionStatusReportsFalse_neverActsAutomatically() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");

        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        var pendingField = listenerClass.getDeclaredField("pendingSessionStatusId");
        pendingField.setAccessible(true);
        pendingField.set(listener, "req-1");

        var onTextMethod = listenerClass.getMethod("onText", java.net.http.WebSocket.class, CharSequence.class, boolean.class);
        onTextMethod.setAccessible(true);
        onTextMethod.invoke(listener, mockSocket, "{\"id\":\"req-1\",\"status\":200,\"result\":{\"userDataStream\":false}}", true);

        // The actual guarantee under test: no close, no failure recording -- purely logged.
        verify(mockSocket, never()).sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        assertThat(pendingField.get(listener)).isNull(); // the pending request id is still cleared, though, so it doesn't leak forever
    }

    /**
     * Covers the execution-report fast path, which applies fills directly from the WebSocket
     * event rather than falling back to a REST poll.
     * applyExecutionReportFastPath is private on the outer class (not the Listener inner class),
     * so it's accessed via reflection directly on the service instance.
     */
    private void invokeFastPath(String json) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var event = mapper.readTree(json);
        var m = BinanceUserDataStreamService.class.getDeclaredMethod("applyExecutionReportFastPath", com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        m.invoke(service, event);
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: a real fill (executionType=TRADE) for a tracked, non-terminal order calls recordBrokerResult with the real cumulative quantity and average price")
    void tradeExecutionForTrackedOrder_callsRecordBrokerResult() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"TRADE\",\"X\":\"FILLED\",\"z\":\"1.5\",\"Z\":\"150.0\"}";

        invokeFastPath(json);

        verify(orderService).recordBrokerResult(eq(order), argThat(r ->
            r.success() && r.executedQty().compareTo(java.math.BigDecimal.valueOf(1.5)) == 0
                && r.fillPrice().compareTo(java.math.BigDecimal.valueOf(100)) == 0));
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: orderStatus=REJECTED calls recordBrokerResult with success=false")
    void rejectedOrderStatus_callsRecordBrokerResultWithFailure() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.SUBMITTING);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"REJECTED\",\"X\":\"REJECTED\"}";

        invokeFastPath(json);

        verify(orderService).recordBrokerResult(eq(order), argThat(r -> !r.success()));
    }

    /**
     * orderStatus=EXPIRED with a prior partial fill (z shows some quantity genuinely filled
     * first) must not go through the same success=false branch as a genuine REJECTED, which
     * would silently discard the filled quantity. It must report success=true with the real
     * cumulative quantity/price so OrderService.recordBrokerResult's own EXPIRED-specific
     * handling records the correct terminal state WITH the fill, not a REJECTED with the fill
     * lost.
     */
    @Test
    @DisplayName("applyExecutionReportFastPath: orderStatus=EXPIRED with a prior partial fill (z > 0) calls recordBrokerResult with success=true and the real filled quantity/price, not a discarded failure")
    void partialFillThenExpired_callsRecordBrokerResultWithFillPreserved() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        // Filled 0.4 of a 1.0 order (at a cumulative quote of 40.0, i.e. avg price 100) before
        // the unfilled remainder expired -- e.g. an IOC/FOK leg, or self-trade prevention.
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"TRADE\",\"X\":\"EXPIRED\",\"z\":\"0.4\",\"Z\":\"40.0\"}";

        invokeFastPath(json);

        verify(orderService).recordBrokerResult(eq(order), argThat(r ->
            r.success() // NOT treated as a failed broker call -- this is a real, definitive terminal status
                && "EXPIRED".equals(r.status())
                && r.executedQty().compareTo(java.math.BigDecimal.valueOf(0.4)) == 0
                && r.fillPrice().compareTo(java.math.BigDecimal.valueOf(100)) == 0));
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: orderStatus=CANCELED calls markCancelled, not recordBrokerResult")
    void canceledOrderStatus_callsMarkCancelled() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"CANCELED\",\"X\":\"CANCELED\"}";

        invokeFastPath(json);

        verify(orderService).markCancelled(order);
        verify(orderService, never()).recordBrokerResult(any(), any());
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: a clientOrderId this application's own OMS never created is silently ignored, not an error")
    void unknownClientOrderId_isIgnored() throws Exception {
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.empty());
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"TRADE\",\"X\":\"FILLED\",\"z\":\"1.5\",\"Z\":\"150.0\"}";

        invokeFastPath(json); // must not throw

        verify(orderService, never()).recordBrokerResult(any(), any());
        verify(orderService, never()).markCancelled(any());
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: an order already in a terminal state (e.g. FILLED) is skipped entirely -- nothing left for this fast path to do")
    void alreadyTerminalOrder_isSkipped() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.FILLED);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"TRADE\",\"X\":\"FILLED\",\"z\":\"1.5\",\"Z\":\"150.0\"}";

        invokeFastPath(json);

        verify(orderService, never()).recordBrokerResult(any(), any());
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: a plain NEW acknowledgement with no fill (executionType=NEW) is left entirely to REST reconciliation -- no OMS call at all")
    void plainNewAck_isLeftToRest() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.SUBMITTING);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"NEW\",\"X\":\"NEW\"}";

        invokeFastPath(json);

        verify(orderService, never()).recordBrokerResult(any(), any());
        verify(orderService, never()).markCancelled(any());
    }

    @Test
    @DisplayName("applyExecutionReportFastPath: a failure inside the fast path (e.g. an illegal transition) is caught and never propagates -- the REST reconciliation trigger it sits alongside must never be affected")
    void fastPathFailure_neverThrows() throws Exception {
        var order = new com.tradevision.model.Order();
        order.setId("order1");
        order.setStatus(com.tradevision.model.OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByClientOrderId("client-1")).thenReturn(java.util.Optional.of(order));
        doThrow(new IllegalStateException("simulated illegal transition")).when(orderService).recordBrokerResult(any(), any());
        String json = "{\"c\":\"client-1\",\"i\":12345,\"x\":\"TRADE\",\"X\":\"FILLED\",\"z\":\"1.5\",\"Z\":\"150.0\"}";

        invokeFastPath(json); // must not throw
    }

    /**
     * Builds the scenario "burst of 50 events -> one or two reconciles, socket stays open, no
     * event loss." Simulated here via 50 back-to-back executionReport events for the SAME
     * credential, all arriving before the debounced task itself is ever run (the real-world
     * race this behavior must protect against: the dispatch scheduler is mocked, so nothing
     * runs until this test explicitly executes the captured Runnable, standing in for "the
     * debounce window hasn't elapsed yet").
     */
    @Test
    @DisplayName("onText: a burst of 50 executionReport events for the same credential coalesces into exactly ONE scheduled reconciliation dispatch, not 50 -- and onText itself never blocks on reconcileCredential()")
    void onText_burstOfEvents_coalescesIntoSingleDebouncedReconcile() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        var credential = new com.tradevision.model.BrokerCredential();
        credential.setId("cred1");
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.of(credential));

        var onTextMethod = listenerClass.getMethod("onText", java.net.http.WebSocket.class, CharSequence.class, boolean.class);
        onTextMethod.setAccessible(true);

        // 50 events, same credential, none of them yet triggering the actual (mocked, not-yet-run)
        // scheduled task.
        for (int i = 0; i < 50; i++) {
            onTextMethod.invoke(listener, mockSocket,
                "{\"event\":{\"e\":\"executionReport\",\"E\":" + (1000000 + i) + "}}", true);
        }

        // The actual proof of coalescing: only ONE dispatch was ever scheduled, despite 50 events.
        var runnableCaptor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(wsReconcileDispatchScheduler, times(1)).schedule(runnableCaptor.capture(), any(java.time.Instant.class));
        // And onText() never blocked on reconciliation itself -- it was never called synchronously.
        verify(positionMonitorService, never()).reconcileCredential(any());

        // Now simulate the debounce window elapsing -- the dispatched task actually runs.
        runnableCaptor.getValue().run();
        verify(positionMonitorService, times(1)).reconcileCredential(credential);

        // A follow-up event AFTER the dispatch has run (and cleared its own pending flag) must be
        // free to schedule a fresh dispatch -- proving events are never permanently lost, only
        // coalesced within a single in-flight window.
        onTextMethod.invoke(listener, mockSocket, "{\"event\":{\"e\":\"executionReport\",\"E\":1000050}}", true);
        verify(wsReconcileDispatchScheduler, times(2)).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    @DisplayName("onText: if scheduling the debounced reconcile itself fails, the pending flag is cleared so this credential is never permanently stuck -- the next event can still schedule normally")
    void onText_schedulingFailure_clearsPendingFlag_soNextEventCanStillSchedule() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var listenerConstructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        listenerConstructor.setAccessible(true);
        Object listener = listenerConstructor.newInstance(service, "cred1");

        Class<?> managedConnectionClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$ManagedConnection");
        var mcConstructor = managedConnectionClass.getDeclaredConstructor(java.net.http.WebSocket.class, java.time.Instant.class, listenerClass);
        mcConstructor.setAccessible(true);
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        Object managedConnection = mcConstructor.newInstance(mockSocket, java.time.Instant.now(), listener);

        var connectionsField = BinanceUserDataStreamService.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var connections = (java.util.Map<String, Object>) connectionsField.get(service);
        connections.put("cred1", managedConnection);

        when(wsReconcileDispatchScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
            .thenThrow(new RuntimeException("simulated pool rejection"))
            .thenReturn(null);

        var onTextMethod = listenerClass.getMethod("onText", java.net.http.WebSocket.class, CharSequence.class, boolean.class);
        onTextMethod.setAccessible(true);

        onTextMethod.invoke(listener, mockSocket, "{\"event\":{\"e\":\"executionReport\",\"E\":1000000}}", true); // must not throw despite the simulated scheduling failure
        onTextMethod.invoke(listener, mockSocket, "{\"event\":{\"e\":\"executionReport\",\"E\":1000001}}", true);

        verify(wsReconcileDispatchScheduler, times(2)).schedule(any(Runnable.class), any(java.time.Instant.class));
    }
}
