package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.constants.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AddSkillRequest {
    private Long skillId;
    @NotNull
    private Long skilledWorkerId;
    @NotBlank
    private String skillName;
    @NotNull
    private Category category;

}
