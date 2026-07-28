package com.guildworkman.api.escrow.api;

import com.guildworkman.api.escrow.model.EscrowOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @NotBlank @Size(max = 128) String operationRef,
        @NotBlank String signedTransactionXdr) {
}
