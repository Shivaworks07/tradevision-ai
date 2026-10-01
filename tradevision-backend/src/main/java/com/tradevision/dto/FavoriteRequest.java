package com.tradevision.dto;

import lombok.Data;

@Data
public class FavoriteRequest {
    private String symbol;   // e.g. "RELIANCE", "BTC", "USD/INR"
    private String type;     // "STOCK", "CRYPTO", "FOREX"
    private boolean add;     // true = add, false = remove
}
