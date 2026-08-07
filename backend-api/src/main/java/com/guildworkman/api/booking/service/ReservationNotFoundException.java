package com.guildworkman.api.booking.service;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(Long id) {
        super("Slot reservation not found: " + id);
    }
}
