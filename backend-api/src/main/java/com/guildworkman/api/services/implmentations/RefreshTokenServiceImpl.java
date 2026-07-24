package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.config.JwtProperties;
import com.guildworkman.api.data.models.RefreshToken;
import com.guildworkman.api.data.repository.RefreshTokenRepository;
import com.guildworkman.api.exceptions.TokenRefreshException;
import com.guildworkman.api.services.ServiceUtils.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh-token rotation with reuse detection.
 *
 * <p>Security properties:
 * <ul>
 *   <li><b>Opaque + hashed:</b> the raw token is 256 bits of CSPRNG output;
 *       only its SHA-256 is stored, so a DB leak yields nothing usable.</li>
 *   <li><b>Rotation:</b> every {@link #rotate} revokes the presented token and
 *       issues a fresh one in the same family.</li>
 *   <li><b>Reuse detection:</b> presenting an already-revoked token means it was
 *       replayed after the legitimate client rotated past it — the entire family
 *       is revoked, killing the session on every device in that lineage.</li>
 *   <li><b>Concurrency-safe:</b> the revoke-then-issue step hinges on a
 *       conditional {@code revokeIfActive} update, so two simultaneous refreshes
 *       of the same token cannot both succeed — the loser is treated as reuse.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public String issueForNewFamily(Long userAccountId) {
        return createToken(userAccountId, UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public RotationResult rotate(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new TokenRefreshException("Invalid refresh token"));

        if (token.isExpired()) {
            throw new TokenRefreshException("Refresh token has expired");
        }
        if (token.isRevoked()) {
            // Replay of a token we already rotated past — revoke the lineage.
            // In a committed side-transaction so the throw below can't undo it.
            familyRevoker.revokeFamily(token.getFamilyId());
            throw new TokenRefreshException("Refresh token reuse detected; session revoked");
        }

        // Win-or-lose the rotation race atomically. Losing means another request
        // already consumed this exact token concurrently: treat as reuse.
        int won = refreshTokenRepository.revokeIfActive(token.getId());
        if (won == 0) {
            familyRevoker.revokeFamily(token.getFamilyId());
            throw new TokenRefreshException("Refresh token reuse detected; session revoked");
        }

        String newRaw = createToken(token.getUserAccountId(), token.getFamilyId());
        return new RotationResult(token.getUserAccountId(), newRaw);
    }

    @Override
    @Transactional
    public void revoke(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new TokenRefreshException("Invalid refresh token"));
        refreshTokenRepository.revokeFamily(token.getFamilyId());
    }

    private String createToken(Long userAccountId, String familyId) {
        String raw = randomToken();
        RefreshToken token = new RefreshToken();
        token.setTokenHash(sha256(raw));
        token.setUserAccountId(userAccountId);
        token.setFamilyId(familyId);
        token.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        token.setRevoked(false);
        refreshTokenRepository.save(token);
        return raw;
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
