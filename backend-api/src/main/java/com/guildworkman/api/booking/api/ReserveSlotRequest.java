package com.guildworkman.api.booking.api;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Takes a short-lived hold on one of a worker's slots.
 *
 * @param idempotencyKey  caller-generated key, unique per booking attempt (a UUID is the obvious
 *                        choice); re-sending the same key returns the original hold instead of
 *                        taking a second one, so a retried request or a double-clicked button
 *                        can't consume two slots. Two different visitors reusing one key would
 *                        each be handed the same reservation, so it must not be a value derived
 *                        from the slot itself
 * @param skilledWorkerId the worker whose calendar is being claimed
 * @param clientId        the client taking the hold
 * @param slotStart       start of the slot, in the server's timezone — same wall-clock convention
 *                        as {@code Appointment.scheduleTime}
 * @param durationMinutes slot length; defaults to {@code booking.reservation.slot-duration}
 *                        (1 hour) when omitted, and is capped by
 *                        {@code booking.reservation.max-slot-duration}
 */
public record ReserveSlotRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotNull Long skilledWorkerId,
        @NotNull Long clientId,
        // A hold on a slot that has already started can never become a bookable
        // appointment, and would occupy the calendar until its TTL ran out.
        @NotNull @Future LocalDateTime slotStart,
        @Min(1) Integer durationMinutes) {
}
