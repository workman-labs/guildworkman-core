package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.constants.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** Token pair plus the identity it belongs to, returned by register/login/refresh. */
@Getter
@Builder
@AllArgsConstructor
public class AuthenticationResponse {

    private final String accessToken;
    private final String refreshToken;
    @Builder.Default
    private final String tokenType = "Bearer";
    /** Access-token lifetime in seconds. */
    private final long expiresIn;
    private final Long userId;
    private final String email;
    private final Role role;
}
