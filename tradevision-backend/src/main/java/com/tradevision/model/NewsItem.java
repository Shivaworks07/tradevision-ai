package com.tradevision.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class NewsItem {
    private String id;           // hash of url
    private String title;
    private String summary;
    private String url;
    private String source;       // e.g. "Economic Times"
    private String category;     // CRYPTO | STOCKS | FOREX | IPO | GOLD | GLOBAL
    private String publishedAt;  // ISO string
    private String imageUrl;
    private long   publishedMs;  // epoch ms for sorting
}
