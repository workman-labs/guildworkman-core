package com.guildworkman.api.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class EditReviewRequest {
    @NotNull
    private Long reviewId;
    @NotNull
    private Long clientId;
    @NotBlank
    private String review;
}
