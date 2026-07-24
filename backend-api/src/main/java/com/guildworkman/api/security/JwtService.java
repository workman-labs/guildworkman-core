package com.guildworkman.api.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.guildworkman.api.config.JwtProperties;
import com.guildworkman.api.data.constants.Role;
import com.guildworkman.api.data.models.UserAccount;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies short-lived access tokens (HS256 JWTs).
 *
 * <p>Refresh tokens are intentionally <em>not</em> JWTs — they are opaque,
 * server-stored, and rotated ({@code RefreshTokenService}). Only the access
 * token is a stateless JWT so per-request auth needs no database hit.
 */
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.getSecret());
        this.verifier = JWT.require(algorithm)
                .withIssuer(properties.getIssuer())
                .build();
    }

    /** Mints an access token for {@code account}, expiring after the configured TTL. */
    public String generateAccessToken(UserAccount account) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtl());
        return JWT.create()
                .withIssuer(properties.getIssuer())
                .withSubject(String.valueOf(account.getId()))
                .withClaim(CLAIM_EMAIL, account.getEmail())
                .withClaim(CLAIM_ROLE, account.getRole().name())
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiry))
                .sign(algorithm);
    }

    /**
     * Verifies signature, issuer and expiry.
     *
     * @throws JWTVerificationException if the token is invalid or expired
     */
    public DecodedJWT verify(String token) {
        return verifier.verify(token);
    }

    public Long extractUserId(DecodedJWT jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    public String extractEmail(DecodedJWT jwt) {
        return jwt.getClaim(CLAIM_EMAIL).asString();
    }

    public Role extractRole(DecodedJWT jwt) {
        return Role.valueOf(jwt.getClaim(CLAIM_ROLE).asString());
    }

    /** Access-token lifetime in seconds, for the {@code expiresIn} response field. */
    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }
}
