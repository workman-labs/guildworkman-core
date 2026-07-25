package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.models.Address;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Partial update: only {@code skilledWorkerId} (the target) is required; the rest are optional patches. */
@Setter
@Getter
public class UpdateSkilledWorkerRequest {
    @NotNull
    private Long skilledWorkerId;
    private String fullName;
    private String username;
    private String email;
    private String phoneNumber;
    private String password;
    private String houseNumber;
    private String street;
    private String area;
}
