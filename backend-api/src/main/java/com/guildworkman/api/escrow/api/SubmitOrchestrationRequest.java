package com.guildworkman.api.escrow.api;

import com.guildworkman.api.escrow.model.EscrowOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Submits one escrow-contract operation for orchestration.
 *
 * @param idempotencyKey        caller-generated key; resubmitting the same key returns the original request
 * @param operationType         which escrow contract entrypoint this transaction invokes
 * @param contractId             Soroban contract id (strkey, e.g. {@code C...})
 * @param operationRef           appointment id or milestone-escrow id this operation acts on, as a string
 * @param signedTransactionXdr  base64 {@code TransactionEnvelope} XDR, already signed by the caller
 */
public record SubmitOrchestrationRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotNull EscrowOperationType operationType,
        @NotBlank @Size(max = 128) String contractId,
        // No '"' allowed: EscrowReconciliationService matches this value as a
        // literal JSON-quoted substring against ingested event topics, so a
        // stray quote could make it match (or fail to match) unrelated events.
        @NotBlank @Size(max = 128) @Pattern(regexp = "[^\"]*", message = "must not contain '\"'") String operationRef,
        // 8192 chars comfortably covers a realistic single-invoke-host-function
        // envelope (typically well under 2KB base64-encoded) with headroom for
        // multi-operation/fee-bump transactions, while still bounding request
        // size against an oversized/malicious payload.
        @NotBlank @Size(max = 8192)
        @Pattern(regexp = "^[A-Za-z0-9+/]+={0,2}$", message = "must be base64-encoded")
        String signedTransactionXdr) {
}
