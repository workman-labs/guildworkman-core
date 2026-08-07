package com.guildworkman.api.booking.repository;

import com.guildworkman.api.booking.model.SlotReservation;
import com.guildworkman.api.booking.model.SlotReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SlotReservationRepository extends JpaRepository<SlotReservation, Long> {

    Optional<SlotReservation> findByIdempotencyKey(String idempotencyKey);

    /**
     * Takes the per-worker booking lock: {@code SELECT ... FOR UPDATE} on the
     * worker's own row, which every writer that wants to occupy one of that
     * worker's slots must hold first.
     *
     * <p>This is the primary double-booking guard. Two concurrent requests for
     * the same worker are serialised here — the second one blocks inside
     * Postgres until the first commits or rolls back, and only then runs its
     * own {@link #findOccupying} overlap check, by which time it can see the
     * slot the winner just took. Without it, both requests would run their
     * overlap query against a pre-write snapshot, both see the slot as free,
     * and both insert.
     *
     * <p>The lock is per worker, so bookings for different workers never
     * contend. It cannot deadlock: a booking transaction locks exactly one
     * worker row and never a second, so no wait-cycle between two bookings can
     * form.
     *
     * <p>Native SQL projecting only {@code id} on purpose. Loading the
     * {@code SkilledWorker} entity instead would hydrate its
     * {@code EAGER}/{@code cascade = ALL} appointment collection on a path that
     * only needs a row lock.
     *
     * @return the worker's id if the row exists (and is now locked), empty if
     *         there is no such worker — which doubles as the existence check.
     */
    @Query(value = "SELECT id FROM skilled_workers WHERE id = :workerId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockWorkerForBooking(@Param("workerId") Long workerId);

    /**
     * Active reservations overlapping the half-open interval
     * {@code [slotStart, slotEnd)} for one worker.
     *
     * <p>Expired holds are filtered out here rather than relied on being swept:
     * a hold that ran out of time must stop blocking the slot immediately, not
     * whenever {@code expireStaleHolds} next runs. {@link SlotReservationStatus#CONFIRMED}
     * rows have a null {@code expiresAt} and are therefore never filtered.
     *
     * <p>Callers must already hold {@link #lockWorkerForBooking} for this
     * worker; on its own this query is a read against a snapshot and proves
     * nothing about concurrent writers.
     */
    @Query("""
            select r from SlotReservation r
            where r.skilledWorkerId = :workerId
              and r.status in :statuses
              and r.slotStart < :slotEnd
              and r.slotEnd > :slotStart
              and (r.expiresAt is null or r.expiresAt > :now)
            order by r.slotStart""")
    List<SlotReservation> findOccupying(@Param("workerId") Long workerId,
                                        @Param("slotStart") LocalDateTime slotStart,
                                        @Param("slotEnd") LocalDateTime slotEnd,
                                        @Param("statuses") Collection<SlotReservationStatus> statuses,
                                        @Param("now") Instant now);

    /** Reservation rows still occupying a slot inside a calendar window; powers the availability read. */
    @Query("""
            select r from SlotReservation r
            where r.skilledWorkerId = :workerId
              and r.status in :statuses
              and r.slotStart < :to
              and r.slotEnd > :from
              and (r.expiresAt is null or r.expiresAt > :now)
            order by r.slotStart""")
    List<SlotReservation> findOccupyingWindow(@Param("workerId") Long workerId,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              @Param("statuses") Collection<SlotReservationStatus> statuses,
                                              @Param("now") Instant now);

    /**
     * Locks one reservation row for a state transition (confirm/release), so
     * two concurrent confirms of the same hold can't both create an appointment.
     * The loser blocks, then re-reads a row that is already {@code CONFIRMED}
     * and returns the first confirm's appointment instead of making a second.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SlotReservation r where r.id = :id")
    Optional<SlotReservation> findByIdForUpdate(@Param("id") Long id);

    /**
     * Frees every hold for one worker whose TTL has elapsed, clearing
     * {@code activeSlotKey} so the slot's unique index no longer rejects a new
     * claim. Run under {@link #lockWorkerForBooking} on the reserve path, which
     * is what stops an already-dead hold from blocking a fresh booking even if
     * the background sweep hasn't caught it yet.
     *
     * <p>Deliberately <b>not</b> {@code clearAutomatically}. This runs inside
     * whatever transaction the caller brought — including
     * {@code ClientServiceImpl#bookAppointment}, which is mid-way through
     * building an appointment — and clearing the persistence context would
     * detach that caller's own entities out from under it. It isn't needed
     * either: the only read that follows is {@link #findOccupying}, which
     * excludes lapsed holds in SQL regardless of what the context still
     * believes about them, and no lapsed hold is loaded as an entity on this
     * path (so none can dirty-check its stale state back over the update).
     *
     * @return how many holds were expired
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update SlotReservation r
            set r.status = com.guildworkman.api.booking.model.SlotReservationStatus.EXPIRED,
                r.activeSlotKey = null,
                r.releasedAt = :now
            where r.skilledWorkerId = :workerId
              and r.status = com.guildworkman.api.booking.model.SlotReservationStatus.HELD
              and r.expiresAt <= :now""")
    int expireHoldsForWorker(@Param("workerId") Long workerId, @Param("now") Instant now);

    /**
     * Claims a page of expired holds across all workers for the background
     * sweep, via {@code SELECT ... FOR UPDATE} so two application instances
     * polling the same table never process the same row. Ordered by id so
     * concurrent sweepers lock rows in the same sequence and cannot deadlock
     * against each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from SlotReservation r
            where r.status = com.guildworkman.api.booking.model.SlotReservationStatus.HELD
              and r.expiresAt <= :now
            order by r.id""")
    List<SlotReservation> claimExpiredHolds(@Param("now") Instant now, Pageable pageable);

    /** The reservation an appointment came from, so cancelling/deleting it can free the slot. */
    List<SlotReservation> findByAppointmentIdAndStatus(Long appointmentId, SlotReservationStatus status);
}
