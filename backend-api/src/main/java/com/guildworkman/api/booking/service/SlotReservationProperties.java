package com.guildworkman.api.booking.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds the {@code booking.reservation.*} properties. Durations are ISO-8601
 * (e.g. {@code PT5M}) and bound to {@link Duration} by Spring.
 */
@Component
@ConfigurationProperties(prefix = "booking.reservation")
@Getter
@Setter
public class SlotReservationProperties {

    /**
     * How long an unconfirmed hold keeps a slot. Defaults to 5 minutes, matching
     * the TTL the booking calendar in guildworkman-web already used for its
     * client-side lock, so the server-side behaviour a visitor sees is unchanged
     * in duration — only in authority.
     */
    private Duration holdTtl = Duration.ofMinutes(5);

    /**
     * Slot length used when a reservation request doesn't specify one, and when
     * interpreting the end of a legacy {@code Appointment} (which stores only a
     * start time).
     */
    private Duration slotDuration = Duration.ofHours(1);

    /** Upper bound on a caller-supplied slot duration, to keep one request from blocking a whole calendar. */
    private Duration maxSlotDuration = Duration.ofHours(12);

    /** Widest calendar window the availability endpoint will return in one call. */
    private Duration maxAvailabilityWindow = Duration.ofDays(62);

    /** Rows the background expiry sweep claims per pass. */
    private int expirySweepBatchSize = 100;
}
