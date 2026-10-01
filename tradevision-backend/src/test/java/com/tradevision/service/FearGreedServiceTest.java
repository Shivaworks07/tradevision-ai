package com.tradevision.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("#2 — Full authoritative strategy engine"): unlike every other test this
 * session (which verifies logic by hand-tracing), this one verifies against a REAL, CAPTURED
 * reference — the actual fear-greed.service.ts getSignal/getColor/getEmoji functions, extracted
 * verbatim (not rewritten) and run under Node against 19 cases spanning every threshold
 * boundary, with the exact output captured and hardcoded below. This is the closest this session
 * gets to a genuine cross-language parity test, specifically because this piece was small and
 * self-contained enough to make that kind of verification possible — the same standard could not
 * responsibly be applied to SMC/order-flow/volume-profile, which is exactly why those remain
 * unported rather than blindly guessed at.
 */
class FearGreedServiceTest {

    private final FearGreedService service = new FearGreedService();

    @Test
    @DisplayName("classify: exact parity with the real TypeScript output across every threshold boundary — verified against a captured Node run, not assumed")
    void classify_exactParityWithOriginalTypeScript() {
        assertCase(0, 0, "STRONG_BUY", "Extreme Fear 0 — historically the best time to accumulate. Market panic creates opportunity. ", "#FF3B5C", "Extreme Fear");
        assertCase(15, 10, "STRONG_BUY", "Extreme Fear 15 — historically the best time to accumulate. Market panic creates opportunity. Sentiment recovering (+5).", "#FF3B5C", "Extreme Fear");
        assertCase(20, 15, "STRONG_BUY", "Extreme Fear 20 — historically the best time to accumulate. Market panic creates opportunity. Sentiment recovering (+5).", "#FF3B5C", "Extreme Fear");
        assertCase(21, 20, "BUY", "Fear 21 — market is fearful. Contrarian signal: consider buying quality assets. ", "#FF6B35", "Fear");
        assertCase(30, 25, "BUY", "Fear 30 — market is fearful. Contrarian signal: consider buying quality assets. ", "#FF6B35", "Fear");
        assertCase(35, 30, "BUY", "Fear 35 — market is fearful. Contrarian signal: consider buying quality assets. ", "#FF6B35", "Fear");
        assertCase(36, 35, "NEUTRAL", "Neutral 36 — market sentiment balanced. Follow technical signals. Stable sentiment.", "#FF6B35", "Fear");
        assertCase(50, 45, "NEUTRAL", "Neutral 50 — market sentiment balanced. Follow technical signals. Stable sentiment.", "#FFB800", "Neutral");
        assertCase(50, 60, "NEUTRAL", "Neutral 50 — market sentiment balanced. Follow technical signals. Shifting towards fear.", "#FFB800", "Neutral");
        assertCase(64, 60, "NEUTRAL", "Neutral 64 — market sentiment balanced. Follow technical signals. Stable sentiment.", "#00D4FF", "Greed");
        assertCase(65, 64, "SELL", "Greed 65 — market is overconfident. Reduce risk, take partial profits. Greed still rising — be cautious.", "#00D4FF", "Greed");
        assertCase(66, 70, "SELL", "Greed 66 — market is overconfident. Reduce risk, take partial profits. ", "#00D4FF", "Greed");
        assertCase(79, 75, "SELL", "Greed 79 — market is overconfident. Reduce risk, take partial profits. Greed still rising — be cautious.", "#00D4FF", "Greed");
        assertCase(80, 79, "STRONG_SELL", "Extreme Greed 80 — market is euphoric. Historically precedes corrections. Consider taking profits.", "#00D4FF", "Greed");
        assertCase(81, 85, "STRONG_SELL", "Extreme Greed 81 — market is euphoric. Historically precedes corrections. Greed decreasing — watch for reversal.", "#00FF88", "Extreme Greed");
        assertCase(100, 90, "STRONG_SELL", "Extreme Greed 100 — market is euphoric. Historically precedes corrections. Consider taking profits.", "#00FF88", "Extreme Greed");
        assertCase(50, 50, "NEUTRAL", "Neutral 50 — market sentiment balanced. Follow technical signals. Stable sentiment.", "#FFB800", "Neutral");
        assertCase(10, 20, "STRONG_BUY", "Extreme Fear 10 — historically the best time to accumulate. Market panic creates opportunity. ", "#FF3B5C", "Extreme Fear");
        assertCase(90, 95, "STRONG_SELL", "Extreme Greed 90 — market is euphoric. Historically precedes corrections. Greed decreasing — watch for reversal.", "#00FF88", "Extreme Greed");
    }

    private void assertCase(int val, int prev, String expectedSignal, String expectedReason, String expectedColor, String expectedEmoji) {
        var result = service.classify(val, prev);
        assertThat(result.signal()).as("signal for val=%d prev=%d", val, prev).isEqualTo(expectedSignal);
        assertThat(result.reason()).as("reason for val=%d prev=%d", val, prev).isEqualTo(expectedReason);
        assertThat(result.color()).as("color for val=%d prev=%d", val, prev).isEqualTo(expectedColor);
        assertThat(result.emoji()).as("emoji for val=%d prev=%d", val, prev).isEqualTo(expectedEmoji);
    }
}
