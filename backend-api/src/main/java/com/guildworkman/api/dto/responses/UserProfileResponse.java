package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.constants.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** The authenticated caller's identity, returned by {@code GET /api/v1/auth/me}. */
@Getter
@AllArgsConstructor
public class UserProfileResponse {
    private final Long userId;
    private final String email;
    private final Role role;
}
