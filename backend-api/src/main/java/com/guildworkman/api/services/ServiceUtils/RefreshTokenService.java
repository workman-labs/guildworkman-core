package com.guildworkman.api.services.ServiceUtils;

/**
 * Manages opaque, rotating refresh tokens with revocation and reuse detection.
 * Implementations persist only token hashes.
 */
public interface RefreshTokenService {

    /** Result of a successful rotation: the owner and the new raw token. */
    record RotationResult(Long userAccountId, String newRefreshToken) {
    }

    /**
     * Starts a brand-new token family for a login/registration and returns the
     * raw refresh token (shown to the client exactly once).
     */
    String issueForNewFamily(Long userAccountId);

    /**
     * Validates and rotates a refresh token. Revokes the presented token and
     * mints a successor in the same family. If the token is unknown, expired,
     * already-rotated, or lost a concurrency race, the whole family is revoked
     * (when applicable) and a {@code TokenRefreshException} is thrown.
     */
    RotationResult rotate(String rawRefreshToken);

    /** Logout: revokes the entire family the token belongs to. */
    void revoke(String rawRefreshToken);
}
