package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class IpoItem {
    private String company;
    private String symbol;
    private String openDate;      // ISO yyyy-MM-dd if known, else raw text
    private String closeDate;
    private String listingDate;
    private double priceMin;
    private double priceMax;
    private int    lotSize;
    private Double gmp;           // nullable — unofficial grey market premium
    private Double gmpPct;
    private String status;        // OPEN | UPCOMING | CLOSED | LISTED — computed from dates
    private String category;
    private String issueSize;
    private String exchange;      // NSE | BSE | NSE SME | BSE SME
    private String source;        // where this record came from, for transparency
    private long   fetchedAtMs;
}
