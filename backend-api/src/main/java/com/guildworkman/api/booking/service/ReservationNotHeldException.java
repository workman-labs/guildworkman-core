package com.guildworkman.api.booking.service;

import com.guildworkman.api.booking.model.SlotReservationStatus;

/**
 * A hold can only be confirmed while it is still {@link SlotReservationStatus#HELD}
 * and inside its TTL. Confirming a released or expired one is a
 * {@code 409 Conflict}: the slot may since have gone to another client, so the
 * caller has to reserve again rather than have the server silently re-take it.
 */
public class ReservationNotHeldException extends RuntimeException {

    public ReservationNotHeldException(Long id, SlotReservationStatus current) {
        super("Slot reservation " + id + " is " + current + ", not HELD; reserve the slot again to book it");
    }

    public static ReservationNotHeldException expired(Long id) {
        return new ReservationNotHeldException(id, SlotReservationStatus.EXPIRED);
    }
}
