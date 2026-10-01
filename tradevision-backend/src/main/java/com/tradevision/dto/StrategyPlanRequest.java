package com.tradevision.dto;

import com.tradevision.model.TradeDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.util.Set;

/**
 * User's own explicit multi-strategy-plan design, full context in StrategyPlan's own class
 * javadoc: the request shape backing the new create/update API -- every field the user's own
 * mockup named ("Timeframe", "Direction", "Coin Selection", "Risk per Trade", "Maximum Open
 * Trades", "Maximum Holding Time", "Cooldown", "Auto Trade").
 */
@Data
public class StrategyPlanRequest {
    @NotBlank private String credentialId;
    private String name = "New Plan";
    @NotBlank private String timeframe;
    private TradeDirection direction = TradeDirection.LONG;

    private Set<String> enabledSymbols;
    private boolean dynamicUniverseEnabled;
    @Positive private int dynamicUniverseMaxSymbols = 10;

    @Positive private double riskPerTradePercent = 1.0;
    @Positive private int maxConcurrentTrades = 1;
    private Double maxCapital;

    @PositiveOrZero private Integer maxHoldMinutes;
    private boolean exitOnSignalReversal;
    private boolean exitOnRiskEmergency = true;

    private com.tradevision.model.SessionMode sessionMode = com.tradevision.model.SessionMode.ALWAYS_ON;
    private java.time.LocalTime sessionStart;
    private java.time.LocalTime sessionEnd;
    private String sessionTimezone = "UTC";
    private Set<java.time.DayOfWeek> sessionDays;
    private com.tradevision.model.EndOfSessionAction endOfSessionAction = com.tradevision.model.EndOfSessionAction.CLOSE_POSITIONS;

    @Positive private double minConfidence = 75.0;
    @PositiveOrZero private int cooldownMinutes = 15;

    private boolean enabled = true;
}
