package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.models.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findAllById(Long id);
    Long findByClientId(Long id);
    Optional<Appointment> findById(Long id);

    /**
     * Appointments of one worker that overlap the half-open interval
     * {@code [slotStart, slotEnd)}.
     *
     * <p>An {@code Appointment} records only a {@code scheduleTime}, no end, so
     * each one is treated as occupying {@code [scheduleTime, scheduleTime + slot
     * duration)}. That is expressed as {@code scheduleTime > earliestStart},
     * where the caller passes {@code earliestStart = slotStart - slotDuration}
     * — an appointment starting at or before that point has already finished by
     * {@code slotStart}.
     *
     * <p>Needed because appointments predating the reservation table (and any
     * booked through the legacy one-step endpoint before this feature shipped)
     * have no {@code SlotReservation} row, yet still occupy the worker's
     * calendar. Reservations alone would under-report availability.
     */
    @Query("""
            select a from Appointment a
            where a.skilledWorker.id = :workerId
              and a.status in :statuses
              and a.scheduleTime is not null
              and a.scheduleTime < :slotEnd
              and a.scheduleTime > :earliestStart
            order by a.scheduleTime""")
    List<Appointment> findOverlapping(@Param("workerId") Long workerId,
                                      @Param("earliestStart") LocalDateTime earliestStart,
                                      @Param("slotEnd") LocalDateTime slotEnd,
                                      @Param("statuses") Collection<AppointmentStatus> statuses);
}
