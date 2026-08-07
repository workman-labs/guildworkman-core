package com.guildworkman.api.booking.api;

import com.guildworkman.api.data.constants.Category;

import java.math.BigDecimal;

/**
 * Turns a held slot into a real appointment. The worker, client and time all
 * come from the reservation itself — only the details the hold didn't need are
 * supplied here.
 *
 * @param category the job category; falls back to the worker's own category when omitted
 * @param amount   agreed price, optional (as it is on {@code BookAppointmentRequest})
 */
public record ConfirmReservationRequest(Category category, BigDecimal amount) {
}
