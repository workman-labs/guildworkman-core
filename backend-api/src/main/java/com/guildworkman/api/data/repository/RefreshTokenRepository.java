package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every token in a family in one statement — the reaction to a
     * detected reuse, and to an explicit logout. {@code clearAutomatically} so
     * the persistence context doesn't serve stale (still-active) copies after
     * the bulk update.
     */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.familyId = :familyId and t.revoked = false")
    int revokeFamily(@Param("familyId") String familyId);

    /**
     * Atomically revokes a single token only if it is still active. Returns the
     * number of rows affected: {@code 1} for the caller that won, {@code 0} if
     * another transaction already revoked it. This conditional update is what
     * makes rotation safe under concurrent refreshes — two requests presenting
     * the same token cannot both mint a successor, because the DB serialises the
     * row update and only one sees {@code revoked = false}.
     */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") Long id);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteAllExpiredBefore(@Param("cutoff") Instant cutoff);
}
