package com.tradevision.config;

import com.tradevision.model.ApiMetric;
import com.tradevision.service.MetricsService;
import com.tradevision.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Records latency and error rate for every /api/ request.
 * Review finding (P1 — "API metrics are not actually zero-impact"): this comment used to claim
 * "zero impact on response time" while calling metricRepo.save() synchronously right here — now
 * genuinely true, via MetricsService.recordAsync (an actual @Async method on its own bounded
 * executor, not just a comment saying so).
 */
@Component
@RequiredArgsConstructor
public class MetricsFilter extends OncePerRequestFilter {

    private final MetricsService metricsService;
    private final JwtUtil jwt;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        // Only track /api/ endpoints — skip static assets
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long latency = System.currentTimeMillis() - start;
            int  status  = res.getStatus();

            ApiMetric m = new ApiMetric();
            m.setEndpoint(path);
            m.setMethod(req.getMethod());
            m.setStatusCode(status);
            m.setLatencyMs(latency);
            m.setError(status >= 400);

            // Extract userId from JWT if present (non-blocking)
            try {
                String h = req.getHeader("Authorization");
                if (h != null && h.startsWith("Bearer ")) {
                    String tok = h.substring(7);
                    if (jwt.isValid(tok)) m.setUserId(jwt.getUserId(tok));
                }
            } catch (Exception ignored) {}

            metricsService.recordAsync(m);
        }
    }
}
