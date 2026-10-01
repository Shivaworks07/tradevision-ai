package com.tradevision.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external review):
 * confirmed real -- with multiple requests interleaving across this application's own
 * thread-per-request model (order placement, position monitoring, risk-profile resume all log
 * from several different classes during a single request), there was previously no way to pull
 * every log line for ONE specific request back out of the aggregate log stream. This puts a
 * correlation id into SLF4J's MDC for the lifetime of every request -- logging.pattern.level in
 * application.properties embeds %X{correlationId} into every log line's own level marker via
 * Spring Boot's own documented MDC-in-pattern mechanism, so this required no logback.xml at all.
 *
 * Reuses an inbound X-Request-Id/X-Correlation-Id header when the caller (a load balancer, an
 * upstream gateway, or this application's own frontend, if it's ever wired to send one) already
 * supplied one, so a request's id stays stable across service boundaries rather than being
 * needlessly replaced -- generates a fresh random one otherwise. Echoed back on the response's
 * own X-Correlation-Id header so a caller (or a person report a bug with browser devtools open)
 * can hand support the exact id to search logs for.
 *
 * @Order(HIGHEST_PRECEDENCE): must run before every other filter in this application --
 * including Spring Security's own FilterChainProxy (registered well after
 * Ordered.HIGHEST_PRECEDENCE by Spring Boot's own defaults) and MetricsFilter -- so that
 * absolutely everything logged for a request, including security-filter-level rejections that
 * never reach a controller at all, carries this same id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    public static final String RESPONSE_HEADER = "X-Correlation-Id";
    private static final String[] INBOUND_HEADERS = { "X-Correlation-Id", "X-Request-Id" };

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = extractInbound(req);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        // MDC is thread-local; this application's own servlet container is thread-per-request
        // (no reactive/virtual-thread-hopping in play here), so setting it once at the top of the
        // request and clearing it in the finally below is sufficient -- checked this is actually
        // true for this codebase's own request-handling model before relying on it, not assumed.
        MDC.put(MDC_KEY, correlationId);
        res.setHeader(RESPONSE_HEADER, correlationId);
        try {
            chain.doFilter(req, res);
        } finally {
            // Always cleared, even on an exception -- an uncleared MDC value would otherwise leak
            // onto whatever unrelated request this same worker thread handles next from the
            // servlet container's own thread pool.
            MDC.remove(MDC_KEY);
        }
    }

    private String extractInbound(HttpServletRequest req) {
        for (String header : INBOUND_HEADERS) {
            String value = req.getHeader(header);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
