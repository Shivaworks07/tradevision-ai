package com.tradevision.model.features;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Order Flow: Funding Rate, Open Interest, CVD (crypto only) */
@Data @NoArgsConstructor
public class OrderFlowFeatures {
    String  bias;
    int     score;
    double  fundingRate;
    String  oiSignal;
    double  oiChange1h;
    String  cvdTrend;
    boolean shortSqueezePotential;
    boolean longSqueezePotential;
}
