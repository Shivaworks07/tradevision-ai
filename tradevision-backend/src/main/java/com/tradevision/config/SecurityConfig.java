package com.tradevision.config;

import com.tradevision.util.JwtUtil;
import com.tradevision.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwt;
    private final UserRepository userRepo;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            // Cookie-based CSRF protection for the Angular SPA. csrfTokenRequestHandler uses
            // CsrfTokenRequestAttributeHandler rather than Spring Security's default
            // XorCsrfTokenRequestAttributeHandler (BREACH-protection encoding meant for HTML form
            // submissions), because this frontend sends the plain header-based token Angular's
            // withXsrfConfiguration produces. csrfTokenRepository writes the XSRF-TOKEN cookie,
            // matching Angular's default cookie/header names, and reads the token back from the
            // X-XSRF-TOKEN header the frontend sends automatically. The token is deferred by
            // default until something reads it; csrfCookieFilter() below forces that eager load.
            .csrf(c -> c
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
                // Endpoints that can't carry a CSRF token yet (a fresh browser has no
                // XSRF-TOKEN cookie until after its first response from this backend) or that
                // are called by non-browser clients with no cookie jar at all.
                .ignoringRequestMatchers("/api/auth/**", "/api/v1/auth/**", "/api/admin/bootstrap", "/api/feedback"))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Content Security Policy built to match what this app actually loads rather than a
            // generic template: connect-src explicitly allows the two hosts the frontend calls
            // directly from the browser in production (api.binance.com, fapi.binance.com for
            // live market data); everything else (forex/Yahoo/news) is proxied through this same
            // backend, so 'self' already covers it. No external fonts or CDN scripts are loaded,
            // so this stays strict without style-src/font-src exceptions for a third party.
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    // No Google Fonts origins needed here — the app uses system font stacks
                    // instead of an @import. 'unsafe-inline' remains for style-src because
                    // Angular injects component styles at runtime; removing it would need a
                    // per-request CSP nonce (Angular's ngCspNonce), which in turn needs Angular
                    // SSR or a reverse proxy rewriting index.html per request — infrastructure
                    // this backend's SecurityConfig alone cannot provide.
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data:; " +
                    "font-src 'self'; " +
                    // api.coingecko.com is deliberately not here — it is proxied through this
                    // backend (ProxyController's /coingecko-api/**) instead of widening this
                    // policy further; Binance alone stays direct-from-browser, by design.
                    "connect-src 'self' https://api.binance.com https://fapi.binance.com; " +
                    "frame-ancestors 'none'; " +
                    "base-uri 'self'; " +
                    "form-action 'self'"
                ))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .contentTypeOptions(contentTypeOptions -> {})
                .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicy(permissions -> permissions.policy(
                    "geolocation=(), microphone=(), camera=(), payment=()"))
            )
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(
                    // Angular static files — MUST be public
                    "/", "/index.html", "/favicon.svg", "/favicon.ico",
                    "/*.js", "/*.css", "/*.map", "/*.json", "/*.txt",
                    "/assets/**", "/fonts/**", "/icons/**","/app/**", "/landing",
                    // API public endpoints
                    "/api/auth/**", "/api/v1/auth/**",
                    "/api/admin/bootstrap",
                    "/api/feedback",
                    "/api/news/**",             // public news feed
                    "/api/ipo/**",              // public live IPO feed
                    "/api/stocks/symbols/**",   // public NSE symbol search
                    "/actuator/health/**",
                    // springdoc/Swagger UI is not used in this deployment, so no
                    // /swagger-ui/**, /v3/api-docs/**, or /webjars/** routes are registered here.
                    // External API proxies (public - no auth needed)
                    "/binance-spot/**", "/binance-futures/**",
                    "/yf-api/**", "/forex-api/**", "/fng-api/**", "/nse-api/**", "/coingecko-api/**"
                ).permitAll()
                // OPTIONS preflight always allowed
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                // Everything else requires auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter(), UsernamePasswordAuthenticationFilter.class)
            // Spring Security's CsrfToken loading is deferred by default — without forcing it,
            // the XSRF-TOKEN cookie this SPA depends on would not be set until something else
            // happened to trigger it, which could be never for a read-only session.
            // csrfCookieFilter() below accesses the token on every request so
            // CookieCsrfTokenRepository always has something to write. It is placed immediately
            // after Spring Security's own CsrfFilter, which validates/regenerates the token this
            // filter then forces to be saved.
            .addFilterAfter(csrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
            .build();
    }

    @Bean
    public OncePerRequestFilter csrfCookieFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws jakarta.servlet.ServletException, IOException {
                Object csrfToken = req.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
                if (csrfToken instanceof org.springframework.security.web.csrf.CsrfToken token) {
                    token.getToken(); // the access itself is what forces CookieCsrfTokenRepository to actually save the cookie
                }
                chain.doFilter(req, res);
            }
        };
    }

    /**
     * Authenticates each request from the Authorization header when present and valid, falling
     * back to the access-token cookie otherwise. The fallback triggers whenever the header
     * attempt didn't produce an authenticated context, not only when the header is entirely
     * absent — several frontend services construct an empty `Authorization: Bearer ` header
     * since the token no longer lives in localStorage, so the header can be present but useless,
     * and an absent-only fallback would never try the cookie for those requests.
     */
    @Bean
    public OncePerRequestFilter jwtFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                    HttpServletResponse res, FilterChain chain)
                    throws jakarta.servlet.ServletException, IOException {
                String header = req.getHeader("Authorization");
                boolean authenticated = false;
                if (header != null && header.startsWith("Bearer ")) {
                    authenticated = tryAuthenticate(header.substring(7));
                }
                if (!authenticated && req.getCookies() != null) {
                    for (var cookie : req.getCookies()) {
                        // "tv_access_token" must match AuthController.ACCESS_COOKIE exactly --
                        // duplicated as a literal here rather than a shared constant since these
                        // two classes don't otherwise share a dependency; a mismatch would fail
                        // silently (cookie auth simply wouldn't kick in), so keep this in sync.
                        if ("tv_access_token".equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                            tryAuthenticate(cookie.getValue());
                            break;
                        }
                    }
                }
                chain.doFilter(req, res);
            }

            /** Returns true only when this token produced a genuinely authenticated SecurityContext. */
            private boolean tryAuthenticate(String token) {
                try {
                    if (jwt.isValid(token) && !jwt.isRefreshToken(token)) {
                        String userId = jwt.getUserId(token);
                        if (userId != null) {
                            // The token's tokenVersion claim is embedded at login and checked
                            // against the user's current tokenVersion here. Logout bumps
                            // User.tokenVersion, so a mismatch means the token was issued before
                            // the most recent logout and must be rejected even though it is still
                            // cryptographically valid. This costs one extra indexed lookup by
                            // primary key per authenticated request, the price of logout actually
                            // invalidating outstanding access tokens.
                            var userOpt = userRepo.findById(userId);
                            long tokenVersion = jwt.getTokenVersion(token);
                            boolean versionOk = userOpt.map(u -> u.getTokenVersion() == tokenVersion).orElse(false);
                            if (versionOk) {
                                // Grants the user's real role as a Spring Security authority.
                                // management.endpoint.health.roles is set to ADMIN in
                                // application.properties, so this authority is what lets
                                // Spring Boot's "when-authorized" health detail check
                                // (HealthEndpointWebExtension) actually distinguish admins from
                                // any other authenticated user, rather than treating every
                                // logged-in principal as authorized to see full component health
                                // details (Mongo connectivity, disk space, internal diagnostics).
                                String role = userOpt.map(u -> u.getRole()).orElse("USER");
                                var authorities = List.<org.springframework.security.core.GrantedAuthority>of(
                                    new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
                                var auth = new UsernamePasswordAuthenticationToken(
                                    userId, null, authorities);
                                SecurityContextHolder.getContext().setAuthentication(auth);
                                return true;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                return false;
            }
        };
    }
}
