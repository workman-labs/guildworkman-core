package com.guildworkman.api.dto.requests;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Partial update: only {@code clientId} (the target) is required; the rest are optional patches. */
@Setter
@Getter
public class UpdateClientRequest {
    @NotNull
    private Long clientId;
    private String username;
    private String phoneNumber;
    private String password;
    private String houseNumber;
    private String street;
    private String area;

}
