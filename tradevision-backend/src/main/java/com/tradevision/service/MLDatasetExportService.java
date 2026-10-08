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
 * Exports resolved TradeCallRecord history as a clean, labeled dataset for downstream
 * ML training. This service only shapes and exports the data — it does not train any
 * model, predict any probability, or validate anything; that happens in a separate
 * training/validation pipeline outside this codebase, once enough resolved trades exist.
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
            // These fields are BigDecimal on the source record; MLDatasetRow deliberately
            // stays double since it's an external export format, so we convert here at the
            // export boundary rather than propagating BigDecimal outward.
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
