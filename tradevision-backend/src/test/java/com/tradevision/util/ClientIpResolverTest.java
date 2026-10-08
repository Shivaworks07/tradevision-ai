package com.tradevision.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the client-IP resolution logic shared by ProxyController.rateLimited and
 * AuthController.clientIp, independent of either controller's own request-handling or
 * rate-limit wiring.
 */
class ClientIpResolverTest {

    private HttpServletRequest requestFrom(String remoteAddr, String forwardedFor) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        when(req.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return req;
    }

    @Test
    @DisplayName("trustForwardedFor=false: header is ignored entirely regardless of CIDR config, real remote address always used")
    void trustDisabled_alwaysUsesRemoteAddr() {
        HttpServletRequest req = requestFrom("203.0.113.5", "1.2.3.4");

        String ip = ClientIpResolver.resolve(req, false, "0.0.0.0/0");

        assertThat(ip).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("trustForwardedFor=true, no CIDR restriction configured: legacy all-or-nothing behavior -- header honored from any remote address")
    void trustEnabled_blankCidrs_honorsHeaderFromAnywhere() {
        HttpServletRequest req = requestFrom("203.0.113.5", "1.2.3.4");

        String ip = ClientIpResolver.resolve(req, true, "");

        assertThat(ip).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("trustForwardedFor=true, remote address inside the configured trusted CIDR: header honored, first (left-most) entry used")
    void trustEnabled_remoteAddrInsideCidr_honorsHeaderFirstEntry() {
        HttpServletRequest req = requestFrom("10.0.0.5", "203.0.113.10, 10.0.0.5, 10.0.0.1");

        String ip = ClientIpResolver.resolve(req, true, "10.0.0.0/8,172.16.0.0/12");

        assertThat(ip).isEqualTo("203.0.113.10");
    }

    @Test
    @DisplayName("trustForwardedFor=true, remote address OUTSIDE every configured trusted CIDR: header ignored, real remote address used")
    void trustEnabled_remoteAddrOutsideCidr_ignoresHeader() {
        HttpServletRequest req = requestFrom("203.0.113.99", "1.2.3.4");

        String ip = ClientIpResolver.resolve(req, true, "10.0.0.0/8,172.16.0.0/12");

        assertThat(ip).isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("Two different clients behind the same trusted proxy get two different resolved IPs")
    void twoClientsBehindSameTrustedProxy_resolveToDifferentIps() {
        HttpServletRequest req1 = requestFrom("10.0.0.5", "203.0.113.10");
        HttpServletRequest req2 = requestFrom("10.0.0.5", "203.0.113.20");

        String ip1 = ClientIpResolver.resolve(req1, true, "10.0.0.0/8");
        String ip2 = ClientIpResolver.resolve(req2, true, "10.0.0.0/8");

        assertThat(ip1).isEqualTo("203.0.113.10");
        assertThat(ip2).isEqualTo("203.0.113.20");
        assertThat(ip1).isNotEqualTo(ip2);
    }

    @Test
    @DisplayName("Blank/whitespace-only X-Forwarded-For header from a trusted proxy falls back to the real remote address rather than an empty string")
    void trustedProxy_blankHeader_fallsBackToRemoteAddr() {
        HttpServletRequest req = requestFrom("10.0.0.5", "   ");

        String ip = ClientIpResolver.resolve(req, true, "10.0.0.0/8");

        assertThat(ip).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("Null X-Forwarded-For header from a trusted proxy falls back to the real remote address")
    void trustedProxy_nullHeader_fallsBackToRemoteAddr() {
        HttpServletRequest req = requestFrom("10.0.0.5", null);

        String ip = ClientIpResolver.resolve(req, true, "10.0.0.0/8");

        assertThat(ip).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("A bare IP (no /prefix) in the trusted-proxy list is treated as a /32 -- matches only that exact address")
    void bareIpInCidrList_treatedAsSlash32() {
        HttpServletRequest matching = requestFrom("10.0.0.5", "203.0.113.10");
        HttpServletRequest nonMatching = requestFrom("10.0.0.6", "203.0.113.10");

        assertThat(ClientIpResolver.resolve(matching, true, "10.0.0.5")).isEqualTo("203.0.113.10");
        assertThat(ClientIpResolver.resolve(nonMatching, true, "10.0.0.5")).isEqualTo("10.0.0.6"); // not trusted -> own remote addr
    }

    @Test
    @DisplayName("Malformed CIDR entries are skipped without failing the whole trust check, and a genuinely unparseable remote address never matches any real CIDR")
    void malformedCidrEntry_skippedGracefully() {
        HttpServletRequest req = requestFrom("10.0.0.5", "203.0.113.10");

        // "not-a-cidr" is malformed and must be skipped, but the second, valid entry still matches.
        String ip = ClientIpResolver.resolve(req, true, "not-a-cidr,10.0.0.0/8");

        assertThat(ip).isEqualTo("203.0.113.10");
    }
}
