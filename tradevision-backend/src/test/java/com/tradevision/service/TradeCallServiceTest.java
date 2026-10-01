package com.tradevision.service;

import com.tradevision.dto.*;
import com.tradevision.model.*;
import com.tradevision.repository.TradeCallRepository;
import com.tradevision.repository.OrderRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeCallServiceTest {

    @Mock TradeCallRepository callRepo;
    // Added when TradeCallService gained AutoTradeService/ExecutedOrderRepository dependencies
    // (review items #2/#20/#23 work) — without these @InjectMocks leaves the fields null and
    // every test here throws NullPointerException the moment saveCall/updateResult runs.
    @Mock AutoTradeService autoTradeService;
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // TradeCallService's own removal of its ExecutedOrderRepository field): migrated to the
    // real Order (OMS) repository -- same "without this, @InjectMocks leaves the field null"
    // reasoning as this file's own comment above.
    @Mock OrderRepository orderRepo;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @InjectMocks TradeCallService callService;

    @Test @DisplayName("saveCall: creates call with correct symbol")
    void saveCall_setsSymbol() {
        TradeCallRequest req = new TradeCallRequest();
        req.setSymbol("BTC/USDT"); req.setMarket("CRYPTO");
        req.setDirection("LONG"); req.setSignal("BUY"); req.setConfidence(75);
        req.setEntryPrice(50000); req.setStopLoss(48000);
        req.setTarget1(52000); req.setTarget2(54000);
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        var resp = callService.saveCall("user1", req);
        assertThat(resp.isSuccess()).isTrue();
        verify(callRepo).save(argThat(r -> "BTC".equals(r.getSymbol())));
    }

    /**
     * Review finding ("'Save Call' can trigger real auto-trading" -- external review,
     * seventeenth pass, P0, full context in saveCall's own updated javadoc): the review's own
     * explicitly required test -- a manual save must save the history record and must NEVER
     * dispatch to AutoTradeService, regardless of auto-trade/LIVE configuration, since the user
     * clicking "Save Call" never took an action that should be interpreted as "execute this."
     */
    @Test
    @DisplayName("saveCall(userId, req): the ordinary two-argument overload -- what the user-facing \"Save Call\" button actually calls -- saves the record but NEVER dispatches to AutoTradeService")
    void saveCall_twoArgOverload_neverDispatchesToAutoTrade() {
        TradeCallRequest req = new TradeCallRequest();
        req.setSymbol("BTC/USDT"); req.setMarket("CRYPTO");
        req.setDirection("LONG"); req.setSignal("BUY"); req.setConfidence(75);
        req.setEntryPrice(50000); req.setStopLoss(48000);
        req.setTarget1(52000); req.setTarget2(54000);
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        callService.saveCall("user1", req);

        verify(callRepo).save(any());
        verify(autoTradeService, never()).evaluateSignal(any(), any());
    }

    @Test
    @DisplayName("saveCall(userId, req, true): the explicit three-argument overload AutonomousScannerService actually calls -- saves AND dispatches to AutoTradeService")
    void saveCall_threeArgOverloadWithTrue_dispatchesToAutoTrade() {
        TradeCallRequest req = new TradeCallRequest();
        req.setSymbol("BTC/USDT"); req.setMarket("CRYPTO");
        req.setDirection("LONG"); req.setSignal("BUY"); req.setConfidence(75);
        req.setEntryPrice(50000); req.setStopLoss(48000);
        req.setTarget1(52000); req.setTarget2(54000);
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        callService.saveCall("user1", req, true);

        verify(callRepo).save(any());
        verify(autoTradeService).evaluateSignal(eq("user1"), any());
    }

    @Test @DisplayName("updateResult: calculates pnlPct correctly for LONG")
    void updateResult_longPnl() {
        TradeCallResultRequest req = new TradeCallResultRequest();
        req.setId("call1"); req.setResult("HIT_T1"); req.setExitPrice(52000.0);

        TradeCallRecord r = new TradeCallRecord();
        r.setId("call1"); r.setUserId("user1");
        r.setDirection("LONG"); r.setEntryPrice(java.math.BigDecimal.valueOf(50000)); r.setStopLoss(java.math.BigDecimal.valueOf(48000));
        r.setOutcome(new TradeOutcome());

        when(callRepo.findById("call1")).thenReturn(Optional.of(r));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        callService.updateResult("user1", req);

        verify(callRepo).save(argThat(saved -> {
            Double pnl = saved.getOutcome().getPnlPct();
            return pnl != null && Math.abs(pnl - 4.0) < 0.01; // (52000-50000)/50000*100 = 4%
        }));
    }

    @Test @DisplayName("getStats: returns correct win count")
    void getStats_winCount() {
        TradeCallRecord win  = callWithResult("HIT_T1");
        TradeCallRecord win2 = callWithResult("HIT_T2");
        TradeCallRecord loss = callWithResult("HIT_SL");

        when(callRepo.findByUserIdOrderByCalledAtDesc(eq("u1"), any()))
            .thenReturn(List.of(win, win2, loss));

        var resp = callService.getStats("u1");
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().toString()).contains("wins=2");
    }

    private TradeCallRecord callWithResult(String result) {
        TradeCallRecord r = new TradeCallRecord();
        r.setUserId("u1");
        TradeOutcome o = new TradeOutcome(); o.setResult(result);
        r.setOutcome(o); r.setFeatures(new TradeFeatures()); return r;
    }

    // ── cancelSignal ("Signal lifecycle is still partial" — "CANCELLED still unused") ────────

    private TradeCallRecord cancellableSignal(SignalStatus status) {
        TradeCallRecord r = new TradeCallRecord();
        r.setId("sig1");
        r.setUserId("user1");
        r.setSignalStatus(status);
        return r;
    }

    @Test
    @DisplayName("cancelSignal: a GENERATED signal is cancellable — atomic write succeeds")
    void cancelSignal_generatedStatus_succeeds() {
        TradeCallRecord signal = cancellableSignal(SignalStatus.GENERATED);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(signal));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class))).thenReturn(signal);

        var resp = callService.cancelSignal("user1", "sig1");

        assertThat(resp.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("cancelSignal: a VALIDATING signal is cancellable too")
    void cancelSignal_validatingStatus_succeeds() {
        TradeCallRecord signal = cancellableSignal(SignalStatus.VALIDATING);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(signal));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class))).thenReturn(signal);

        var resp = callService.cancelSignal("user1", "sig1");

        assertThat(resp.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("cancelSignal: an already-EXECUTED signal is refused — a real outcome must never be silently overwritten by a late cancel")
    void cancelSignal_executedStatus_refused() {
        TradeCallRecord signal = cancellableSignal(SignalStatus.EXECUTED);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(signal));

        var resp = callService.cancelSignal("user1", "sig1");

        assertThat(resp.isSuccess()).isFalse();
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(TradeCallRecord.class));
    }

    @Test
    @DisplayName("cancelSignal: an already-APPROVED signal is refused — same reasoning, it's past the cancellable window")
    void cancelSignal_approvedStatus_refused() {
        TradeCallRecord signal = cancellableSignal(SignalStatus.APPROVED);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(signal));

        var resp = callService.cancelSignal("user1", "sig1");

        assertThat(resp.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("cancelSignal: a signal belonging to a DIFFERENT user is refused with the same message as not-found — never reveals another user's signal exists")
    void cancelSignal_differentUser_refusedAsNotFound() {
        TradeCallRecord signal = cancellableSignal(SignalStatus.GENERATED); // owned by user1
        when(callRepo.findById("sig1")).thenReturn(Optional.of(signal));

        var resp = callService.cancelSignal("user2", "sig1"); // user2 tries to cancel user1's signal

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).isEqualTo("Signal not found.");
    }

    @Test
    @DisplayName("cancelSignal: a nonexistent signal id is refused")
    void cancelSignal_notFound_refused() {
        when(callRepo.findById("nonexistent")).thenReturn(Optional.empty());

        var resp = callService.cancelSignal("user1", "nonexistent");

        assertThat(resp.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("cancelSignal: the atomic conditional write losing a race (signal progressed just now) is reported honestly, not treated as a silent success")
    void cancelSignal_raceLost_reportedHonestly() {
        TradeCallRecord signal = cancellableSignal(SignalStatus.GENERATED);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(signal));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class))).thenReturn(null); // lost the race

        var resp = callService.cancelSignal("user1", "sig1");

        assertThat(resp.isSuccess()).isFalse();
    }

    // ── exportCSVPage ("Some analytics are deliberately bounded rather than truly
    // paginated" -- external review, thirty-sixth pass, P2, full context in
    // TradeCallRepository.findAllByUserIdOrderByCalledAtDesc's own updated javadoc) ────

    private TradeCallRecord exportableCall(String symbol) {
        TradeCallRecord r = new TradeCallRecord();
        r.setUserId("u1");
        r.setSymbol(symbol);
        r.setEntryPrice(java.math.BigDecimal.valueOf(100));
        r.setStopLoss(java.math.BigDecimal.valueOf(95));
        r.setTarget1(java.math.BigDecimal.valueOf(105));
        r.setTarget2(java.math.BigDecimal.valueOf(110));
        r.setAtr(java.math.BigDecimal.valueOf(2));
        r.setFeatures(new TradeFeatures());
        r.setOutcome(new TradeOutcome());
        return r;
    }

    @Test
    @DisplayName("exportCSVPage: real pagination -- calls Page 0 through the repository's own real page-aware query, includes the correct total record count and hasNext flag from that same query")
    void exportCSVPage_realPagination_reportsCorrectTotalAndHasNext() {
        var records = java.util.List.of(exportableCall("BTCUSDT"), exportableCall("ETHUSDT"));
        var page = new org.springframework.data.domain.PageImpl<>(records,
            org.springframework.data.domain.PageRequest.of(0, 1000), 2500); // 2500 total -- well beyond one page
        when(callRepo.findAllByUserIdOrderByCalledAtDesc(eq("u1"), any())).thenReturn(page);

        var result = callService.exportCSVPage("u1", 0, 1000);

        assertThat(result.totalRecords()).isEqualTo(2500);
        assertThat(result.hasNext()).isTrue(); // 2500 total, page size 1000 -- more pages genuinely exist
        assertThat(result.page()).contains("BTCUSDT").contains("ETHUSDT");
    }

    @Test
    @DisplayName("exportCSVPage: the last page correctly reports hasNext=false -- a caller can tell when it's genuinely reached the end")
    void exportCSVPage_lastPage_reportsNoNextPage() {
        var records = java.util.List.of(exportableCall("BTCUSDT"));
        var page = new org.springframework.data.domain.PageImpl<>(records,
            org.springframework.data.domain.PageRequest.of(2, 1000), 2001); // page 2 of 2001 total, page size 1000 -- this IS the last page
        when(callRepo.findAllByUserIdOrderByCalledAtDesc(eq("u1"), any())).thenReturn(page);

        var result = callService.exportCSVPage("u1", 2, 1000);

        assertThat(result.hasNext()).isFalse();
        assertThat(result.totalRecords()).isEqualTo(2001);
    }

    @Test
    @DisplayName("exportCSVPage: every page independently includes the CSV header row, so a page downloaded on its own is a valid, complete CSV file")
    void exportCSVPage_everyPage_includesHeaderRow() {
        var page = new org.springframework.data.domain.PageImpl<>(java.util.List.of(exportableCall("BTCUSDT")),
            org.springframework.data.domain.PageRequest.of(1, 1000), 1500);
        when(callRepo.findAllByUserIdOrderByCalledAtDesc(eq("u1"), any())).thenReturn(page);

        var result = callService.exportCSVPage("u1", 1, 1000);

        assertThat(result.page()).startsWith("symbol,market,timeframe,direction,signal,confidence,");
    }

    @Test
    @DisplayName("exportCSV (the original, backward-compatible 1-arg method): unchanged behavior -- still fetches exactly the first 1000 records via the original List-returning repository method, not the new Page-returning one")
    void exportCSV_backwardCompatible_stillUsesOriginalMethod() {
        when(callRepo.findByUserIdOrderByCalledAtDesc(eq("u1"), any())).thenReturn(java.util.List.of(exportableCall("BTCUSDT")));

        String csv = callService.exportCSV("u1");

        assertThat(csv).contains("BTCUSDT");
        verify(callRepo, never()).findAllByUserIdOrderByCalledAtDesc(any(), any());
    }
}
