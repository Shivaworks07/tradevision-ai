package com.tradevision.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;

/**
 * Validates app.trading.stop-loss-limit-gap-percent at startup and refuses to start if it is
 * outside the only sane range, [0, 1). This value feeds directly into
 * {@code stopTrigger.multiply(BigDecimal.ONE.subtract(gap))} at every OCO placement site in
 * AutoTradeService and PositionMonitorService:
 * <ul>
 *   <li>a negative value produces a stop-limit price ABOVE the stop trigger — the stop leg would
 *       rest above its own trigger, which is not how a protective stop-limit sell is supposed to
 *       behave;</li>
 *   <li>a value of exactly 1.0 produces a stop-limit price of zero;</li>
 *   <li>a value greater than 1.0 produces a negative stop-limit price.</li>
 * </ul>
 * None of these are plausible intentional configurations — they are typos (e.g. "0.5" meant as
 * "0.5%" i.e. 0.005, or a stray extra digit) that would otherwise only surface the first time an
 * OCO is actually placed, potentially with a LIVE credential already connected. Validating here,
 * once, at process boot, and refusing to start on a bad value follows the same pattern as this
 * codebase's other config-sanity checks (see AlertChannelStartupGuard, DevSecretStartupGuard).
 */
@Component
public class TradingConfigStartupGuard {

    @Value("${app.trading.stop-loss-limit-gap-percent:0.005}")
    private BigDecimal stopLossLimitGapPercent;

    @PostConstruct
    public void validateStopLossLimitGapPercent() {
        if (stopLossLimitGapPercent == null || stopLossLimitGapPercent.signum() < 0
                || stopLossLimitGapPercent.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException("REFUSING TO START: app.trading.stop-loss-limit-gap-percent is set to "
                + stopLossLimitGapPercent + ", which is outside the only sane range for this value, [0, 1). This gap is "
                + "subtracted from the stop trigger price to compute the stop-limit price for every OCO exit this application "
                + "places (stopLimit = stopTrigger * (1 - gap)) -- a negative value would place the stop-limit ABOVE its own "
                + "trigger, and a value of 1.0 or more would produce a zero or negative stop-limit price. Set it to a small "
                + "fraction (the default is 0.005, i.e. 0.5%) via app.trading.stop-loss-limit-gap-percent.");
        }
    }
}
