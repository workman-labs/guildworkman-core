package com.guildworkman.api.signing.model;

/**
 * Lifecycle of one channel account in the pool.
 *
 * <pre>
 *   AVAILABLE ──lease──▶ LEASED ──confirmed/on-chain failure──▶ AVAILABLE
 *       ▲                   │
 *       │                   └──anything that didn't consume the sequence──▶ NEEDS_RESYNC
 *       └──resync from chain──────────────────────────────────────────────────┘
 * </pre>
 */
public enum ChannelAccountStatus {

    /** Free to lease; {@code nextSequence} is believed to match the chain. */
    AVAILABLE,

    /** Exclusively held by one in-flight submission — the invariant that keeps sequence numbers gap-free. */
    LEASED,

    /**
     * The account's sequence may have drifted from the chain (a transaction we
     * allocated a sequence for never landed, or a {@code txBAD_SEQ} came back).
     * Still leasable, but the next lease re-reads the real sequence from the
     * network first.
     */
    NEEDS_RESYNC,

    /** Taken out of the pool by an operator; never leased. */
    DISABLED
}
