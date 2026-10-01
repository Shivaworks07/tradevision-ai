package com.tradevision.model;

/**
 * User's own explicit design: "Give them: Session mode - 24/7 - Fixed daily session - Custom
 * days + session." ALWAYS_ON is the default on StrategyPlan itself (see that field's own
 * javadoc) -- the user's own explicit instruction: "I'd make 24/7 the default, so existing
 * strategies aren't unexpectedly flattened."
 */
public enum SessionMode {
    ALWAYS_ON,
    DAILY,
    CUSTOM_DAYS
}
