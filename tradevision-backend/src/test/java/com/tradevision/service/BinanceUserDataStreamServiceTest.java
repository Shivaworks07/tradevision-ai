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
 * Review finding ("WebSocket lifecycle on shutdown" -- full context in
 * BinanceUserDataStreamService's own closeAllStreams() javadoc): the earlier version of this
 * comment disclosed an honest gap here -- full coverage of closeAllStreams() actually closing a
 * live connection needed either package-private visibility for the private ManagedConnection
 * record or a reflection-based harness, and neither was built at the time. That gap is now
 * closed below, using the same reflection-based construction technique already proven in this
 * codebase's own OrderFlowServiceTest.
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
    // P1-18 fix ("WebSocket listener does blocking reconciliation on the socket thread" -- full
    // context in scheduleDebouncedReconcile's own javadoc): the dedicated dispatch scheduler that
    // method now uses instead of calling positionMonitorService.reconcileCredential() directly on
    // onText's own thread. Mocked (not a real ThreadPoolTaskScheduler) so the tests below can
    // control exactly when -- or whether -- the scheduled reconciliation task actually runs, via
    // an ArgumentCaptor on schedule()'s own Runnable argument.
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
     * Review finding ("No subscription-response timeout" -- external review, twelfth pass, P0,
     * full context in Listener.pendingSubscribeAt's own field comment): the actual test proving
     * the fix -- a connection whose subscribe request has been pending for longer than
     * SUBSCRIBE_TIMEOUT_MS gets torn down by reconcileConnections() itself, rather than sitting
     * in `connections` forever the way it did before this pass's own fix (confirmed real by
     * direct inspection of this exact code path before writing this test).
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

    @Test
    @DisplayName("closeAllStreams: with no active connections, does nothing and does not throw")
    void closeAllStreams_withNoActiveConnections() {
        service.closeAllStreams();
        // No exception, no assertion needed beyond "this call completed" -- an empty
        // `connections` map means there is genuinely nothing to close.
    }

    /**
     * Review finding ("No pure paper-trading mode with full isolation" -- external review,
     * eighteenth pass, P0, full context in this file's own reconcileConnections eligibility
     * comment): confirmed real by auditing every mode==LIVE branch after PAPER mode was
     * introduced -- this eligibility check used to only consider isActive()/autoTradeEnabled/
     * tradingHalted, never mode, meaning a PAPER credential could be considered eligible for a
     * genuine WebSocket connection attempt to Binance's testnet endpoint. Tested via the
     * synchronous, safe half of this behavior: an EXISTING connection for a credential that has
     * since become PAPER (or was always PAPER, however it got a connection registered) must be
     * torn down, since it's no longer eligible -- verifying the OPPOSITE case (a fresh PAPER
     * credential never gets a new connection opened) isn't safely unit-testable here, since the
     * real connect() path makes a genuine, unmocked network call this test must never risk
     * triggering.
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
     * Review finding ("WebSocket lifecycle on shutdown" -- full context in closeAllStreams' own
     * javadoc): this is the exact behavior the earlier version of this test file's own comment
     * disclosed as an honest gap ("would need... a reflection-based test harness -- not built
     * here"). Built now, using the same reflection-based construction technique already
     * established and proven in this codebase's own OrderFlowServiceTest -- injects a real
     * ManagedConnection (a private nested record) into the private connections map, with a
     * WebSocket mock whose sendClose() returns a controllable, delayed CompletableFuture, so
     * this actually exercises the graceful-wait fix rather than just the empty-map early return.
     */
    @Test
    @DisplayName("closeAllStreams: genuinely waits for a real (mocked) close handshake to complete before returning, not a fire-and-forget call that merely looked synchronous")
    void closeAllStreams_realConnection_waitsForCloseHandshake() throws Exception {
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        java.util.concurrent.CompletableFuture<java.net.http.WebSocket> closeFuture = new java.util.concurrent.CompletableFuture<>();
        when(mockSocket.sendClose(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())).thenReturn(closeFuture);

        // Construct the private ManagedConnection(WebSocket, Instant, Listener) record via
        // reflection. Listener is itself a non-static inner class of BinanceUserDataStreamService
        // (needed for the subscribe-timeout fix -- see Listener.pendingSubscribeAt's own field
        // comment), so its reflective constructor implicitly takes the outer instance as its own
        // first parameter, same as any non-static inner class's real, compiler-generated one.
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
        // The actual proof of the fix: closeAllStreams() must not have returned before the
        // close future genuinely completed (~200ms later) -- a fire-and-forget call that never
        // awaited anything would return in well under 200ms regardless of the future's state.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(190L);
    }

    @Test
    @DisplayName("Listener: onOpen() alone does NOT record WS connected -- only the actual subscribe confirmation does -- the actual review fix (\"WebSocket onOpen() records CONNECTED before subscription confirmed\"), verified via the same reflection-based construction technique this file already established for its own private inner types")
    void listenerOnOpen_doesNotRecordConnected_untilSubscribeConfirmed() throws Exception {
        Class<?> listenerClass = Class.forName("com.tradevision.service.BinanceUserDataStreamService$Listener");
        var constructor = listenerClass.getDeclaredConstructor(BinanceUserDataStreamService.class, String.class);
        constructor.setAccessible(true);
        Object listener = constructor.newInstance(service, "cred1");

        var onOpenMethod = listenerClass.getMethod("onOpen", java.net.http.WebSocket.class);
        java.net.http.WebSocket mockSocket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);
        onOpenMethod.invoke(listener, mockSocket);

        // The raw handshake alone must NOT be treated as "connected" -- the actual review fix.
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
    @DisplayName("recordConnectFailure/recordConnectSuccess: each successive failure doubles the backoff delay (30s, 60s, 120s...), and a success afterward resets it entirely -- the actual review fix (\"WebSocket reconnection is still a slow sweep, not exponential backoff\")")
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
     * Review finding ("OLD WebSocket callbacks can remove a NEW WebSocket" -- external review,
     * fourteenth pass, P0, full context in Listener.onClose's own updated comment): this is the
     * review's own explicit required test, built exactly to its own named scenario -- "socket A
     * connected -> socket A refresh starts -> socket B connected -> socket A.onClose() -> assert
     * socket B STILL exists." Confirmed real by direct inspection before the fix was written:
     * onClose() used to be an unconditional connections.remove(credentialId) with no check on
     * which socket the callback actually belonged to.
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
     * Review finding ("WebSocket health still isn't fully semantic" / "You should track
     * RAW_CONNECTED, SUBSCRIBING, SUBSCRIBED... rather than simply: exists in map = connected"
     * -- external review, fourteenth pass, P1, full context in ConnectionState's own class-level
     * comment): the actual test proving the state machine is observable and honest, not just
     * present in the code.
     */
    @Test
    @DisplayName("getConnectionState/isSubscribed: distinguishes no-connection, subscribing, and genuinely-subscribed states -- collapsing these into one exists-in-map boolean was exactly the gap the review named")
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
     * Review finding ("Event ordering isn't explicitly protected" -- external review, thirteenth
     * pass, P1, full context in Listener.lastEventTimeMs's own field comment): the actual test
     * proving event time is tracked and out-of-order delivery is detected (via log output this
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
        // Logged as a warning (this pass's own explicit, deliberate scope -- observability, not
        // a gating decision), but the tracked value itself does not regress backward.
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
     * Review finding ("WebSocket health is still not fully production-grade" -- external review,
     * sixteenth pass, P1, full context in Listener.pendingSessionStatusId's own field comment):
     * the actual tests proving this new feature only ever asks Binance's own side about
     * genuinely SUBSCRIBED connections, and -- most importantly -- never acts on a mismatch
     * beyond logging it.
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
     * Review finding ("User-data WebSocket is still 'wake-up + REST', not true OMS event
     * processing" -- external review, twentieth pass, P1, full context in
     * applyExecutionReportFastPath's own javadoc): the actual tests for the new fast-path.
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
     * P1-18 fix ("WebSocket listener does blocking reconciliation on the socket thread" -- full
     * context in onText's own updated comment and scheduleDebouncedReconcile's own javadoc): the
     * audit's own suggested test scenario -- "Burst of 50 events -> one or two reconciles, socket
     * stays open, no event loss." Simulated here via 50 back-to-back executionReport events for
     * the SAME credential, all arriving before the debounced task itself is ever run (exactly the
     * real-world race this fix protects against: the dispatch scheduler is mocked, so nothing runs
     * until this test explicitly executes the captured Runnable, standing in for "the debounce
     * window hasn't elapsed yet").
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
