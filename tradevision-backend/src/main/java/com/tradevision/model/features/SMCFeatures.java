package com.tradevision.model.features;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Smart Money: BOS, CHoCH, Order Blocks, FVG, Liquidity, P/D zones */
@Data @NoArgsConstructor
public class SMCFeatures {
    String  smcBias;
    int     smcBiasStrength;
    boolean bosDetected;
    boolean chochDetected;
    boolean orderBlockNear;
    boolean fvgNear;
    boolean liquidityGrabbed;
    String  pdZone;
    double  pdFibLevel;
    int     activeOrderBlocks;
    int     unfilledFVGs;
}
