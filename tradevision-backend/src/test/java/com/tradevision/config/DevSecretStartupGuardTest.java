package com.tradevision.config;

import com.tradevision.model.BrokerMode;
import com.tradevision.repository.BrokerCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P1-16 fix ("Committed secrets + 'local' as default profile" -- second, independent layer of
 * defense; full context in DevSecretStartupGuard's own class javadoc). @Value fields aren't
 * populated by @InjectMocks (that's a Spring container concern, not a Mockito one) -- set
 * directly via ReflectionTestUtils in each test instead, mirroring exactly what a real
 * application.properties/application-local.properties resolution would have produced.
 */
@ExtendWith(MockitoExtension.class)
class DevSecretStartupGuardTest {

    private static final String KNOWN_DEV_JWT_SECRET = "p9b28r5s+b0fz+BhL51ef1y7wfpKZr5W/8tEDHVjxZk=";
    private static final String KNOWN_DEV_ENCRYPTION_KEY = "FVzfJYk/8RCI4V0V1Jy3vk50Ni7fJg1GspyINhozLLk=";
    private static final String REAL_SECRET = "a-genuinely-unique-production-secret-value-not-in-any-repo";

    @Mock BrokerCredentialRepository credentialRepo;

    private DevSecretStartupGuard guard;

    @BeforeEach
    void setup() {
        guard = new DevSecretStartupGuard(credentialRepo);
        // Real, non-dev secrets by default -- every test overrides only the field(s) it cares about.
        ReflectionTestUtils.setField(guard, "jwtSecret", REAL_SECRET);
        ReflectionTestUtils.setField(guard, "encryptionKey", REAL_SECRET);
        ReflectionTestUtils.setField(guard, "adminBootstrapSecret", REAL_SECRET);
        ReflectionTestUtils.setField(guard, "otpHmacSecret", REAL_SECRET);
    }

    @Test
    @DisplayName("all real, unique secrets -- never even queries for LIVE credentials, never throws")
    void realSecrets_doesNothing() {
        guard.checkForDevSecretsAgainstLiveCredentials();

        verify(credentialRepo, never()).countByMode(any());
    }

    @Test
    @DisplayName("a known dev secret (e.g. app.jwt.secret) is active, but zero LIVE credentials exist -- logs the risk but does NOT refuse to start")
    void knownDevSecret_noLiveCredentials_doesNotThrow() {
        ReflectionTestUtils.setField(guard, "jwtSecret", KNOWN_DEV_JWT_SECRET);
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(0L);

        // Must not throw -- a fresh install/genuine local dev environment with no LIVE
        // credential connected yet is not blocked.
        guard.checkForDevSecretsAgainstLiveCredentials();

        verify(credentialRepo).countByMode(BrokerMode.LIVE);
    }

    @Test
    @DisplayName("a known dev secret is active AND at least one LIVE credential already exists -- REFUSES TO START (throws)")
    void knownDevSecret_withLiveCredentials_refusesToStart() {
        ReflectionTestUtils.setField(guard, "encryptionKey", KNOWN_DEV_ENCRYPTION_KEY);
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(2L);

        assertThatThrownBy(() -> guard.checkForDevSecretsAgainstLiveCredentials())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("LIVE");
    }

    @Test
    @DisplayName("every one of the four secret fields is independently checked -- a known dev value in ANY of them, with a LIVE credential present, refuses to start")
    void anyOfTheFourFields_withLiveCredentials_refusesToStart() {
        // admin-bootstrap-secret specifically, none of the others.
        ReflectionTestUtils.setField(guard, "adminBootstrapSecret", "ev0/Nk5FhRBgIx5cNEfDq1iQU+dEcZTIet58FsxJWME=");
        when(credentialRepo.countByMode(BrokerMode.LIVE)).thenReturn(1L);

        assertThatThrownBy(() -> guard.checkForDevSecretsAgainstLiveCredentials())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty/unresolved secret value is never treated as a match against the known-dev-secret set")
    void emptySecret_neverMatchesKnownDevSecrets() {
        ReflectionTestUtils.setField(guard, "jwtSecret", "");
        ReflectionTestUtils.setField(guard, "encryptionKey", "");
        ReflectionTestUtils.setField(guard, "adminBootstrapSecret", "");
        ReflectionTestUtils.setField(guard, "otpHmacSecret", "");

        guard.checkForDevSecretsAgainstLiveCredentials();

        verify(credentialRepo, never()).countByMode(any());
    }
}
