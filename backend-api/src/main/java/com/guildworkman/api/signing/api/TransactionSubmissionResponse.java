package com.guildworkman.api.signing.api;

import com.guildworkman.api.signing.model.TransactionSubmission;

import java.time.Instant;

/**
 * Public view of a submission.
 *
 * <p>Everything here is either public chain data (account, sequence, fee,
 * hashes, ledger) or our own bookkeeping. Notably absent, and deliberately:
 * the signed envelope — which carries signatures — and anything resembling
 * key material. {@link #keyRef} is an alias, not a key; see
 * {@code SigningProvider}.
 */
public record TransactionSubmissionResponse(
        Long id,
        String idempotencyKey,
        String reference,
        String status,
        String failureReason,
        String sourceAccount,
        String keyRef,
        String signingProvider,
        Long sequenceNumber,
        Long feeStroops,
        int feeBumpCount,
        String transactionHash,
        String innerTransactionHash,
        Long ledgerSequence,
        int attempts,
        String lastError,
        Instant validUntil,
        Instant createdAt,
        Instant signedAt,
        Instant broadcastAt,
        Instant confirmedAt) {

    public static TransactionSubmissionResponse from(TransactionSubmission submission) {
        return new TransactionSubmissionResponse(
                submission.getId(),
                submission.getIdempotencyKey(),
                submission.getReference(),
                submission.getStatus().name(),
                submission.getFailureReason().name(),
                submission.getSourceAccount(),
                submission.getKeyRef(),
                submission.getSigningProvider(),
                submission.getSequenceNumber(),
                submission.getFeeStroops(),
                submission.getFeeBumpCount(),
                submission.getTransactionHash(),
                submission.getInnerTransactionHash(),
                submission.getLedgerSequence(),
                submission.getAttempts(),
                submission.getLastError(),
                submission.getValidUntil(),
                submission.getCreatedAt(),
                submission.getSignedAt(),
                submission.getBroadcastAt(),
                submission.getConfirmedAt());
    }
}
