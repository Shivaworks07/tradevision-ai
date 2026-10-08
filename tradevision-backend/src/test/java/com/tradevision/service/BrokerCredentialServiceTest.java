package com.tradevision.service;

import com.tradevision.dto.BrokerCredentialResponse;
import com.tradevision.dto.ConnectBrokerRequest;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.repository.BrokerAuditLogRepository;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.AccountPermissions;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests BrokerCredentialService's two-step LIVE connect flow: a genuinely separate credential
 * row, validated against Binance's actual LIVE endpoint, never inheriting a TESTNET-validated
 * key.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrokerCredentialServiceTest {

    @Mock BrokerCredentialRepository credentialRepo;
    @Mock BrokerAuditLogRepository auditRepo;
    @Mock com.tradevision.service.AuditChainService auditChainService;
    @Mock CredentialEncryptionService encryption;
    @Mock BrokerAdapter adapter;
    @Mock com.tradevision.repository.PaperOcoRepository paperOcoRepo;
    @Mock com.tradevision.repository.PaperAccountBalanceRepository paperAccountBalanceRepo;
    @Mock com.tradevision.repository.PositionRepository positionRepo;
    @Mock com.tradevision.repository.OrderRepository orderRepo;
    @Mock com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    // requestLiveConnect/confirmLiveConnect go through this repo instead of an instance-local
    // map -- backed here by a real in-memory map (see setup() below) so this mock behaves
    // statefully across the two calls, exactly like a real repository would.
    @Mock com.tradevision.repository.PendingLiveConnectRepository pendingLiveConnectRepo;
    // confirmLiveConnect calls this synchronously before persisting -- an unstubbed mock's void
    // method already no-ops (Mockito's own real default), so every existing confirmLiveConnect
    // test here continues to represent "this user has an alert channel configured". A test that
    // specifically wants to exercise the "no alert channel" refusal stubs this to throw instead.
    @Mock com.tradevision.config.AlertChannelStartupGuard alertChannelStartupGuard;
    // confirmLiveConnect/confirmApiKeyRotation call verifyStepUpOtp synchronously before doing
    // anything else -- an unstubbed mock's void method already no-ops (Mockito's own real
    // default), so every existing confirm test here continues to represent "this OTP check
    // passed". A test that specifically wants to exercise the "bad step-up OTP" refusal stubs
    // this to throw instead.
    @Mock AuthService authService;
    private final java.util.Map<String, com.tradevision.model.PendingLiveConnect> fakePendingLiveConnectStore = new java.util.HashMap<>();

    @InjectMocks BrokerCredentialService service;

    private ConnectBrokerRequest testnetReq() {
        ConnectBrokerRequest req = new ConnectBrokerRequest();
        req.setBroker(BrokerType.BINANCE);
        req.setApiKey("testnet-key");
        req.setApiSecret("testnet-secret");
        req.setMode(BrokerMode.TESTNET);
        return req;
    }

    private ConnectBrokerRequest liveReq() {
        ConnectBrokerRequest req = new ConnectBrokerRequest();
        req.setBroker(BrokerType.BINANCE);
        req.setApiKey("live-key");
        req.setApiSecret("live-secret");
        req.setMode(BrokerMode.LIVE);
        return req;
    }

    /** A safe apiRestrictions response -- IP-restricted, no withdrawal/transfer rights, spot trading enabled. */
    private static com.tradevision.service.broker.dto.ApiKeyRestrictions safeApiKeyRestrictions() {
        return new com.tradevision.service.broker.dto.ApiKeyRestrictions(true, false, false, false, true);
    }

    @BeforeEach
    void setup() {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(encryption.encrypt(any())).thenAnswer(i -> "enc(" + i.getArguments()[0] + ")");
        // BrokerCredentialService calls the AAD-context-aware 2-arg overload for every real
        // apiKey/apiSecret encryption -- stubbed the same way as the legacy 1-arg overload above.
        when(encryption.encrypt(any(), any())).thenAnswer(i -> "enc(" + i.getArguments()[0] + ")");
        when(credentialRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        // Every LIVE-flow test below also passes through the real, key-level apiRestrictions
        // check -- default it to a safe response so only the tests that actually care about
        // apiRestrictions need to override it.
        when(adapter.getApiKeyRestrictions(any(), any(), eq(BrokerMode.LIVE))).thenReturn(safeApiKeyRestrictions());
        // See the pendingLiveConnectRepo field's own comment above.
        when(pendingLiveConnectRepo.save(any())).thenAnswer(i -> {
            com.tradevision.model.PendingLiveConnect p = i.getArgument(0);
            fakePendingLiveConnectStore.put(p.getToken(), p);
            return p;
        });
        when(pendingLiveConnectRepo.findById(any())).thenAnswer(i ->
            java.util.Optional.ofNullable(fakePendingLiveConnectStore.get((String) i.getArgument(0))));
        doAnswer(i -> fakePendingLiveConnectStore.remove((String) i.getArgument(0)))
            .when(pendingLiveConnectRepo).deleteById(any());
    }

    @Test
    @DisplayName("connect: mode=TESTNET saves immediately, validated against the TESTNET endpoint")
    void connect_testnet_savesImmediately() {
        when(adapter.getAccountPermissions("testnet-key", "testnet-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(true, false, true));

        BrokerCredentialResponse result = service.connect("user1", testnetReq());

        assertThat(result.mode()).isEqualTo(BrokerMode.TESTNET);
        verify(credentialRepo).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // Proves BrokerCredentialService's own side of AAD binding -- the context strings it
    // actually passes to encrypt()/decryptWithLegacyFallback are bound to the specific
    // credential row, not just the field name, and the row id used is the SAME one the saved
    // credential itself ends up with (so the ciphertext and the row it's bound to never drift
    // apart). The cryptographic half (that a mismatched context really does fail decryption) is
    // covered directly against the real CredentialEncryptionService in
    // CredentialEncryptionServiceTest; this mock-based test is about call-site correctness.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("P2 fix (weak AAD binding): connect() encrypts apiKey/apiSecret under a context bound to the SAME id the saved credential itself carries, not a bare field name")
    void connect_encryptsUnderRowScopedContext_matchingTheSavedCredentialId() {
        when(adapter.getAccountPermissions("testnet-key", "testnet-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(true, false, true));
        ArgumentCaptor<BrokerCredential> savedCaptor = ArgumentCaptor.forClass(BrokerCredential.class);
        ArgumentCaptor<String> apiKeyContextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> apiSecretContextCaptor = ArgumentCaptor.forClass(String.class);

        service.connect("user1", testnetReq());

        verify(encryption).encrypt(eq("testnet-key"), apiKeyContextCaptor.capture());
        verify(encryption).encrypt(eq("testnet-secret"), apiSecretContextCaptor.capture());
        verify(credentialRepo).save(savedCaptor.capture());
        String savedId = savedCaptor.getValue().getId();

        assertThat(savedId).isNotBlank(); // pre-generated BEFORE encryption, not left to Mongo to assign after
        assertThat(apiKeyContextCaptor.getValue()).isEqualTo(savedId + ":apiKey");
        assertThat(apiSecretContextCaptor.getValue()).isEqualTo(savedId + ":apiSecret");
    }

    @Test
    @DisplayName("the package-private decrypt(credential, ...) helper asks for the row-scoped context first, with the bare field name only as CredentialEncryptionService's own legacy fallback -- never the other way around")
    void decrypt_requestsRowScopedContext_withBareFieldNameAsLegacyFallbackOnly() {
        BrokerCredential credential = new BrokerCredential();
        credential.setId("cred-123");
        credential.setEncryptedApiKey("enc(the-api-key)");
        credential.setEncryptedApiSecret("enc(the-api-secret)");
        when(encryption.decryptWithLegacyFallback(eq("enc(the-api-key)"), eq("cred-123:apiKey"), eq("apiKey")))
            .thenReturn("the-api-key");
        when(encryption.decryptWithLegacyFallback(eq("enc(the-api-secret)"), eq("cred-123:apiSecret"), eq("apiSecret")))
            .thenReturn("the-api-secret");

        assertThat(service.decrypt(credential, true)).isEqualTo("the-api-key");
        assertThat(service.decrypt(credential, false)).isEqualTo("the-api-secret");
    }

    /**
     * The withdrawal-permission check must apply only to LIVE, not TESTNET, since testnet
     * accounts commonly report canWithdraw=true and have no real funds to protect.
     */
    @Test
    @DisplayName("connect: mode=TESTNET succeeds even when the broker reports canWithdraw=true -- testnet has no real funds to protect, and reportedly cannot even have this permission restricted, so this check must not apply there")
    void connect_testnet_withdrawalPermissionReported_stillSucceeds() {
        when(adapter.getAccountPermissions("testnet-key", "testnet-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(true, true, true)); // canWithdraw = true, exactly what real testnet accounts report

        BrokerCredentialResponse result = service.connect("user1", testnetReq());

        assertThat(result.mode()).isEqualTo(BrokerMode.TESTNET);
        verify(credentialRepo).save(any());
    }

    @Test
    @DisplayName("connect: mode=LIVE is refused — must go through requestLiveConnect/confirmLiveConnect instead")
    void connect_live_refusesSingleStep() {
        assertThatThrownBy(() -> service.connect("user1", liveReq()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("two-step");
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("requestLiveConnect: validates against Binance's actual LIVE endpoint, not TESTNET")
    void requestLiveConnect_validatesAgainstLiveEndpoint() {
        when(adapter.getAccountPermissions("live-key", "live-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));

        String token = service.requestLiveConnect("user1", liveReq());

        assertThat(token).isNotBlank();
        // Confirms the call actually targeted LIVE, not TESTNET — a TESTNET-stored key must
        // never be sent to the LIVE endpoint. Here, the correct key/mode pairing is used from
        // the start since there's no pre-existing stored credential involved at all.
        verify(adapter).getAccountPermissions("live-key", "live-secret", BrokerMode.LIVE);
        verify(credentialRepo, never()).save(any()); // nothing persisted yet — step 1 only
    }

    /**
     * The withdrawal check must look at the real, key-level apiRestrictions response, not the
     * account-level getAccountPermissions().canWithdraw() flag, since an account can report
     * canWithdraw=true regardless of what THIS key is restricted to.
     */
    @Test
    @DisplayName("requestLiveConnect: refuses a key with withdrawal permission enabled on LIVE, per the real key-level apiRestrictions check")
    void requestLiveConnect_refusesWithdrawalPermission() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getApiKeyRestrictions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new com.tradevision.service.broker.dto.ApiKeyRestrictions(true, true, false, false, true)); // enableWithdrawals = true

        assertThatThrownBy(() -> service.requestLiveConnect("user1", liveReq()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("withdrawal");
    }

    @Test
    @DisplayName("requestLiveConnect: refuses a key that is NOT IP-restricted on Binance")
    void requestLiveConnect_refusesMissingIpRestriction() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getApiKeyRestrictions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new com.tradevision.service.broker.dto.ApiKeyRestrictions(false, false, false, false, true)); // ipRestrict = false

        assertThatThrownBy(() -> service.requestLiveConnect("user1", liveReq()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IP-restricted");
    }

    @Test
    @DisplayName("requestLiveConnect: refuses a key with internal-transfer or universal-transfer permission enabled")
    void requestLiveConnect_refusesTransferPermissions() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getApiKeyRestrictions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new com.tradevision.service.broker.dto.ApiKeyRestrictions(true, false, true, false, true)); // enableInternalTransfer = true

        assertThatThrownBy(() -> service.requestLiveConnect("user1", liveReq()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("internal-transfer");
    }

    @Test
    @DisplayName("requestLiveConnect -> confirmLiveConnect: the full flow persists a genuinely separate LIVE credential")
    void requestThenConfirm_persistsSeparateLiveCredential() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));

        String token = service.requestLiveConnect("user1", liveReq());
        BrokerCredentialResponse result = service.confirmLiveConnect("user1", token, "123456");

        assertThat(result.mode()).isEqualTo(BrokerMode.LIVE);
        // The saved credential carries the LIVE key/secret that were actually validated against
        // LIVE — captured via the encrypt() calls, since credentialRepo.save() just echoes back
        // whatever BrokerCredential object was passed in.
        org.mockito.ArgumentCaptor<BrokerCredential> captor = org.mockito.ArgumentCaptor.forClass(BrokerCredential.class);
        verify(credentialRepo).save(captor.capture());
        assertThat(captor.getValue().getEncryptedApiKey()).isEqualTo("enc(live-key)");
        assertThat(captor.getValue().getMode()).isEqualTo(BrokerMode.LIVE);
    }

    @Test
    @DisplayName("confirmLiveConnect succeeds for a token this exact service instance never issued -- proving retrieval goes through the durable repo, not any instance-local state, exactly the case where a real load-balanced confirm call lands on a different replica than the one that issued the token")
    void confirmLiveConnect_succeedsForTokenNeverIssuedByThisInstance_provingNoInstanceLocalState() {
        // Simulates a token that "another replica" issued -- written straight into the fake
        // durable store this test's own pendingLiveConnectRepo mock is backed by, never through
        // THIS service instance's own requestLiveConnect call.
        var pending = new com.tradevision.model.PendingLiveConnect();
        pending.setToken("token-from-another-replica");
        pending.setUserId("user1");
        pending.setBroker(BrokerType.BINANCE);
        pending.setEncryptedApiKey("enc(live-key)");
        pending.setEncryptedApiSecret("enc(live-secret)");
        pending.setKeyHint("-key");
        pending.setAccountUid("uid-1");
        pending.setExpiresAt(java.time.Instant.now().plusSeconds(60));
        fakePendingLiveConnectStore.put(pending.getToken(), pending);

        BrokerCredentialResponse result = service.confirmLiveConnect("user1", "token-from-another-replica", "123456");

        assertThat(result.mode()).isEqualTo(BrokerMode.LIVE);
        verify(pendingLiveConnectRepo).deleteById("token-from-another-replica"); // one-time use, same as the old map's remove()
    }

    @Test
    @DisplayName("confirmLiveConnect: rejects an unknown/invalid token")
    void confirmLiveConnect_rejectsInvalidToken() {
        assertThatThrownBy(() -> service.confirmLiveConnect("user1", "not-a-real-token", "123456"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid or expired");
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("confirmLiveConnect: a token issued for a DIFFERENT user is rejected, even if otherwise valid")
    void confirmLiveConnect_rejectsTokenForWrongUser() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));
        String token = service.requestLiveConnect("user1", liveReq());

        assertThatThrownBy(() -> service.confirmLiveConnect("some-other-user", token, "123456"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid or expired");
    }

    /**
     * A user with no alert channel at all must not be allowed to end up with a LIVE credential,
     * even though every other validation (key permissions, token, user match) passes cleanly.
     */
    @Test
    @DisplayName("confirmLiveConnect: refuses to persist a LIVE credential for a user with no alert channel configured, and consumes the one-time token regardless")
    void confirmLiveConnect_refusesWhenNoAlertChannelConfigured() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));
        String token = service.requestLiveConnect("user1", liveReq());
        doThrow(new IllegalStateException("Cannot connect a LIVE broker credential: your account has no alert channel configured"))
            .when(alertChannelStartupGuard).requireAlertChannelCoverage("user1");

        assertThatThrownBy(() -> service.confirmLiveConnect("user1", token, "123456"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("alert channel");
        verify(credentialRepo, never()).save(any());
    }

    /**
     * confirmLiveConnect must refuse outright on a bad/missing step-up OTP, before the token
     * itself is ever even looked up -- a real token with no valid OTP must not be consumable.
     */
    @Test
    @DisplayName("confirmLiveConnect: refuses a genuinely valid token when the step-up OTP itself is wrong/missing, and never persists the credential")
    void confirmLiveConnect_refusesOnBadStepUpOtp() {
        when(adapter.getAccountPermissions(any(), any(), eq(BrokerMode.LIVE)))
            .thenReturn(new AccountPermissions(true, false, true));
        String token = service.requestLiveConnect("user1", liveReq());
        doThrow(new IllegalArgumentException("OTP expired. Request a new one."))
            .when(authService).verifyStepUpOtp("user1", BrokerCredentialService.CREDENTIAL_CHANGE_STEPUP_PURPOSE, "wrong-code");

        assertThatThrownBy(() -> service.confirmLiveConnect("user1", token, "wrong-code"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OTP");
        verify(credentialRepo, never()).save(any());
    }

    /**
     * The single most important test for PAPER mode isolation -- a PAPER credential's own
     * connect-time validation call must NEVER reach the real, injected BrokerAdapter mock
     * (which stands in for BinanceBrokerAdapter here). If this ever regressed, a PAPER
     * credential's placeholder keys would be sent to Binance as a genuine authenticated
     * request.
     */
    @Test
    @DisplayName("connect: mode=PAPER never calls the real adapter's own getAccountPermissions -- routed entirely to the simulated PaperBrokerAdapter instead")
    void connect_paper_neverCallsRealAdapter() {
        ConnectBrokerRequest req = new ConnectBrokerRequest();
        req.setBroker(BrokerType.BINANCE);
        req.setApiKey("not-a-real-key");
        req.setApiSecret("not-a-real-secret");
        req.setMode(BrokerMode.PAPER);

        BrokerCredentialResponse result = service.connect("user1", req);

        assertThat(result.mode()).isEqualTo(BrokerMode.PAPER);
        verify(adapter, never()).getAccountPermissions(any(), any(), any());
        verify(credentialRepo).save(any());
    }

    /**
     * Proves every one of a user's active credentials -- not just one -- gets deactivated and
     * audited.
     */
    @Test
    @DisplayName("deactivateAll: deactivates and audits every one of the user's active credentials, not just one")
    void deactivateAll_deactivatesEveryActiveCredential() {
        BrokerCredential c1 = new BrokerCredential(); c1.setId("cred1"); c1.setUserId("user1"); c1.setBroker(BrokerType.BINANCE); c1.setActive(true);
        BrokerCredential c2 = new BrokerCredential(); c2.setId("cred2"); c2.setUserId("user1"); c2.setBroker(BrokerType.BINANCE); c2.setActive(true);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of(c1, c2));

        service.deactivateAll("user1", "suspected compromise");

        assertThat(c1.isActive()).isFalse();
        assertThat(c2.isActive()).isFalse();
        verify(credentialRepo, times(2)).save(any());
        verify(auditChainService, times(2)).appendToChain(argThat(log -> "EMERGENCY_REVOKE".equals(log.getAction())));
    }

    /**
     * Proves deactivating a credential is refused while it still has an open position, rather
     * than abandoning that position.
     */
    @Test
    @DisplayName("delete: refuses to deactivate a credential with an OPEN position -- throws rather than silently abandoning it")
    void delete_openPositionExists_throwsAndDoesNotDeactivate() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        var openPosition = new com.tradevision.model.Position();
        openPosition.setId("pos1"); openPosition.setStatus("OPEN");
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of(openPosition));

        assertThatThrownBy(() -> service.delete("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("still need this application's own management");

        assertThat(credential.isActive()).isTrue();
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("delete: with no open/unresolved positions, deactivation proceeds normally")
    void delete_noActivePositions_deactivatesNormally() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true); credential.setBroker(BrokerType.BINANCE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());

        service.delete("user1", "cred1");

        assertThat(credential.isActive()).isFalse();
        verify(credentialRepo).save(credential);
    }

    /**
     * Proves deactivation also checks for non-terminal orders and active WebSocket/
     * reconciliation state, not just open positions.
     */
    @Test
    @DisplayName("delete: refuses to deactivate a credential with a non-terminal order (e.g. still SUBMITTING) even with no Position yet at all")
    void delete_pendingOrderExists_throwsAndDoesNotDeactivate() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        var pendingOrder = new com.tradevision.model.Order();
        pendingOrder.setId("order1"); pendingOrder.setStatus(com.tradevision.model.OrderStatus.SUBMITTING);
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of(pendingOrder));

        assertThatThrownBy(() -> service.delete("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("still pending, unacknowledged, or awaiting manual reconciliation");

        assertThat(credential.isActive()).isTrue();
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("delete: refuses to deactivate a credential with an unresolved CRITICAL incident")
    void delete_unresolvedCriticalIncidentExists_throwsAndDoesNotDeactivate() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        var incident = new com.tradevision.model.TradingIncident();
        incident.setId("incident1"); incident.setSeverity("CRITICAL");
        when(tradingIncidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc("cred1")).thenReturn(List.of(incident));

        assertThatThrownBy(() -> service.delete("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unresolved CRITICAL");

        assertThat(credential.isActive()).isTrue();
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("delete: a LOW-severity unresolved incident does NOT block deletion -- only CRITICAL does")
    void delete_unresolvedLowSeverityIncident_deactivatesNormally() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true); credential.setBroker(BrokerType.BINANCE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        var incident = new com.tradevision.model.TradingIncident();
        incident.setId("incident1"); incident.setSeverity("WARNING");
        when(tradingIncidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc("cred1")).thenReturn(List.of(incident));

        service.delete("user1", "cred1");

        assertThat(credential.isActive()).isFalse();
        verify(credentialRepo).save(credential);
    }

    /**
     * Covers the API-key rotation workflow. rotateApiKey refuses while any open position/order
     * exists (refuseIfCredentialHasOpenWork), so every non-LIVE scenario below stubs
     * positionRepo/orderRepo to return empty lists -- exactly what a credential with nothing
     * outstanding looks like. rotateApiKey itself refuses outright for LIVE credentials
     * (redirecting to requestApiKeyRotation/confirmApiKeyRotation), so the
     * withdrawal-permission-rejected scenario is exercised against requestApiKeyRotation
     * instead, below.
     */
    @Test
    @DisplayName("rotateApiKey: a valid new key updates the SAME credential's own encrypted fields in place, preserving its id")
    void rotateApiKey_validNewKey_updatesInPlacePreservingId() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.TESTNET);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(true, false, true));

        var result = service.rotateApiKey("user1", "cred1", "new-key", "new-secret");

        assertThat(result.id()).isEqualTo("cred1"); // same credential, not a new one
        assertThat(credential.getEncryptedApiKey()).isEqualTo("enc(new-key)");
        assertThat(credential.getEncryptedApiSecret()).isEqualTo("enc(new-secret)");
        verify(credentialRepo).save(credential);
        verify(auditChainService).appendToChain(argThat(log -> "API_KEY_ROTATED".equals(log.getAction())));
    }

    @Test
    @DisplayName("rotateApiKey: a new key with trading disabled is rejected")
    void rotateApiKey_tradingDisabled_rejected() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.TESTNET);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(false, false, true));

        assertThatThrownBy(() -> service.rotateApiKey("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trading permission");
    }

    @Test
    @DisplayName("rotateApiKey: a credential the user doesn't own (or that doesn't exist) throws IllegalArgumentException, distinct from a validation refusal")
    void rotateApiKey_credentialNotFound_throwsIllegalArgumentException() {
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.rotateApiKey("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(adapter, never()).getAccountPermissions(any(), any(), any());
    }

    @Test
    @DisplayName("rotateApiKey: a LIVE credential is refused outright -- must go through requestApiKeyRotation/confirmApiKeyRotation instead")
    void rotateApiKey_liveCredential_refusesSingleStep() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));

        assertThatThrownBy(() -> service.rotateApiKey("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("two-step");
        verify(credentialRepo, never()).save(any());
        verify(adapter, never()).getAccountPermissions(any(), any(), any());
    }

    @Test
    @DisplayName("rotateApiKey: refuses while an open position still needs this credential's own management")
    void rotateApiKey_openPosition_rejected() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.TESTNET);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        var openPosition = new com.tradevision.model.Position();
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of(openPosition));

        assertThatThrownBy(() -> service.rotateApiKey("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("position(s)");
        verify(credentialRepo, never()).save(any());
        verify(adapter, never()).getAccountPermissions(any(), any(), any());
    }

    @Test
    @DisplayName("rotateApiKey: refuses a new key that reports a DIFFERENT Binance account uid than this credential was originally connected under")
    void rotateApiKey_accountUidMismatch_rejected() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.TESTNET);
        credential.setAccountUid("uid-old-account");
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getAccountUid("new-key", "new-secret", BrokerMode.TESTNET)).thenReturn("uid-different-account");

        assertThatThrownBy(() -> service.rotateApiKey("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("different Binance account");
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("rotateApiKey: a matching account uid rotates normally, and backfills a previously-null accountUid from the new key")
    void rotateApiKey_accountUidMatches_backfillsWhenPreviouslyNull() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.TESTNET);
        // accountUid intentionally left null -- a pre-migration credential
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.TESTNET))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getAccountUid("new-key", "new-secret", BrokerMode.TESTNET)).thenReturn("uid-newly-observed");

        service.rotateApiKey("user1", "cred1", "new-key", "new-secret");

        assertThat(credential.getAccountUid()).isEqualTo("uid-newly-observed");
        verify(credentialRepo).save(credential);
    }

    /**
     * Covers the LIVE two-step rotation flow, mirroring requestLiveConnect/confirmLiveConnect's
     * own test coverage above. This is also where the withdrawal-permission-rejected scenario
     * lives -- rotateApiKey itself refuses outright for LIVE before ever reaching a permission
     * check, so that scenario is only reachable through this entry point. As with
     * requestLiveConnect, the withdrawal check here is the real, key-level apiRestrictions
     * check, not the account-level getAccountPermissions().canWithdraw() flag.
     */
    @Test
    @DisplayName("requestApiKeyRotation: refuses a new key with withdrawal permission enabled on LIVE, per the real key-level apiRestrictions check")
    void requestApiKeyRotation_withdrawalEnabled_rejected() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getApiKeyRestrictions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new com.tradevision.service.broker.dto.ApiKeyRestrictions(true, true, false, false, true)); // enableWithdrawals = true

        assertThatThrownBy(() -> service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("withdrawal permission");
    }

    @Test
    @DisplayName("requestApiKeyRotation: refuses on a non-LIVE credential -- rotateApiKey is the single-step path for those")
    void requestApiKeyRotation_nonLiveCredential_refused() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.TESTNET);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));

        assertThatThrownBy(() -> service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rotateApiKey");
    }

    @Test
    @DisplayName("requestApiKeyRotation: refuses a new key reporting a different Binance account uid, on LIVE")
    void requestApiKeyRotation_accountUidMismatch_rejected() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        credential.setAccountUid("uid-old-account");
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getAccountUid("new-key", "new-secret", BrokerMode.LIVE)).thenReturn("uid-different-account");

        assertThatThrownBy(() -> service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("different Binance account");
    }

    @Test
    @DisplayName("requestApiKeyRotation -> confirmApiKeyRotation: the full LIVE flow applies the rotation in place, preserving the credential id")
    void requestThenConfirmApiKeyRotation_appliesRotation() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        credential.setAccountUid("uid-same-account");
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        when(adapter.getAccountUid("new-key", "new-secret", BrokerMode.LIVE)).thenReturn("uid-same-account");

        String token = service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret");
        var result = service.confirmApiKeyRotation("user1", token, "123456");

        assertThat(result.id()).isEqualTo("cred1");
        assertThat(credential.getEncryptedApiKey()).isEqualTo("enc(new-key)");
        assertThat(credential.getEncryptedApiSecret()).isEqualTo("enc(new-secret)");
        assertThat(credential.getAccountUid()).isEqualTo("uid-same-account");
        verify(credentialRepo).save(credential);
        verify(auditChainService).appendToChain(argThat(log -> "API_KEY_ROTATION_CONFIRMED".equals(log.getAction())));
    }

    @Test
    @DisplayName("confirmApiKeyRotation: rejects an unknown/invalid token")
    void confirmApiKeyRotation_rejectsInvalidToken() {
        assertThatThrownBy(() -> service.confirmApiKeyRotation("user1", "not-a-real-token", "123456"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid or expired");
        verify(credentialRepo, never()).save(any());
    }

    /**
     * The same step-up requirement, same reasoning, as confirmLiveConnect_refusesOnBadStepUpOtp,
     * for the rotation flow.
     */
    @Test
    @DisplayName("confirmApiKeyRotation: refuses a genuinely valid token when the step-up OTP itself is wrong/missing, and never persists the rotation")
    void confirmApiKeyRotation_refusesOnBadStepUpOtp() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        String token = service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret");
        doThrow(new IllegalArgumentException("OTP expired. Request a new one."))
            .when(authService).verifyStepUpOtp("user1", BrokerCredentialService.CREDENTIAL_CHANGE_STEPUP_PURPOSE, "wrong-code");

        assertThatThrownBy(() -> service.confirmApiKeyRotation("user1", token, "wrong-code"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OTP");
        verify(credentialRepo, never()).save(any());
    }

    @Test
    @DisplayName("confirmApiKeyRotation: a token issued for a DIFFERENT user is rejected, even if otherwise valid")
    void confirmApiKeyRotation_rejectsTokenForWrongUser() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        String token = service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret");

        assertThatThrownBy(() -> service.confirmApiKeyRotation("some-other-user", token, "123456"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid or expired");
    }

    @Test
    @DisplayName("confirmApiKeyRotation: re-checks open work at confirm time -- a position that opened AFTER requestApiKeyRotation still blocks the rotation")
    void confirmApiKeyRotation_reChecksOpenWorkAtConfirmTime() {
        var credential = new BrokerCredential();
        credential.setId("cred1"); credential.setUserId("user1"); credential.setActive(true);
        credential.setBroker(BrokerType.BINANCE); credential.setMode(BrokerMode.LIVE);
        when(credentialRepo.findByIdAndUserId("cred1", "user1")).thenReturn(java.util.Optional.of(credential));
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of()); // clean at request time
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of());
        when(adapter.getAccountPermissions("new-key", "new-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        String token = service.requestApiKeyRotation("user1", "cred1", "new-key", "new-secret");

        // A position opened in the window between request and confirm.
        var openPosition = new com.tradevision.model.Position();
        when(positionRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of(openPosition));

        assertThatThrownBy(() -> service.confirmApiKeyRotation("user1", token, "123456"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("position(s)");
        verify(credentialRepo, never()).save(any());
    }

    /**
     * Covers PAPER adapter isolation between users: each credential must get its own
     * PaperBrokerAdapter instance, not a shared one.
     */
    @Test
    @DisplayName("adapterForCredential: two different PAPER credentials get two genuinely different PaperBrokerAdapter instances -- real isolation, not a shared one")
    void adapterForCredential_differentPaperCredentials_getDifferentAdapterInstances() {
        BrokerCredential credA = new BrokerCredential();
        credA.setId("paper-cred-A"); credA.setBroker(BrokerType.BINANCE); credA.setMode(BrokerMode.PAPER);
        BrokerCredential credB = new BrokerCredential();
        credB.setId("paper-cred-B"); credB.setBroker(BrokerType.BINANCE); credB.setMode(BrokerMode.PAPER);

        BrokerAdapter adapterA = service.adapterForCredential(credA);
        BrokerAdapter adapterB = service.adapterForCredential(credB);

        assertThat(adapterA).isNotSameAs(adapterB);
    }

    @Test
    @DisplayName("adapterForCredential: the SAME PAPER credential called twice gets the SAME adapter instance -- caching still works correctly, this isn't a fresh instance (and a fresh 100k balance) on every single call")
    void adapterForCredential_samePaperCredentialCalledTwice_getsSameAdapterInstance() {
        BrokerCredential cred = new BrokerCredential();
        cred.setId("paper-cred-A"); cred.setBroker(BrokerType.BINANCE); cred.setMode(BrokerMode.PAPER);

        BrokerAdapter first = service.adapterForCredential(cred);
        BrokerAdapter second = service.adapterForCredential(cred);

        assertThat(first).isSameAs(second);
    }
}
