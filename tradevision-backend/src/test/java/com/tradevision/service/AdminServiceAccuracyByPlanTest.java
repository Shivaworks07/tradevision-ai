package com.tradevision.service;

import com.tradevision.dto.ApiResponse;
import com.tradevision.model.TradeCallRecord;
import com.tradevision.model.TradeOutcome;
import com.tradevision.repository.ApiMetricRepository;
import com.tradevision.repository.TradeCallRepository;
import com.tradevision.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies AdminService's accuracyByPlan segmentation. Scoped to this one addition --
 * comprehensively testing AdminService's other, unrelated computations is out of scope here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminServiceAccuracyByPlanTest {

    @Mock UserRepository userRepo;
    @Mock TradeCallRepository callRepo;
    @Mock ApiMetricRepository metricRepo;

    private TradeCallRecord resolvedCall(String planId, String result) {
        var call = new TradeCallRecord();
        call.setPlanId(planId);
        var outcome = new TradeOutcome();
        outcome.setResult(result);
        call.setOutcome(outcome);
        return call;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> accuracyByPlanFrom(ApiResponse<?> response) {
        var data = (java.util.Map<String, Object>) response.getData();
        var signals = (java.util.Map<String, Object>) data.get("signals");
        return (java.util.Map<String, Object>) signals.get("accuracyByPlan");
    }

    @Test
    @DisplayName("getDashboard: real trade calls are correctly grouped by planId, with a genuine win-rate computed per plan")
    void accuracyByPlan_groupsRealCallsByPlan() {
        when(callRepo.findByCalledAtAfterOrderByCalledAtDesc(any(), any())).thenReturn(List.of(
            resolvedCall("plan-A", "HIT_T1"),
            resolvedCall("plan-A", "HIT_T2"),
            resolvedCall("plan-A", "HIT_SL"),
            resolvedCall("plan-B", "HIT_SL")
        ));
        var service = new AdminService(userRepo, callRepo, metricRepo);

        var response = service.getDashboard();

        var accuracyByPlan = accuracyByPlanFrom(response);
        assertThat(accuracyByPlan).containsKey("plan-A");
        assertThat(accuracyByPlan).containsKey("plan-B");
        @SuppressWarnings("unchecked")
        var planA = (java.util.Map<String, Object>) accuracyByPlan.get("plan-A");
        assertThat(planA.get("total")).isEqualTo(3);
        assertThat(planA.get("wins")).isEqualTo(2L);
        // 2 of 3 = 66.7%
        assertThat((double) planA.get("winRate")).isEqualTo(66.7);
    }

    @Test
    @DisplayName("getDashboard: calls with no planId (manual/unattributed signals) are grouped separately, not silently dropped")
    void accuracyByPlan_noPlanId_groupedAsUnattributed() {
        when(callRepo.findByCalledAtAfterOrderByCalledAtDesc(any(), any())).thenReturn(List.of(
            resolvedCall(null, "HIT_T1")
        ));
        var service = new AdminService(userRepo, callRepo, metricRepo);

        var response = service.getDashboard();

        var accuracyByPlan = accuracyByPlanFrom(response);
        assertThat(accuracyByPlan).containsKey("MANUAL_OR_UNATTRIBUTED");
    }

    @Test
    @DisplayName("getDashboard: a PENDING (unresolved) call is excluded from every plan's attribution, same as the existing by-market segment already does")
    void accuracyByPlan_pendingCall_excluded() {
        when(callRepo.findByCalledAtAfterOrderByCalledAtDesc(any(), any())).thenReturn(List.of(
            resolvedCall("plan-A", "PENDING")
        ));
        var service = new AdminService(userRepo, callRepo, metricRepo);

        var response = service.getDashboard();

        var accuracyByPlan = accuracyByPlanFrom(response);
        assertThat(accuracyByPlan).doesNotContainKey("plan-A");
    }
}
