package com.guildworkman.api.data.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single issued refresh token, persisted only as a hash.
 *
 * <p><b>Rotation:</b> every successful refresh revokes the presented token and
 * issues a new one in the same {@link #familyId family}. A family is created at
 * login and represents one device/session lineage.
 *
 * <p><b>Reuse detection:</b> because a rotated token is marked {@link #revoked},
 * presenting an already-revoked token means the token was stolen and replayed
 * (the legitimate client already rotated past it). The service reacts by
 * revoking the entire family, forcing a fresh login — see
 * {@code RefreshTokenServiceImpl}.
 *
 * <p>The raw token value is never stored; only {@link #tokenHash} (a SHA-256 of
 * the opaque secret) is, so a database leak does not expose usable tokens.
 */
@Entity
@Getter
@Setter
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_refresh_token_hash", columnList = "tokenHash", unique = true),
                @Index(name = "idx_refresh_token_family", columnList = "familyId")
        }
)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 (hex) of the opaque refresh token handed to the client. */
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Owning {@link UserAccount} id. */
    @Column(nullable = false)
    private Long userAccountId;

    /** Session lineage id; constant across a login's rotation chain. */
    @Column(nullable = false, length = 36)
    private String familyId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Transient
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    @Transient
    public boolean isActive() {
        return !revoked && !isExpired();
    }
}
