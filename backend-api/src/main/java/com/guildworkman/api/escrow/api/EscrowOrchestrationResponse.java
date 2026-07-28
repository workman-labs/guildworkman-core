package com.guildworkman.api.escrow.api;

import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;

import java.time.Instant;

public record EscrowOrchestrationResponse(
        Long id,
        String idempotencyKey,
        String operationType,
        String contractId,
        String operationRef,
        String status,
        String sorobanTxHash,
        String reconciliationStatus,
        int attempts,
        String lastError,
        Instant submittedAt,
        Instant confirmedAt,
        Instant reconciledAt) {

    public static EscrowOrchestrationResponse from(EscrowOrchestrationRequest r) {
        return new EscrowOrchestrationResponse(
                r.getId(),
                r.getIdempotencyKey(),
                r.getOperationType().name(),
                r.getContractId(),
                r.getOperationRef(),
                r.getStatus().name(),
                r.getSorobanTxHash(),
                r.getReconciliationStatus().name(),
                r.getAttempts(),
                r.getLastError(),
                r.getSubmittedAt(),
                r.getConfirmedAt(),
                r.getReconciledAt());
    }
}
