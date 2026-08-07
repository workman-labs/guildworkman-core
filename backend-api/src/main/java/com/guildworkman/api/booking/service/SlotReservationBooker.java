package com.guildworkman.api.booking.service;

import com.guildworkman.api.booking.api.ReserveSlotRequest;
import com.guildworkman.api.booking.model.SlotReservation;
import com.guildworkman.api.booking.model.SlotReservationStatus;
import com.guildworkman.api.booking.repository.SlotReservationRepository;
import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.models.Appointment;
import com.guildworkman.api.data.repository.AppointmentRepository;
import com.guildworkman.api.data.repository.ClientRepository;
import com.guildworkman.api.exceptions.GuildWorkmanException;
import com.guildworkman.api.exceptions.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The one place a slot on a worker's calendar is claimed. Everything that
 * occupies time — the two-step hold and the one-step legacy booking — goes
 * through {@link #claim}, so there is exactly one critical section to reason
 * about.
 *
 * <h2>How double-booking is prevented</h2>
 * <ol>
 *   <li><b>Serialise writers per worker.</b> {@link SlotReservationRepository#lockWorkerForBooking}
 *       takes {@code SELECT ... FOR UPDATE} on the worker's row. Concurrent
 *       requests for the same worker queue up inside Postgres; requests for
 *       different workers don't contend at all.</li>
 *   <li><b>Then look.</b> Only once the lock is held does the overlap check run,
 *       so it sees every committed claim including the one the previous holder
 *       of the lock just made. This ordering is the whole point: an overlap
 *       query run <i>before</i> the lock reads a stale snapshot, which is
 *       exactly how two concurrent bookings both conclude a slot is free.</li>
 *   <li><b>Backstop with a unique index.</b> The inserted row carries
 *       {@code active_slot_key}, unique across all rows still occupying a slot
 *       (see {@link SlotReservation}). If a future code path ever claims a slot
 *       without taking the lock, the database rejects it rather than allowing
 *       a double-booking.</li>
 * </ol>
 *
 * <p><b>Why pessimistic rather than optimistic locking.</b> An optimistic
 * (version/compare-and-swap) scheme needs an existing row to contend over. The
 * race here is between two <i>inserts</i> — there is no shared row whose version
 * could clash, so a version check has nothing to fail on. The alternatives are a
 * lock that covers the read-then-insert window (this) or a unique index alone
 * (which works, but turns the ordinary "someone else got there first" case into
 * a rolled-back transaction plus an integrity-violation round trip, and can't
 * express partial overlaps at all).
 *
 * <p><b>Deadlock safety.</b> A claim locks exactly one worker row and never a
 * second, and takes no other row lock before it, so two concurrent claims can't
 * form a wait-cycle. The lock is held only for the overlap query and one insert
 * — no network calls inside the critical section.
 */
@Service
@RequiredArgsConstructor
public class SlotReservationBooker {

    private static final Logger log = LoggerFactory.getLogger(SlotReservationBooker.class);

    static final Set<SlotReservationStatus> OCCUPYING_RESERVATION_STATUSES =
            EnumSet.of(SlotReservationStatus.HELD, SlotReservationStatus.CONFIRMED);

    private final SlotReservationRepository reservations;
    private final AppointmentRepository appointments;
    private final ClientRepository clients;
    private final SlotReservationProperties properties;

    /** Takes a temporary hold for the two-step booking flow. */
    @Transactional
    public SlotReservation hold(ReserveSlotRequest request) {
        Duration duration = resolveDuration(request.durationMinutes());
        LocalDateTime slotEnd = request.slotStart().plus(duration);
        Instant now = Instant.now();

        SlotReservation reservation = claim(request.skilledWorkerId(), request.clientId(),
                request.slotStart(), slotEnd, request.idempotencyKey(), now, null);
        reservation.setStatus(SlotReservationStatus.HELD);
        reservation.setExpiresAt(now.plus(properties.getHoldTtl()));
        return reservations.saveAndFlush(reservation);
    }

    /**
     * Claims a slot that is booked outright, with no intervening hold — the
     * path {@code POST /api/v1/client/bookAppointment} takes. Runs in the
     * caller's transaction so the reservation and the appointment it guards are
     * committed or rolled back together; a booking that fails downstream must
     * not leave the slot marked as taken.
     */
    @Transactional
    public SlotReservation claimConfirmed(Long skilledWorkerId, Long clientId, LocalDateTime slotStart,
                                          Duration duration, String idempotencyKey) {
        return claimConfirmed(skilledWorkerId, clientId, slotStart, duration, idempotencyKey, null);
    }

    /**
     * @param excludedAppointmentId an appointment to ignore when looking for conflicts. Set when
     *                              <i>moving</i> an existing appointment: it still carries its old
     *                              {@code scheduleTime}, so a small shift would otherwise be
     *                              reported as conflicting with itself.
     */
    @Transactional
    public SlotReservation claimConfirmed(Long skilledWorkerId, Long clientId, LocalDateTime slotStart,
                                          Duration duration, String idempotencyKey,
                                          Long excludedAppointmentId) {
        Instant now = Instant.now();
        SlotReservation reservation = claim(skilledWorkerId, clientId, slotStart,
                slotStart.plus(resolveDuration(duration)), idempotencyKey, now, excludedAppointmentId);
        reservation.setStatus(SlotReservationStatus.CONFIRMED);
        reservation.setExpiresAt(null);
        reservation.setConfirmedAt(now);
        return reservations.saveAndFlush(reservation);
    }

    /**
     * Records which appointment a {@link #claimConfirmed} reservation produced.
     * Split out because the appointment doesn't have an id until it's been
     * saved, and it can only be saved after the slot has been claimed — saving
     * it first would make the claim's own overlap check trip over it.
     */
    @Transactional
    public SlotReservation linkAppointment(SlotReservation reservation, Long appointmentId) {
        reservation.setAppointmentId(appointmentId);
        return reservations.save(reservation);
    }

    /** Expires one page of lapsed holds for the background sweep. */
    @Transactional
    public int expireLapsedHolds(Pageable pageable) {
        Instant now = Instant.now();
        List<SlotReservation> lapsed = reservations.claimExpiredHolds(now, pageable);
        for (SlotReservation reservation : lapsed) {
            reservation.releaseAs(SlotReservationStatus.EXPIRED, now);
            reservations.save(reservation);
            log.info("Slot reservation id={} expired (worker={} slot={})",
                    reservation.getId(), reservation.getSkilledWorkerId(), reservation.getSlotStart());
        }
        return lapsed.size();
    }

    /**
     * The critical section. Returns an unsaved reservation whose slot is
     * guaranteed free as of the lock the caller now holds; the caller sets the
     * status-specific fields and saves it before the transaction (and therefore
     * the lock) ends.
     */
    private SlotReservation claim(Long skilledWorkerId, Long clientId, LocalDateTime slotStart,
                                  LocalDateTime slotEnd, String idempotencyKey, Instant now,
                                  Long excludedAppointmentId) {
        if (!slotEnd.isAfter(slotStart)) {
            throw new GuildWorkmanException("Slot end must be after slot start");
        }
        if (!clients.existsById(clientId)) {
            throw new UserNotFoundException("Client not found: " + clientId);
        }

        // Step 1 — serialise every writer for this worker. Also doubles as the
        // worker existence check: no row, no lock, no such worker.
        reservations.lockWorkerForBooking(skilledWorkerId)
                .orElseThrow(() -> new UserNotFoundException("Skilled worker not found: " + skilledWorkerId));

        // Step 2 — under the lock, retire this worker's lapsed holds. A hold
        // whose TTL ran out must not block a fresh claim just because the
        // background sweep hasn't reached it: the overlap check below ignores
        // it, but its active_slot_key would still trip the unique index.
        reservations.expireHoldsForWorker(skilledWorkerId, now);

        // Step 3 — look for anything already occupying the interval.
        assertSlotFree(skilledWorkerId, slotStart, slotEnd, now, excludedAppointmentId);

        SlotReservation reservation = new SlotReservation();
        reservation.setIdempotencyKey(idempotencyKey);
        reservation.setSkilledWorkerId(skilledWorkerId);
        reservation.setClientId(clientId);
        reservation.setSlotStart(slotStart);
        reservation.setSlotEnd(slotEnd);
        reservation.setActiveSlotKey(SlotReservation.activeSlotKey(skilledWorkerId, slotStart));
        reservation.setCreatedAt(now);
        return reservation;
    }

    private void assertSlotFree(Long workerId, LocalDateTime slotStart, LocalDateTime slotEnd, Instant now,
                                Long excludedAppointmentId) {
        List<SlotReservation> occupying =
                reservations.findOccupying(workerId, slotStart, slotEnd, OCCUPYING_RESERVATION_STATUSES, now);
        if (!occupying.isEmpty()) {
            throw new SlotUnavailableException(workerId, slotStart, slotEnd);
        }

        // Appointments booked before this feature existed have no reservation
        // row but still occupy the calendar. Filtering the exclusion in Java
        // rather than SQL: this returns at most a handful of rows (one worker's
        // appointments overlapping one slot), and it keeps the query free of a
        // nullable-parameter branch.
        boolean conflicts = appointments.findOverlapping(workerId,
                        slotStart.minus(properties.getSlotDuration()), slotEnd, AppointmentStatus.OCCUPYING)
                .stream()
                .anyMatch(appointment -> !appointment.getId().equals(excludedAppointmentId));
        if (conflicts) {
            throw new SlotUnavailableException(workerId, slotStart, slotEnd);
        }
    }

    private Duration resolveDuration(Integer durationMinutes) {
        return resolveDuration(durationMinutes == null ? null : Duration.ofMinutes(durationMinutes));
    }

    private Duration resolveDuration(Duration requested) {
        if (requested == null) {
            return properties.getSlotDuration();
        }
        if (requested.isZero() || requested.isNegative()) {
            throw new GuildWorkmanException("Slot duration must be positive");
        }
        if (requested.compareTo(properties.getMaxSlotDuration()) > 0) {
            throw new GuildWorkmanException("Slot duration must not exceed "
                    + properties.getMaxSlotDuration().toMinutes() + " minutes");
        }
        return requested;
    }
}
