package com.tradevision.model.features;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Market sentiment: Fear & Greed (news/social in Phase 3+) */
@Data @NoArgsConstructor
public class SentimentFeatures {
    Integer fearGreedValue;
    String  fearGreedClass;
    String  fearGreedSignal;
    Integer fearGreedChange1d;
    Integer fearGreedChange7d;
}
