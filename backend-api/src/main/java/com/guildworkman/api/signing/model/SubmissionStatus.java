package com.guildworkman.api.signing.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one transaction submission.
 *
 * <pre>
 *   PENDING ──lease + simulate + sign──▶ SIGNED ──sendTransaction──▶ BROADCAST
 *      ▲                                                               │
 *      │                                                   getTransaction
 *      │                                                               ▼
 *      └──rebuild (txBAD_SEQ / txTOO_LATE)──── CONFIRMED / FAILED / DEAD_LETTER
 * </pre>
 *
 * <p>{@link #SIGNED} is the state that makes restart safety possible: the
 * signed envelope and its hash are committed <em>before</em> the envelope is
 * ever handed to the network, so a process that dies mid-broadcast comes back
 * knowing exactly what it may have already sent, and asks the network about
 * that hash instead of signing something new.
 */
public enum SubmissionStatus {

    /** Accepted from the caller; no channel account leased and nothing signed yet. */
    PENDING,

    /** Built, simulated and signed; the envelope and hash are durable. May or may not have been broadcast. */
    SIGNED,

    /** Handed to Soroban RPC and accepted into the mempool. Awaiting inclusion in a ledger. */
    BROADCAST,

    /** Included in a ledger and succeeded. Terminal. */
    CONFIRMED,

    /** Rejected, or included and failed on-chain, with no recovery path left. Terminal. */
    FAILED,

    /** Retries exhausted without reaching a decision. Terminal, and an operator's problem. */
    DEAD_LETTER;

    private static final Set<SubmissionStatus> TERMINAL = EnumSet.of(CONFIRMED, FAILED, DEAD_LETTER);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
