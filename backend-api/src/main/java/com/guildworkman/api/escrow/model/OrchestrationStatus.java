package com.guildworkman.api.escrow.model;

/**
 * Lifecycle of a single {@link EscrowOrchestrationRequest}.
 *
 * <pre>
 * PENDING --submit--> SUBMITTED --poll(SUCCESS)--> CONFIRMED
 *    |                    |
 *    | (RPC errors,       | poll(FAILED on-chain)
 *    |  retries exhausted) v
 *    +----------------> FAILED
 *    |
 *    v (retries exhausted before ever reaching SUBMITTED)
 * DEAD_LETTER
 * </pre>
 */
public enum OrchestrationStatus {
    PENDING, SUBMITTED, CONFIRMED, FAILED, DEAD_LETTER
}
