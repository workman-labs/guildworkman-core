package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.models.SkilledWorker;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class PostReviewRequest {
    @NotNull
    private SkilledWorker skilledWorker;
    @NotBlank
    private String review;
}
