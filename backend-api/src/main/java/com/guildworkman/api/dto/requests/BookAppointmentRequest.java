package com.guildworkman.api.dto.requests;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.data.models.Client;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Setter
@Getter
public class BookAppointmentRequest {

    @NotNull
    private Long clientId;
    private AppointmentStatus status;
    @NotNull
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime scheduleTime;
    @NotNull
    private Category category;

    /**
     * The tradesperson being booked. Optional for backward compatibility with
     * callers that don't send it — but without it the appointment records no
     * worker, and {@code viewAllAppointment} returns {@code worker: null}.
     */
    private Long skilledWorkerId;

    /** Agreed price for the job. Optional; stored on the appointment. */
    private BigDecimal amount;

}
