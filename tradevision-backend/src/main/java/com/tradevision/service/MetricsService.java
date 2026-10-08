package com.tradevision.service;

import com.tradevision.model.ApiMetric;
import com.tradevision.repository.ApiMetricRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Persists API metrics off the request-handling thread. This lives in its own Spring-managed
 * bean rather than as a method on the filter that calls it, because @Async only takes effect
 * through a Spring proxy — it has no effect on a plain self-invocation within the same class.
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final ApiMetricRepository metricRepo;

    @Async("metricsExecutor")
    public void recordAsync(ApiMetric metric) {
        try {
            metricRepo.save(metric);
        } catch (Exception e) {
            // A metric write failing is never worth surfacing anywhere but a log line — it must
            // never affect the request that triggered it, which has already completed by now.
            log.debug("Failed to persist API metric for {}: {}", metric.getEndpoint(), e.getMessage());
        }
    }
}
