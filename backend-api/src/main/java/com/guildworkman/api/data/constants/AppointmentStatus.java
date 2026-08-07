package com.guildworkman.api.data.constants;

import java.util.Set;

public enum AppointmentStatus {
    ACCEPTED,
    DECLINED,
    SCHEDULED,
    CANCELLED,
    UPDATED;

    /**
     * Statuses that still occupy the worker's calendar. {@code CANCELLED} and
     * {@code DECLINED} give the time back; {@code UPDATED} is what this codebase
     * writes on a generic edit, so it stays occupied.
     *
     * <p>Used by the booking guard both to decide whether a slot is free and to
     * decide whether a status change should release the slot's reservation — see
     * {@code com.guildworkman.api.booking.service.SlotReservationBooker}.
     */
    public static final Set<AppointmentStatus> OCCUPYING = Set.of(SCHEDULED, ACCEPTED, UPDATED);

    public boolean occupiesSlot() {
        return OCCUPYING.contains(this);
    }
}
