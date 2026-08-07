package com.guildworkman.api.booking.api;

import com.guildworkman.api.booking.model.SlotReservation;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * @param expiresAt    when this hold stops occupying the slot; {@code null} once confirmed
 * @param appointmentId the appointment this reservation produced; {@code null} until confirmed
 */
public record SlotReservationResponse(
        Long id,
        String idempotencyKey,
        Long skilledWorkerId,
        Long clientId,
        LocalDateTime slotStart,
        LocalDateTime slotEnd,
        String status,
        Instant expiresAt,
        Long appointmentId,
        Instant createdAt,
        Instant confirmedAt,
        Instant releasedAt) {

    public static SlotReservationResponse from(SlotReservation reservation) {
        return new SlotReservationResponse(
                reservation.getId(),
                reservation.getIdempotencyKey(),
                reservation.getSkilledWorkerId(),
                reservation.getClientId(),
                reservation.getSlotStart(),
                reservation.getSlotEnd(),
                reservation.getStatus().name(),
                reservation.getExpiresAt(),
                reservation.getAppointmentId(),
                reservation.getCreatedAt(),
                reservation.getConfirmedAt(),
                reservation.getReleasedAt());
    }
}
