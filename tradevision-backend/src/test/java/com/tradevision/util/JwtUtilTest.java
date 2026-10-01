package com.tradevision.util;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwt;

    @BeforeEach
    void setup() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret",         "TestSecret_256bit_Key_ForJUnit_TradeVision_2025!!");
        ReflectionTestUtils.setField(jwt, "expiration",     86400000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 604800000L);
    }

    @Test @DisplayName("Access token: generate and parse")
    void accessToken_generateAndParse() {
        String token = jwt.generateToken("9999999999", "user123", 1L);
        assertThat(token).isNotBlank();
        assertThat(jwt.getMobile(token)).isEqualTo("9999999999");
        assertThat(jwt.getUserId(token)).isEqualTo("user123");
        assertThat(jwt.getTokenVersion(token)).isEqualTo(1L);
        assertThat(jwt.isValid(token)).isTrue();
    }

    @Test @DisplayName("Refresh token: generate and detect type")
    void refreshToken_type() {
        String rt = jwt.generateRefreshToken("user123");
        assertThat(rt).isNotBlank();
        assertThat(jwt.isValid(rt)).isTrue();
        assertThat(jwt.isRefreshToken(rt)).isTrue();
    }

    @Test @DisplayName("Access token is not a refresh token")
    void accessToken_notRefreshType() {
        String at = jwt.generateToken("9999999999", "user123", 1L);
        assertThat(jwt.isRefreshToken(at)).isFalse();
    }

    @Test @DisplayName("Token hash is consistent")
    void hashToken_consistent() {
        String token = "sometoken";
        assertThat(jwt.hashToken(token)).isEqualTo(jwt.hashToken(token));
        assertThat(jwt.hashToken(token)).isNotEqualTo(jwt.hashToken("othertoken"));
    }

    @Test @DisplayName("Invalid token returns false")
    void invalidToken() {
        assertThat(jwt.isValid("bad.token.here")).isFalse();
        assertThat(jwt.isValid("")).isFalse();
    }

    @Test @DisplayName("Token version changes invalidate old behavior")
    void tokenVersion_differentVersions() {
        String v1 = jwt.generateToken("9999999999", "user123", 1L);
        String v2 = jwt.generateToken("9999999999", "user123", 2L);
        assertThat(jwt.getTokenVersion(v1)).isEqualTo(1L);
        assertThat(jwt.getTokenVersion(v2)).isEqualTo(2L);
    }

    /**
     * Review finding ("JWT secret format is not strongly validated" -- external review,
     * twenty-third pass, P2, full context in validateSecretAtStartup's own javadoc): the actual
     * tests for the new startup validation.
     */
    @Test
    @DisplayName("validateSecretAtStartup: a sufficiently long secret (the shared test fixture's own 49-byte value) passes without throwing")
    void validateSecretAtStartup_validSecret_doesNotThrow() {
        assertThatCode(() -> jwt.validateSecretAtStartup()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateSecretAtStartup: a null secret refuses to start, rather than failing later at the first real login attempt")
    void validateSecretAtStartup_nullSecret_throws() {
        ReflectionTestUtils.setField(jwt, "secret", null);

        assertThatThrownBy(() -> jwt.validateSecretAtStartup())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not configured");
    }

    @Test
    @DisplayName("validateSecretAtStartup: a blank secret refuses to start")
    void validateSecretAtStartup_blankSecret_throws() {
        ReflectionTestUtils.setField(jwt, "secret", "   ");

        assertThatThrownBy(() -> jwt.validateSecretAtStartup())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not configured");
    }

    @Test
    @DisplayName("validateSecretAtStartup: a secret shorter than 32 bytes (the real, verified HS256 minimum per RFC 7518) refuses to start")
    void validateSecretAtStartup_tooShortSecret_throws() {
        ReflectionTestUtils.setField(jwt, "secret", "short"); // 5 bytes

        assertThatThrownBy(() -> jwt.validateSecretAtStartup())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
    }
}
