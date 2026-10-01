package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class StockSymbol {
    private String symbol;    // e.g. RELIANCE
    private String name;      // e.g. Reliance Industries Limited
    private String series;    // EQ, BE, etc.
    private String isin;
}
