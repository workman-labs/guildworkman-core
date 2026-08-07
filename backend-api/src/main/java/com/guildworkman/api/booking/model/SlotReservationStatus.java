package com.guildworkman.api.booking.model;

/**
 * Lifecycle of a {@link SlotReservation}.
 *
 * <p>Only {@link #HELD} and {@link #CONFIRMED} occupy a worker's slot. The two
 * terminal states ({@link #RELEASED}, {@link #EXPIRED}) free it again, and are
 * kept as rows rather than deleted so a slot's history stays auditable.
 */
public enum SlotReservationStatus {

    /** Temporary hold. Occupies the slot until {@code expiresAt} passes. */
    HELD,

    /** Hold turned into a real appointment. Occupies the slot indefinitely. */
    CONFIRMED,

    /** Explicitly given up by the client, or freed when its appointment was cancelled/deleted. */
    RELEASED,

    /** A {@link #HELD} reservation whose {@code expiresAt} elapsed before it was confirmed. */
    EXPIRED;

    /** True for the states that make a slot unavailable to anyone else. */
    public boolean occupiesSlot() {
        return this == HELD || this == CONFIRMED;
    }
}
