package com.guildworkman.api.booking.service;

import java.time.LocalDateTime;

/**
 * The requested slot is already taken by someone else — the concurrency-safe
 * outcome for the loser of a race, rendered as {@code 409 Conflict}.
 */
public class SlotUnavailableException extends RuntimeException {

    public SlotUnavailableException(Long workerId, LocalDateTime slotStart, LocalDateTime slotEnd) {
        super("Slot " + slotStart + " to " + slotEnd + " is no longer available for worker " + workerId);
    }
}
