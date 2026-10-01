package com.tradevision.service;

import com.tradevision.model.TradeCallRecord;
import com.tradevision.model.TradeOutcome;
import com.tradevision.repository.TradeCallRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/**
 * P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external review, full
 * context in this codebase's own new CorrelationIdFilter javadoc): this class's own three
 * System.out.println calls are replaced with this class's own SLF4J logger below -- println
 * bypasses logging.level entirely (can't be turned down in production, isn't captured by any log
 * aggregator that scrapes SLF4J/Logback output specifically, and carries none of the correlation
 * id CorrelationIdFilter now injects into every other log line for the same request). No PII
 * here (symbol/price/result only), so no masking is needed in this class specifically.
 */
@Service
@RequiredArgsConstructor
public class CallResultUpdater {
    private static final Logger log = LoggerFactory.getLogger(CallResultUpdater.class);
    private final TradeCallRepository callRepo;
    /**
     * Real compile error, confirmed by the person's own local build ("ExecutedOrderRepository...
     * not existing"): this field used to be typed com.tradevision.repository.ExecutedOrderRepository,
     * a class that genuinely does not exist anywhere in this project -- every other file that
     * once used it (OrderExecutionService, PositionDashboardService, PositionMonitorService,
     * AutoTradeService, TradeCallService -- confirmed by direct search, not assumed) already
     * migrated to OrderRepository/Order per the "OMS/ExecutedOrder full unification" review
     * finding; this was the one file that migration missed. OrderRepository.existsBySignalId
     * already exists with the identical name and signature this file's own check needs.
     */
    private final com.tradevision.repository.OrderRepository orderRepo;
    // Review finding ("CallResultUpdater can race with real position outcomes" -- P1): needed
    // for the actual atomic guard in updateResult below.
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Review finding (P1 #5 -- "Global ML weights can be poisoned by unverified, client-supplied
    // trade outcomes"): the MLWeightService dependency that used to live here is removed. This
    // class's own two result paths (the ticker-crossing TP/SL guess in updateResult, and the
    // 30-day-EXPIRED sweep above) are never backed by a real broker fill -- see both call sites'
    // own updated comments for the full reasoning -- so neither may feed the GLOBAL,
    // unscoped-by-user ML weights every live signal reads from. That write now happens only from
    // PositionMonitorService.writeRealOutcomeBackToSignal, the one place a real, verified fill is
    // actually known.
    /**
     * Review finding ("Graceful shutdown does not stop @Scheduled work or WebSocket listeners
     * from starting new work" -- external review, nineteenth pass, P1, confirmed real by direct
     * inspection: this scheduled task had no shutdown-awareness at all, unlike every other
     * @Scheduled method in this codebase, which already check this exact field): the fix.
     */
    private final com.tradevision.config.ShutdownState shutdownState;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Scheduled(fixedDelay = 300000, scheduler = "maintenanceScheduler")
    public void updatePendingCalls() {
        if (shutdownState.isShuttingDown()) return;
        // P2-19 fix ("CallResultUpdater: ... newest 50 only" -- external review, full context in
        // TradeCallRepository.findByOutcome_ResultOrderByCalledAtAsc's own updated javadoc):
        // oldest-first, not newest-first -- a backlog beyond 50 PENDING calls now actually drains
        // over successive 5-minute runs instead of permanently starving behind newer calls.
        List<TradeCallRecord> pending = callRepo.findByOutcome_ResultOrderByCalledAtAsc("PENDING", PageRequest.of(0, 50));
        for (TradeCallRecord call : pending) {
            try {
                // Review items #20 / #2 (this doc's numbering): if this call was actually
                // auto-traded, its real outcome comes from PositionMonitorService's broker
                // reconciliation — this theoretical ticker-crossing check must never overwrite
                // that with a guessed result. (It also shouldn't normally get the chance to,
                // since PositionMonitorService now writes the real outcome back onto the call
                // when a linked position closes — but this guard is the actual enforcement.)
                if (orderRepo.existsBySignalId(call.getId())) {
                    continue;
                }
                if (call.getCalledAt().isBefore(LocalDateTime.now().minusDays(30))) {
                    TradeOutcome o = call.getOutcome() != null ? call.getOutcome() : new TradeOutcome();
                    o.setResult("EXPIRED"); o.setResolvedAt(LocalDateTime.now());
                    // Review finding ("CallResultUpdater can race with real position outcomes"
                    // -- P1, full context in updateResult's own identical fix below): same
                    // atomic-conditional pattern, same reasoning -- a plain callRepo.save() here
                    // could overwrite a real outcome written between this loop iteration's own
                    // existsBySignalId() check above and this save.
                    var expiredUpdateResult = mongoTemplate.updateFirst(
                        new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(call.getId())
                                .and("outcome.result").is("PENDING")),
                        new org.springframework.data.mongodb.core.query.Update().set("outcome", o),
                        TradeCallRecord.class);
                    // Review finding ("Strategy engine is not the complete strategy actually
                    // represented by the frontend" -- P1, full context in updateResult's own
                    // identical wiring below): recorded here too, for the same reason -- an
                    // EXPIRED result still counts toward totalCalls (the warm-up threshold)
                    // even though it affects neither wins nor losses (see
                    // MLWeightService.recordOutcome's own isWin/loss logic). Skipping this whole
                    // category of resolved signal would leave totalCalls artificially low.
                    // Review finding (P1 #5 -- "Global ML weights can be poisoned by unverified,
                    // client-supplied trade outcomes"): this used to call
                    // mlWeightService.recordOutcome(..., "EXPIRED", ...) here. Removed -- this
                    // whole branch is reached precisely for calls with NO linked real order (the
                    // existsBySignalId guard above already sent every order-backed call down a
                    // different path), which includes every call saved via POST /api/calls/save
                    // with arbitrary client-supplied RSI/MACD/pattern features that simply aged
                    // out unresolved. The GLOBAL, unscoped per-symbol ML weights that every
                    // user's live signal-scoring reads from must only ever learn from real,
                    // broker-confirmed fills -- see PositionMonitorService.
                    // writeRealOutcomeBackToSignal, the one place that now does this, wired in
                    // the same pass as this removal. "EXPIRED" never counted as a win or a loss
                    // in MLWeightService's own isWin/loss logic anyway (neither startsWith
                    // "HIT_T" nor equals "HIT_SL") -- it only ever inflated totalCalls (the
                    // warm-up counter) for calls nobody ever actually traded, which is removed
                    // along with the call itself.
                    continue;
                }
                PriceRange range = fetchPriceRange(call);
                if (range == null) continue;
                updateResultFromRange(call, range.high(), range.low());
            } catch (Exception e) {
                log.warn("[Updater] {}: {}", call.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * P2-19 fix ("CallResultUpdater: point-in-time price decides HIT_T/HIT_SL" -- external
     * review, confirmed real): the highest and lowest price actually reached over an interval,
     * not a single instant. Package-private (record, not private class) purely so its accessors
     * are directly usable from CallResultUpdaterTest without needing a real network call.
     */
    record PriceRange(double high, double low) {}

    /**
     * P2-19 fix ("CallResultUpdater: point-in-time price decides HIT_T/HIT_SL" -- external
     * review, confirmed real by direct inspection before this fix): this used to fetch a single
     * CURRENT spot price every 5 minutes (fetchPrice, removed) and compare THAT ONE instant
     * against stopLoss/target1/2/3 -- a real price spike or crash that touched a target or the
     * stop loss and reverted before the next 5-minute check was simply invisible, silently
     * misreporting a call's actual outcome. Fetches the real high/low actually reached across the
     * whole window from the call's own calledAt to now instead, via each market's own OHLC
     * candle data (Binance klines for crypto; Yahoo's chart quote arrays for stocks), and
     * updateResultFromRange below evaluates crossing against those actual extremes.
     *
     * Interval size is picked per elapsed duration to stay within each provider's own per-request
     * candle-count ceiling (Binance klines: 1000 max) while still covering the full window in one
     * call -- a coarser interval for an older, longer-open call trades finer intra-candle
     * resolution for coverage of the whole elapsed window, which is still strictly more accurate
     * than a single point-in-time price for that same call.
     */
    PriceRange fetchPriceRange(TradeCallRecord call) {
        try {
            long calledAtMillis = call.getCalledAt()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long elapsedMs = System.currentTimeMillis() - calledAtMillis;
            long elapsedHours = elapsedMs / 3_600_000L;

            if ("CRYPTO".equals(call.getMarket())) {
                String sym = call.getSymbol().replace("/USDT", "").replace("/", "") + "USDT";
                // Chosen so the full elapsed window fits comfortably under Binance's own 1000-
                // candle-per-request ceiling: 288 5m candles/day, 168 1h candles/week, 180 4h
                // candles for the full 30-day expiry window this class already enforces above.
                String interval = elapsedHours < 24 ? "5m" : elapsedHours < 24 * 7 ? "1h" : "4h";
                String resp = restTemplate.getForObject(
                    "https://api.binance.com/api/v3/klines?symbol=" + sym + "&interval=" + interval
                        + "&startTime=" + calledAtMillis + "&limit=1000", String.class);
                return rangeFromBinanceKlines(mapper.readTree(resp));
            } else if ("STOCK".equals(call.getMarket())) {
                String sym = call.getSymbol().contains(".") ? call.getSymbol() : call.getSymbol() + ".NS";
                // Honest scope note: Yahoo's own chart API only accepts a fixed set of range
                // buckets, not an arbitrary start/end window the way Binance's klines do -- the
                // closest bucket that still covers the elapsed window is used, which means an
                // older STOCK call's range check is coarser (daily, not intraday) than a CRYPTO
                // call's. Still strictly more accurate than the single point-in-time price this
                // replaces, and stock calls are a secondary path for this application (a crypto
                // auto-trading bot, per this project's own README) -- a fully accurate
                // arbitrary-range intraday history for equities would need a real, licensed
                // market-data provider this repository has no access to.
                String rangeParam = elapsedHours < 24 ? "1d" : elapsedHours < 24 * 5 ? "5d" : "1mo";
                String interval = elapsedHours < 24 ? "5m" : elapsedHours < 24 * 5 ? "15m" : "1d";
                String resp = restTemplate.getForObject(
                    "https://query1.finance.yahoo.com/v8/finance/chart/" + sym + "?range=" + rangeParam
                        + "&interval=" + interval, String.class);
                return rangeFromYahooChart(mapper.readTree(resp));
            }
        } catch (Exception e) {
            log.warn("[Updater] {}: failed to fetch price range ({})", call.getSymbol(), e.getMessage());
        }
        return null;
    }

    private PriceRange rangeFromBinanceKlines(JsonNode klines) {
        if (!klines.isArray() || klines.isEmpty()) return null;
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (JsonNode k : klines) {
            // Kline array layout: [openTime, open, high, low, close, volume, ...] -- same field
            // positions BinanceBrokerAdapter.getRecentCandles already relies on elsewhere.
            high = Math.max(high, k.get(2).asDouble());
            low = Math.min(low, k.get(3).asDouble());
        }
        if (Double.isInfinite(high) || Double.isInfinite(low)) return null;
        return new PriceRange(high, low);
    }

    private PriceRange rangeFromYahooChart(JsonNode chart) {
        JsonNode quote = chart.at("/chart/result/0/indicators/quote/0");
        if (quote.isMissingNode()) return null;
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (JsonNode h : highs) {
            if (!h.isNull()) high = Math.max(high, h.asDouble());
        }
        for (JsonNode l : lows) {
            if (!l.isNull()) low = Math.min(low, l.asDouble());
        }
        if (Double.isInfinite(high) || Double.isInfinite(low)) {
            // Every bar in range was null (e.g. a market holiday) -- fall back to the single
            // current price from the chart's own meta block rather than reporting nothing.
            double current = chart.at("/chart/result/0/meta/regularMarketPrice").asDouble(0);
            if (current <= 0) return null;
            return new PriceRange(current, current);
        }
        return new PriceRange(high, low);
    }

    /**
     * P2-19 fix, full context in fetchPriceRange's own updated comment above: evaluates crossing
     * against the actual high/low reached over the window, not a single price. Stop-loss is
     * always checked first regardless of whether a target was also touched in the same window --
     * deliberately conservative, since a candle's high/low alone can't say which was touched
     * FIRST, and a trading-outcome report should never overstate a result it can't actually
     * verify. Delegates to updateResult(call, price) with the exact boundary value that was
     * crossed (stopLoss/target1/2/3) rather than re-implementing the same precedence and PnL
     * logic a second time -- passing the boundary value itself makes updateResult's own
     * comparisons trivially true for that same outcome, so there's exactly one place this
     * decision logic lives.
     */
    void updateResultFromRange(TradeCallRecord call, double periodHigh, double periodLow) {
        boolean isLong = "LONG".equals(call.getDirection());
        double stopLoss = call.getStopLoss().doubleValue();
        double target1 = call.getTarget1().doubleValue();
        double target2 = call.getTarget2().doubleValue();
        double target3 = call.getTarget3().doubleValue();

        Double decisivePrice = null;
        if (isLong) {
            if (periodLow <= stopLoss) decisivePrice = stopLoss;
            else if (periodHigh >= target3) decisivePrice = target3;
            else if (periodHigh >= target2) decisivePrice = target2;
            else if (periodHigh >= target1) decisivePrice = target1;
        } else {
            if (periodHigh >= stopLoss) decisivePrice = stopLoss;
            else if (periodLow <= target3) decisivePrice = target3;
            else if (periodLow <= target2) decisivePrice = target2;
            else if (periodLow <= target1) decisivePrice = target1;
        }
        if (decisivePrice != null) {
            updateResult(call, decisivePrice);
        }
    }

    // Review finding ("CallResultUpdater can race with real position outcomes" -- P1, full
    // context in this method's own comment below): package-private, not private, specifically
    // so the atomic-update fix is directly testable without needing a real network call through
    // fetchPrice() -- restTemplate here is a real, non-mockable inline-initialized field, not a
    // constructor-injected dependency a test could swap out.
    void updateResult(TradeCallRecord call, double price) {
        boolean isLong = "LONG".equals(call.getDirection());
        TradeOutcome o = call.getOutcome() != null ? call.getOutcome() : new TradeOutcome();
        String result = null;
        // Review finding ("Financial values still mix double and BigDecimal" -- external
        // review, twenty-fourth pass, P2, full context in TradeCallRecord's own updated field
        // comment): those fields are BigDecimal now -- .doubleValue() here converts at this
        // method's own internal-comparison boundary, deliberately not cascading this change
        // into the shared PnlCalculator utility below (used by TradeCallService too) or
        // TradeOutcome's own Double fields, which stay outside this pass's scope.
        double stopLoss = call.getStopLoss().doubleValue();
        double target1 = call.getTarget1().doubleValue();
        double target2 = call.getTarget2().doubleValue();
        double target3 = call.getTarget3().doubleValue();
        if (isLong) {
            if (price <= stopLoss) result="HIT_SL";
            else if (price >= target3) result="HIT_T3";
            else if (price >= target2) result="HIT_T2";
            else if (price >= target1) result="HIT_T1";
        } else {
            if (price >= stopLoss) result="HIT_SL";
            else if (price <= target3) result="HIT_T3";
            else if (price <= target2) result="HIT_T2";
            else if (price <= target1) result="HIT_T1";
        }
        if (result != null) {
            o.setResult(result); o.setExitPrice(price); o.setResolvedAt(LocalDateTime.now());
            if (call.getEntryPrice().doubleValue() > 0) {
                // Review finding ("Financial model still mixes double and BigDecimal" --
                // external review, sixteenth pass, P2, full context in PnlCalculator's own
                // class javadoc): this formula used to be duplicated verbatim here and in
                // TradeCallService -- now the single, shared implementation.
                var pnl = com.tradevision.util.PnlCalculator.compute(call.getEntryPrice().doubleValue(), price, stopLoss, isLong);
                o.setPnlPct(pnl.pnlPct());
                o.setPnlR(pnl.pnlR());
            }
            call.setOutcome(o);
            // Review finding ("CallResultUpdater can race with real position outcomes" -- P1):
            // confirmed real -- the guard at the top of updatePendingCalls() only checks
            // existsBySignalId() ONCE, at the start of this call's own loop iteration. Between
            // that check and this save, fetchPrice() makes a real, potentially slow network
            // call -- if a real trade executes and PositionMonitorService writes the ACTUAL
            // outcome onto this same call during that exact window, the old plain
            // callRepo.save(call) here would silently overwrite it with this theoretical,
            // ticker-crossing-based guess. Fixed with an atomic conditional update, re-checked
            // fresh at the moment of the actual write, not trusted from when the loop iteration
            // started: only applies if outcome.result is STILL "PENDING" in the database right
            // now. A real outcome write landing in that window means this update simply doesn't
            // apply -- exactly the same "lost the race, and that's correct" pattern already
            // established elsewhere in this codebase this session.
            var updateResult = mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(call.getId())
                        .and("outcome.result").is("PENDING")),
                new org.springframework.data.mongodb.core.query.Update().set("outcome", o),
                TradeCallRecord.class);
            if (updateResult.getModifiedCount() == 0) {
                log.info("[Updater] {}: skipped applying theoretical result {} -- this call's real outcome was written by "
                    + "something else during price-fetch.", call.getSymbol(), result);
                return;
            }
            // Review finding (P1 #5 -- "Global ML weights can be poisoned by unverified,
            // client-supplied trade outcomes"): this used to call mlWeightService.recordOutcome
            // right here with THIS method's own guessed result (a plain price-vs-SL/target
            // comparison against whatever entry/SL/target values the call was saved with --
            // client-suppliable, never verified against a real fill). That guess is exactly what
            // reaches this line for every call with no linked real order (the existsBySignalId
            // guard in updatePendingCalls already sent every order-backed call down a different,
            // real-outcome path) -- including any call saved via POST /api/calls/save with
            // fabricated RSI/MACD/pattern/SL/target values designed to nudge the GLOBAL,
            // unscoped per-symbol weights that every user's live signal-scoring reads from.
            // Removed: the learner now only ever hears about real, broker-confirmed fills, via
            // PositionMonitorService.writeRealOutcomeBackToSignal. This method still writes the
            // theoretical result onto the call's own outcome (above) -- that's real, useful
            // history for the user who saved the call -- it just no longer feeds the shared
            // learner.
            log.info("[Updater] {} -> {} @{}", call.getSymbol(), result, price);
        }
    }
}
