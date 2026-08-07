package com.guildworkman.api.booking.api;

import com.guildworkman.api.booking.service.SlotReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Concurrency-safe booking: reserve a slot, then confirm it.
 *
 * <p>See {@code docs/APPOINTMENT_BOOKING.md} for the full write-up. In short:
 * <ul>
 *   <li>{@code POST /reservations} takes a short-lived, server-authoritative
 *       hold. Exactly one concurrent caller can hold a given slot — everyone
 *       else gets {@code 409 slot-unavailable}. Idempotent on
 *       {@code idempotencyKey}, signalled by {@link #IDEMPOTENT_REPLAY_HEADER}
 *       rather than a different status code, since a replay is not an error.</li>
 *   <li>{@code POST /reservations/{id}/confirm} turns the hold into an
 *       appointment.</li>
 *   <li>{@code DELETE /reservations/{id}} gives the hold back early.</li>
 *   <li>{@code GET /workers/{workerId}/availability} reports what is already
 *       taken on one worker's calendar — the read a booking UI needs, which
 *       {@code /api/v1/client/viewAllAppointment} can't provide because it only
 *       returns the calling client's own bookings.</li>
 * </ul>
 *
 * <p>Errors follow the same RFC 7807 contract as the rest of the API; the
 * booking-specific ones are wired up in {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/booking")
@RequiredArgsConstructor
@Validated
public class BookingController {

    /** {@code true}/{@code false} on the reserve response; see class Javadoc. */
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final SlotReservationService slotReservationService;

    @PostMapping("/reservations")
    @Operation(summary = "Hold a slot on a worker's calendar",
            description = "Atomically reserves the slot under a per-worker database lock, so two "
                    + "concurrent callers can never both hold it. Idempotent on idempotencyKey: "
                    + "re-sending the same key returns the original hold (see the X-Idempotent-Replay "
                    + "response header) instead of taking a second one. The hold lapses on its own "
                    + "after booking.reservation.hold-ttl (5 minutes by default).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Slot held"),
            @ApiResponse(responseCode = "404", description = "No such client or skilled worker"),
            @ApiResponse(responseCode = "409", description = "Slot already held or booked by someone else")
    })
    public ResponseEntity<SlotReservationResponse> reserve(@Valid @RequestBody ReserveSlotRequest request) {
        var outcome = slotReservationService.reserve(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IDEMPOTENT_REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(SlotReservationResponse.from(outcome.reservation()));
    }

    @GetMapping("/reservations/{reservationId}")
    @Operation(summary = "Fetch a reservation's current state")
    public SlotReservationResponse get(@PathVariable Long reservationId) {
        return SlotReservationResponse.from(slotReservationService.get(reservationId));
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    @Operation(summary = "Confirm a held slot into an appointment",
            description = "Idempotent: confirming an already-confirmed reservation returns it rather "
                    + "than booking a second appointment. 409 if the hold has since expired or been "
                    + "released, because the slot may already have gone to another client.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Appointment booked"),
            @ApiResponse(responseCode = "404", description = "No such reservation"),
            @ApiResponse(responseCode = "409", description = "Reservation is expired or released")
    })
    public SlotReservationResponse confirm(@PathVariable Long reservationId,
                                           @RequestBody(required = false) ConfirmReservationRequest request) {
        return SlotReservationResponse.from(slotReservationService.confirm(reservationId, request));
    }

    @DeleteMapping("/reservations/{reservationId}")
    @Operation(summary = "Release a held slot early",
            description = "Idempotent for an already-released or expired hold. 409 for a confirmed "
                    + "reservation — free that slot by cancelling its appointment instead.")
    public SlotReservationResponse release(@PathVariable Long reservationId) {
        return SlotReservationResponse.from(slotReservationService.release(reservationId));
    }

    @GetMapping("/workers/{workerId}/availability")
    @Operation(summary = "List a worker's taken slots in a calendar window",
            description = "Returns booked appointments and live holds, so a booking UI can render "
                    + "availability from server state instead of guessing. Reports what is taken, not "
                    + "what is free — a slot missing from the response was unclaimed at read time, and "
                    + "still has to be won by reserving it.")
    public WorkerAvailabilityResponse availability(
            @PathVariable Long workerId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return slotReservationService.availability(workerId, from, to);
    }
}
