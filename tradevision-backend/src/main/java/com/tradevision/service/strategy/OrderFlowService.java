package com.tradevision.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradevision.service.ExchangeHealthService;
import com.tradevision.service.strategy.dto.OrderFlowAnalysis;
import com.tradevision.service.strategy.dto.OrderFlowAnalysis.CVDData;
import com.tradevision.service.strategy.dto.OrderFlowAnalysis.FundingData;
import com.tradevision.service.strategy.dto.OrderFlowAnalysis.OpenInterestData;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives an order-flow bias from Binance futures data: funding rate, open interest change, and
 * cumulative volume delta (CVD) from recent aggregated trades. Each component contributes an
 * interpretation and a score, which calculateBias combines into an overall directional bias.
 *
 * Always calls the live Binance futures API (fapi.binance.com), regardless of whether the
 * accompanying spot credential is in TESTNET or LIVE mode — unlike other broker calls in this
 * codebase, which are mode-aware. Testnet futures data is synthetic and unrelated to real market
 * sentiment, so a testnet-mode credential asking what funding/open interest are actually doing
 * needs the real market answer, not a sandboxed one.
 *
 * Per-endpoint fetch failures are non-fatal and additive: if one endpoint (e.g. OI history) fails,
 * the others still populate their respective fields rather than blanking the whole analysis.
 */
