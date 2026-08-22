package com.guildworkman.api.signing.model;

/**
 * Why a submission failed — the machine-readable half of the error contract,
 * returned alongside a human-readable {@code lastError}.
 *
 * <p>The Soroban/Horizon failure modes the issue calls out each land on their
 * own constant because each has its own recovery path, and conflating them
 * produces exactly the wrong response: retrying a {@code txBAD_SEQ} without
 * resyncing the sequence just reproduces it, while <em>not</em> retrying a
 * {@code txINSUFFICIENT_FEE} abandons a transaction that a fee bump would have
 * landed. See {@code TransactionSubmissionService} for the handling and
 * {@code FailureClassifier} for how a {@code TransactionResult} XDR becomes
 * one of these.
 */
public enum SubmissionFailureReason {

    /** No failure recorded. */
    NONE,

    /** Simulation rejected the transaction before it was ever signed. Terminal: the contract call itself is wrong. */
    SIMULATION_FAILED,

    /** {@code txBAD_SEQ} — our sequence number disagreed with the chain. Resync the channel account and rebuild. */
    BAD_SEQUENCE,

    /** {@code txINSUFFICIENT_FEE} — outbid in the mempool. Fee-bump immediately, up to the ceiling. */
    INSUFFICIENT_FEE,

    /**
     * {@code txTOO_LATE} — the time bounds expired before inclusion. Rebuild with fresh bounds. Also covers
     * {@code txTOO_EARLY}, which shouldn't arise (this service never sets a minimum time) but has the same fix.
     */
    TOO_LATE,

    /** A fee bump would have crossed {@code stellar.signing.fee.max-total-stroops}. Terminal by policy. */
    FEE_CEILING_REACHED,

    /** {@code txMALFORMED}/{@code txMISSING_OPERATION}/{@code txSOROBAN_INVALID} — the envelope is wrong. Terminal. */
    MALFORMED,

    /** {@code txBAD_AUTH}/{@code txBAD_AUTH_EXTRA} — signatures didn't satisfy the account's signers. Terminal. */
    BAD_AUTH,

    /** {@code txINSUFFICIENT_BALANCE}/{@code txNO_ACCOUNT} — the channel account can't pay. Terminal until funded. */
    INSUFFICIENT_BALANCE,

    /** Included in a ledger and failed there. Terminal — the sequence number is spent. */
    ON_CHAIN_FAILED,

    /** The pool had no free channel account. Retried, not failed, until attempts run out. */
    NO_CHANNEL_ACCOUNT,

    /** The custody backend could not sign. Retried while attempts remain. */
    SIGNING_FAILED,

    /** Soroban RPC was unreachable or errored. Retried while attempts remain. */
    RPC_ERROR,

    /** A result code we don't have a specific recovery path for. Retried while attempts remain. */
    UNKNOWN
}
