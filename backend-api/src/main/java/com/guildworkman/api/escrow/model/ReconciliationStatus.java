package com.guildworkman.api.escrow.model;

/**
 * Result of comparing a {@link OrchestrationStatus#CONFIRMED} request against
 * the ingested on-chain event stream (see {@code com.guildworkman.api.chain}).
 */
public enum ReconciliationStatus {
    /** Not yet due for reconciliation, or awaiting the indexer to catch up. */
    PENDING,
    /** A corresponding processed on-chain event was found. */
    MATCHED,
    /** No corresponding on-chain event appeared within the reconciliation window. */
    MISMATCHED
}
