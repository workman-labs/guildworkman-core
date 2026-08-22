package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.model.SubmissionFailureReason;
import com.guildworkman.api.signing.model.SubmissionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for the submission pipeline, scraped at
 * {@code /actuator/prometheus}.
 *
 * <p>These exist because the interesting failures here are the quiet ones.
 * A dead-lettered submission and a stalled one look identical from outside:
 * nothing happens. So the counters are deliberately keyed on the things an
 * operator would want an alert on — the rate of fee bumps (the network is
 * congested, or the ceiling is about to start biting), terminal states by
 * reason (a rising {@code SIGNING_FAILED} is a custody outage, a rising
 * {@code NO_CHANNEL_ACCOUNT} is a pool that needs more accounts), and lease
 * reclaims (a process is dying mid-submission).
 *
 * <p>Tag values are all closed sets — enum names and a fixed vocabulary of
 * phases and lease events — so the cardinality of these series is bounded by
 * the code, not by traffic. Nothing caller-supplied (no idempotency key, no
 * reference, no account) is ever a tag.
 */
@Component
@RequiredArgsConstructor
public class SigningMetrics {

    static final String SUBMISSIONS = "stellar.signing.submissions";
    static final String PHASE_ATTEMPTS = "stellar.signing.phase.attempts";
    static final String FEE_BUMPS = "stellar.signing.fee.bumps";
    static final String TERMINAL = "stellar.signing.terminal";
    static final String LEASES = "stellar.signing.leases";

    /** Lease lifecycle events, {@link #LEASES}' {@code event} tag. */
    public enum LeaseEvent {
        /** A submission took a channel account and a sequence number. */
        ACQUIRED,
        /** Released after the transaction reached a ledger — the sequence number was spent. */
        RELEASED_CONSUMED,
        /** Released without landing — the account's counter is ahead of the chain and needs a resync. */
        RELEASED_NEEDS_RESYNC,
        /** The sweeper reclaimed a lease whose holder never came back. */
        RECLAIMED,
        /** The sweeper found an expired lease whose submission is still in flight, and extended it. */
        EXTENDED,
        /** An account's sequence number was re-read from the chain. */
        RESYNCED
    }

    private final MeterRegistry registry;

    /** @param replayed whether this was an idempotent replay rather than a new submission. */
    public void submissionAccepted(boolean replayed) {
        counter(SUBMISSIONS, "outcome", replayed ? "replayed" : "created").increment();
    }

    /** @param phase {@code prepare}, {@code broadcast} or {@code poll}. */
    public void phaseAttempt(String phase) {
        counter(PHASE_ATTEMPTS, "phase", phase).increment();
    }

    public void feeBumped() {
        registry.counter(FEE_BUMPS).increment();
    }

    /** A submission reached a state it will never leave on its own. */
    public void terminal(SubmissionStatus status, SubmissionFailureReason reason) {
        registry.counter(TERMINAL, "status", status.name(), "reason", reason.name()).increment();
    }

    public void lease(LeaseEvent event) {
        counter(LEASES, "event", event.name()).increment();
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return registry.counter(name, tagKey, tagValue);
    }
}
