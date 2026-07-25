package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.constants.AppointmentStatus;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Partial update: every field is an optional patch, so nothing here is {@code @NotNull}. */
@Setter
@Getter
public class UpdateAppointmentRequest {
    private Long clientId;
    @Positive
    private BigDecimal amount;
    private LocalDateTime startTime;
    private AppointmentStatus status;

}
