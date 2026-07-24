package com.guildworkman.api.services;

import com.guildworkman.api.config.JwtProperties;
import com.guildworkman.api.data.models.RefreshToken;
import com.guildworkman.api.data.repository.RefreshTokenRepository;
import com.guildworkman.api.exceptions.TokenRefreshException;
import com.guildworkman.api.services.ServiceUtils.RefreshTokenService;
import com.guildworkman.api.services.implmentations.RefreshTokenFamilyRevoker;
import com.guildworkman.api.services.implmentations.RefreshTokenServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for refresh-token rotation, revocation and reuse detection. The
 * repository is mocked, so these run without a database. True multi-threaded
 * concurrency is exercised by the Postgres-backed integration test.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository repository;

    @Mock
    private RefreshTokenFamilyRevoker familyRevoker;

    // A real properties object (not a mock) — simplest way to supply the TTL.
    private final JwtProperties jwtProperties = new JwtProperties();

    private RefreshTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenServiceImpl(repository, jwtProperties, familyRevoker);
    }

    private RefreshToken activeToken() {
        RefreshToken token = new RefreshToken();
        token.setId(1L);
        token.setTokenHash("does-not-matter-here");
        token.setUserAccountId(7L);
        token.setFamilyId("fam-1");
        token.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        token.setRevoked(false);
        return token;
    }

    @Test
    void issueForNewFamilyPersistsHashedTokenAndReturnsRawSecret() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);

        String raw = service.issueForNewFamily(7L);

        assertThat(raw).isNotBlank();
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        // Stored value is the hash, never the raw token.
        assertThat(saved.getTokenHash()).isNotEqualTo(raw).hasSize(64);
        assertThat(saved.getUserAccountId()).isEqualTo(7L);
        assertThat(saved.getFamilyId()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        assertThat(saved.isRevoked()).isFalse();
    }

    @Test
    void rotateHappyPathRevokesOldAndIssuesNewInSameFamily() {
        RefreshToken token = activeToken();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(repository.revokeIfActive(1L)).thenReturn(1);

        RefreshTokenService.RotationResult result = service.rotate("raw-token");

        assertThat(result.userAccountId()).isEqualTo(7L);
        assertThat(result.newRefreshToken()).isNotBlank();
        verify(repository).revokeIfActive(1L);
        // The successor is persisted; the family is NOT wiped on a clean rotation.
        verify(repository).save(any(RefreshToken.class));
        verify(repository, never()).revokeFamily(anyString());
    }

    @Test
    void rotateUnknownTokenIsRejected() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("nope"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("Invalid");
        verify(repository, never()).revokeFamily(anyString());
    }

    @Test
    void rotateExpiredTokenIsRejected() {
        RefreshToken token = activeToken();
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rotateRevokedTokenIsReuseAndRevokesWholeFamily() {
        RefreshToken token = activeToken();
        token.setRevoked(true);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("reuse");
        // Revoked via the REQUIRES_NEW bean so the throw can't roll it back.
        verify(familyRevoker).revokeFamily("fam-1");
        verify(repository, never()).save(any());
    }

    @Test
    void rotateLosingTheRaceIsTreatedAsReuse() {
        // Token looked active at read time, but revokeIfActive found 0 rows —
        // another concurrent refresh already consumed it.
        RefreshToken token = activeToken();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(repository.revokeIfActive(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("reuse");
        verify(familyRevoker).revokeFamily("fam-1");
        verify(repository, never()).save(any());
    }

    @Test
    void revokeLogsOutTheWholeFamily() {
        RefreshToken token = activeToken();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        service.revoke("raw");

        verify(repository, times(1)).revokeFamily("fam-1");
    }

    @Test
    void revokeUnknownTokenIsRejected() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke("nope"))
                .isInstanceOf(TokenRefreshException.class);
        verify(repository, never()).revokeFamily(eq("fam-1"));
    }
}
