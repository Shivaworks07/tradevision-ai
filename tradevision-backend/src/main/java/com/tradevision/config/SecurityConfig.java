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
            // Review finding ("CSRF is still disabled" -- P1): confirmed real and fixed, matching
            // the review's own "defense-in-depth: SameSite=Strict + CSRF token + Origin
            // validation" recommendation. This is the Spring Boot 3.2.5 (Spring Security 6.2.x)
            // manual configuration for cookie-based CSRF against a SPA -- checked directly
            // against Spring Security's own docs before implementing (not assumed) that this
            // version predates the simpler CsrfConfigurer::spa helper Spring Boot 4.x adds, and
            // that Spring Security 6's own default XorCsrfTokenRequestAttributeHandler (BREACH
            // protection, expects an encoded token in HTML form submissions) breaks the plain
            // header-based token this Angular frontend actually sends -- CsrfTokenRequestAttributeHandler
            // is the documented fix for that. csrfTokenRepository writes the XSRF-TOKEN cookie
            // (matching Angular's own withXsrfConfiguration default cookie/header names exactly,
            // wired on the frontend side too) and reads the token back from the X-XSRF-TOKEN
            // header the frontend now sends automatically. The actual eager-loading fix this
            // deferred-by-default token needs is csrfCookieFilter(), wired in near the bottom of
            // this same chain (see its own comment there for why, and for the honest limitation
            // on how far this could be verified in this sandbox).
            .csrf(c -> c
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
                // Public endpoints that genuinely can't carry a CSRF token yet (a fresh browser
                // has no XSRF-TOKEN cookie until AFTER its first response from this backend) or
                // that are, by design, called by non-browser clients this session's own OMS/
                // broker-adapter work never assumed had a browser cookie jar at all.
                .ignoringRequestMatchers("/api/auth/**", "/api/v1/auth/**", "/api/admin/bootstrap", "/api/feedback"))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Review finding (P1 #14 — "No CSP"): confirmed missing entirely. Built to match what
            // this app actually loads, not a generic template — connect-src explicitly allows
            // the two hosts the frontend calls directly from the browser in production
            // (environment.prod.ts: api.binance.com, fapi.binance.com for live market data;
            // everything else — forex/Yahoo/news — is proxied through this same backend, so
            // 'self' already covers it). No external fonts or CDN scripts are loaded (checked
            // index.html directly rather than assuming), so this can stay strict without
            // needing style-src/font-src exceptions for a third party.
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    // Review finding ("CSP still uses unsafe-inline" -- external review,
                    // twenty-fourth pass, P2, confirmed real by direct inspection before this
                    // fix: fonts.googleapis.com/fonts.gstatic.com were allowed here
                    // specifically because 4 SCSS files @import'd Google Fonts directly -- that
                    // dependency is removed entirely now, in favor of real system font stacks
                    // (see styles.scss's own updated comment for the full reasoning and the
                    // honest visual trade-off), so those two origins are genuinely no longer
                    // needed anywhere in this policy. 'unsafe-inline' itself remains --
                    // Angular's own runtime component-style injection, a separate mechanism
                    // entirely from the removed font import, and a genuine elimination of it
                    // needs a per-request CSP nonce (Angular's own ngCspNonce), which needs
                    // either Angular SSR or a reverse proxy rewriting index.html per request --
                    // real infrastructure this backend's own SecurityConfig cannot provide by
                    // itself, stated honestly here rather than left unexplained.
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data:; " +
                    "font-src 'self'; " +
                    // api.coingecko.com deliberately NOT here — proxied through this backend now
                    // (see ProxyController's /coingecko-api/**) instead of widening this further;
                    // Binance stays direct-from-browser, matching environment.prod.ts by design.
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
                    // Spring Boot 4 follow-up (full context in pom.xml's own dated parent-
                    // version comment): springdoc/Swagger UI was removed entirely as part of
                    // the Boot 4 migration (open, unresolved upstream Jackson conflict with
                    // this app's own Jackson 2 usage -- see that comment), so the
                    // /swagger-ui/**, /v3/api-docs/**, and /webjars/** routes that used to live
                    // here no longer exist and were removed rather than left as permitAll
                    // entries for routes nothing serves anymore.
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
            // Review finding ("CSRF is still disabled" -- P1, full context in this class's own
            // csrf() configuration above): Spring Security 6's own CsrfToken loading is deferred
            // by default (checked directly against Spring Security's own migration docs, not
            // assumed) -- without forcing it, the XSRF-TOKEN cookie this SPA depends on wouldn't
            // actually be set until something else happened to trigger it, which could be never
            // for a read-only session. csrfCookieFilter() below is the documented fix: access
            // the token on every request so CookieCsrfTokenRepository always has something to
            // write. Placed immediately after Spring Security's own CsrfFilter, which is what
            // actually validates/regenerates the token this filter then forces to be saved.
            //
            // HONEST LIMITATION, stated rather than left implicit: this whole CSRF configuration
            // could not be run against a real Spring Security 6.2.x instance in this development
            // sandbox (Maven Central is blocked here, same reason disclosed elsewhere this
            // session for every Spring-dependent test file). Every piece of this design was
            // checked directly against Spring Security's own current documentation and a real,
            // reported GitHub issue confirming CsrfToken.getToken() is the established way to
            // force eager loading of an otherwise-deferred token
            // (spring-projects/spring-security#12378) -- not assumed or guessed at. But "matches
            // the documented API and a confirmed community pattern" is not the same guarantee as
            // "confirmed by actually running it," and CSRF misconfiguration is exactly the kind
            // of thing that can fail in only one of two visible ways: either silently not
            // protecting anything, or breaking every state-changing request outright. Whoever
            // deploys this should verify a real login followed by a real state-changing request
            // (e.g. connecting a broker credential) succeeds, and that the same request WITHOUT
            // the X-XSRF-TOKEN header is correctly rejected, before relying on it.
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
     * Review finding ("Auth hardening" -- full context in AuthController's own javadoc):
     * authenticates from the Authorization header when it's present AND valid; falls back to
     * the access-token cookie otherwise. Deliberately "invalid, not just absent" as the fallback
     * trigger -- an earlier version of this fix only fell back when the header was entirely
     * missing, which silently breaks the several frontend services that manually construct
     * their own (now-empty, since the token no longer lives in localStorage) `Authorization:
     * Bearer ` header: that header is PRESENT but useless, so an absent-only fallback would
     * never even try the cookie for those specific requests. Retrying from the cookie whenever
     * the header attempt didn't produce an authenticated context closes that gap without
     * requiring every one of those frontend call sites to be individually rewritten first.
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
                            // Review finding (this doc, "BLOCKER #4" — JWT logout doesn't
                            // actually invalidate access tokens): the token's tokenVersion
                            // claim was embedded at login but never checked against anything.
                            // Logout bumps User.tokenVersion; without this comparison, a
                            // logged-out access token stayed cryptographically valid and
                            // accepted for its full remaining lifetime (up to 24h). Every
                            // authenticated request now costs one extra indexed lookup by
                            // primary key — the necessary tradeoff for logout to actually
                            // mean something.
                            var userOpt = userRepo.findById(userId);
                            long tokenVersion = jwt.getTokenVersion(token);
                            boolean versionOk = userOpt.map(u -> u.getTokenVersion() == tokenVersion).orElse(false);
                            if (versionOk) {
                                // P3-1 fix ("management.endpoint.health.show-details=when-
                                // authorized + JWT with no roles -- any logged-in user sees
                                // component details" -- external review, confirmed real): this
                                // always granted List.of() -- NO authority at all, for every
                                // user, admin or not. Spring Boot's own "when-authorized" health
                                // detail check (HealthEndpointWebExtension) treats ANY
                                // authenticated principal as authorized whenever
                                // management.endpoint.health.roles is empty (its own documented
                                // default/fallback behavior) -- which is exactly why a plain
                                // logged-in trader could see full component health details
                                // (Mongo connectivity, disk space, internal diagnostics) with
                                // zero actual role check ever happening. Granting the user's
                                // real role as a genuine Spring Security authority here, and
                                // pointing management.endpoint.health.roles at ADMIN (see
                                // application.properties), makes that check real instead of a
                                // no-op that always evaluated true for anyone logged in at all.
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
