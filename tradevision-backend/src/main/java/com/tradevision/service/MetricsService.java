package com.tradevision.service;

import com.tradevision.model.ApiMetric;
import com.tradevision.repository.ApiMetricRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Review finding (P1 — "API metrics are not actually zero-impact"): MetricsFilter used to call
 * metricRepo.save() directly, on the request-handling thread, despite its own comment claiming
 * this was async. @Async only takes effect through a Spring-managed proxy — it does nothing on a
 * plain self-invocation within the same class, which is exactly why this had to move into its
 * own service rather than just adding the annotation in place.
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
