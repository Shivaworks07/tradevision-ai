package com.tradevision.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Classifies the Fear & Greed Index into a trading signal, color and label. This is a Java port
 * of the original fear-greed.service.ts getSignal/getColor/getEmoji logic, chosen as a standalone
 * port because it is a small, pure, self-contained function with no candle data or dependency on
 * the larger strategy-engine code (SMC/BOS/CHOCH/order-blocks/FVG/volume-profile/order-flow/CVD/
 * regime-detection), which remains in TypeScript. The port is checked against the original
 * TypeScript's captured output across threshold boundaries in FearGreedServiceTest.
 */
@Service
public class FearGreedService {

    public record FearGreedSignal(String signal, String reason, String color, String emoji) {}

    private final RestTemplate http = new RestTemplate();

    /**
     * Fetches the current Fear &amp; Greed Index directly from the upstream API
     * (https://api.alternative.me), the same source ProxyController.fngApi forwards to. This
     * calls the upstream directly rather than going through this backend's own frontend-facing
     * proxy route, which exists for the browser to reach the API through this backend, not for
     * this backend to call itself.
     */
    public FearGreedSignal fetchCurrent() {
        try {
            var resp = http.getForObject("https://api.alternative.me/fng/?limit=2&format=json", com.fasterxml.jackson.databind.JsonNode.class);
            if (resp == null || !resp.has("data") || resp.get("data").isEmpty()) return null;
            var data = resp.get("data");
            int val = data.get(0).get("value").asInt();
            int prev = data.size() > 1 ? data.get(1).get("value").asInt() : val;
            return classify(val, prev);
        } catch (Exception e) {
            return null; // upstream unavailable — never fabricate a "neutral" reading when the real value is unknown
        }
    }

    /** Classifies a Fear &amp; Greed value (and its trend vs. the previous reading) into a signal and reason; ported from fear-greed.service.ts's getSignal. */
    public FearGreedSignal classify(int val, int prev) {
        int trend = val - prev;
        String signal;
        String reason;
        if (val <= 20) {
            signal = "STRONG_BUY";
            reason = "Extreme Fear " + val + " — historically the best time to accumulate. Market panic creates opportunity. "
                + (trend > 0 ? "Sentiment recovering (+" + trend + ")." : "");
        } else if (val <= 35) {
            signal = "BUY";
            reason = "Fear " + val + " — market is fearful. Contrarian signal: consider buying quality assets. "
                + (trend > 5 ? "Fear is decreasing — sentiment improving." : "");
        } else if (val >= 80) {
            signal = "STRONG_SELL";
            reason = "Extreme Greed " + val + " — market is euphoric. Historically precedes corrections. "
                + (trend < 0 ? "Greed decreasing — watch for reversal." : "Consider taking profits.");
        } else if (val >= 65) {
            signal = "SELL";
            reason = "Greed " + val + " — market is overconfident. Reduce risk, take partial profits. "
                + (trend > 0 ? "Greed still rising — be cautious." : "");
        } else {
            signal = "NEUTRAL";
            reason = "Neutral " + val + " — market sentiment balanced. Follow technical signals. "
                + (trend > 5 ? "Shifting towards greed." : trend < -5 ? "Shifting towards fear." : "Stable sentiment.");
        }
        return new FearGreedSignal(signal, reason, getColor(val), getEmoji(val));
    }

    public String getColor(int val) {
        if (val <= 20) return "#FF3B5C";
        if (val <= 40) return "#FF6B35";
        if (val <= 60) return "#FFB800";
        if (val <= 80) return "#00D4FF";
        return "#00FF88";
    }

    public String getEmoji(int val) {
        if (val <= 20) return "Extreme Fear";
        if (val <= 40) return "Fear";
        if (val <= 60) return "Neutral";
        if (val <= 80) return "Greed";
        return "Extreme Greed";
    }
}
