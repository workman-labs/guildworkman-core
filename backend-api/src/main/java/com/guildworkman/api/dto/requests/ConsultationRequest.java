package com.guildworkman.api.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsultationRequest {
    @NotNull
    private Long clientId;
    @NotNull
    private Long workerId;
    @NotBlank
    private String details;
}
