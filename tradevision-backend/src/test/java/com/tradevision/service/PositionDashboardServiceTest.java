package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.Position;
import com.tradevision.repository.OrderRepository;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.TradingIncidentRepository;
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
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for PositionDashboardService.listPositions, covering pagination bounds and
 * position protection-status classification.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionDashboardServiceTest {

    @Mock PositionRepository positionRepo;
    @Mock OrderRepository orderRepo;
    @Mock BrokerCredentialService credentialService;
    @Mock PositionSafetyService positionSafetyService;
    @Mock PositionMonitorService positionMonitorService;
    @Mock TradingIncidentRepository incidentRepo;
    // listPositions fetches symbol rules via credentialService.adapterForCredential() and
    // adapter.getSymbolRules(), so both must be stubbed for the protection-classification
    // path to run against real values instead of hitting an unstubbed null.
    @Mock com.tradevision.service.broker.BrokerAdapter adapter;

    @InjectMocks PositionDashboardService service;

    @BeforeEach
    void setup() {
        BrokerCredential credential = new BrokerCredential();
        credential.setId("cred1");
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(credential);
        when(positionRepo.findByUserIdAndCredentialId(any(), any(), any(Pageable.class))).thenReturn(List.of());
        // BTCUSDT's real minQty on Binance is 0.00001, small enough that the "partially
        // protected" fixture below (a 0.1 gap) is correctly classified as PARTIAL, not
        // DUST_RESIDUAL.
        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        when(adapter.getSymbolRules(any(), any())).thenReturn(
            new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
                java.math.BigDecimal.valueOf(0.01), java.math.BigDecimal.valueOf(0.00001), java.math.BigDecimal.valueOf(0.00001),
                java.math.BigDecimal.TEN, 2, 5, java.math.BigDecimal.ZERO, false, false, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
    }

    @Test
    @DisplayName("listPositions: bounded via a real Pageable, not an unbounded query")
    void listPositions_usesPageableNotUnboundedQuery() {
        service.listPositions("user1", "cred1", null, 0, 50);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(positionRepo).findByUserIdAndCredentialId(eq("user1"), eq("cred1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
        // The old, unbounded query method must never be used by this call path.
        verify(positionRepo, org.mockito.Mockito.never()).findByUserIdAndCredentialIdOrderByOpenedAtDesc(any(), any());
    }

    @Test
    @DisplayName("listPositions: a requested page size above the 200 cap is clamped down, not honored as-is")
    void listPositions_pageSizeAboveCap_clampedTo200() {
        service.listPositions("user1", "cred1", null, 0, 100_000);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(positionRepo).findByUserIdAndCredentialId(eq("user1"), eq("cred1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("listPositions: a negative page number is clamped to 0, not passed through as-is")
    void listPositions_negativePage_clampedToZero() {
        service.listPositions("user1", "cred1", null, -5, 50);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(positionRepo).findByUserIdAndCredentialId(eq("user1"), eq("cred1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    @DisplayName("listPositions: a size of 0 or below is clamped up to at least 1, not passed through as a broken, empty-page request")
    void listPositions_zeroOrNegativeSize_clampedToAtLeastOne() {
        service.listPositions("user1", "cred1", null, 0, 0);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(positionRepo).findByUserIdAndCredentialId(eq("user1"), eq("cred1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("listPositions: an unowned credential throws before ever querying positions")
    void listPositions_unownedCredential_throwsBeforeQuerying() {
        when(credentialService.ownedCredential("user1", "cred2")).thenThrow(new IllegalArgumentException("not found"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.listPositions("user1", "cred2", null, 0, 50))
            .isInstanceOf(IllegalArgumentException.class);

        verify(positionRepo, org.mockito.Mockito.never()).findByUserIdAndCredentialId(any(), any(), any());
    }

    @Test
    @DisplayName("listPositions: protectedByOco is true only when protectedQuantity actually covers the position's full quantity, not merely because an OCO id is present")
    void listPositions_protectedByOco_requiresFullCoverage() {
        Position fullyProtected = new Position();
        fullyProtected.setId("pos1");
        fullyProtected.setUserId("user1");
        fullyProtected.setCredentialId("cred1");
        fullyProtected.setStatus("OPEN");
        fullyProtected.setQuantity(java.math.BigDecimal.valueOf(1.0));
        fullyProtected.setOcoOrderListId("oco-1");
        fullyProtected.setProtectedQuantity(java.math.BigDecimal.valueOf(1.0));

        when(positionRepo.findByUserIdAndCredentialId(eq("user1"), eq("cred1"), any(Pageable.class)))
            .thenReturn(List.of(fullyProtected));

        var result = service.listPositions("user1", "cred1", null, 0, 50);

        assertThat(result.get(0).protectedByOco()).isTrue();
        assertThat(result.get(0).protectedQuantity()).isEqualByComparingTo("1.0");
        assertThat(result.get(0).protectionStatus()).isEqualTo("FULL");
    }

    @Test
    @DisplayName("listPositions: a residual gap at or above the exchange's minQty is classified PARTIAL, representing meaningful naked exposure rather than dust")
    void listPositions_meaningfulResidual_classifiedPartial() {
        Position p = new Position();
        p.setId("pos-partial"); p.setUserId("user1"); p.setCredentialId("cred1"); p.setSymbol("BTCUSDT");
        p.setStatus("OPEN");
        p.setQuantity(java.math.BigDecimal.valueOf(1.0));
        p.setOcoOrderListId("oco-x");
        p.setProtectedQuantity(java.math.BigDecimal.valueOf(0.9)); // 0.1 residual -- well above BTCUSDT's real 0.00001 minQty
        when(positionRepo.findByUserIdAndCredentialId(eq("user1"), eq("cred1"), any(Pageable.class))).thenReturn(List.of(p));

        var result = service.listPositions("user1", "cred1", null, 0, 50);

        assertThat(result.get(0).protectionStatus()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("listPositions: a residual gap below the exchange's minQty is classified DUST_RESIDUAL, not PARTIAL, since it's an artifact of exchange step-size rounding rather than meaningful exposure")
    void listPositions_dustResidual_classifiedDustNotPartial() {
        Position p = new Position();
        p.setId("pos-dust"); p.setUserId("user1"); p.setCredentialId("cred1"); p.setSymbol("BTCUSDT");
        p.setStatus("OPEN");
        p.setQuantity(java.math.BigDecimal.valueOf(1.0));
        p.setOcoOrderListId("oco-y");
        p.setProtectedQuantity(java.math.BigDecimal.valueOf(0.999999)); // 0.000001 residual -- genuinely below BTCUSDT's real 0.00001 minQty
        when(positionRepo.findByUserIdAndCredentialId(eq("user1"), eq("cred1"), any(Pageable.class))).thenReturn(List.of(p));

        var result = service.listPositions("user1", "cred1", null, 0, 50);

        assertThat(result.get(0).protectionStatus()).isEqualTo("DUST_RESIDUAL");
    }

    @Test
    @DisplayName("listPositions: when a symbol's minQty can't be fetched, a residual gap defaults to PARTIAL rather than DUST_RESIDUAL, so classification never understates risk when uncertain")
    void listPositions_symbolRulesFetchFails_defaultsToSaferPartialClassification() {
        when(adapter.getSymbolRules(any(), any())).thenThrow(new RuntimeException("simulated exchange lookup failure"));
        Position p = new Position();
        p.setId("pos-unknown"); p.setUserId("user1"); p.setCredentialId("cred1"); p.setSymbol("BTCUSDT");
        p.setStatus("OPEN");
        p.setQuantity(java.math.BigDecimal.valueOf(1.0));
        p.setOcoOrderListId("oco-z");
        p.setProtectedQuantity(java.math.BigDecimal.valueOf(0.999999)); // would be DUST if minQty were known -- but it isn't here
        when(positionRepo.findByUserIdAndCredentialId(eq("user1"), eq("cred1"), any(Pageable.class))).thenReturn(List.of(p));

        var result = service.listPositions("user1", "cred1", null, 0, 50);

        assertThat(result.get(0).protectionStatus()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("listPositions: an OCO covering only part of the position reports protectedByOco as false, while still exposing the raw protectedQuantity so the frontend can show \"partially protected\" instead of a flat unprotected")
    void listPositions_partiallyProtected_notReportedAsFullyProtected() {
        Position partiallyProtected = new Position();
        partiallyProtected.setId("pos2");
        partiallyProtected.setUserId("user1");
        partiallyProtected.setCredentialId("cred1");
        partiallyProtected.setStatus("OPEN");
        partiallyProtected.setQuantity(java.math.BigDecimal.valueOf(1.0));
        partiallyProtected.setOcoOrderListId("oco-2");
        partiallyProtected.setProtectedQuantity(java.math.BigDecimal.valueOf(0.9)); // covers only 0.9 of the real 1.0

        when(positionRepo.findByUserIdAndCredentialId(eq("user1"), eq("cred1"), any(Pageable.class)))
            .thenReturn(List.of(partiallyProtected));

        var result = service.listPositions("user1", "cred1", null, 0, 50);

        assertThat(result.get(0).protectedByOco()).isFalse();
        assertThat(result.get(0).protectedQuantity()).isEqualByComparingTo("0.9");
    }

    @Test
    @DisplayName("listPositions: an ocoOrderListId present but protectedQuantity null (e.g. a recovery-path record that never resolved a real value) -- protectedByOco is FALSE, not true just because the id exists")
    void listPositions_ocoIdPresentButProtectedQuantityNull_notReportedAsProtected() {
        Position unresolvedProtection = new Position();
        unresolvedProtection.setId("pos3");
        unresolvedProtection.setUserId("user1");
        unresolvedProtection.setCredentialId("cred1");
        unresolvedProtection.setStatus("OPEN");
        unresolvedProtection.setQuantity(java.math.BigDecimal.valueOf(1.0));
        unresolvedProtection.setOcoOrderListId("oco-3");
        // protectedQuantity deliberately left null.

        when(positionRepo.findByUserIdAndCredentialId(eq("user1"), eq("cred1"), any(Pageable.class)))
            .thenReturn(List.of(unresolvedProtection));

        var result = service.listPositions("user1", "cred1", null, 0, 50);

        assertThat(result.get(0).protectedByOco()).isFalse();
        assertThat(result.get(0).protectedQuantity()).isNull();
    }
}
