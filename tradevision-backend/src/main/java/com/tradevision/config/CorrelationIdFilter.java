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
 * Puts a correlation id into SLF4J's MDC for the lifetime of every request, so every log line
 * written by any class involved in handling that request (order placement, position monitoring,
 * risk-profile resume, and so on) can be pulled back out of the aggregate log stream by that one
 * id. logging.pattern.level in application.properties embeds %X{correlationId} into every log
 * line's level marker via Spring Boot's documented MDC-in-pattern mechanism, so no logback.xml
 * is needed.
 *
 * Reuses an inbound X-Request-Id/X-Correlation-Id header when the caller (a load balancer, an
 * upstream gateway, or this application's own frontend) already supplied one, so a request's id
 * stays stable across service boundaries; generates a fresh random one otherwise. Echoed back on
 * the response's X-Correlation-Id header so a caller, or a person reporting a bug with browser
 * devtools open, can hand support the exact id to search logs for.
 *
 * @Order(HIGHEST_PRECEDENCE) runs this before every other filter, including Spring Security's
 * own FilterChainProxy and MetricsFilter, so that everything logged for a request — including a
 * security-filter-level rejection that never reaches a controller — carries this same id.
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
        // MDC is thread-local; this application's servlet container is thread-per-request, so
        // setting it once at the top of the request and clearing it in the finally below is
        // sufficient.
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
