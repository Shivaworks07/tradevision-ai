package com.tradevision.config;

import com.tradevision.model.User;
import com.tradevision.repository.UserRepository;
import com.tradevision.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the jwtFilter bean's authentication logic, in particular which authorities it
 * grants, since management.endpoint.health.roles=ADMIN depends on a real ROLE_ADMIN
 * authority being granted rather than an empty list. Constructs the filter bean directly
 * rather than via Spring context, matching this codebase's established pattern for testing
 * hand-written filter beans without a full Spring Security test slice (see MetricsFilter's
 * own test style).
 */
class SecurityConfigTest {

    private final JwtUtil jwt = mock(JwtUtil.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final SecurityConfig config = new SecurityConfig(jwt, userRepo);

    @Test
    void jwtFilter_validTokenForAdminUser_grantsRoleAdminAuthority() throws Exception {
        SecurityContextHolder.clearContext();
        when(jwt.isValid("good-token")).thenReturn(true);
        when(jwt.isRefreshToken("good-token")).thenReturn(false);
        when(jwt.getUserId("good-token")).thenReturn("admin-1");
        when(jwt.getTokenVersion("good-token")).thenReturn(1L);
        User admin = new User();
        admin.setId("admin-1");
        admin.setRole("ADMIN");
        admin.setTokenVersion(1L);
        when(userRepo.findById("admin-1")).thenReturn(Optional.of(admin));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer good-token");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        try {
            config.jwtFilter().doFilter(req, res, chain);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            // A real ROLE_ADMIN authority, not an empty list -- this is what makes
            // management.endpoint.health.roles=ADMIN a genuine check instead of a no-op
            // that always evaluates true for anyone logged in at all.
            assertThat(auth.getAuthorities()).extracting(Object::toString).contains("ROLE_ADMIN");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void jwtFilter_validTokenForOrdinaryUser_grantsRoleUserAuthority_notAdmin() throws Exception {
        SecurityContextHolder.clearContext();
        when(jwt.isValid("good-token")).thenReturn(true);
        when(jwt.isRefreshToken("good-token")).thenReturn(false);
        when(jwt.getUserId("good-token")).thenReturn("user-1");
        when(jwt.getTokenVersion("good-token")).thenReturn(1L);
        User plainUser = new User();
        plainUser.setId("user-1");
        plainUser.setRole("USER");
        plainUser.setTokenVersion(1L);
        when(userRepo.findById("user-1")).thenReturn(Optional.of(plainUser));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer good-token");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        try {
            config.jwtFilter().doFilter(req, res, chain);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(Object::toString)
                .contains("ROLE_USER")
                .doesNotContain("ROLE_ADMIN");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void jwtFilter_tokenVersionMismatch_neverAuthenticatesAndGrantsNoAuthority() throws Exception {
        SecurityContextHolder.clearContext();
        when(jwt.isValid("stale-token")).thenReturn(true);
        when(jwt.isRefreshToken("stale-token")).thenReturn(false);
        when(jwt.getUserId("stale-token")).thenReturn("user-1");
        when(jwt.getTokenVersion("stale-token")).thenReturn(1L); // stale — token was issued before logout bumped it
        User plainUser = new User();
        plainUser.setId("user-1");
        plainUser.setRole("USER");
        plainUser.setTokenVersion(2L); // bumped by a logout since this token was issued
        when(userRepo.findById("user-1")).thenReturn(Optional.of(plainUser));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer stale-token");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        try {
            config.jwtFilter().doFilter(req, res, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
