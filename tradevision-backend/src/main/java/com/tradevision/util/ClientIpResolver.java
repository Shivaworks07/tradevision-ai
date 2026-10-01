package com.tradevision.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * P2-9 fix ("ProxyController.rateLimited / AuthController.clientIp: trust-forwarded-for=false by
 * default; behind a LB all users share one IP -> 60 req/min global, 20 OTP/h global" -- external
 * review, confirmed real by direct inspection before this fix): both controllers already had
 * near-identical, independently-duplicated X-Forwarded-For extraction logic gated by a single
 * app.proxy.trust-forwarded-for boolean. That boolean genuinely does let a real deployment behind
 * a trusted reverse proxy/load balancer key rate limits on the real client IP instead of the
 * proxy's own IP -- but it is all-or-nothing: turning it on trusts X-Forwarded-For from ANY
 * source, including a request that reaches this application directly (bypassing the proxy
 * entirely, e.g. if the proxy's own network isn't otherwise firewalled off, or in a
 * misconfigured/transitional deployment) -- letting an attacker forge the header and get a fresh
 * rate-limit bucket on every request, defeating the very limiter this flag was meant to make
 * effective.
 *
 * The actual fix, consolidated into one shared, independently-testable place instead of the two
 * near-duplicate copies this codebase had before: X-Forwarded-For is only ever honored when BOTH
 * (a) app.proxy.trust-forwarded-for is true, AND (b) the request's own real TCP peer
 * (HttpServletRequest.getRemoteAddr(), which is never spoofable -- it's the actual socket source
 * address, not a header) is itself inside one of the configured trusted-proxy CIDR ranges. A
 * request whose remote address is NOT a trusted proxy gets its own real remote address used as
 * the rate-limit key regardless of any X-Forwarded-For header it sends, exactly like
 * trustForwardedFor=false already did -- there is no way to opt out of this check by supplying a
 * header alone.
 *
 * HONEST SCOPE: if trusted-proxy-cidrs is left blank while trust-forwarded-for is true, this
 * preserves the OLD all-or-nothing behavior (X-Forwarded-For trusted from anywhere) rather than
 * silently disabling the feature a deployment may already depend on -- narrowing that further
 * requires actually configuring the real proxy/LB's own source CIDR(s), which only the operator
 * of a given deployment can correctly supply. IPv4 only (CIDR notation "a.b.c.d/n", or a bare
 * address treated as /32) -- this codebase's own deployment infra (see this class's own
 * surrounding config) has no IPv6 requirement stated anywhere, and a real gap here fails CLOSED
 * (an unparseable/IPv6 remote address is never treated as trusted), not open.
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
            // No CIDR restriction configured -- legacy behavior, still gated by trustForwardedFor
            // itself (a deployment must have explicitly opted in) per this class's own HONEST
            // SCOPE note above.
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
