package com.tradevision.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradevision.service.ExchangeHealthService;
import com.tradevision.service.strategy.dto.OrderFlowAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ported TA computation logic against known reference outputs. Private methods
 * are accessed via reflection because RestTemplate here is inline-initialized rather than
 * constructor-injected, so it isn't mockable — these tests exercise the computation directly
 * instead of mocking the call path.
 */
class OrderFlowServiceTest {

    // This test never exercises ExchangeHealthService's own behavior, so a plain Mockito.mock()
    // suffices for its required MongoTemplate argument.
    private final OrderFlowService service = new OrderFlowService(
        new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class)));
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String jsonText) throws Exception { return mapper.readTree(jsonText); }

    private Object invokeProcessFunding(JsonNode data, String symbol) throws Exception {
        Method m = OrderFlowService.class.getDeclaredMethod("processFunding", JsonNode.class, String.class);
        m.setAccessible(true);
        return m.invoke(service, data, symbol);
    }

    private Object invokeProcessOI(JsonNode oi, JsonNode oiHist, JsonNode ticker, String symbol) throws Exception {
        Method m = OrderFlowService.class.getDeclaredMethod("processOI", JsonNode.class, JsonNode.class, JsonNode.class, String.class);
        m.setAccessible(true);
        return m.invoke(service, oi, oiHist, ticker, symbol);
    }

    private Object invokeProcessCVD(JsonNode trades) throws Exception {
        Method m = OrderFlowService.class.getDeclaredMethod("processCVD", JsonNode.class);
        m.setAccessible(true);
        return m.invoke(service, trades);
    }

    private Object invokeCalculateBias(Object f, Object oi, Object cvd) throws Exception {
        Method m = OrderFlowService.class.getDeclaredMethod("calculateBias",
            OrderFlowAnalysis.FundingData.class, OrderFlowAnalysis.OpenInterestData.class, OrderFlowAnalysis.CVDData.class);
        m.setAccessible(true);
        return m.invoke(service, f, oi, cvd);
    }

    @Test
    @DisplayName("processFunding: matches the real TypeScript engine's reference output — mixed bullish scenario")
    void processFunding_matchesReferenceOutput() throws Exception {
        var funding = (OrderFlowAnalysis.FundingData) invokeProcessFunding(json("{\"lastFundingRate\":\"0.0002\"}"), "BTCUSDT");

        assertThat(funding.fundingRate()).isEqualTo(0.02);
        assertThat(funding.bias()).isEqualTo("LONG_BIAS");
        assertThat(funding.interpretation()).contains("slightly positive");
    }

    @Test
    @DisplayName("processFunding: extreme negative funding produces SHORT_BIAS with squeeze language")
    void processFunding_extremeNegative_shortBias() throws Exception {
        var funding = (OrderFlowAnalysis.FundingData) invokeProcessFunding(json("{\"lastFundingRate\":\"-0.0006\"}"), "ETHUSDT");

        assertThat(funding.fundingRate()).isEqualTo(-0.06);
        assertThat(funding.bias()).isEqualTo("SHORT_BIAS");
        assertThat(funding.interpretation()).contains("short squeeze");
    }

    @Test
    @DisplayName("processFunding: a missing node (failed fetch upstream) returns null, never a fabricated funding rate")
    void processFunding_missingNode_returnsNull() throws Exception {
        JsonNode missing = mapper.readTree("{}").path("nonexistent"); // MissingNode

        var result = invokeProcessFunding(missing, "BTCUSDT");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("processOI: matches the real TypeScript engine's reference output — longs building scenario")
    void processOI_matchesReferenceOutput() throws Exception {
        var oi = (OrderFlowAnalysis.OpenInterestData) invokeProcessOI(
            json("{\"openInterest\":\"50000\"}"),
            json("[{\"sumOpenInterest\":\"48000\"},{\"sumOpenInterest\":\"49000\"}]"),
            json("{\"lastPrice\":\"50000\",\"priceChangePercent\":\"1.5\"}"),
            "BTCUSDT"
        );

        assertThat(oi.oi()).isEqualTo(2_500_000_000.0);
        assertThat(oi.oiChange1h()).isEqualTo(4.17);
        assertThat(oi.signal()).isEqualTo("LONGS_BUILDING");
    }

    @Test
    @DisplayName("processOI: dropping OI with rising price is a SHORT_SQUEEZE")
    void processOI_droppingOiRisingPrice_shortSqueeze() throws Exception {
        var oi = (OrderFlowAnalysis.OpenInterestData) invokeProcessOI(
            json("{\"openInterest\":\"10000\"}"),
            json("[{\"sumOpenInterest\":\"11000\"},{\"sumOpenInterest\":\"10800\"}]"),
            json("{\"lastPrice\":\"3000\",\"priceChangePercent\":\"3.0\"}"),
            "ETHUSDT"
        );

        assertThat(oi.signal()).isEqualTo("SHORT_SQUEEZE");
        assertThat(oi.oiChange1h()).isEqualTo(-9.09);
    }

    @Test
    @DisplayName("processCVD: buyer-aggressor trades (m=false) count as buy volume, matching Binance's own isBuyerMaker convention")
    void processCVD_matchesReferenceOutput() throws Exception {
        var cvd = (OrderFlowAnalysis.CVDData) invokeProcessCVD(json(
            "[{\"q\":\"1.0\",\"p\":\"50000\",\"m\":false},{\"q\":\"2.0\",\"p\":\"50010\",\"m\":false},{\"q\":\"0.5\",\"p\":\"49990\",\"m\":true}]"
        ));

        assertThat(cvd.cvd()).isEqualTo(125025.0);
        assertThat(cvd.cvdTrend()).isEqualTo("RISING");
        assertThat(cvd.buyVol()).isEqualTo(150020.0);
        assertThat(cvd.sellVol()).isEqualTo(24995.0);
    }

    @Test
    @DisplayName("processCVD: empty trade list returns null, not a fabricated zero CVD")
    void processCVD_emptyTrades_returnsNull() throws Exception {
        var result = invokeProcessCVD(json("[]"));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("calculateBias: combined bullish signals (longs building + rising CVD) score BULL, matching the real reference output")
    void calculateBias_combinedBullishSignals() throws Exception {
        var funding = invokeProcessFunding(json("{\"lastFundingRate\":\"0.0002\"}"), "BTCUSDT");
        var oi = invokeProcessOI(json("{\"openInterest\":\"50000\"}"),
            json("[{\"sumOpenInterest\":\"48000\"},{\"sumOpenInterest\":\"49000\"}]"),
            json("{\"lastPrice\":\"50000\",\"priceChangePercent\":\"1.5\"}"), "BTCUSDT");
        var cvd = invokeProcessCVD(json("[{\"q\":\"1.0\",\"p\":\"50000\",\"m\":false},{\"q\":\"2.0\",\"p\":\"50010\",\"m\":false},{\"q\":\"0.5\",\"p\":\"49990\",\"m\":true}]"));

        var biasResultClass = Class.forName("com.tradevision.service.strategy.OrderFlowService$BiasResult");
        Object bias = invokeCalculateBias(funding, oi, cvd);
        double score = (double) biasResultClass.getDeclaredMethod("score").invoke(bias);
        String biasStr = (String) biasResultClass.getDeclaredMethod("bias").invoke(bias);

        assertThat(biasStr).isEqualTo("BULL");
        assertThat(score).isEqualTo(30.0);
    }

    @Test
    @DisplayName("calculateBias: all-null inputs produce NEUTRAL with zero score and no reasons — never fabricates a bias from missing data")
    void calculateBias_allNullInputs_neutral() throws Exception {
        var biasResultClass = Class.forName("com.tradevision.service.strategy.OrderFlowService$BiasResult");
        Object bias = invokeCalculateBias(null, null, null);
        double score = (double) biasResultClass.getDeclaredMethod("score").invoke(bias);
        String biasStr = (String) biasResultClass.getDeclaredMethod("bias").invoke(bias);
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) biasResultClass.getDeclaredMethod("reasons").invoke(bias);

        assertThat(biasStr).isEqualTo("NEUTRAL");
        assertThat(score).isEqualTo(0.0);
        assertThat(reasons).isEmpty();
    }

    // Note: analyze() itself (the full method, including its live HTTP calls) is deliberately
    // NOT unit-tested here — it isn't a deterministic unit under test, since its behavior
    // depends on genuine network reachability to Binance, which varies by environment (blocked
    // in this sandbox, likely reachable in a real deployment). A test asserting on its outcome
    // would be testing network conditions, not this class's own logic, and could pass here for
    // the wrong reason and fail elsewhere for a different wrong reason. The computation methods
    // above are the actual, deterministic unit under test.
}
