package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerType;
import com.tradevision.model.RiskProfile;
import com.tradevision.model.StrategyPlan;
import com.tradevision.model.TradeDirection;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.repository.StrategyPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyPlanServiceTest {

    @Mock StrategyPlanRepository strategyPlanRepo;
    @Mock RiskProfileRepository riskProfileRepo;
    @Mock com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    @Mock DynamicUniverseService dynamicUniverseService;
    @Mock com.tradevision.service.broker.BrokerAdapter adapter;
    @Mock com.tradevision.repository.PositionRepository positionRepo;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @InjectMocks StrategyPlanService service;

    BrokerCredential credential;

    @BeforeEach
    void setUp() {
        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setBroker(BrokerType.BINANCE);
    }

    @Test
    @DisplayName("getOrCreateDefaultPlan: an existing default plan is returned as-is, never duplicated -- idempotent")
    void getOrCreateDefaultPlan_alreadyExists_returnsUnchanged() {
        StrategyPlan existing = new StrategyPlan();
        existing.setId("plan1");
        when(strategyPlanRepo.findByCredentialIdAndDefaultPlanTrue("cred1")).thenReturn(Optional.of(existing));

        StrategyPlan result = service.getOrCreateDefaultPlan("cred1");

        assertThat(result).isSameAs(existing);
    }

    @Test
    @DisplayName("getOrCreateDefaultPlan: with no default plan yet, creates one that exactly mirrors the credential's existing RiskProfile settings")
    void getOrCreateDefaultPlan_noneExists_migratesFromRiskProfile() {
        when(strategyPlanRepo.findByCredentialIdAndDefaultPlanTrue("cred1")).thenReturn(Optional.empty());
        RiskProfile profile = new RiskProfile();
        profile.setUserId("user1");
        profile.setCredentialId("cred1");
        profile.setScanTimeframe("15m");
        profile.setEnabledSymbols(Set.of("DOGEUSDT"));
        profile.setDynamicUniverseEnabled(true);
        profile.setDynamicUniverseMaxSymbols(7);
        profile.setRiskPerTradePercent(2.5);
        profile.setMaxConcurrentTrades(4);
        profile.setMinConfidence(80.0);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(profile));
        when(strategyPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyPlan result = service.getOrCreateDefaultPlan("cred1");

        assertThat(result.isDefaultPlan()).isTrue();
        assertThat(result.getUserId()).isEqualTo("user1");
        assertThat(result.getTimeframe()).isEqualTo("15m");
        assertThat(result.getEnabledSymbols()).containsExactly("DOGEUSDT");
        assertThat(result.isDynamicUniverseEnabled()).isTrue();
        assertThat(result.getDynamicUniverseMaxSymbols()).isEqualTo(7);
        assertThat(result.getRiskPerTradePercent()).isEqualTo(2.5);
        assertThat(result.getMaxConcurrentTrades()).isEqualTo(4);
        assertThat(result.getMinConfidence()).isEqualTo(80.0);
        assertThat(result.getDirection()).isEqualTo(TradeDirection.LONG);
        // Migrated default must not silently introduce exit behavior this codebase doesn't
        // actually have today.
        assertThat(result.getMaxHoldMinutes()).isNull();
        assertThat(result.isExitOnSignalReversal()).isFalse();
    }

    @Test
    @DisplayName("validateDirectionForMarket: LONG is always valid")
    void validateDirectionForMarket_long_valid() {
        service.validateDirectionForMarket(TradeDirection.LONG, credential); // must not throw
    }

    @Test
    @DisplayName("validateDirectionForMarket: SHORT is rejected for a Binance (spot) credential, which cannot actually short")
    void validateDirectionForMarket_shortOnBinance_rejected() {
        assertThatThrownBy(() -> service.validateDirectionForMarket(TradeDirection.SHORT, credential))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("validateDirectionForMarket: BOTH is rejected for a Binance (spot) credential, same as SHORT -- BOTH still requires short capability")
    void validateDirectionForMarket_bothOnBinance_rejected() {
        assertThatThrownBy(() -> service.validateDirectionForMarket(TradeDirection.BOTH, credential))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("validateDirectionForMarket: SHORT is also rejected for MUDREX, which has no working adapter at all -- never assume support for a connection type that doesn't functionally exist yet")
    void validateDirectionForMarket_shortOnMudrex_alsoRejected() {
        credential.setBroker(BrokerType.MUDREX);

        assertThatThrownBy(() -> service.validateDirectionForMarket(TradeDirection.SHORT, credential))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create: rejects a credential that doesn't belong to this user -- never lets one user create a plan against another user's broker connection")
    void create_credentialNotOwnedByUser_rejected() {
        credential.setUserId("someone-else");
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.create("user1", req)).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("create: succeeds for a credential the user actually owns, applying every field from the request")
    void create_ownedCredential_savesWithAllFields() {
        credential.setUserId("user1");
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setName("Scalper"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);
        req.setMaxHoldMinutes(60);
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));
        when(strategyPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyPlan result = service.create("user1", req);

        assertThat(result.getUserId()).isEqualTo("user1");
        assertThat(result.getName()).isEqualTo("Scalper");
        assertThat(result.getTimeframe()).isEqualTo("5m");
        assertThat(result.getMaxHoldMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("update: a plan owned by a DIFFERENT user is rejected -- ownership check must never leak whether the plan id even exists, using the same message as a genuine not-found")
    void update_planOwnedByDifferentUser_rejected() {
        StrategyPlan existing = new StrategyPlan();
        existing.setId("plan1"); existing.setUserId("someone-else");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(existing));
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);

        assertThatThrownBy(() -> service.update("user1", "plan1", req)).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("setEnabled: a plan owned by a DIFFERENT user is rejected -- same ownership discipline as update/delete")
    void setEnabled_planOwnedByDifferentUser_rejected() {
        StrategyPlan existing = new StrategyPlan();
        existing.setId("plan1"); existing.setUserId("someone-else");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.setEnabled("user1", "plan1", false)).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("delete: a plan owned by a DIFFERENT user is rejected, never deleted")
    void delete_planOwnedByDifferentUser_rejected() {
        StrategyPlan existing = new StrategyPlan();
        existing.setId("plan1"); existing.setUserId("someone-else");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete("user1", "plan1")).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).delete(any(StrategyPlan.class));
    }

    @Test
    @DisplayName("delete: a plan with an OPEN position still depending on it is rejected -- the user's own explicit design (\"DELETE plan + open positions -> REJECT\"), so an open position never ends up with a planId pointing at nothing")
    void delete_planHasOpenPosition_rejected() {
        StrategyPlan plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setName("Scalper");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(positionRepo.existsByPlanIdAndStatus("plan1", "OPEN")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("user1", "plan1")).isInstanceOf(IllegalStateException.class);
        verify(strategyPlanRepo, never()).delete(any(StrategyPlan.class));
    }

    @Test
    @DisplayName("delete: a plan with NO open positions is deleted normally")
    void delete_planHasNoOpenPositions_deleted() {
        StrategyPlan plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(positionRepo.existsByPlanIdAndStatus("plan1", "OPEN")).thenReturn(false);

        service.delete("user1", "plan1");

        verify(strategyPlanRepo).delete(plan);
    }

    @Test
    @DisplayName("list: only returns plans actually owned by this user, even if the repository query is credential-scoped rather than user-scoped")
    void list_onlyReturnsOwnedPlans() {
        StrategyPlan mine = new StrategyPlan(); mine.setId("plan1"); mine.setUserId("user1");
        StrategyPlan notMine = new StrategyPlan(); notMine.setId("plan2"); notMine.setUserId("someone-else");
        when(strategyPlanRepo.findByCredentialId("cred1")).thenReturn(List.of(mine, notMine));

        var result = service.list("user1", "cred1");

        assertThat(result).containsExactly(mine);
    }

    @Test
    @DisplayName("isWithinSession: ALWAYS_ON (the default) is always true -- the user's own explicit instruction that 24/7 must be a true no-op, not a session check that always happens to pass")
    void isWithinSession_alwaysOn_true() {
        var plan = new StrategyPlan(); // sessionMode defaults to ALWAYS_ON, left untouched

        assertThat(service.isWithinSession(plan)).isTrue();
    }

    @Test
    @DisplayName("isWithinSession: a DAILY session covering the entire 24-hour day is within session regardless of when this test actually runs -- time-independent by construction, not by luck")
    void isWithinSession_dailyFullDayWindow_true() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.MIDNIGHT);
        plan.setSessionEnd(java.time.LocalTime.of(23, 59, 59));
        plan.setSessionTimezone("UTC");

        assertThat(service.isWithinSession(plan)).isTrue();
    }

    @Test
    @DisplayName("isWithinSession: a zero-width DAILY session (start == end) can never be within session -- time-independent by construction: \"now\" cannot simultaneously be >= and < the identical instant")
    void isWithinSession_zeroWidthWindow_alwaysFalse() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.NOON);
        plan.setSessionEnd(java.time.LocalTime.NOON);
        plan.setSessionTimezone("UTC");

        assertThat(service.isWithinSession(plan)).isFalse();
    }

    @Test
    @DisplayName("isWithinSession: CUSTOM_DAYS on a day of the week not in the configured set is never within session, even during the configured time window -- time-independent: covers the full 24-hour window but excludes today's own actual day of week")
    void isWithinSession_customDaysNotToday_false() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.CUSTOM_DAYS);
        plan.setSessionStart(java.time.LocalTime.MIDNIGHT);
        plan.setSessionEnd(java.time.LocalTime.of(23, 59, 59));
        plan.setSessionTimezone("UTC");
        // Every day EXCEPT today's own actual day of week, in UTC -- guarantees today is excluded
        // regardless of which day this test actually runs on.
        var allExceptToday = new java.util.HashSet<>(java.util.List.of(java.time.DayOfWeek.values()));
        allExceptToday.remove(java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")).getDayOfWeek());
        plan.setSessionDays(allExceptToday);

        assertThat(service.isWithinSession(plan)).isFalse();
    }

    @Test
    @DisplayName("isWithinSession: an invalid timezone string fails CLOSED (returns false), since for a real-money system a malformed session must never accidentally bypass a user's own trading boundary")
    void isWithinSession_invalidTimezone_failsClosed() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.of(9, 0));
        plan.setSessionEnd(java.time.LocalTime.of(15, 0));
        plan.setSessionTimezone("Not/A_Real_Timezone");

        assertThat(service.isWithinSession(plan)).isFalse();
    }

    @Test
    @DisplayName("isSessionConfigValid: an invalid timezone is correctly identified as invalid config, distinct from \"valid config, currently outside the window\"")
    void isSessionConfigValid_invalidTimezone_false() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.of(9, 0));
        plan.setSessionEnd(java.time.LocalTime.of(15, 0));
        plan.setSessionTimezone("Not/A_Real_Timezone");

        assertThat(service.isSessionConfigValid(plan)).isFalse();
    }

    @Test
    @DisplayName("isSessionConfigValid: CUSTOM_DAYS with an empty sessionDays set is invalid config")
    void isSessionConfigValid_customDaysEmptySet_false() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.CUSTOM_DAYS);
        plan.setSessionStart(java.time.LocalTime.of(9, 0));
        plan.setSessionEnd(java.time.LocalTime.of(15, 0));
        plan.setSessionTimezone("UTC");
        plan.setSessionDays(new java.util.HashSet<>());

        assertThat(service.isSessionConfigValid(plan)).isFalse();
    }

    @Test
    @DisplayName("isSessionConfigValid: ALWAYS_ON is always valid config -- nothing to misconfigure")
    void isSessionConfigValid_alwaysOn_true() {
        var plan = new StrategyPlan(); // sessionMode defaults to ALWAYS_ON

        assertThat(service.isSessionConfigValid(plan)).isTrue();
    }

    @Test
    @DisplayName("isSessionConfigValid: a complete, valid DAILY configuration is valid")
    void isSessionConfigValid_completeDailyConfig_true() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.of(9, 0));
        plan.setSessionEnd(java.time.LocalTime.of(15, 0));
        plan.setSessionTimezone("Asia/Kolkata");

        assertThat(service.isSessionConfigValid(plan)).isTrue();
    }

    @Test
    @DisplayName("isWithinSession: an overnight session (start > end, e.g. 22:00 -> 02:00) is correctly evaluated as spanning midnight. Time-independent by construction: covers 23 of 24 hours, excluding only a 1-minute window, so this passes regardless of when the test actually runs")
    void isWithinSession_overnightSessionCoveringNearlyFullDay_true() {
        var plan = new StrategyPlan();
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.of(0, 1)); // 00:01
        plan.setSessionEnd(java.time.LocalTime.of(0, 0));   // 00:00 the "next" day -- start > end, overnight
        plan.setSessionTimezone("UTC");

        assertThat(service.isWithinSession(plan)).isTrue();
    }

    @Test
    @DisplayName("validateSessionConfig (via create): rejects a DAILY plan missing sessionStart, catching a broken config before it's ever saved rather than relying on isSessionConfigValid's own runtime fail-closed handling")
    void create_dailyModeMissingSessionStart_rejected() {
        credential.setUserId("user1");
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);
        req.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        req.setSessionEnd(java.time.LocalTime.of(15, 0));
        req.setSessionTimezone("Asia/Kolkata");
        // sessionStart deliberately left null
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.create("user1", req)).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("validateSessionConfig (via create): rejects an invalid IANA timezone string")
    void create_invalidTimezone_rejected() {
        credential.setUserId("user1");
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);
        req.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        req.setSessionStart(java.time.LocalTime.of(9, 0)); req.setSessionEnd(java.time.LocalTime.of(15, 0));
        req.setSessionTimezone("Definitely/Not/Real");
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.create("user1", req)).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("validateSessionConfig (via create): rejects CUSTOM_DAYS with no sessionDays selected")
    void create_customDaysEmptySet_rejected() {
        credential.setUserId("user1");
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);
        req.setSessionMode(com.tradevision.model.SessionMode.CUSTOM_DAYS);
        req.setSessionStart(java.time.LocalTime.of(9, 0)); req.setSessionEnd(java.time.LocalTime.of(15, 0));
        req.setSessionTimezone("UTC");
        req.setSessionDays(new java.util.HashSet<>());
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.create("user1", req)).isInstanceOf(IllegalArgumentException.class);
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("validateSessionConfig (via create): a complete, valid session configuration is accepted")
    void create_completeValidSessionConfig_accepted() {
        credential.setUserId("user1");
        var req = new com.tradevision.dto.StrategyPlanRequest();
        req.setCredentialId("cred1"); req.setTimeframe("5m"); req.setDirection(TradeDirection.LONG);
        req.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        req.setSessionStart(java.time.LocalTime.of(9, 0)); req.setSessionEnd(java.time.LocalTime.of(15, 0));
        req.setSessionTimezone("Asia/Kolkata");
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));
        when(strategyPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyPlan result = service.create("user1", req);

        assertThat(result.getSessionMode()).isEqualTo(com.tradevision.model.SessionMode.DAILY);
    }

    @Test
    @DisplayName("getEnabledPlans: when NO plan has ever existed for this credential, creates and returns the default plan")
    void getEnabledPlans_noPlanEverExisted_createsDefault() {
        when(strategyPlanRepo.findByCredentialId("cred1")).thenReturn(List.of());
        when(strategyPlanRepo.findByCredentialIdAndDefaultPlanTrue("cred1")).thenReturn(Optional.empty());
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.empty());
        when(strategyPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.getEnabledPlans("cred1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isDefaultPlan()).isTrue();
    }

    @Test
    @DisplayName("getEnabledPlans: when plans exist but the user has disabled ALL of them (including a disabled default), returns an EMPTY list, never silently falling back to a plan the user deliberately turned off")
    void getEnabledPlans_allPlansDisabledIncludingDefault_returnsEmpty() {
        StrategyPlan disabledDefault = new StrategyPlan();
        disabledDefault.setId("plan1"); disabledDefault.setDefaultPlan(true); disabledDefault.setEnabled(false);
        StrategyPlan disabledOther = new StrategyPlan();
        disabledOther.setId("plan2"); disabledOther.setEnabled(false);
        when(strategyPlanRepo.findByCredentialId("cred1")).thenReturn(List.of(disabledDefault, disabledOther));

        var result = service.getEnabledPlans("cred1");

        assertThat(result).isEmpty();
        // getOrCreateDefaultPlan's own create path must never be reached when plans already
        // exist, even if all disabled.
        verify(strategyPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("getEnabledPlans: when some plans are enabled and others are not, returns only the enabled ones")
    void getEnabledPlans_mixedEnabledDisabled_returnsOnlyEnabled() {
        StrategyPlan enabled = new StrategyPlan(); enabled.setId("plan1"); enabled.setEnabled(true);
        StrategyPlan disabled = new StrategyPlan(); disabled.setId("plan2"); disabled.setEnabled(false);
        when(strategyPlanRepo.findByCredentialId("cred1")).thenReturn(List.of(enabled, disabled));

        var result = service.getEnabledPlans("cred1");

        assertThat(result).containsExactly(enabled);
    }

    @Test
    @DisplayName("authorizeExecution: a signal with NO plan at all is allowed -- backward compat, the caller falls back to its own legacy check")
    void authorizeExecution_noPlan_allowed() {
        var result = service.authorizeExecution(null, "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isTrue();
    }

    @Test
    @DisplayName("authorizeExecution: a plan that no longer exists (deleted) is denied -- planId is never trusted as bare metadata")
    void authorizeExecution_planDeleted_denied() {
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.empty());

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a plan belonging to a DIFFERENT user is denied -- plan ownership is verified at execution, never trusting signal.planId as a bare security boundary")
    void authorizeExecution_ownershipMismatch_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("someone-else"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a plan belonging to a DIFFERENT credential is denied")
    void authorizeExecution_credentialMismatch_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("some-other-cred"); plan.setEnabled(true);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a DISABLED plan is denied at the final execution gate")
    void authorizeExecution_disabledPlan_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(false);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a plan outside its own currently-open session is denied at the final execution gate")
    void authorizeExecution_outsideSession_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setSessionStart(java.time.LocalTime.NOON); plan.setSessionEnd(java.time.LocalTime.NOON); // zero-width -- never inside session
        plan.setSessionTimezone("UTC");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a SHORT-only plan denies every signal that reaches it -- every signal reaching this method is LONG (this codebase's own scanner never dispatches anything else), so a SHORT-only plan can never legitimately authorize one")
    void authorizeExecution_shortOnlyPlan_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        plan.setDirection(TradeDirection.SHORT);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a symbol not in the plan's own fixed universe AND not TIER1 AND with dynamic universe disabled is denied -- a symbol must be traceable to THIS plan's own real universe")
    void authorizeExecution_symbolNotInPlanUniverse_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        plan.setEnabledSymbols(Set.of("ETHUSDT")); // DOGEUSDT is neither TIER1 nor in this fixed list
        plan.setDynamicUniverseEnabled(false);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        // Profile whitelist explicitly includes DOGEUSDT -- proves the denial is genuinely
        // because the PLAN's own universe doesn't cover it, not the profile whitelist.
        var result = service.authorizeExecution("plan1", "user1", "cred1", "DOGEUSDT", adapter, credential, Set.of("DOGEUSDT", "ETHUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: a symbol legitimately discovered by the plan's own dynamic universe IS authorized, even if not manually present in the legacy Risk Profile symbol list")
    void authorizeExecution_symbolInDynamicUniverse_allowed() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        plan.setDynamicUniverseEnabled(true); plan.setDynamicUniverseMaxSymbols(10);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(dynamicUniverseService.selectTopCandidates(adapter, credential, 10)).thenReturn(List.of("DOGEUSDT", "AVAXUSDT"));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "DOGEUSDT", adapter, credential, Set.of("DOGEUSDT"));

        assertThat(result.authorized()).isTrue();
    }

    @Test
    @DisplayName("authorizeExecution: a TIER1 symbol is authorized when the account's own risk profile has ALSO explicitly enabled it -- TIER1 membership is one candidate source among several, not a free pass on its own")
    void authorizeExecution_tier1Symbol_allowed() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("BTCUSDT"));

        assertThat(result.authorized()).isTrue();
    }

    @Test
    @DisplayName("authorizeExecution: a TIER1 symbol the account's own risk profile never explicitly enabled is DENIED, even though a plan exists and TIER1 is still scanned -- a user who configured \"only ADAUSDT\" must never have BTC/ETH/SOL/BNB/XRP execute with real money just because they're TIER1")
    void authorizeExecution_tier1SymbolNotInProfileWhitelist_denied() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        // The account only ever explicitly enabled ADAUSDT -- BTCUSDT is TIER1, but was never
        // chosen by this user.
        var result = service.authorizeExecution("plan1", "user1", "cred1", "BTCUSDT", adapter, credential, Set.of("ADAUSDT"));

        assertThat(result.authorized()).isFalse();
    }

    @Test
    @DisplayName("authorizeExecution: everything valid -- ownership matches, plan enabled, ALWAYS_ON session, LONG direction, TIER1 symbol, profile whitelist includes it -- is authorized")
    void authorizeExecution_allValid_allowed() {
        var plan = new StrategyPlan();
        plan.setId("plan1"); plan.setUserId("user1"); plan.setCredentialId("cred1"); plan.setEnabled(true);
        plan.setDirection(TradeDirection.LONG);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        var result = service.authorizeExecution("plan1", "user1", "cred1", "ETHUSDT", adapter, credential, Set.of("ETHUSDT"));

        assertThat(result.authorized()).isTrue();
    }

    @Test
    @DisplayName("claimPlanExecution: a null planId is always allowed (no-op) -- a signal with no plan at all has nothing to claim")
    void claimPlanExecution_nullPlanId_alwaysAllowed() {
        boolean result = service.claimPlanExecution(null, 1L, "user1", "cred1");

        assertThat(result).isTrue();
        verify(mongoTemplate, never()).findAndModify(any(org.springframework.data.mongodb.core.query.Query.class),
            any(org.springframework.data.mongodb.core.query.Update.class), any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(StrategyPlan.class));
    }

    @Test
    @DisplayName("claimPlanExecution: when the atomic findAndModify matches (ownership, credential, enabled, and version all still line up), the claim succeeds")
    void claimPlanExecution_findAndModifyMatches_succeeds() {
        var updatedPlan = new StrategyPlan();
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(StrategyPlan.class))).thenReturn(updatedPlan);

        boolean result = service.claimPlanExecution("plan1", 5L, "user1", "cred1");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("claimPlanExecution: when the plan's own current version no longer matches (edited/disabled since the signal was generated), findAndModify matches nothing and the claim atomically fails")
    void claimPlanExecution_versionMismatch_fails() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(StrategyPlan.class))).thenReturn(null);

        boolean result = service.claimPlanExecution("plan1", 5L, "user1", "cred1");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("releasePlanExecution: a null planId is a safe no-op")
    void releasePlanExecution_nullPlanId_noOp() {
        service.releasePlanExecution(null);

        verify(mongoTemplate, never()).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),
            any(org.springframework.data.mongodb.core.query.Update.class), eq(StrategyPlan.class));
    }

    @Test
    @DisplayName("releasePlanExecution: a real planId issues the atomic decrement")
    void releasePlanExecution_realPlanId_issuesDecrement() {
        service.releasePlanExecution("plan1");

        verify(mongoTemplate).updateFirst(any(), any(), eq(StrategyPlan.class));
    }
}
