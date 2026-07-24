package com.guildworkman.api.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Login credentials for the JWT auth flow (distinct from the legacy {@code LoginRequest}). */
@Getter
@Setter
public class AuthenticationRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;
}
