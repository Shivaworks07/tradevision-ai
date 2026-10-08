package com.tradevision.service;

import com.tradevision.dto.*;
import com.tradevision.model.features.*;
import com.tradevision.model.*;
import com.tradevision.repository.TradeCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class TradeCallService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradeCallService.class);

    private final TradeCallRepository callRepo;
    private final AutoTradeService autoTradeService;
    // Looks up whether/how a signal resulted in a real order, via the unified Order (OMS) model
    // (findBySignalIdIn, existsBySignalId) rather than any legacy executed-order representation.
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    // ── Save call ─────────────────────────────────────────────
    /**
     * Saves a signal without dispatching it to auto-trade evaluation. This is what
     * TradeCallController's user-facing "Save Call" endpoint calls — a user manually saving
     * their own analysis expects "save this to my history," not a real order being evaluated
     * off the back of it. AutonomousScannerService calls the explicit dispatchToAutoTrade=true
     * overload below instead, since its own autonomous dispatch genuinely needs that evaluation.
     */
    public ApiResponse<?> saveCall(String userId, TradeCallRequest req) {
        return saveCall(userId, req, false);
    }

    public ApiResponse<?> saveCall(String userId, TradeCallRequest req, boolean dispatchToAutoTrade) {
        TradeCallRecord r = new TradeCallRecord();
        r.setUserId(userId);
        r.setSymbol(req.getSymbol().split("/")[0].toUpperCase());
        r.setMarket(req.getMarket());
        r.setTimeframe(req.getTimeframe());
        r.setPlanId(req.getPlanId());
        r.setPlanVersion(req.getPlanVersion());
        r.setDirection(req.getDirection());
        r.setSignal(req.getSignal());
        r.setConfidence(req.getConfidence());
        // The double-to-BigDecimal boundary crossing: req (the wire-format input DTO)
        // deliberately stays double, r (the persisted record) is BigDecimal — see
        // TradeCallRecord's own field comment for why.
        r.setEntryPrice(java.math.BigDecimal.valueOf(req.getEntryPrice()));
        r.setStopLoss(java.math.BigDecimal.valueOf(req.getStopLoss()));
        r.setTarget1(java.math.BigDecimal.valueOf(req.getTarget1()));
        r.setTarget2(java.math.BigDecimal.valueOf(req.getTarget2()));
        r.setTarget3(java.math.BigDecimal.valueOf(req.getTarget3()));
        r.setAtr(java.math.BigDecimal.valueOf(req.getAtr()));
        r.setRrRatio(java.math.BigDecimal.valueOf(req.getRrRatio()));
        r.setRisk(req.getRisk());
        r.setSummary(req.getSummary());

        // Build features
        TradeFeatures f = new TradeFeatures();
        f.setRsi(req.getRsi()); f.setRsiZone(req.getRsiZone());
        f.setMacdHistogram(req.getMacdHistogram()); f.setMacdBull(req.isMacdBull());
        f.setAdx(req.getAdx()); f.setAtrPct(req.getAtrPct());
        f.setTrendEMA(req.getTrendEMA()); f.setBbSignal(req.getBbSignal());
        f.setStochK(req.getStochK()); f.setStochD(req.getStochD());
        f.setWilliamsR(req.getWilliamsR()); f.setVwapSignal(req.getVwapSignal());
        f.setObvSignal(req.getObvSignal()); f.setVolumeRatio(req.getVolumeRatio());
        f.setVolumeSignal(req.getVolumeSignal());
        f.setSmcBias(req.getSmcBias()); f.setSmcBiasStrength(req.getSmcBiasStrength());
        f.setBosDetected(req.isBosDetected()); f.setChochDetected(req.isChochDetected());
        f.setOrderBlockNear(req.isOrderBlockNear()); f.setFvgNear(req.isFvgNear());
        f.setPdZone(req.getPdZone());
        f.setVpLocation(req.getVpLocation()); f.setVpPoc(req.getVpPoc());
        f.setVpVah(req.getVpVah()); f.setVpVal(req.getVpVal());
        f.setPocDistancePct(req.getPocDistancePct());
        f.setOfBias(req.getOfBias()); f.setOfScore(req.getOfScore());
        f.setFundingRate(req.getFundingRate()); f.setOiSignal(req.getOiSignal());
        f.setCvdTrend(req.getCvdTrend());
        f.setRegime(req.getRegime()); f.setRegimeAdx(req.getRegimeAdx());
        f.setRegimeBbWidth(req.getRegimeBbWidth());
        f.setFearGreedValue(req.getFearGreedValue()); f.setFearGreedClass(req.getFearGreedClass());
        f.setMtfAlignment(req.getMtfAlignment()); f.setHtf1Trend(req.getHtf1Trend());
        f.setHtf2Trend(req.getHtf2Trend());
        f.setPatterns(req.getPatterns()); f.setBullReasons(req.getBullReasons());
        f.setBearReasons(req.getBearReasons());
        f.setMlWinRate(req.getMlWinRate()); f.setMlSampleSize(req.getMlSampleSize());
        // ML evolution tracking
        // ── Populate sub-class objects (v2) ─────────────────
        // TechnicalFeatures
        com.tradevision.model.features.TechnicalFeatures tech = new com.tradevision.model.features.TechnicalFeatures();
        tech.setRsi(req.getRsi()); tech.setRsiZone(req.getRsiZone());
        tech.setMacdHistogram(req.getMacdHistogram()); tech.setMacdBull(req.isMacdBull());
        tech.setAdx(req.getAdx()); tech.setAtrPct(req.getAtrPct());
        tech.setTrendEMA(req.getTrendEMA()); tech.setBbSignal(req.getBbSignal());
        tech.setStochK(req.getStochK()); tech.setStochD(req.getStochD());
        tech.setWilliamsR(req.getWilliamsR()); tech.setVwapSignal(req.getVwapSignal());
        tech.setObvSignal(req.getObvSignal()); tech.setVolumeRatio(req.getVolumeRatio());
        tech.setVolumeSignal(req.getVolumeSignal());
        tech.setMtfAlignment(req.getMtfAlignment()); tech.setHtf1Trend(req.getHtf1Trend()); tech.setHtf2Trend(req.getHtf2Trend());
        f.setTechnical(tech);

        // SMCFeatures
        com.tradevision.model.features.SMCFeatures smcF = new com.tradevision.model.features.SMCFeatures();
        smcF.setSmcBias(req.getSmcBias()); smcF.setSmcBiasStrength(req.getSmcBiasStrength());
        smcF.setBosDetected(req.isBosDetected()); smcF.setChochDetected(req.isChochDetected());
        smcF.setOrderBlockNear(req.isOrderBlockNear()); smcF.setFvgNear(req.isFvgNear());
        smcF.setPdZone(req.getPdZone());
        f.setSmc(smcF);

        // VolumeProfileFeatures
        com.tradevision.model.features.VolumeProfileFeatures vpF = new com.tradevision.model.features.VolumeProfileFeatures();
        vpF.setLocation(req.getVpLocation()); vpF.setPoc(req.getVpPoc());
        vpF.setVah(req.getVpVah()); vpF.setVal(req.getVpVal()); vpF.setPocDistancePct(req.getPocDistancePct());
        f.setVp(vpF);

        // OrderFlowFeatures
        com.tradevision.model.features.OrderFlowFeatures ofF = new com.tradevision.model.features.OrderFlowFeatures();
        ofF.setBias(req.getOfBias()); ofF.setScore(req.getOfScore());
        ofF.setFundingRate(req.getFundingRate()); ofF.setOiSignal(req.getOiSignal()); ofF.setCvdTrend(req.getCvdTrend());
        f.setOrderFlow(ofF);

        // SentimentFeatures
        com.tradevision.model.features.SentimentFeatures sentF = new com.tradevision.model.features.SentimentFeatures();
        sentF.setFearGreedValue(req.getFearGreedValue()); sentF.setFearGreedClass(req.getFearGreedClass());
        f.setSentiment(sentF);

        // MarketContextFeatures
        com.tradevision.model.features.MarketContextFeatures ctxF = new com.tradevision.model.features.MarketContextFeatures();
        ctxF.setRegime(req.getRegime()); ctxF.setRegimeAdx(req.getRegimeAdx()); ctxF.setRegimeBbWidth(req.getRegimeBbWidth());
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        ctxF.setExchange(req.getExchange() != null ? req.getExchange() : "UNKNOWN");
        ctxF.setAssetClass(req.getAssetClass() != null ? req.getAssetClass() : "UNKNOWN");
        ctxF.setDayOfWeek(now.getDayOfWeek().getValue());
        ctxF.setHourOfDay(now.getHour());
        ctxF.setMarketSession(getMarketSession(now.getHour()));
        ctxF.setWeekend(now.getDayOfWeek().getValue() >= 6);
        ctxF.setMonthEnd(now.getDayOfMonth() >= 28);
        f.setContext(ctxF);

        // ── Data Quality ─────────────────────────────────────
        com.tradevision.model.DataQuality dq = new com.tradevision.model.DataQuality();
        dq.setDataSource(req.getDataSource() != null ? req.getDataSource() : "UNKNOWN");
        dq.setMissingFeatures(req.getMissingFeatures() != null ? req.getMissingFeatures() : 0);
        dq.setCandleCount(req.getCandleCount() != null ? req.getCandleCount() : 0);
        dq.setSufficientHistory(dq.getCandleCount() >= 50);
        dq.setQualityScore(req.getQualityScore() != null ? req.getQualityScore() : computeQualityScore(req));
        f.setQuality(dq);

        // ── Rule Engine Score ─────────────────────────────────
        if (req.getRuleScore() != null) {
            com.tradevision.model.RuleEngineScore res = new com.tradevision.model.RuleEngineScore();
            res.setTotalScore(req.getRuleScore()); res.setRawScore(req.getRuleScore());
            res.setDirection(req.getDirection()); res.setConfidence(req.getConfidence());
            res.setBullReasons(req.getBullReasons()); res.setBearReasons(req.getBearReasons());
            res.setIndicatorScores(req.getRuleIndicatorScores());
            res.setIndicatorWeights(req.getRuleIndicatorWeights());
            res.setAdjustments(req.getRuleAdjustments());
            f.setRuleEngineScore(res);
        }

        // Market metadata (flat, for backward compat)
        f.setExchange(req.getExchange() != null ? req.getExchange() : "UNKNOWN");
        f.setAssetClass(req.getAssetClass() != null ? req.getAssetClass() : "UNKNOWN");
        // Time features (flat, for backward compat + analytics queries)
        f.setDayOfWeek(now.getDayOfWeek().getValue());
        f.setHourOfDay(now.getHour());
        f.setMarketSession(getMarketSession(now.getHour()));
        f.setFeatureVersion(req.getFeatureVersion() > 0 ? req.getFeatureVersion() : 1);
        f.setRawCandles(req.getRawCandles());
        f.setDecisionWeights(req.getDecisionWeights());
        r.setFeatures(f);

        // Initialize outcome
        TradeOutcome o = new TradeOutcome();
        o.setResult("PENDING");
        r.setOutcome(o);

        r.setCalledAt(LocalDateTime.now());
        r.setExpiresAt(LocalDateTime.now().plusDays(90));
        callRepo.save(r);

        // Never let auto-trade evaluation break signal saving for the caller. AsyncConfig's
        // autoTradeExecutor has no explicit rejection policy, so its default is
        // ThreadPoolExecutor.AbortPolicy -- under real queue saturation (core 4 / max 16 /
        // queue 200 all exhausted), Spring's @Async proxy throws RejectedExecutionException
        // synchronously, on this thread, right out of the call below, not asynchronously and not
        // silently. The signal itself is safe either way -- it was already saved above,
        // unconditionally, before this call -- and AutoTradeRecoveryService's own
        // recoverStuckSignals() sweep (every 2 minutes) picks up and re-dispatches any PENDING
        // signal whose evaluation never actually started, so catching and logging here rather
        // than reinventing a retry is the minimal correct handling.
        if (dispatchToAutoTrade) {
            try {
                autoTradeService.evaluateSignal(userId, r);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.warn("Auto-trade evaluation for signal {} was rejected (autoTradeExecutor saturated) -- the signal itself is "
                    + "already saved as PENDING, and AutoTradeRecoveryService's own recovery sweep will dispatch it shortly.",
                    r.getId(), e);
            }
        }

        return ApiResponse.ok("Saved.", r);
    }

    // ── Fetch ─────────────────────────────────────────────────
    public ApiResponse<?> getCallsForSymbol(String userId, String symbol) {
        return ApiResponse.ok("OK", callRepo.findByUserIdAndSymbolOrderByCalledAtDesc(
            userId, symbol.toUpperCase(), PageRequest.of(0, 10)));
    }

    public ApiResponse<?> getRecentCalls(String userId, int limit) {
        return ApiResponse.ok("OK", callRepo.findByUserIdOrderByCalledAtDesc(
            userId, PageRequest.of(0, Math.min(limit, 100))));
    }

    /**
     * Lets a user explicitly stop a signal that hasn't been acted on yet. Only legal from
     * GENERATED or VALIDATING (the only genuinely "still pending, not yet decided" states) — a
     * signal already APPROVED/ORDER_PENDING/EXECUTED/RISK_REJECTED/EVALUATION_FAILED/EXPIRED is
     * refused, not silently overwritten; those are already-decided outcomes, not something a
     * cancel request arriving late should be able to override.
     */
    public ApiResponse<?> cancelSignal(String userId, String callId) {
        var signalOpt = callRepo.findById(callId);
        if (signalOpt.isEmpty()) return ApiResponse.error("Signal not found.");
        var signal = signalOpt.get();
        if (!userId.equals(signal.getUserId())) return ApiResponse.error("Signal not found."); // same message as not-found, deliberately — don't reveal existence of another user's signal

        var current = signal.getSignalStatus();
        boolean cancellable = current == null || current == com.tradevision.model.SignalStatus.GENERATED
            || current == com.tradevision.model.SignalStatus.VALIDATING;
        if (!cancellable) {
            return ApiResponse.error("This signal is already " + current + " and can no longer be cancelled.");
        }

        // Atomic, conditional on the status still being cancellable at the moment of the write —
        // avoids a race against evaluateSignal's own concurrent progression of the same record.
        var updated = mongoTemplate.findAndModify(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(callId)
                .and("signalStatus").in((Object) null, com.tradevision.model.SignalStatus.GENERATED, com.tradevision.model.SignalStatus.VALIDATING)),
            new org.springframework.data.mongodb.core.query.Update().set("signalStatus", com.tradevision.model.SignalStatus.CANCELLED),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), com.tradevision.model.TradeCallRecord.class);
        if (updated == null) {
            return ApiResponse.error("This signal progressed past a cancellable state just now — could not cancel.");
        }
        return ApiResponse.ok("Signal cancelled.", updated);
    }

    // ── Update result ─────────────────────────────────────────
    public ApiResponse<?> updateResult(String userId, TradeCallResultRequest req) {
        // A call that was actually auto-traded has its outcome owned by PositionMonitorService's
        // real broker reconciliation — the client must not be able to overwrite real P&L with a
        // claimed "HIT_T3". This only blocks calls linked to a real Order (OMS record);
        // theoretical/paper calls the user never auto-traded can still be self-scored, since
        // there's no real fill to contradict.
        if (orderRepo.existsBySignalId(req.getId())) {
            return ApiResponse.error("This call was auto-traded — its outcome is determined by actual broker "
                + "fills and reconciliation, not by client-submitted results.");
        }
        callRepo.findById(req.getId()).ifPresent(r -> {
            if (!userId.equals(r.getUserId())) return;
            TradeOutcome o = r.getOutcome();
            if (o == null) o = new TradeOutcome();
            o.setResult(req.getResult());
            o.setExitPrice(req.getExitPrice());
            o.setResolvedAt(LocalDateTime.now());
            // Compute duration
            if (r.getCalledAt() != null) {
                long minutes = java.time.Duration.between(r.getCalledAt(), LocalDateTime.now()).toMinutes();
                o.setDurationMinutes(minutes);
                o.setDurationLabel(formatDuration(minutes));
            }
            // r.getEntryPrice()/getStopLoss() are BigDecimal; .doubleValue() converts at this
            // specific comparison/PnlCalculator boundary, deliberately not cascading into the
            // shared PnlCalculator utility itself.
            if (r.getEntryPrice().doubleValue() > 0 && req.getExitPrice() != null) {
                boolean isLong = "LONG".equals(r.getDirection());
                // Same shared formula CallResultUpdater uses.
                var pnl = com.tradevision.util.PnlCalculator.compute(r.getEntryPrice().doubleValue(), req.getExitPrice(), r.getStopLoss().doubleValue(), isLong);
                o.setPnlPct(pnl.pnlPct());
                o.setPnlR(pnl.pnlR());
            }
            r.setOutcome(o);
            callRepo.save(r);
        });
        return ApiResponse.ok("Updated.", null);
    }

    // ── Stats ─────────────────────────────────────────────────
    // Segmented by whether each signal actually resulted in a real order, and in which mode —
    // blending every call (auto-executed via broker, or purely theoretical/never traded) into
    // one win rate would hide whether an "83% win rate" means 37 theoretical calls or 124 actual
    // broker executions.
    public ApiResponse<?> getStats(String userId) {
        var all = callRepo.findByUserIdOrderByCalledAtDesc(userId, PageRequest.of(0, 500));

        List<String> signalIds = all.stream().map(com.tradevision.model.TradeCallRecord::getId).filter(java.util.Objects::nonNull).toList();
        // Looks up execution mode via the unified Order (OMS) repository's own
        // findBySignalIdIn, rather than any legacy executed-order representation.
        Map<String, String> executionModeBySignalId = signalIds.isEmpty() ? java.util.Collections.emptyMap()
            : orderRepo.findBySignalIdIn(signalIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.tradevision.model.Order::getSignalId,
                    o -> o.getMode() != null ? o.getMode().name() : "UNKNOWN",
                    (a, b) -> a)); // if a signal somehow has multiple orders (a retry), any one mode is representative

        Map<String, List<com.tradevision.model.TradeCallRecord>> segments = new java.util.LinkedHashMap<>();
        segments.put("LIVE_EXECUTED", all.stream().filter(c -> "LIVE".equals(executionModeBySignalId.get(c.getId()))).toList());
        segments.put("TESTNET_EXECUTED", all.stream().filter(c -> "TESTNET".equals(executionModeBySignalId.get(c.getId()))).toList());
        segments.put("THEORETICAL", all.stream().filter(c -> !executionModeBySignalId.containsKey(c.getId())).toList());

        Map<String, Object> bySegment = new java.util.LinkedHashMap<>();
        for (var entry : segments.entrySet()) {
            bySegment.put(entry.getKey(), segmentStats(entry.getValue()));
        }

        // Overall blended figures kept for backward compatibility with existing callers, but the
        // segmented breakdown above is what a caller should actually surface — a blended win
        // rate alone can't distinguish real broker performance from purely theoretical calls.
        long wins = all.stream().filter(c -> c.getOutcome()!=null && c.getOutcome().getResult()!=null && c.getOutcome().getResult().startsWith("HIT_T")).count();
        long losses = all.stream().filter(c -> "HIT_SL".equals(c.getOutcome()!=null?c.getOutcome().getResult():"")).count();
        long pending = all.stream().filter(c -> "PENDING".equals(c.getOutcome()!=null?c.getOutcome().getResult():"")).count();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", all.size());
        result.put("wins", wins);
        result.put("losses", losses);
        result.put("pending", pending);
        result.put("winRate", all.isEmpty()?0:Math.round((double)wins/all.size()*1000)/10.0);
        result.put("bySegment", bySegment);
        return ApiResponse.ok("OK", result);
    }

    private Map<String, Object> segmentStats(List<com.tradevision.model.TradeCallRecord> calls) {
        long w = calls.stream().filter(c -> c.getOutcome()!=null && c.getOutcome().getResult()!=null && c.getOutcome().getResult().startsWith("HIT_T")).count();
        long resolved = calls.stream().filter(c -> c.getOutcome()!=null && c.getOutcome().getResult()!=null
            && !"PENDING".equals(c.getOutcome().getResult())).count();
        double winRate = resolved == 0 ? 0 : Math.round((double) w / resolved * 1000) / 10.0;
        return Map.of("total", calls.size(), "resolved", resolved, "wins", w, "winRate", winRate);
    }

    // ── Analytics ─────────────────────────────────────────────
    public ApiResponse<?> getStrategyAnalytics(String userId) {
        var resolved = getResolved(userId);
        if (resolved.isEmpty()) return ApiResponse.ok("No data.", Map.of());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("byRegime",      groupBy(resolved, c -> nvl(c.getFeatures().getRegime(), "UNKNOWN")));
        a.put("byConfidence",  groupBy(resolved, c -> confBucket(c.getConfidence())));
        a.put("bySMC",         groupBy(resolved, c -> nvl(c.getFeatures().getSmcBias(), "NONE")));
        a.put("byTimeframe",   groupBy(resolved, c -> nvl(c.getTimeframe(), "UNKNOWN")));
        a.put("bySymbol",      groupBy(resolved, TradeCallRecord::getSymbol));
        a.put("byVPLocation",  groupBy(resolved, c -> nvl(c.getFeatures().getVpLocation(), "UNKNOWN")));
        a.put("byBOS",         groupBy(resolved, c -> c.getFeatures().isBosDetected() ? "BOS_CONFIRMED" : "NO_BOS"));
        a.put("byOrderBlock",  groupBy(resolved, c -> c.getFeatures().isOrderBlockNear() ? "OB_NEAR" : "NO_OB"));
        a.put("byFearGreed",   groupBy(resolved, c -> fgBucket(c.getFeatures().getFearGreedValue())));
        a.put("byMTF",         groupBy(resolved, c -> nvl(c.getFeatures().getMtfAlignment(), "UNKNOWN")));
        a.put("overall",       overallStats(resolved));
        return ApiResponse.ok("OK", a);
    }

    // ── Confidence Calibration ────────────────────────────────
    public ApiResponse<?> getConfidenceCalibration(String userId) {
        var resolved = getResolved(userId);
        int[][] ranges = {{50,60},{60,70},{70,80},{80,90},{90,100}};
        Map<String, Map<String,Object>> buckets = new LinkedHashMap<>();
        for (int[] range : ranges) {
            String key = range[0]+"-"+range[1];
            var in = resolved.stream().filter(c -> c.getConfidence()>=range[0] && c.getConfidence()<range[1]).collect(Collectors.toList());
            long w = in.stream().filter(c -> isWin(c)).count();
            double wr = in.isEmpty() ? 0 : Math.round((double)w/in.size()*1000)/10.0;
            double avgR = in.stream().filter(c -> c.getOutcome().getPnlR()!=null).mapToDouble(c -> c.getOutcome().getPnlR()).average().orElse(0);
            buckets.put(key, Map.of("bucket",key,"trades",in.size(),"wins",w,"winRate",wr,
                "avgR",Math.round(avgR*100)/100.0,"calibrationGap",Math.round((wr-(range[0]+range[1])/2.0)*10)/10.0));
        }
        return ApiResponse.ok("OK", buckets);
    }

    // ── Walk-Forward ──────────────────────────────────────────
    public ApiResponse<?> getWalkForward(String userId) {
        var all = getResolved(userId).stream().sorted(Comparator.comparing(TradeCallRecord::getCalledAt)).collect(Collectors.toList());
        if (all.size() < 20) return ApiResponse.ok("Need 20+ resolved calls.", Map.of());
        // Multiple windows
        List<Map<String,Object>> windows = new ArrayList<>();
        int[] splits = {50, 60, 70};
        for (int sp : splits) {
            int idx = (int)(all.size()*sp/100.0);
            if (idx < 5 || idx >= all.size()-5) continue;
            var train = all.subList(0, idx);
            var test  = all.subList(idx, all.size());
            windows.add(Map.of("split", sp+"%/"+((int)(100-sp))+"%",
                "trainStats", periodStats(train), "testStats", periodStats(test),
                "trainFrom", train.get(0).getCalledAt().toString().substring(0,10),
                "trainTo", train.get(train.size()-1).getCalledAt().toString().substring(0,10),
                "testFrom", test.get(0).getCalledAt().toString().substring(0,10),
                "testTo", test.get(test.size()-1).getCalledAt().toString().substring(0,10)));
        }
        double trainWR = ((Map<?,?>)((Map<?,?>)windows.get(0)).get("trainStats")).containsKey("winRate") ? ((Number)((Map<?,?>)((Map<?,?>)windows.get(0)).get("trainStats")).get("winRate")).doubleValue() : 0;
        double testWR  = ((Map<?,?>)((Map<?,?>)windows.get(0)).get("testStats")).containsKey("winRate") ? ((Number)((Map<?,?>)((Map<?,?>)windows.get(0)).get("testStats")).get("winRate")).doubleValue() : 0;
        double gap = trainWR - testWR;
        String verdict = gap < 5 ? "LOW_OVERFIT" : gap < 15 ? "MODERATE_OVERFIT" : "HIGH_OVERFIT";
        return ApiResponse.ok("OK", Map.of("windows", windows, "verdict", verdict, "overfitGap", Math.round(gap*10)/10.0));
    }

    // ── Dataset Export (for ML) ───────────────────────────────
    public String exportCSV(String userId) {
        var all = callRepo.findByUserIdOrderByCalledAtDesc(userId, PageRequest.of(0, 1000));
        return buildCsv(all);
    }

    /**
     * A real, page-aware CSV export (see TradeCallRepository.findAllByUserIdOrderByCalledAtDesc
     * for the query). Deliberately not the pattern used by the analytics methods above this one
     * (getStrategyAnalytics, getConfidenceCalibration, getWalkForward) — those compute aggregate
     * statistics (win rate by confidence bucket, walk-forward train/test splits) that genuinely
     * need the full relevant dataset at once, where paginating page-by-page would silently
     * change what they compute, not just how much is shown at a time. A CSV export is a
     * different kind of request — "give me all my own data" — where true, page-by-page
     * pagination is the right fit.
     */
    public CsvPage exportCSVPage(String userId, int page, int pageSize) {
        var resultPage = callRepo.findAllByUserIdOrderByCalledAtDesc(userId, PageRequest.of(page, pageSize));
        return new CsvPage(buildCsv(resultPage.getContent()), resultPage.getTotalElements(), resultPage.hasNext());
    }

    /** page: the actual CSV rows for this page (including the header, on every page, so each
     *  page downloaded independently is itself a valid, complete CSV file). totalRecords: the
     *  real total resolved-call count for this user, regardless of page size, so a caller can
     *  compute how many pages exist. hasNext: whether a further page genuinely exists. */
    public record CsvPage(String page, long totalRecords, boolean hasNext) {}

    private String buildCsv(java.util.List<TradeCallRecord> all) {
        StringBuilder sb = new StringBuilder();
        // Header
        sb.append("symbol,market,timeframe,direction,signal,confidence,");
        sb.append("rsi,macd_bull,adx,atr_pct,trendEMA,bb_signal,stoch_k,williams_r,vwap_signal,obv_signal,volume_ratio,");
        sb.append("smc_bias,smc_strength,bos,choch,ob_near,fvg_near,pd_zone,");
        sb.append("vp_location,poc_distance,of_bias,of_score,funding_rate,oi_signal,cvd_trend,");
        sb.append("regime,fear_greed,mtf_alignment,");
        sb.append("entry,stop_loss,target1,target2,atr,risk,");
        sb.append("result,pnl_pct,pnl_r,called_at,quality_score,rule_score,dataset_version\n");
        // Rows
        for (TradeCallRecord r : all) {
            TradeFeatures f = r.getFeatures() != null ? r.getFeatures() : new TradeFeatures();
            TradeOutcome o  = r.getOutcome()  != null ? r.getOutcome()  : new TradeOutcome();
            sb.append(csv(r.getSymbol())).append(",").append(csv(r.getMarket())).append(",").append(csv(r.getTimeframe())).append(",");
            sb.append(csv(r.getDirection())).append(",").append(csv(r.getSignal())).append(",").append(r.getConfidence()).append(",");
            sb.append(f.getRsi()).append(",").append(f.isMacdBull()?1:0).append(",").append(f.getAdx()).append(",").append(f.getAtrPct()).append(",");
            sb.append(csv(f.getTrendEMA())).append(",").append(csv(f.getBbSignal())).append(",").append(f.getStochK()).append(",").append(f.getWilliamsR()).append(",");
            sb.append(csv(f.getVwapSignal())).append(",").append(csv(f.getObvSignal())).append(",").append(f.getVolumeRatio()).append(",");
            sb.append(csv(f.getSmcBias())).append(",").append(f.getSmcBiasStrength()).append(",").append(f.isBosDetected()?1:0).append(",").append(f.isChochDetected()?1:0).append(",");
            sb.append(f.isOrderBlockNear()?1:0).append(",").append(f.isFvgNear()?1:0).append(",").append(csv(f.getPdZone())).append(",");
            sb.append(csv(f.getVpLocation())).append(",").append(f.getPocDistancePct()).append(",").append(csv(f.getOfBias())).append(",").append(f.getOfScore()).append(",");
            sb.append(f.getFundingRate()).append(",").append(csv(f.getOiSignal())).append(",").append(csv(f.getCvdTrend())).append(",");
            sb.append(csv(f.getRegime())).append(",").append(f.getFearGreedValue()!=null?f.getFearGreedValue():"").append(",").append(csv(f.getMtfAlignment())).append(",");
            // .toPlainString() here, not the bare BigDecimal -- BigDecimal's default toString()
            // can use scientific notation for extreme scale values, which .toPlainString() never
            // does, avoiding a subtle CSV-format bug.
            sb.append(r.getEntryPrice().toPlainString()).append(",").append(r.getStopLoss().toPlainString()).append(",").append(r.getTarget1().toPlainString()).append(",").append(r.getTarget2().toPlainString()).append(",").append(r.getAtr().toPlainString()).append(",").append(csv(r.getRisk())).append(",");
            sb.append(csv(o.getResult()!=null?o.getResult():"PENDING")).append(",");
            sb.append(o.getPnlPct()!=null?o.getPnlPct():"").append(",").append(o.getPnlR()!=null?o.getPnlR():"").append(",");
            sb.append(r.getCalledAt()!=null?r.getCalledAt().toString():"").append(",");
            // Quality + rule score
            sb.append(f.getQuality()!=null?f.getQuality().getQualityScore():"").append(",");
            sb.append(f.getRuleEngineScore()!=null?f.getRuleEngineScore().getTotalScore():"").append(",");
            sb.append(f.getFeatureVersion()).append("\n");
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────
    private List<TradeCallRecord> getResolved(String userId) {
        return callRepo.findByUserIdOrderByCalledAtDesc(userId, PageRequest.of(0,500))
            .stream().filter(c -> {
                String res = c.getOutcome()!=null ? c.getOutcome().getResult() : null;
                return res!=null && !res.equals("PENDING") && !res.equals("EXPIRED");
            }).collect(Collectors.toList());
    }

    private boolean isWin(TradeCallRecord c) {
        String r = c.getOutcome()!=null ? c.getOutcome().getResult() : null;
        return r!=null && r.startsWith("HIT_T");
    }

    private String confBucket(int c) {
        if(c>=90)return"90-100";if(c>=80)return"80-90";if(c>=70)return"70-80";if(c>=60)return"60-70";return"50-60";
    }

    private String fgBucket(Integer fg) {
        if(fg==null)return"UNKNOWN";
        if(fg<=25)return"Extreme Fear";if(fg<=45)return"Fear";if(fg<=55)return"Neutral";if(fg<=75)return"Greed";return"Extreme Greed";
    }

    private String nvl(String s, String def) { return s!=null&&!s.isEmpty()?s:def; }
    private String csv(String s) { return s!=null?s.replace(",",""):""; }

    private List<Map<String,Object>> groupBy(List<TradeCallRecord> calls, Function<TradeCallRecord,String> keyFn) {
        return calls.stream().collect(Collectors.groupingBy(keyFn)).entrySet().stream().map(e -> {
            var g=e.getValue(); long w=g.stream().filter(this::isWin).count();
            double wr=g.isEmpty()?0:Math.round((double)w/g.size()*1000)/10.0;
            double gW=g.stream().filter(c->c.getOutcome().getPnlR()!=null&&c.getOutcome().getPnlR()>0).mapToDouble(c->c.getOutcome().getPnlR()).sum();
            double gL=Math.abs(g.stream().filter(c->c.getOutcome().getPnlR()!=null&&c.getOutcome().getPnlR()<0).mapToDouble(c->c.getOutcome().getPnlR()).sum());
            double pf=gL>0?Math.round(gW/gL*100)/100.0:(gW>0?99.0:0.0);
            double avgR=g.stream().filter(c->c.getOutcome().getPnlR()!=null).mapToDouble(c->c.getOutcome().getPnlR()).average().orElse(0);
            Map<String,Object> m=new LinkedHashMap<>(); m.put("label",e.getKey());m.put("trades",g.size());m.put("wins",w);m.put("winRate",wr);m.put("profitFactor",pf);m.put("avgR",Math.round(avgR*100)/100.0);return m;
        }).sorted(Comparator.comparingDouble(m->-((Number)m.get("profitFactor")).doubleValue())).collect(Collectors.toList());
    }

    private Map<String,Object> overallStats(List<TradeCallRecord> calls) {
        long w=calls.stream().filter(this::isWin).count();
        double wr=calls.isEmpty()?0:Math.round((double)w/calls.size()*1000)/10.0;
        double gW=calls.stream().filter(c->c.getOutcome().getPnlR()!=null&&c.getOutcome().getPnlR()>0).mapToDouble(c->c.getOutcome().getPnlR()).sum();
        double gL=Math.abs(calls.stream().filter(c->c.getOutcome().getPnlR()!=null&&c.getOutcome().getPnlR()<0).mapToDouble(c->c.getOutcome().getPnlR()).sum());
        double avgR=calls.stream().filter(c->c.getOutcome().getPnlR()!=null).mapToDouble(c->c.getOutcome().getPnlR()).average().orElse(0);
        return Map.of("total",calls.size(),"wins",w,"winRate",wr,"avgR",Math.round(avgR*100)/100.0,"profitFactor",gL>0?Math.round(gW/gL*100)/100.0:99.0);
    }

    private Map<String,Object> periodStats(List<TradeCallRecord> calls) {
        long w=calls.stream().filter(this::isWin).count();
        double wr=calls.isEmpty()?0:Math.round((double)w/calls.size()*1000)/10.0;
        double gW=calls.stream().filter(c->c.getOutcome().getPnlR()!=null&&c.getOutcome().getPnlR()>0).mapToDouble(c->c.getOutcome().getPnlR()).sum();
        double gL=Math.abs(calls.stream().filter(c->c.getOutcome().getPnlR()!=null&&c.getOutcome().getPnlR()<0).mapToDouble(c->c.getOutcome().getPnlR()).sum());
        double avgR=calls.stream().filter(c->c.getOutcome().getPnlR()!=null).mapToDouble(c->c.getOutcome().getPnlR()).average().orElse(0);
        return Map.of("trades",calls.size(),"winRate",wr,"avgR",Math.round(avgR*100)/100.0,"profitFactor",gL>0?Math.round(gW/gL*100)/100.0:99.0);
    }

    // ── Correlation Analysis ──────────────────────────────────
    public ApiResponse<?> getCorrelationData(String userId) {
        var resolved = getResolved(userId);
        if (resolved.size() < 10) return ApiResponse.ok("Need 10+ resolved calls.", Map.of());

        Map<String, Object> corr = new LinkedHashMap<>();

        // ADX vs Win Rate (bucketed)
        corr.put("adxVsWinRate", correlate(resolved, c -> {
            double adx = c.getFeatures().getAdx();
            if (adx >= 30) return "ADX 30+ (Strong)";
            if (adx >= 20) return "ADX 20-30 (Moderate)";
            return "ADX < 20 (Weak)";
        }));

        // Confidence vs Win Rate
        corr.put("confidenceVsWinRate", correlate(resolved, c -> confBucket(c.getConfidence())));

        // RSI vs Win Rate
        corr.put("rsiVsWinRate", correlate(resolved, c -> {
            double rsi = c.getFeatures().getRsi();
            if (rsi <= 30) return "RSI ≤30 (Oversold)";
            if (rsi <= 45) return "RSI 30-45";
            if (rsi <= 55) return "RSI 45-55 (Neutral)";
            if (rsi <= 70) return "RSI 55-70";
            return "RSI >70 (Overbought)";
        }));

        // Volume Ratio vs Win Rate
        corr.put("volumeVsWinRate", correlate(resolved, c -> {
            double vr = c.getFeatures().getVolumeRatio();
            if (vr >= 2.0) return "Vol 2x+ (Very High)";
            if (vr >= 1.5) return "Vol 1.5-2x";
            if (vr >= 1.0) return "Vol 1-1.5x";
            return "Vol < 1x (Low)";
        }));

        // Fear & Greed vs Expectancy
        corr.put("fearGreedVsExpectancy", correlate(resolved, c -> fgBucket(c.getFeatures().getFearGreedValue())));

        // Regime vs Profit Factor
        corr.put("regimeVsPF", correlate(resolved, c -> nvl(c.getFeatures().getRegime(), "UNKNOWN")));

        // SMC + BOS combination
        corr.put("smcBosCombo", correlate(resolved, c -> {
            boolean bos = c.getFeatures().isBosDetected();
            String smc  = nvl(c.getFeatures().getSmcBias(), "NONE");
            return smc + (bos ? " + BOS" : " no BOS");
        }));

        return ApiResponse.ok("Correlation data.", corr);
    }

    // ── Strategy Comparison ───────────────────────────────────
    public ApiResponse<?> getStrategyComparison(String userId) {
        var resolved = getResolved(userId);
        if (resolved.size() < 5) return ApiResponse.ok("Need 5+ resolved calls.", Map.of());

        List<Map<String, Object>> strategies = new ArrayList<>();

        // Strategy 1: All signals (baseline)
        strategies.add(buildStrategy("All Signals (Baseline)", resolved));

        // Strategy 2: Only when BOS confirmed
        var withBOS = resolved.stream().filter(c -> c.getFeatures().isBosDetected()).collect(Collectors.toList());
        if (!withBOS.isEmpty()) strategies.add(buildStrategy("With BOS Confirmed", withBOS));

        // Strategy 3: Only trending regimes
        var trending = resolved.stream().filter(c -> {
            String r = nvl(c.getFeatures().getRegime(), "");
            return r.contains("TREND") || r.contains("BREAKOUT");
        }).collect(Collectors.toList());
        if (!trending.isEmpty()) strategies.add(buildStrategy("Trending Regimes Only", trending));

        // Strategy 4: High confidence only (>= 70)
        var highConf = resolved.stream().filter(c -> c.getConfidence() >= 70).collect(Collectors.toList());
        if (!highConf.isEmpty()) strategies.add(buildStrategy("High Confidence (70%+)", highConf));

        // Strategy 5: SMC aligned
        var smcAligned = resolved.stream().filter(c -> {
            String smc = nvl(c.getFeatures().getSmcBias(), "NONE");
            String dir = nvl(c.getDirection(), "");
            return (smc.equals("BULLISH") && dir.equals("LONG")) || (smc.equals("BEARISH") && dir.equals("SHORT"));
        }).collect(Collectors.toList());
        if (!smcAligned.isEmpty()) strategies.add(buildStrategy("SMC Aligned", smcAligned));

        // Strategy 6: Volume Profile confirmed (above VAH for long, below VAL for short)
        var vpConfirmed = resolved.stream().filter(c -> {
            String vp = nvl(c.getFeatures().getVpLocation(), "");
            String dir = nvl(c.getDirection(), "");
            return (vp.equals("ABOVE_VAH") && dir.equals("LONG")) || (vp.equals("BELOW_VAL") && dir.equals("SHORT"));
        }).collect(Collectors.toList());
        if (!vpConfirmed.isEmpty()) strategies.add(buildStrategy("VP Confirmed", vpConfirmed));

        // Strategy 7: Full confluence (BOS + trending + high conf)
        var fullConf = resolved.stream().filter(c ->
            c.getFeatures().isBosDetected() && c.getConfidence() >= 70 &&
            nvl(c.getFeatures().getRegime(),"").contains("TREND")
        ).collect(Collectors.toList());
        if (!fullConf.isEmpty()) strategies.add(buildStrategy("Full Confluence (BOS+Trend+70%)", fullConf));

        return ApiResponse.ok("Strategy comparison.", strategies);
    }

    private Map<String, Object> buildStrategy(String name, List<TradeCallRecord> calls) {
        var stats = overallStats(calls);
        // Build equity curve (normalized to 100)
        List<Double> equity = new ArrayList<>();
        double eq = 100.0;
        for (var c : calls.stream().sorted(Comparator.comparing(TradeCallRecord::getCalledAt)).collect(Collectors.toList())) {
            if (c.getOutcome().getPnlR() != null) {
                eq *= (1 + c.getOutcome().getPnlR() * 0.01);
                equity.add(Math.round(eq * 100.0) / 100.0);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>(stats);
        m.put("name", name);
        m.put("equity", equity);
        // Max drawdown
        double peak = 100, maxDD = 0;
        for (double e : equity) { if (e > peak) peak = e; double dd = (peak-e)/peak*100; if (dd > maxDD) maxDD = dd; }
        m.put("maxDrawdown", Math.round(maxDD * 10.0) / 10.0);
        return m;
    }

    private List<Map<String, Object>> correlate(List<TradeCallRecord> calls, Function<TradeCallRecord, String> keyFn) {
        return groupBy(calls, keyFn);
    }


    // ── Dataset Export: JSON ─────────────────────────────────
    public String exportJSON(String userId) {
        var all = callRepo.findByUserIdOrderByCalledAtDesc(userId, PageRequest.of(0, 2000));
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < all.size(); i++) {
            TradeCallRecord r = all.get(i);
            TradeFeatures f   = r.getFeatures()  != null ? r.getFeatures()  : new TradeFeatures();
            TradeOutcome  o   = r.getOutcome()   != null ? r.getOutcome()   : new TradeOutcome();
            sb.append("  {");
            sb.append("\"id\":\"").append(r.getId()).append("\",");
            sb.append("\"symbol\":\"").append(j(r.getSymbol())).append("\",");
            sb.append("\"market\":\"").append(j(r.getMarket())).append("\",");
            sb.append("\"timeframe\":\"").append(j(r.getTimeframe())).append("\",");
            sb.append("\"direction\":\"").append(j(r.getDirection())).append("\",");
            sb.append("\"signal\":\"").append(j(r.getSignal())).append("\",");
            sb.append("\"confidence\":").append(r.getConfidence()).append(",");
            // .toPlainString() avoids scientific notation in the exported JSON, same reasoning
            // as this class's own CSV export above.
            sb.append("\"entry\":").append(r.getEntryPrice().toPlainString()).append(",");
            sb.append("\"stopLoss\":").append(r.getStopLoss().toPlainString()).append(",");
            sb.append("\"target1\":").append(r.getTarget1().toPlainString()).append(",");
            sb.append("\"target2\":").append(r.getTarget2().toPlainString()).append(",");
            sb.append("\"atr\":").append(r.getAtr().toPlainString()).append(",");
            // Features
            sb.append("\"features\": {");
            sb.append("\"featureVersion\":").append(f.getFeatureVersion()).append(",");
            sb.append("\"rsi\":").append(f.getRsi()).append(",");
            sb.append("\"macdBull\":").append(f.isMacdBull()).append(",");
            sb.append("\"adx\":").append(f.getAdx()).append(",");
            sb.append("\"regime\":\"").append(j(f.getRegime())).append("\",");
            sb.append("\"smcBias\":\"").append(j(f.getSmcBias())).append("\",");
            sb.append("\"bosDetected\":").append(f.isBosDetected()).append(",");
            sb.append("\"vpLocation\":\"").append(j(f.getVpLocation())).append("\",");
            sb.append("\"fearGreed\":").append(f.getFearGreedValue() != null ? f.getFearGreedValue() : "null").append(",");
            sb.append("\"exchange\":\"").append(j(f.getExchange())).append("\",");
            sb.append("\"dayOfWeek\":").append(f.getDayOfWeek()).append(",");
            sb.append("\"hourOfDay\":").append(f.getHourOfDay());
            sb.append("},");
            // Outcome
            sb.append("\"outcome\": {");
            sb.append("\"result\":\"").append(j(o.getResult() != null ? o.getResult() : "PENDING")).append("\",");
            sb.append("\"pnlPct\":").append(o.getPnlPct() != null ? o.getPnlPct() : "null").append(",");
            sb.append("\"pnlR\":").append(o.getPnlR() != null ? o.getPnlR() : "null").append(",");
            sb.append("\"durationMinutes\":").append(o.getDurationMinutes() != null ? o.getDurationMinutes() : "null").append(",");
            sb.append("\"durationLabel\":\"").append(j(o.getDurationLabel())).append("\"");
            sb.append("},");
            sb.append("\"calledAt\":\"").append(r.getCalledAt()).append("\"");
            sb.append("}").append(i < all.size()-1 ? "," : "").append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private String j(String s) { return s != null ? s.replace("\\", "\\\\").replace("\"", "\\\"") : ""; }

    private int computeQualityScore(TradeCallRequest req) {
        int score = 100;
        // Deduct for missing key indicators
        if (req.getRsi() == 0)           score -= 5;
        if (req.getAdx() == 0)           score -= 5;
        if (req.getSmcBias() == null)    score -= 10;
        if (req.getVpLocation() == null) score -= 8;
        if (req.getRegime() == null)     score -= 10;
        if (req.getCandleCount() != null && req.getCandleCount() < 50) score -= 15;
        if (req.getFearGreedValue() == null) score -= 3;
        return Math.max(0, score);
    }

    private String getMarketSession(int hourUTC) {
        if (hourUTC >= 0  && hourUTC < 8)  return "ASIA";
        if (hourUTC >= 8  && hourUTC < 13) return "EUROPE";
        if (hourUTC >= 13 && hourUTC < 21) return "US";
        return "AFTER_HOURS";
    }

    private String formatDuration(long minutes) {
        if (minutes < 60)  return minutes + "m";
        if (minutes < 1440) return (minutes/60) + "h " + (minutes%60) + "m";
        long days = minutes / 1440;
        long hrs  = (minutes % 1440) / 60;
        return days + "d " + (hrs > 0 ? hrs + "h" : "");
    }

}