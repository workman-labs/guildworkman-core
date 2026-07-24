package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a token family in its <em>own</em> committed transaction.
 *
 * <p>Reuse detection happens inside {@code rotate()}, which then signals the
 * caller by throwing — and that throw would roll back the enclosing
 * transaction. If the revocation ran in that same transaction it would be
 * undone, defeating the whole point. Running it {@code REQUIRES_NEW} means the
 * family stays revoked no matter what the caller's transaction does next.
 *
 * <p>Kept in a separate bean deliberately: {@code REQUIRES_NEW} only takes
 * effect through the Spring proxy, which a self-invocation would bypass.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String familyId) {
        refreshTokenRepository.revokeFamily(familyId);
    }
}
