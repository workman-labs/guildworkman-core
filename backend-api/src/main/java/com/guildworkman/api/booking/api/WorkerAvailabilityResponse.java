package com.guildworkman.api.booking.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A worker's taken time inside a calendar window — the read the booking
 * calendar needs and {@code viewAllAppointment} can't give it, since that
 * endpoint only ever returns the logged-in client's <i>own</i> bookings.
 *
 * <p>Deliberately reports what is <b>taken</b>, not what is free. Free time is
 * whatever the worker's published working hours leave over, which is a
 * front-end (and future working-hours feature) concern; the server is only
 * authoritative about what is already claimed. Anything not listed here was
 * unclaimed at the moment of the read — a booking still has to survive the
 * reserve call, which is where the race is actually settled.
 *
 * @param slotDurationMinutes the default slot length used to derive an end time for legacy
 *                            appointments, which store only a start
 */
public record WorkerAvailabilityResponse(
        Long workerId,
        LocalDateTime from,
        LocalDateTime to,
        int slotDurationMinutes,
        List<TakenSlot> taken) {

    /**
     * @param state         {@link SlotState#BOOKED} for a confirmed appointment,
     *                      {@link SlotState#HELD} for a hold that may still lapse
     * @param holdExpiresAt when a {@link SlotState#HELD} slot frees up again; {@code null} when {@code BOOKED}
     */
    public record TakenSlot(
            LocalDateTime start,
            LocalDateTime end,
            SlotState state,
            Instant holdExpiresAt,
            Long reservationId,
            Long appointmentId) {
    }

    public enum SlotState {
        /** Confirmed — an appointment exists for it. */
        BOOKED,
        /** Reserved but unconfirmed. Unavailable now; may free up when the hold expires. */
        HELD
    }
}
