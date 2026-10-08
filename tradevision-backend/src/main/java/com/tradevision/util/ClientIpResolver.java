package com.tradevision.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the real client IP to use as a rate-limit key, for use behind a reverse proxy or load
 * balancer where every request's TCP peer would otherwise be the proxy itself (collapsing every
 * user onto one shared rate-limit bucket). app.proxy.trust-forwarded-for alone would be
 * all-or-nothing: trusting X-Forwarded-For from any source would let a request that reaches this
 * application directly (bypassing the proxy) forge the header and get a fresh rate-limit bucket
 * on every request, defeating the limiter entirely.
 *
 * X-Forwarded-For is therefore only honored when both (a) app.proxy.trust-forwarded-for is true,
 * and (b) the request's real TCP peer (HttpServletRequest.getRemoteAddr(), the actual socket
 * source address, never spoofable via a header) is itself inside one of the configured
 * trusted-proxy CIDR ranges. A request whose remote address is not a trusted proxy always uses
 * its own real remote address as the rate-limit key, regardless of any X-Forwarded-For header it
 * sends.
 *
 * If trusted-proxy-cidrs is left blank while trust-forwarded-for is true, X-Forwarded-For is
 * trusted from any source — narrowing that further requires configuring the real proxy/LB's
 * source CIDR(s), which only the deployment's operator can correctly supply. CIDR matching is
 * IPv4 only ("a.b.c.d/n", or a bare address treated as /32); an unparseable or IPv6 remote
 * address is never treated as trusted, so a mismatch fails closed, not open.
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private ClientIpResolver() {}

    /**
     * Resolves the client IP to use as a rate-limit key. header is only trusted (and its FIRST,
     * left-most entry used -- the original client as seen by the nearest trusted hop) when both
     * trustForwardedFor is true and the request's real remote address is a trusted proxy per
     * trustedProxyCidrsCsv (comma-separated CIDRs, or blank to trust any remote address the same
     * way this codebase's own pre-fix behavior always did once the boolean alone was set).
     */
    public static String resolve(HttpServletRequest req, boolean trustForwardedFor, String trustedProxyCidrsCsv) {
        String remoteAddr = req.getRemoteAddr();
        if (trustForwardedFor && isTrustedProxy(remoteAddr, trustedProxyCidrsCsv)) {
            String header = req.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {
                String candidate = header.split(",")[0].trim();
                if (!candidate.isBlank()) return candidate;
            }
        }
        return remoteAddr;
    }

    private static boolean isTrustedProxy(String remoteAddr, String trustedProxyCidrsCsv) {
        if (trustedProxyCidrsCsv == null || trustedProxyCidrsCsv.isBlank()) {
            // No CIDR restriction configured: trust any remote address, still gated by
            // trustForwardedFor itself requiring an explicit opt-in.
            return true;
        }
        for (String cidr : trustedProxyCidrsCsv.split(",")) {
            cidr = cidr.trim();
            if (cidr.isEmpty()) continue;
            try {
                if (matchesCidr(cidr, remoteAddr)) return true;
            } catch (Exception e) {
                log.warn("Could not evaluate trusted-proxy CIDR entry '{}' against remote address '{}' ({}) -- skipping this entry "
                    + "rather than failing the whole trust check.", cidr, remoteAddr, e.getMessage());
            }
        }
        return false;
    }

    private static boolean matchesCidr(String cidr, String remoteAddr) throws UnknownHostException {
        String[] parts = cidr.split("/", 2);
        InetAddress network = InetAddress.getByName(parts[0]);
        InetAddress candidate = InetAddress.getByName(remoteAddr);
        byte[] networkBytes = network.getAddress();
        byte[] candidateBytes = candidate.getAddress();
        if (networkBytes.length != candidateBytes.length) return false; // one IPv4, one IPv6 -- never a match
        int prefixLength = parts.length == 2 ? Integer.parseInt(parts[1]) : networkBytes.length * 8;
        BigInteger networkInt = new BigInteger(1, networkBytes);
        BigInteger candidateInt = new BigInteger(1, candidateBytes);
        int totalBits = networkBytes.length * 8;
        if (prefixLength <= 0) return true; // 0.0.0.0/0 -- matches everything, an explicit (if unusual) operator choice
        BigInteger mask = BigInteger.valueOf(-1).shiftLeft(totalBits - prefixLength)
            .and(BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE));
        return networkInt.and(mask).equals(candidateInt.and(mask));
    }
}
