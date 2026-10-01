package com.tradevision.dto;

import lombok.Data;

@Data
public class TradeCallResultRequest {
    private String id;          // primary - matches what frontend sends
    private String callId;      // fallback alias
    private String result;      // HIT_T1, HIT_T2, HIT_T3, HIT_SL
    private Double exitPrice;   // Double (nullable) not double

    // Support both id and callId
    public String getId() {
        return id != null ? id : callId;
    }
}
