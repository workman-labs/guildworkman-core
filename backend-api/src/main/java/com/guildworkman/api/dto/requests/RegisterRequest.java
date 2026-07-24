package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.constants.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    /**
     * Optional requested role. ADMIN is never honoured from self-registration
     * (privilege escalation); the service clamps anything but SKILLED_WORKER to
     * CLIENT. Defaults to CLIENT when omitted.
     */
    private Role role;
}
