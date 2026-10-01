package com.tradevision.service;

import com.tradevision.dto.MLDatasetRow;
import com.tradevision.model.TradeCallRecord;
import com.tradevision.repository.TradeCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Review item #17 — this is explicitly NOT the ML pipeline. It's the one honest, bounded piece
 * of it that's actually buildable today: turning resolved TradeCallRecord history into a clean,
 * labeled export. No model is trained here, no probability is predicted, nothing is validated.
 * That work needs enough real resolved trades to exist first (which review item #16's TTL fix
 * is a prerequisite for) and then a real training/validation pipeline outside this codebase.
 */
@Service
@RequiredArgsConstructor
public class MLDatasetExportService {

    private final TradeCallRepository callRepo;

    public List<MLDatasetRow> exportResolved(int limit) {
        List<TradeCallRecord> calls = callRepo.findByOutcome_ResultNotOrderByCalledAtDesc(
            "PENDING", PageRequest.of(0, Math.min(limit, 5000), Sort.by(Sort.Direction.DESC, "calledAt")));

        return calls.stream().map(this::toRow).toList();
    }

    private MLDatasetRow toRow(TradeCallRecord c) {
        var features = c.getFeatures();
        var quality = features != null ? features.getQuality() : null;
        var outcome = c.getOutcome();

        return new MLDatasetRow(
            // Review finding ("Financial values still mix double and BigDecimal" -- external
            // review, twenty-fourth pass, P2, full context in TradeCallRecord's own updated
            // field comment): these 5 fields are BigDecimal now on the source record --
            // .doubleValue() converts at this specific export boundary, since MLDatasetRow
            // deliberately stays double (an external export format, same reasoning as
            // TradeCallRequest staying double at the JSON input boundary).
            c.getId(), c.getSymbol(), c.getMarket(), c.getTimeframe(),
            c.getDirection(), c.getConfidence(), c.getEntryPrice().doubleValue(), c.getStopLoss().doubleValue(),
            c.getTarget1().doubleValue(), c.getRrRatio().doubleValue(), c.getAtr().doubleValue(),
            features != null ? features.getRsi() : null,
            features != null ? features.isMacdBull() : null,
            features != null ? features.getTrendEMA() : null,
            features != null ? features.getRegime() : null,
            features != null ? features.getSmcBias() : null,
            quality != null ? quality.getQualityScore() : null,
            quality != null ? quality.getCandleCount() : null,
            outcome != null ? outcome.getResult() : null,
            outcome != null ? outcome.getPnlPct() : null,
            outcome != null ? outcome.getPnlR() : null,
            outcome != null ? outcome.getDurationMinutes() : null
        );
    }
}
