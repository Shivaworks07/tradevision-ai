package com.tradevision.model.features;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Market context: regime, session, time features */
@Data @NoArgsConstructor
public class MarketContextFeatures {
    String  regime;
    double  regimeAdx;
    double  regimeBbWidth;
    double  regimeAtrPct;
    String  exchange;
    String  assetClass;
    String  marketSession;
    int     dayOfWeek;
    int     hourOfDay;
    boolean isWeekend;
    boolean isMonthEnd;
}
