package com.tradevision.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external review, full
 * context in CorrelationIdFilter's own header javadoc): this file did not exist before this fix
 * -- CorrelationIdFilter is entirely new.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void doFilterInternal_noInboundHeader_generatesAFreshIdAndSetsBothMdcAndResponseHeader() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        String[] observedDuringChain = new String[1];
        doAnswer(inv -> {
            observedDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilterInternal(req, res, chain);

        assertThat(observedDuringChain[0]).isNotBlank();
        verify(res).setHeader(org.mockito.ArgumentMatchers.eq(CorrelationIdFilter.RESPONSE_HEADER), org.mockito.ArgumentMatchers.eq(observedDuringChain[0]));
        // MDC is cleared once the request finishes, so it never leaks onto whatever this same
        // worker thread handles next.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilterInternal_inboundXCorrelationIdHeader_reusesItInsteadOfGeneratingANewOne() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Correlation-Id")).thenReturn("caller-supplied-id-123");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        String[] observedDuringChain = new String[1];
        doAnswer(inv -> {
            observedDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilterInternal(req, res, chain);

        assertThat(observedDuringChain[0]).isEqualTo("caller-supplied-id-123");
        verify(res).setHeader(CorrelationIdFilter.RESPONSE_HEADER, "caller-supplied-id-123");
    }

    @Test
    void doFilterInternal_inboundXRequestIdHeader_usedWhenNoXCorrelationIdPresent() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Correlation-Id")).thenReturn(null);
        when(req.getHeader("X-Request-Id")).thenReturn("gateway-request-id-456");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        String[] observedDuringChain = new String[1];
        doAnswer(inv -> {
            observedDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilterInternal(req, res, chain);

        assertThat(observedDuringChain[0]).isEqualTo("gateway-request-id-456");
    }

    @Test
    void doFilterInternal_downstreamException_stillClearsMdc() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> { throw new RuntimeException("boom"); }).when(chain).doFilter(req, res);

        try {
            filter.doFilterInternal(req, res, chain);
        } catch (RuntimeException expected) {
            // expected -- rethrown past this filter, exactly like any other exception would be
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilterInternal_twoSequentialRequestsOnTheSameThread_getDifferentIds() throws Exception {
        HttpServletRequest req1 = mock(HttpServletRequest.class);
        HttpServletResponse res1 = mock(HttpServletResponse.class);
        FilterChain chain1 = mock(FilterChain.class);
        String[] first = new String[1];
        doAnswer(inv -> { first[0] = MDC.get(CorrelationIdFilter.MDC_KEY); return null; }).when(chain1).doFilter(req1, res1);
        filter.doFilterInternal(req1, res1, chain1);

        HttpServletRequest req2 = mock(HttpServletRequest.class);
        HttpServletResponse res2 = mock(HttpServletResponse.class);
        FilterChain chain2 = mock(FilterChain.class);
        String[] second = new String[1];
        doAnswer(inv -> { second[0] = MDC.get(CorrelationIdFilter.MDC_KEY); return null; }).when(chain2).doFilter(req2, res2);
        filter.doFilterInternal(req2, res2, chain2);

        assertThat(first[0]).isNotEqualTo(second[0]);
    }
}
