package com.guildworkman.api.booking.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A claim on one time slot of one skilled worker's calendar.
 *
 * <p>Every booking goes through a reservation: the two-step flow takes a
 * short-lived {@link SlotReservationStatus#HELD} row first and confirms it into
 * an appointment later, and the one-step legacy flow
 * ({@code POST /api/v1/client/bookAppointment}) creates an already
 * {@link SlotReservationStatus#CONFIRMED} row inline. That single choke point is
 * what makes "is this slot free?" answerable from one table.
 *
 * <p><b>Why {@link #activeSlotKey} exists.</b> The authoritative guard against
 * double-booking is the {@code SELECT ... FOR UPDATE} taken on the worker's row
 * before the overlap check (see
 * {@link com.guildworkman.api.booking.service.SlotReservationBooker}), which
 * serialises every writer for a given worker. {@code activeSlotKey} is a
 * database-enforced backstop underneath it: it holds
 * {@code "<workerId>@<slotStart>"} while the reservation occupies the slot and
 * is set to {@code null} the moment it stops. Postgres treats {@code NULL}s in a
 * unique index as distinct, so any number of released/expired rows can coexist
 * for the same slot while at most one active row ever can. If a future code
 * path ever forgets the worker lock, the unique index still rejects the second
 * writer rather than silently double-booking.
 *
 * <p>The key can only catch two claims on the <i>identical</i> start instant —
 * partially overlapping claims with different starts (10:00-11:00 vs
 * 10:30-11:30) are caught by the overlap query under the worker lock, not here.
 *
 * <p>{@link #slotStart}/{@link #slotEnd} are {@link LocalDateTime}, matching
 * {@code Appointment.scheduleTime}: they are wall-clock times in the server's
 * zone, as every appointment in this codebase already is. {@link #expiresAt} and
 * the audit timestamps are {@link Instant}s because they are absolute points in
 * time, not calendar positions.
 */
@Entity
@Table(name = "slot_reservations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_slot_reservation_idempotency_key", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_slot_reservation_active_slot", columnNames = "active_slot_key")
        },
        indexes = {
                @Index(name = "idx_slot_reservation_worker_window", columnList = "skilled_worker_id,slot_start"),
                @Index(name = "idx_slot_reservation_expiry", columnList = "status,expires_at"),
                @Index(name = "idx_slot_reservation_appointment", columnList = "appointment_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class SlotReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Caller-generated key. Re-sending the same key returns the original
     * reservation instead of taking a second hold, so a retried HTTP request
     * (or a double-clicked "reserve" button) is harmless.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    /**
     * Stored as a plain id rather than a {@code @ManyToOne SkilledWorker}
     * deliberately: {@code SkilledWorker.appointment} is
     * {@code EAGER + cascade ALL + orphanRemoval}, so holding a managed
     * reference here would drag the worker's whole appointment graph into every
     * reservation read and re-expose the cascade hazards documented in
     * {@code ClientServiceImpl#deleteAppointment}. The service validates the id
     * against {@code SkilledWorkerRepository} on the way in.
     */
    @Column(name = "skilled_worker_id", nullable = false, updatable = false)
    private Long skilledWorkerId;

    /** The client holding the slot. Same rationale as {@link #skilledWorkerId}. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private Long clientId;

    @Column(name = "slot_start", nullable = false, updatable = false)
    private LocalDateTime slotStart;

    /** Exclusive end of the slot: {@code [slotStart, slotEnd)}. */
    @Column(name = "slot_end", nullable = false, updatable = false)
    private LocalDateTime slotEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SlotReservationStatus status = SlotReservationStatus.HELD;

    /** See the class Javadoc. {@code null} exactly when {@link #status} no longer occupies the slot. */
    @Column(name = "active_slot_key", length = 160)
    private String activeSlotKey;

    /** When a {@link SlotReservationStatus#HELD} row stops occupying the slot. Null once confirmed. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Set when the hold is confirmed; links this reservation to the appointment it produced. */
    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    private long version;

    /** The value {@link #activeSlotKey} takes while the slot is occupied. */
    public static String activeSlotKey(Long skilledWorkerId, LocalDateTime slotStart) {
        return skilledWorkerId + "@" + slotStart;
    }

    /**
     * Moves this reservation to a terminal state and frees the slot for other
     * clients by clearing {@link #activeSlotKey}.
     */
    public void releaseAs(SlotReservationStatus terminalStatus, Instant at) {
        this.status = terminalStatus;
        this.activeSlotKey = null;
        this.releasedAt = at;
    }

    /** True for a {@link SlotReservationStatus#HELD} row whose TTL has run out. */
    public boolean isExpiredHold(Instant now) {
        return status == SlotReservationStatus.HELD && expiresAt != null && !expiresAt.isAfter(now);
    }
}
