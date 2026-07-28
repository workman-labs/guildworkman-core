package com.guildworkman.api.escrow.model;

/**
 * The escrow contract entrypoints the orchestration service can submit.
 * Mirrors the subset of {@code EscrowContract} functions (see
 * {@code soroban-contracts/contracts/escrow}) that move funds or change
 * escrow/appointment status on-chain.
 */
public enum EscrowOperationType {
    CREATE_APPOINTMENT,
    CONFIRM_COMPLETION,
    CANCEL_APPOINTMENT,
    RAISE_DISPUTE,
    RESOLVE_DISPUTE,
    RELEASE_MILESTONE_FUNDS
}
