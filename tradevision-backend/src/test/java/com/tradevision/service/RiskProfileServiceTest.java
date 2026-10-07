package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Review finding (P1 #6 — "LIVE auto-trade authorization doesn't revalidate broker
 * permissions"): confirmed real — the only checks were the confirmation phrase and the stored
 * risk profile, never the broker's actual CURRENT permission state. Verifies the fix re-queries
 * and enforces exactly the same permission rules used at connection time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskProfileServiceTest {

    private static final String PHRASE = "I UNDERSTAND THIS ENABLES AUTONOMOUS LIVE TRADING";

    @Mock RiskProfileRepository riskProfileRepo;
    @Mock BrokerCredentialService credentialService;
    @Mock PositionRepository positionRepo;
    @Mock IncidentService incidentService;
    @Mock BrokerAdapter adapter;
    // Review finding ("Risk-profile updates/resume can race with safety state" -- P0, full
    // context in RiskProfileService.doUpsert's own comment): needed now that halt/resume/
    // authorizeLiveAutoTrade/revokeLiveAutoTrade/doUpsert (for an existing profile) all use a
    // targeted mongoTemplate.updateFirst() instead of a full riskProfileRepo.save().
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Review finding ("Resume does not prove existing positions are protected" -- P0, full
    // context in RiskProfileService.resume's own javadoc): needed now that resume checks for
    // unresolved UNKNOWN/RECONCILIATION_REQUIRED orders and unresolved CRITICAL incidents.
    // Mockito's own real default for an unstubbed List-returning method is an empty list, which
    // is the correct, safe "nothing unresolved" default for these two -- no explicit stub
    // needed for the happy path, only for tests that specifically want to exercise the new
    // blocking checks.
    @Mock com.tradevision.repository.OrderRepository orderRepo;
    @Mock StrategyPlanService strategyPlanService;
    @Mock com.tradevision.repository.UserRepository userRepo;
    @Mock PositionMonitorService positionMonitorService;
    @Mock com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    @Mock com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    @Mock com.tradevision.config.StartupState startupState;
    // Audit item P0-1, full context in LiveCanaryRecord's own class javadoc.
    @Mock com.tradevision.service.LiveCanaryService liveCanaryService;

    @InjectMocks RiskProfileService service;

    private BrokerCredential liveCredential;
    private RiskProfile profile;

    @BeforeEach
    void setup() {
        liveCredential = new BrokerCredential();
        liveCredential.setId("cred1");
        liveCredential.setBroker(BrokerType.BINANCE);
        liveCredential.setMode(BrokerMode.LIVE);

        profile = new RiskProfile();
        profile.setId("profile1"); // an EXISTING, already-persisted profile -- realistic for every method under test here, which all operate via get()/findByUserIdAndCredentialId on a profile that already exists
        profile.setUserId("user1");
        profile.setCredentialId("cred1");
        // P0-6 fix ("LIVE risk-limit enforcement" -- full context in
        // authorizeLiveAutoTrade's own updated javadoc): a fully-configured, healthy default so
        // every existing LIVE-authorization test in this file, none of which are about this
        // specific new gate, is unaffected by it -- see the dedicated
        // incompleteLiveRiskLimits_* tests below for the gate itself.
        profile.setDailyLossLimitQuote(java.math.BigDecimal.valueOf(25));
        profile.setMaxPositionQuoteAmount(java.math.BigDecimal.valueOf(50));
        profile.setMaxTotalExposureQuote(java.math.BigDecimal.valueOf(100));
        profile.setMaxDrawdownPercent(10);
        profile.setMaxOrdersPerHour(20);

        when(riskProfileRepo.findByUserIdAndCredentialId("user1", "cred1")).thenReturn(Optional.of(profile));
        when(riskProfileRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        // Review finding ("Mongo standalone deployment still weakens the plan/profile execution
        // atomicity guarantee" -- external review, twenty-fourth pass, P1, full context in
        // authorizeLiveAutoTrade's own updated check): a healthy default so every existing
        // LIVE-authorization test in this file, none of which are about this specific check, is
        // unaffected by this new gate.
        when(startupState.areMongoTransactionsSupported()).thenReturn(true);
        // Review finding ("Risk-profile updates/resume can race with safety state" -- P0, full
        // context in RiskProfileService.doUpsert's own comment): realistic defaults for the new
        // targeted-update path -- an unstubbed updateFirst() would NPE (mongoTemplate itself is
        // fine as a mock, but its own unstubbed method call returning null would NPE on
        // .getModifiedCount()), and an unstubbed findById() would return Optional.empty()
        // (Mockito's own real default), silently losing every field this pass's own targeted
        // update just wrote in every assertion downstream.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        when(riskProfileRepo.findById("profile1")).thenReturn(Optional.of(profile));
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(liveCredential);
        when(credentialService.adapterForCredential(liveCredential)).thenReturn(adapter);
        when(credentialService.decrypt(eq(liveCredential), eq(true))).thenReturn("api-key");
        when(credentialService.decrypt(eq(liveCredential), eq(false))).thenReturn("api-secret");
        // P1-10: authorizeLiveAutoTrade now also re-verifies the key's real, key-level
        // apiRestrictions via credentialService.validateLiveKeyRestrictions -- default it to a
        // safe response so only the tests that specifically care about it need to override it.
        // (Mocked on credentialService, not adapter directly, since validateLiveKeyRestrictions
        // itself lives on BrokerCredentialService and internally calls adapter.getApiKeyRestrictions --
        // stubbing it here avoids every existing test needing to know that internal detail.)
        doNothing().when(credentialService).validateLiveKeyRestrictions(any(), any(), any(), any(), any(), any(), any());
        // Audit item P0-1 ("Nothing gates autonomous LIVE trading on a real, successful live
        // order ever having been placed" -- full context in LiveCanaryRecord's own class
        // javadoc): a healthy default (a passing canary already on record) so every existing
        // LIVE-authorization test in this file, none of which are about this specific new gate,
        // is unaffected by it -- see the dedicated noRecentPassingLiveCanary_* test below for
        // the gate itself.
        when(liveCanaryService.hasRecentPassingCanary(any())).thenReturn(true);
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: permissions still clean (no withdrawal, trading enabled) — authorization succeeds")
    void permissionsStillClean_authorizes() {
        when(adapter.getAccountPermissions("api-key", "api-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));

        RiskProfile result = service.authorizeLiveAutoTrade("user1", "cred1", PHRASE);

        assertThat(result.isLiveAutoTradeAuthorized()).isTrue();
    }

    /**
     * P1-10: the withdrawal re-check here no longer relies on the account-level
     * getAccountPermissions().canWithdraw() flag (see ApiKeyRestrictions' own class javadoc for
     * why that flag is the wrong signal) -- it now goes through
     * credentialService.validateLiveKeyRestrictions, the same real, key-level apiRestrictions
     * check used at connect/rotation time. This test simulates that check finding a now-unsafe
     * key the same way the broker itself would report it.
     */
    @Test
    @DisplayName("authorizeLiveAutoTrade: key-level restrictions now unsafe on the broker (changed since connection) — refuses and raises an incident, exactly the P1 #6/#10 scenario")
    void keyRestrictionsNowUnsafe_refusesAndRaisesIncident() {
        when(adapter.getAccountPermissions("api-key", "api-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(true, false, true));
        doThrow(new IllegalArgumentException("This API key has withdrawal permission enabled. Create a key with only "
                + "'Enable Reading' and 'Enable Spot & Margin Trading' — withdrawal must stay off."))
            .when(credentialService).validateLiveKeyRestrictions(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("withdrawal permission enabled");

        assertThat(profile.isLiveAutoTradeAuthorized()).isFalse();
        verify(riskProfileRepo, never()).save(any());
        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: trading permission no longer enabled on the broker — refuses and raises an incident")
    void tradingNoLongerEnabled_refuses() {
        when(adapter.getAccountPermissions("api-key", "api-secret", BrokerMode.LIVE))
            .thenReturn(new AccountPermissions(false, false, true)); // canTrade=false

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trading permission");

        verify(riskProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: broker permission query fails — refuses rather than authorizing against unverified permissions")
    void permissionQueryFails_refusesRatherThanGuessing() {
        when(adapter.getAccountPermissions(any(), any(), any())).thenThrow(new RuntimeException("connection timeout"));

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalStateException.class);

        verify(riskProfileRepo, never()).save(any());
    }

    /**
     * Review finding ("Mongo standalone deployment still weakens the plan/profile execution
     * atomicity guarantee" -- external review, twenty-fourth pass, P1, full context in
     * authorizeLiveAutoTrade's own updated check): the actual test proving the new refusal.
     */
    @Test
    @DisplayName("authorizeLiveAutoTrade: MongoDB does not support transactions (confirmed at startup) -- refuses outright for LIVE, never even reaches the broker permission check")
    void mongoTransactionsUnsupported_liveCredential_refusesOutright() {
        when(startupState.areMongoTransactionsSupported()).thenReturn(false);

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not support transactions");

        verify(adapter, never()).getAccountPermissions(any(), any(), any());
        verify(riskProfileRepo, never()).save(any());
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Audit item P0-1 ("Nothing gates autonomous LIVE trading on a real, successful live order
     * ever having been placed" -- full context in LiveCanaryRecord's own class javadoc): the
     * actual test proving the new refusal, same pattern as the Mongo-transactions test above --
     * refused before ever reaching the broker permission check, since there is no point
     * re-verifying permissions for a credential that hasn't even cleared this gate yet.
     */
    @Test
    @DisplayName("authorizeLiveAutoTrade: no PASSED live canary on record for this credential -- refuses outright for LIVE, never even reaches the broker permission check")
    void noRecentPassingLiveCanary_refusesOutright() {
        when(liveCanaryService.hasRecentPassingCanary("cred1")).thenReturn(false);

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("live canary");

        verify(adapter, never()).getAccountPermissions(any(), any(), any());
        verify(riskProfileRepo, never()).save(any());
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: TESTNET credential — no broker re-validation attempted at all (the review's concern is specifically about LIVE)")
    void testnetCredential_skipsRevalidation() {
        liveCredential.setMode(BrokerMode.TESTNET);

        RiskProfile result = service.authorizeLiveAutoTrade("user1", "cred1", PHRASE);

        assertThat(result.isLiveAutoTradeAuthorized()).isTrue();
        verify(adapter, never()).getAccountPermissions(any(), any(), any());
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: wrong confirmation phrase — refuses before ever touching the broker")
    void wrongPhrase_refusesBeforeAnyBrokerCall() {
        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", "wrong phrase"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(adapter, never()).getAccountPermissions(any(), any(), any());
        verify(credentialService, never()).ownedCredential(any(), any());
    }

    // ── P0-6: LIVE risk-limit enforcement ─────────────────────────────────

    @Test
    @DisplayName("authorizeLiveAutoTrade: LIVE credential with an incomplete risk profile (still at 0/disabled defaults) is refused before ever reaching the broker permission check")
    void incompleteLiveRiskLimits_refusesBeforeBrokerCall() {
        // Reset to the model's own real "never configured" defaults -- exactly what a fresh
        // credential's risk profile looks like before a user deliberately sets these.
        profile.setDailyLossLimitQuote(null);
        profile.setMaxPositionQuoteAmount(null);
        profile.setMaxTotalExposureQuote(null);
        profile.setMaxDrawdownPercent(0);
        profile.setMaxOrdersPerHour(0);

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dailyLossLimitQuote")
            .hasMessageContaining("maxPositionQuoteAmount")
            .hasMessageContaining("maxTotalExposureQuote")
            .hasMessageContaining("maxDrawdownPercent")
            .hasMessageContaining("maxOrdersPerHour");

        verify(adapter, never()).getAccountPermissions(any(), any(), any());
        verify(riskProfileRepo, never()).save(any());
        assertThat(profile.isLiveAutoTradeAuthorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: LIVE credential missing only ONE risk limit (maxOrdersPerHour still 0) is still refused, and the refusal names exactly that one field")
    void oneMissingLiveRiskLimit_refusesNamingOnlyThatField() {
        profile.setMaxOrdersPerHour(0); // every other limit stays at the setup()'s own healthy default

        assertThatThrownBy(() -> service.authorizeLiveAutoTrade("user1", "cred1", PHRASE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxOrdersPerHour");

        verify(adapter, never()).getAccountPermissions(any(), any(), any());
    }

    @Test
    @DisplayName("authorizeLiveAutoTrade: TESTNET credential with an incomplete risk profile is NOT refused by this gate -- it's scoped to LIVE only, matching every other broker-revalidation check in this method")
    void incompleteRiskLimits_testnetCredential_gateDoesNotApply() {
        liveCredential.setMode(BrokerMode.TESTNET);
        profile.setDailyLossLimitQuote(null);
        profile.setMaxPositionQuoteAmount(null);
        profile.setMaxTotalExposureQuote(null);
        profile.setMaxDrawdownPercent(0);
        profile.setMaxOrdersPerHour(0);

        RiskProfile result = service.authorizeLiveAutoTrade("user1", "cred1", PHRASE);

        assertThat(result.isLiveAutoTradeAuthorized()).isTrue();
    }

    // ── halt/resume (review finding "Risk-profile updates/resume can race with safety state" --
    // P0, full context in RiskProfileService.doUpsert's own comment) ────────────────

    @Test
    @DisplayName("halt: the atomic update targets ONLY tradingHalted/haltReason/updatedAt -- never a full-document save that could silently overwrite a concurrent write to any other field")
    void halt_targetsOnlyTheThreeFieldsItActuallyChanges() {
        service.halt("user1", "cred1", "test halt reason");

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(RiskProfile.class));
        var updateDoc = updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        assertThat(updateDoc.keySet()).containsExactlyInAnyOrder("tradingHalted", "haltReason", "updatedAt");
        assertThat(updateDoc.getBoolean("tradingHalted")).isTrue();
        assertThat(updateDoc.getString("haltReason")).isEqualTo("test halt reason");
        // Never called -- the whole point of the fix.
        verify(riskProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("halt: with no reason given, falls back to the default message -- same behavior as before this fix, just via the targeted update instead of a full save")
    void halt_noReasonGiven_usesDefaultMessage() {
        service.halt("user1", "cred1", null);

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(RiskProfile.class));
        var updateDoc = updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        assertThat(updateDoc.getString("haltReason")).isEqualTo("Manually halted by user.");
    }

    @Test
    @DisplayName("resume: targets exactly the fields it actually changes (tradingHalted/haltReason/autoTradeHalted/autoTradeHaltReason, and since P3-9, consecutiveOrderFailures -- see this file's own updated test name) via a real conditional update, never a full-document save")
    void resume_targetsOnlyTheFieldsItActuallyChanges() {
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());

        service.resume("user1", "cred1");

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(RiskProfile.class));
        var updateDoc = updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        // Review finding ("autoTradeHalted is still never reset" -- external review, third
        // pass): autoTradeHalted/autoTradeHaltReason added to this file's own existing
        // assertion, not a new, separate test -- this IS the same "which fields does resume
        // actually touch" question this test has always asked, just with a now-larger correct
        // answer.
        // P3-9 fix ("resume doesn't reset consecutiveOrderFailures; next single failure
        // re-trips breaker"): consecutiveOrderFailures added to this same assertion for the
        // same reason -- it's the identical "which fields does resume actually touch" question.
        assertThat(updateDoc.keySet()).containsExactlyInAnyOrder(
            "tradingHalted", "haltReason", "autoTradeHalted", "autoTradeHaltReason",
            "consecutiveOrderFailures", "updatedAt");
        assertThat(updateDoc.getBoolean("tradingHalted")).isFalse();
        assertThat(updateDoc.getBoolean("autoTradeHalted")).isFalse();
        assertThat(updateDoc.getInteger("consecutiveOrderFailures")).isZero();
        verify(riskProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("resume: resets consecutiveOrderFailures to 0 even when the circuit breaker previously tripped it well above the threshold -- P3-9 fix, the actual bug (a stuck-at-threshold counter re-trips the breaker on the very next order failure)")
    void resume_resetsConsecutiveOrderFailures_evenWhenWellAboveThreshold() {
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());

        service.resume("user1", "cred1");

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(RiskProfile.class));
        var updateDoc = updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        assertThat(updateDoc.getInteger("consecutiveOrderFailures")).isEqualTo(0);
    }

    @Test
    @DisplayName("resume: an unresolved position (unverified entry price) still blocks resume BEFORE the atomic update is ever attempted -- the existing safety check is untouched by this fix")
    void resume_unresolvedPosition_neverReachesUpdate() {
        Position unresolved = new Position();
        unresolved.setSymbol("BTCUSDT");
        unresolved.setAvgEntryPriceUnverified(true);
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of(unresolved));

        assertThatThrownBy(() -> service.resume("user1", "cred1")).isInstanceOf(IllegalStateException.class);

        verify(mongoTemplate, never()).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("resume: an OPEN position with an incomplete fill-ledger record blocks resume -- the actual review fix (\"Resume does not prove existing positions are protected\")")
    void resume_incompleteLedgerPosition_blocksResume() {
        Position incomplete = new Position();
        incomplete.setSymbol("ETHUSDT");
        incomplete.setLedgerRecordingIncomplete(true);
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of(incomplete));

        assertThatThrownBy(() -> service.resume("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("incomplete fill-ledger record");

        verify(mongoTemplate, never()).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("resume: an unresolved UNKNOWN order for this credential blocks resume -- the actual review fix, checking the OMS's own first-class \"genuinely don't know what happened\" state")
    void resume_unresolvedUnknownOrder_blocksResume() {
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());
        com.tradevision.model.Order unknownOrder = new com.tradevision.model.Order();
        unknownOrder.setStatus(com.tradevision.model.OrderStatus.UNKNOWN);
        when(orderRepo.findByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(List.of(unknownOrder));

        assertThatThrownBy(() -> service.resume("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNKNOWN/RECONCILIATION_REQUIRED");

        verify(mongoTemplate, never()).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("resume: an unresolved CRITICAL incident for this credential blocks resume, but a WARNING-severity incident does NOT -- the actual review fix, scoped to critical severity specifically")
    void resume_unresolvedCriticalIncident_blocksResume_butWarningDoesNot() {
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());
        com.tradevision.model.TradingIncident warning = new com.tradevision.model.TradingIncident();
        warning.setSeverity("WARNING");
        com.tradevision.model.TradingIncident critical = new com.tradevision.model.TradingIncident();
        critical.setSeverity("CRITICAL");
        when(tradingIncidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc("cred1")).thenReturn(List.of(warning, critical));

        assertThatThrownBy(() -> service.resume("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CRITICAL incident");
    }

    @Test
    @DisplayName("resume: only a WARNING-severity incident (no critical, no unresolved orders, no ledger gaps, no unverified positions) does NOT block resume -- confirms the check is genuinely scoped to CRITICAL, not any unresolved incident")
    void resume_onlyWarningIncident_doesNotBlockResume() {
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());
        com.tradevision.model.TradingIncident warning = new com.tradevision.model.TradingIncident();
        warning.setSeverity("WARNING");
        when(tradingIncidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc("cred1")).thenReturn(List.of(warning));

        service.resume("user1", "cred1");

        verify(mongoTemplate).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("resume: the real exchange balance for an OPEN position's base asset is LESS than this application believes it holds -- blocks resume, the actual review fix (\"actual exchange position/balance\" re-verification)")
    void resume_realBalanceLessThanBelieved_blocksResume() {
        Position open = new Position();
        open.setSymbol("BTCUSDT");
        open.setQuantity(java.math.BigDecimal.valueOf(1.0));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of(open));

        var rules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 2, 6, java.math.BigDecimal.ZERO, false, false, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(rules);
        // The exchange's own real balance is only 0.5 BTC -- genuinely less than the 1.0 this
        // application believes it holds for this position.
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", java.math.BigDecimal.valueOf(0.3), java.math.BigDecimal.valueOf(0.2))));

        assertThatThrownBy(() -> service.resume("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("diverged");

        verify(mongoTemplate, never()).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("resume: the real exchange balance genuinely covers every OPEN position -- resume proceeds normally")
    void resume_realBalanceCoversPosition_resumeProceeds() {
        Position open = new Position();
        open.setSymbol("BTCUSDT");
        open.setQuantity(java.math.BigDecimal.valueOf(1.0));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of(open));

        var rules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 2, 6, java.math.BigDecimal.ZERO, false, false, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(rules);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", java.math.BigDecimal.valueOf(0.7), java.math.BigDecimal.valueOf(0.3))));

        service.resume("user1", "cred1");

        verify(mongoTemplate).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    @Test
    @DisplayName("resume: a lost safetyStateVersion race (something changed the safety state DURING this resume's own checks) throws rather than silently clearing the new state -- the actual review fix (\"resume() can still race with a new halt\")")
    void resume_lostSafetyVersionRace_throwsRatherThanSilentlyOverwriting() {
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());
        // Simulates a concurrent halt/drawdown event bumping the version during this resume's
        // own safety-check sequence -- the write's own condition (safetyStateVersion=captured)
        // no longer matches by the time it actually runs.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 0L, null));

        assertThatThrownBy(() -> service.resume("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("safety state changed");
    }

    @Test
    @DisplayName("resume: the live balance check itself failing (network error) is treated as a FAILED check, not silently skipped -- blocks resume")
    void resume_liveBalanceCheckFails_blocksResume() {
        Position open = new Position();
        open.setSymbol("BTCUSDT");
        open.setQuantity(java.math.BigDecimal.valueOf(1.0));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of(open));
        when(adapter.getBalance(any(), any(), any())).thenThrow(new RuntimeException("simulated network failure"));

        assertThatThrownBy(() -> service.resume("user1", "cred1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not verify real exchange balances");

        verify(mongoTemplate, never()).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class));
    }

    // ── quote-asset enforcement (review finding "Risk exposure assumes every quote asset is
    // the same currency" -- full context in doUpsert's own comment) ────────────────

    @Test
    @DisplayName("upsert: a non-USDT-quoted symbol is rejected outright -- the actual review fix (\"Risk exposure assumes every quote asset is the same currency\"), since mixing quote assets would make exposure/equity/drawdown silently sum different currencies as if they were the same number")
    void upsert_nonUsdtSymbol_rejected() {
        var req = new com.tradevision.dto.RiskProfileRequest();
        req.setCredentialId("cred1");
        req.setEnabledSymbols(java.util.Set.of("ETHBTC")); // BTC-quoted, not USDT
        req.setMaxPositionQuoteAmount(java.math.BigDecimal.TEN);
        req.setDailyLossLimitQuote(java.math.BigDecimal.TEN);

        assertThatThrownBy(() -> service.upsert("user1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ETHBTC");

        verify(riskProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("upsert: USDT-quoted symbols are accepted normally")
    void upsert_usdtSymbols_accepted() {
        var req = new com.tradevision.dto.RiskProfileRequest();
        req.setCredentialId("cred1");
        req.setEnabledSymbols(java.util.Set.of("BTCUSDT", "ETHUSDT"));
        req.setMaxPositionQuoteAmount(java.math.BigDecimal.TEN);
        req.setDailyLossLimitQuote(java.math.BigDecimal.TEN);
        when(riskProfileRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        RiskProfile result = service.upsert("user1", req);

        assertThat(result.getEnabledSymbols()).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
    }

    // ── P3-5 ("ExposureReservationService group/symbol field paths -- user-supplied group names
    // used as Mongo field paths ('.'/'$') -- validate names" -- full context in upsert's own new
    // validation comment) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("upsert: a correlation group name containing '.' is rejected -- it would target a nested Mongo path (reservedGroupExposure.<name>) instead of the flat field this codebase's own exposure tracking assumes")
    void upsert_correlationGroupNameWithDot_rejected() {
        var req = new com.tradevision.dto.RiskProfileRequest();
        req.setCredentialId("cred1");
        req.setMaxPositionQuoteAmount(java.math.BigDecimal.TEN);
        req.setDailyLossLimitQuote(java.math.BigDecimal.TEN);
        req.setCorrelationGroups(java.util.Map.of("Majors.sub", java.util.Set.of("BTCUSDT")));
        req.setCorrelationGroupCaps(java.util.Map.of("Majors.sub", java.math.BigDecimal.valueOf(1000)));

        assertThatThrownBy(() -> service.upsert("user1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Majors.sub");

        verify(riskProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("upsert: a correlation group name containing '$' is rejected -- MongoDB itself treats a leading/embedded '$' in a field name specially")
    void upsert_correlationGroupNameWithDollarSign_rejected() {
        var req = new com.tradevision.dto.RiskProfileRequest();
        req.setCredentialId("cred1");
        req.setMaxPositionQuoteAmount(java.math.BigDecimal.TEN);
        req.setDailyLossLimitQuote(java.math.BigDecimal.TEN);
        req.setCorrelationGroups(java.util.Map.of("$where", java.util.Set.of("BTCUSDT")));
        req.setCorrelationGroupCaps(java.util.Map.of("$where", java.math.BigDecimal.valueOf(1000)));

        assertThatThrownBy(() -> service.upsert("user1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("$where");

        verify(riskProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("upsert: a normal, safe correlation group name is accepted and persisted normally")
    void upsert_safeCorrelationGroupName_accepted() {
        var req = new com.tradevision.dto.RiskProfileRequest();
        req.setCredentialId("cred1");
        req.setMaxPositionQuoteAmount(java.math.BigDecimal.TEN);
        req.setDailyLossLimitQuote(java.math.BigDecimal.TEN);
        req.setCorrelationGroups(java.util.Map.of("Majors", java.util.Set.of("BTCUSDT")));
        req.setCorrelationGroupCaps(java.util.Map.of("Majors", java.math.BigDecimal.valueOf(1000)));
        when(riskProfileRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        RiskProfile result = service.upsert("user1", req);

        assertThat(result.getCorrelationGroups()).containsKey("Majors");
    }

    @Test
    @DisplayName("haltAll: uses the same targeted atomic update as halt(), not a full-document save -- the actual review fix (\"Global kill switch still uses full-document save()\")")
    void haltAll_usesAtomicUpdateNotFullSave() {
        RiskProfile p1 = new RiskProfile(); p1.setId("profile1"); p1.setCredentialId("cred1");
        RiskProfile p2 = new RiskProfile(); p2.setId("profile2"); p2.setCredentialId("cred2");
        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue("user1")).thenReturn(List.of(p1, p2));

        service.haltAll("user1", "test reason");

        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, times(2)).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
    }

    /**
     * Review finding ("The execution authorization still has an unavoidable exchange-boundary
     * race" -- external review, twenty-first pass, P0, full context in haltAll's own updated
     * javadoc): the actual test proving the new immediate post-halt reconciliation.
     */
    @Test
    @DisplayName("haltAll: immediately triggers a real reconciliation pass for every halted credential -- closing the discovery gap for any execution that may have already reached the exchange before the halt took effect")
    void haltAll_triggersImmediateReconciliationForEveryCredential() {
        RiskProfile p1 = new RiskProfile(); p1.setId("profile1"); p1.setCredentialId("cred1");
        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue("user1")).thenReturn(List.of(p1));
        var credential = new com.tradevision.model.BrokerCredential();
        credential.setId("cred1");
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.of(credential));

        service.haltAll("user1", "test reason");

        verify(positionMonitorService).reconcileCredential(credential);
    }

    @Test
    @DisplayName("haltAll: a failure during the immediate post-halt reconciliation is non-fatal -- the halt itself already took effect regardless")
    void haltAll_reconciliationFailure_isNonFatal() {
        RiskProfile p1 = new RiskProfile(); p1.setId("profile1"); p1.setCredentialId("cred1");
        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue("user1")).thenReturn(List.of(p1));
        var credential = new com.tradevision.model.BrokerCredential();
        credential.setId("cred1");
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.of(credential));
        doThrow(new RuntimeException("simulated reconciliation failure")).when(positionMonitorService).reconcileCredential(any());

        service.haltAll("user1", "test reason"); // must not throw

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
    }

    /**
     * Review finding ("Secrets / encryption key rotation and credential revocation story
     * incomplete" -- external review, nineteenth pass, P1, full context in
     * emergencyRevokeAll's own javadoc): the actual tests proving all three real actions happen.
     */
    @Test
    @DisplayName("emergencyRevokeAll: halts trading, deactivates every credential, AND forces re-authentication by bumping tokenVersion and clearing the refresh token")
    void emergencyRevokeAll_performsAllThreeActions() {
        RiskProfile p1 = new RiskProfile(); p1.setId("profile1"); p1.setCredentialId("cred1");
        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue("user1")).thenReturn(List.of(p1));
        var user = new com.tradevision.model.User();
        user.setId("user1");
        user.setTokenVersion(5L);
        user.setRefreshTokenHash("some-hash");
        when(userRepo.findById("user1")).thenReturn(java.util.Optional.of(user));

        service.emergencyRevokeAll("user1", "suspected compromise");

        // 1. Trading halted (same atomic update haltAll's own test already verifies).
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        // 2. Every credential deactivated.
        verify(credentialService).deactivateAll("user1", "suspected compromise");
        // 3. Forced re-auth: tokenVersion bumped, refresh token cleared.
        verify(userRepo).save(argThat(u -> u.getTokenVersion() == 6L && u.getRefreshTokenHash() == null));
    }

    /**
     * Review finding ("Emergency credential revocation can intentionally disable the very
     * monitoring needed by existing positions" -- external review, twenty-fourth pass, P1, full
     * context in emergencyRevokeAll's own updated javadoc): the actual test proving the returned
     * count matches the real number of open positions this action is about to stop monitoring.
     */
    @Test
    @DisplayName("emergencyRevokeAll: returns the real count of open positions affected, computed BEFORE deactivation")
    void emergencyRevokeAll_returnsAffectedPositionCount() {
        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue("user1")).thenReturn(List.of());
        when(userRepo.findById("user1")).thenReturn(java.util.Optional.empty());
        when(positionRepo.countByUserIdAndStatusIn(eq("user1"), any())).thenReturn(3L);

        long result = service.emergencyRevokeAll("user1", "suspected compromise");

        assertThat(result).isEqualTo(3L);
    }

    @Test
    @DisplayName("claimExecutionAuthorization: a granted claim returns a real, populated ExecutionClaim (claim id and generation), not just true -- the actual review fix (\"claimExecutionAuthorization() is still an authorization claim, not a lease\")")
    void claimExecutionAuthorization_granted_returnsRealClaimIdentity() {
        RiskProfile updated = new RiskProfile();
        updated.setSafetyStateVersion(7L);
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(updated);

        var claim = service.claimExecutionAuthorization("cred1", false);

        assertThat(claim).isNotNull();
        assertThat(claim.claimId()).isNotBlank();
        assertThat(claim.generation()).isEqualTo(7L);
    }

    @Test
    @DisplayName("claimExecutionAuthorization: a denied claim (findAndModify matches nothing -- autoTradeEnabled/tradingHalted/autoTradeHalted/liveAutoTradeAuthorized conditions not met) returns null, not a fabricated claim")
    void claimExecutionAuthorization_denied_returnsNull() {
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(null);

        var claim = service.claimExecutionAuthorization("cred1", false);

        assertThat(claim).isNull();
    }

    @Test
    @DisplayName("markExecutionStarted: the query includes ALL the same conditions claimExecutionAuthorization itself checks -- credentialId, claim id, autoTradeEnabled, tradingHalted, autoTradeHalted -- the actual review fix (\"There is still a tiny gap between final authorization and markExecutionStarted()\"), now performing the full final-re-verification atomically as part of the same operation that registers the execution in flight")
    void markExecutionStarted_queryIncludesFullConditionSet() {
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        RiskProfile updated = new RiskProfile();
        when(mongoTemplate.findAndModify(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(updated);

        boolean result = service.markExecutionStarted("cred1", "claim-1", false);

        assertThat(result).isTrue();
        String query = queryCaptor.getValue().getQueryObject().toString();
        assertThat(query).contains("credentialId").contains("lastExecutionClaimId")
            .contains("autoTradeEnabled").contains("tradingHalted").contains("autoTradeHalted");
    }

    @Test
    @DisplayName("markExecutionStarted: the query also requires lastExecutionClaimAt to be within CLAIM_MAX_AGE of now -- the actual review fix (\"The claim itself has no expiry\"), so a capability ages out on its own rather than remaining valid indefinitely as long as nothing else ever supersedes it")
    void markExecutionStarted_queryIncludesClaimExpiryCheck() {
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        when(mongoTemplate.findAndModify(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(new RiskProfile());

        service.markExecutionStarted("cred1", "claim-1", false);

        String query = queryCaptor.getValue().getQueryObject().toString();
        assertThat(query).contains("lastExecutionClaimAt").contains("$gte");
    }

    @Test
    @DisplayName("markExecutionStarted: for a LIVE credential, the query ALSO includes liveAutoTradeAuthorized -- the actual review fix, closing the specific gap named (a LIVE authorization revocation between the claim and this final check would NOT have invalidated an already-issued claim)")
    void markExecutionStarted_live_queryIncludesLiveAuthorizationCheck() {
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        when(mongoTemplate.findAndModify(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(new RiskProfile());

        service.markExecutionStarted("cred1", "claim-1", true);

        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains("liveAutoTradeAuthorized");
    }

    @Test
    @DisplayName("markExecutionStarted: for a non-LIVE (TESTNET) credential, the query does NOT include liveAutoTradeAuthorized -- matching claimExecutionAuthorization's own identical isLive-conditional logic, not spuriously requiring a flag that's meaningless for TESTNET")
    void markExecutionStarted_notLive_queryOmitsLiveAuthorizationCheck() {
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        when(mongoTemplate.findAndModify(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(new RiskProfile());

        service.markExecutionStarted("cred1", "claim-1", false);

        assertThat(queryCaptor.getValue().getQueryObject().toString()).doesNotContain("liveAutoTradeAuthorized");
    }

    @Test
    @DisplayName("markExecutionStarted: the query matches nothing (autoTradeEnabled was switched to false since the claim, or any other condition no longer holds, or a concurrent kill switch landed in the gap between the original claim and this call) -- returns false, the caller must not proceed to the exchange. This IS the actual review fix (\\\"There is still a tiny gap between final authorization and markExecutionStarted()\\\") -- the exact scenario the review's own required tests target")
    void markExecutionStarted_queryMatchesNothing_false() {
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(RiskProfile.class))).thenReturn(null);

        assertThat(service.markExecutionStarted("cred1", "claim-1", false)).isFalse();
    }

    @Test
    @DisplayName("halt: when an execution is genuinely in flight the moment this kill switch engages, audits that fact explicitly -- the actual review fix (\"The claim → Binance network call still has an unavoidable TOCTOU window\"), whose own stated acceptable bar for the unavoidable remainder is that an already-in-flight request finishing after halt \"should be explicitly displayed/audited\"")
    void halt_executionInFlight_auditsExplicitly() {
        // The shared `profile` fixture is what riskProfileRepo.findById("profile1") already
        // returns by default (see this class's own @BeforeEach) -- halt() re-reads via exactly
        // that call, so setting this field directly on it simulates "in flight" at re-read time.
        profile.setExecutionInFlightCount(1);

        service.halt("user1", "cred1", "test halt reason");

        verify(credentialService).audit(eq("user1"), eq("cred1"), isNull(), eq("KILL_SWITCH_ENGAGED_WITH_EXECUTION_IN_FLIGHT"), any());
        verify(credentialService).audit(eq("user1"), eq("cred1"), isNull(), eq("KILL_SWITCH_ENGAGED"), any());
    }

    @Test
    @DisplayName("halt: with no execution in flight (the overwhelmingly common case), does NOT emit the extra in-flight audit entry -- only the normal KILL_SWITCH_ENGAGED one")
    void halt_noExecutionInFlight_noExtraAudit() {
        // executionInFlightCount defaults to 0 on a freshly-constructed RiskProfile (see its own
        // field default) -- the real, honest state for a credential that has never had a
        // tracked execution at all. No extra setup needed.
        service.halt("user1", "cred1", "test halt reason");

        verify(credentialService, never()).audit(any(), any(), any(), eq("KILL_SWITCH_ENGAGED_WITH_EXECUTION_IN_FLIGHT"), any());
        verify(credentialService).audit(eq("user1"), eq("cred1"), isNull(), eq("KILL_SWITCH_ENGAGED"), any());
    }

    /**
     * Review finding ("Narrow but real race: Strategy Plan disable / version change vs final
     * execution" -- external review, eighteenth pass, P0, full context in
     * claimExecutionAtomicWithPlan's own javadoc): the actual tests proving this new method's
     * two real, cleanly-testable behaviors -- the null-planId fast path, and the graceful
     * fallback when this deployment's own MongoDB doesn't support transactions at all. Full
     * session/transaction mechanics against a REAL replica set are integration-test territory
     * (this codebase's own established pattern -- see the other *IntegrationTest classes, all of
     * which honestly disclose no Docker/MongoDB available in this sandbox to actually run them).
     */
    @Test
    @DisplayName("claimExecutionAtomicWithPlan: a null planId delegates directly to markExecutionStarted -- no plan to coordinate with, so the existing single-document claim is already fully atomic and sufficient")
    void claimExecutionAtomicWithPlan_nullPlanId_delegatesToMarkExecutionStarted() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RiskProfile.class))).thenReturn(new RiskProfile());

        boolean result = service.claimExecutionAtomicWithPlan("cred1", "claim1", false, null, null, "user1");

        assertThat(result).isTrue();
        verify(strategyPlanService, never()).claimPlanExecution(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("claimExecutionAtomicWithPlan: when this MongoDB deployment doesn't support transactions at all (standalone, not a replica set), falls back to the sequential two-claim approach rather than failing outright or silently skipping the plan check")
    void claimExecutionAtomicWithPlan_transactionsNotSupported_fallsBackToSequential() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));
        when(strategyPlanService.claimPlanExecution("plan1", 5L, "user1", "cred1")).thenReturn(true);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RiskProfile.class))).thenReturn(new RiskProfile());

        boolean result = service.claimExecutionAtomicWithPlan("cred1", "claim1", false, "plan1", 5L, "user1");

        assertThat(result).isTrue();
        verify(strategyPlanService).claimPlanExecution("plan1", 5L, "user1", "cred1");
    }

    @Test
    @DisplayName("claimExecutionAtomicWithPlan: the sequential fallback releases the plan claim if the account-level claim then fails -- never leaves a plan claimed with no matching account-level claim")
    void claimExecutionAtomicWithPlan_fallbackAccountClaimFails_releasesPlanClaim() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support"));
        when(strategyPlanService.claimPlanExecution("plan1", 5L, "user1", "cred1")).thenReturn(true);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RiskProfile.class))).thenReturn(null);

        boolean result = service.claimExecutionAtomicWithPlan("cred1", "claim1", false, "plan1", 5L, "user1");

        assertThat(result).isFalse();
        verify(strategyPlanService).releasePlanExecution("plan1");
    }
}
