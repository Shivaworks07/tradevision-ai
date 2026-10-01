package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.LocalDateTime;

/**
 * One record per API request — used for latency, error rate, and usage analytics.
 * Stored in 'api_metrics' collection with a TTL of 7 days to avoid unbounded growth.
 */
@Data @NoArgsConstructor
@Document(collection = "api_metrics")
public class ApiMetric {
    @Id private String id;

    private String  endpoint;         // e.g. "/api/calls/save"
    private String  method;           // GET, POST, etc.
    private int     statusCode;
    private long    latencyMs;        // request duration in ms
    private boolean error;            // true if 4xx/5xx
    private String  errorMessage;
    private String  userId;           // which user made the request (nullable)

    @Indexed(expireAfterSeconds = 604800) // 7-day TTL
    private LocalDateTime recordedAt = LocalDateTime.now();
}