@Service
@RequiredArgsConstructor
public class OrderFlowService {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowService.class);
    private static final String FUTURES_BASE = "https://fapi.binance.com";

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExchangeHealthService exchangeHealth;

    /**
     * spotSymbol is expected in "BTCUSDT" form, this codebase's convention throughout (see
     * AutonomousScannerService's TIER1_SYMBOLS). The uppercase+suffix normalization here is a
     * no-op for a symbol already in that form, but guards against a stray "/USDT" separator.
     */
    public OrderFlowAnalysis analyze(String spotSymbol) {
        String symbol = spotSymbol.toUpperCase().replace("/USDT", "") + "USDT";
        try {
            JsonNode fundingJson = getJson("/fapi/v1/premiumIndex?symbol=" + symbol);
            JsonNode oiJson = getJson("/fapi/v1/openInterest?symbol=" + symbol);
            JsonNode oiHistJson = getJson("/futures/data/openInterestHist?symbol=" + symbol + "&period=1h&limit=24");
            JsonNode tradesJson = getJson("/fapi/v1/aggTrades?symbol=" + symbol + "&limit=200");
            JsonNode tickerJson = getJson("/fapi/v1/ticker/24hr?symbol=" + symbol);

            FundingData funding = processFunding(fundingJson, symbol);
            OpenInterestData oi = processOI(oiJson, oiHistJson, tickerJson, symbol);
            CVDData cvd = processCVD(tradesJson);
            BiasResult bias = calculateBias(funding, oi, cvd);

            return new OrderFlowAnalysis(funding, oi, cvd, bias.bias(), bias.score(), bias.reasons(),
                buildSummary(spotSymbol, bias.bias(), bias.score(), funding, oi, cvd), true);
        } catch (Exception e) {
            log.warn("Order flow analysis failed for {}: {}", spotSymbol, e.getMessage());
            return emptyAnalysis();
        }
    }

    private JsonNode getJson(String path) {
        long startedAt = System.currentTimeMillis();
        try {
            ResponseEntity<String> resp = http.exchange(FUTURES_BASE + path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            exchangeHealth.record("FUTURES_PUBLIC_ENDPOINT", true, System.currentTimeMillis() - startedAt);
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            exchangeHealth.record("FUTURES_PUBLIC_ENDPOINT", false, System.currentTimeMillis() - startedAt);
            return null; // additive/non-fatal per-field — a single failed endpoint (e.g. OI history) shouldn't blank out funding/CVD too
        }
    }

    private FundingData processFunding(JsonNode data, String symbol) {
        if (data == null || data.isMissingNode()) return null;
        double rate = data.path("lastFundingRate").asDouble(0) * 100;
        String bias, interpretation;
        if (rate > 0.05) { bias = "LONG_BIAS"; interpretation = String.format("Funding %.4f%% -- longs paying shorts. Market overleveraged long. Potential long squeeze if price dips.", rate); }
        else if (rate < -0.05) { bias = "SHORT_BIAS"; interpretation = String.format("Funding %.4f%% -- shorts paying longs. Market overleveraged short. Potential short squeeze.", rate); }
        else if (rate > 0.01) { bias = "LONG_BIAS"; interpretation = String.format("Funding %.4f%% -- slightly positive. Mild bullish sentiment in futures.", rate); }
        else if (rate < -0.01) { bias = "SHORT_BIAS"; interpretation = String.format("Funding %.4f%% -- slightly negative. Mild bearish sentiment in futures.", rate); }
        else { bias = "NEUTRAL"; interpretation = String.format("Funding %.4f%% -- neutral. Balanced leverage, no extreme positioning.", rate); }
        return new FundingData(symbol, round(rate, 4), round(rate, 4), bias, interpretation);
    }

    private OpenInterestData processOI(JsonNode oi, JsonNode oiHist, JsonNode ticker, String symbol) {
        if (oi == null || oi.isMissingNode()) return null;
        double currentOI = oi.path("openInterest").asDouble(0);
        double price = (ticker != null) ? ticker.path("lastPrice").asDouble(0) : 0;
        double oiUSD = currentOI * price;

        double oiChange1h = 0, oiChange24h = 0, priceChange = 0;
        if (oiHist != null && oiHist.isArray() && oiHist.size() >= 2) {
            double oldest = oiHist.get(0).path("sumOpenInterest").asDouble(currentOI);
            double hour1 = oiHist.get(Math.max(0, oiHist.size() - 2)).path("sumOpenInterest").asDouble(currentOI);
            oiChange1h = oldest > 0 ? ((currentOI - hour1) / hour1) * 100 : 0;
            oiChange24h = oldest > 0 ? ((currentOI - oldest) / oldest) * 100 : 0;
        }
        if (ticker != null) priceChange = ticker.path("priceChangePercent").asDouble(0);

        String signal, interpretation;
        if (oiChange1h > 2 && priceChange > 0) { signal = "LONGS_BUILDING"; interpretation = String.format("OI +%.1f%% with price up -- longs building. Bullish momentum.", oiChange1h); }
        else if (oiChange1h > 2 && priceChange < 0) { signal = "SHORTS_BUILDING"; interpretation = String.format("OI +%.1f%% with price down -- shorts building. Bearish pressure.", oiChange1h); }
        else if (oiChange1h < -3 && priceChange > 0) { signal = "SHORT_SQUEEZE"; interpretation = String.format("OI dropping %.1f%% with price rising -- SHORT SQUEEZE in progress!", oiChange1h); }
        else if (oiChange1h < -3 && priceChange < 0) { signal = "LONG_SQUEEZE"; interpretation = String.format("OI dropping %.1f%% with price falling -- LONG SQUEEZE / capitulation.", oiChange1h); }
        else { signal = "NEUTRAL"; interpretation = String.format("OI change %.1f%% -- no strong positioning bias.", oiChange1h); }

        return new OpenInterestData(symbol, round(oiUSD, 0), round(oiChange1h, 2), round(oiChange24h, 2), round(priceChange, 2), signal, interpretation);
    }

    private CVDData processCVD(JsonNode trades) {
        if (trades == null || !trades.isArray() || trades.isEmpty()) return null;
        double buyVol = 0, sellVol = 0;
        for (JsonNode t : trades) {
            double qty = t.has("q") ? t.get("q").asDouble(0) : t.path("quantity").asDouble(0);
            double price = t.has("p") ? t.get("p").asDouble(0) : t.path("price").asDouble(0);
            double val = qty * price;
            boolean isBuyerMaker = t.has("m") ? t.get("m").asBoolean(true) : t.path("isBuyerMaker").asBoolean(true);
            if (!isBuyerMaker) buyVol += val; else sellVol += val;
        }
        double cvd = buyVol - sellVol;
        double total = (buyVol + sellVol) != 0 ? (buyVol + sellVol) : 1;
        String cvdTrend = cvd > total * 0.05 ? "RISING" : cvd < -total * 0.05 ? "FALLING" : "FLAT";
        String interpretation = cvdTrend.equals("RISING") ? String.format("CVD +%.2fM -- more aggressive buying. Bulls in control.", cvd / 1000000)
            : cvdTrend.equals("FALLING") ? String.format("CVD %.2fM -- more aggressive selling. Bears in control.", cvd / 1000000)
            : "CVD balanced -- no dominant aggressor.";
        return new CVDData(round(cvd, 0), cvdTrend, round(buyVol, 0), round(sellVol, 0), interpretation);
    }

    private record BiasResult(String bias, double score, List<String> reasons) {}

    private BiasResult calculateBias(FundingData f, OpenInterestData oi, CVDData cvd) {
        double score = 0;
        List<String> reasons = new ArrayList<>();
        if (f != null) {
            if (f.fundingRate() < -0.03) { score += 25; reasons.add(String.format("Funding %.3f%% -- shorts overleveraged (squeeze fuel)", f.fundingRate())); }
            else if (f.fundingRate() < 0) { score += 10; reasons.add("Funding slightly negative (bearish positioning)"); }
            else if (f.fundingRate() > 0.05) { score -= 20; reasons.add(String.format("Funding %.3f%% -- longs overleveraged (squeeze risk)", f.fundingRate())); }
            else if (f.fundingRate() > 0) { score -= 5; }
        }
        if (oi != null) {
            if (oi.signal().equals("LONGS_BUILDING")) { score += 20; reasons.add("OI rising with price -- longs building (+" + oi.oiChange1h() + "%)"); }
            if (oi.signal().equals("SHORT_SQUEEZE")) { score += 30; reasons.add("SHORT SQUEEZE -- OI dropping as price rises"); }
            if (oi.signal().equals("SHORTS_BUILDING")) { score -= 20; reasons.add("OI rising with price down -- shorts building"); }
            if (oi.signal().equals("LONG_SQUEEZE")) { score -= 30; reasons.add("LONG SQUEEZE -- forced long liquidations"); }
        }
        if (cvd != null) {
            if (cvd.cvdTrend().equals("RISING")) { score += 15; reasons.add("CVD rising -- aggressive buying pressure"); }
            if (cvd.cvdTrend().equals("FALLING")) { score -= 15; reasons.add("CVD falling -- aggressive selling pressure"); }
        }
        score = Math.max(-100, Math.min(100, score));
        String bias = score >= 40 ? "STRONG_BULL" : score >= 15 ? "BULL" : score <= -40 ? "STRONG_BEAR" : score <= -15 ? "BEAR" : "NEUTRAL";
        return new BiasResult(bias, score, reasons);
    }

    private String buildSummary(String symbol, String bias, double score, FundingData f, OpenInterestData oi, CVDData cvd) {
        List<String> parts = new ArrayList<>();
        parts.add(String.format("Order Flow: %s (score: %s%.0f)", bias, score > 0 ? "+" : "", score));
        if (f != null) parts.add(f.interpretation());
        if (oi != null) parts.add(oi.interpretation());
        if (cvd != null) parts.add(cvd.interpretation());
        return String.join(" | ", parts);
    }

    private OrderFlowAnalysis emptyAnalysis() {
        return new OrderFlowAnalysis(null, null, null, "NEUTRAL", 0, List.of(), "Order flow data unavailable.", false);
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
