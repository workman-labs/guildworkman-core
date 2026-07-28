package com.guildworkman.api.escrow.service;

import com.guildworkman.api.escrow.api.SubmitOrchestrationRequest;
import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.model.ReconciliationStatus;
import com.guildworkman.api.escrow.repository.EscrowOrchestrationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Isolated insert so a unique-key race on {@code idempotency_key} aborts only
 * this nested transaction (Postgres), leaving the caller's transaction able
 * to re-read the winner. Mirrors {@code ChainEventInserter}.
 */
@Service
@RequiredArgsConstructor
public class EscrowOrchestrationInserter {
    private final EscrowOrchestrationRequestRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EscrowOrchestrationRequest insert(SubmitOrchestrationRequest request) {
        EscrowOrchestrationRequest entity = new EscrowOrchestrationRequest();
        entity.setIdempotencyKey(request.idempotencyKey());
        entity.setOperationType(request.operationType());
        entity.setContractId(request.contractId());
        entity.setOperationRef(request.operationRef());
        entity.setSignedTransactionXdr(request.signedTransactionXdr());
        entity.setStatus(OrchestrationStatus.PENDING);
        entity.setReconciliationStatus(ReconciliationStatus.PENDING);
        entity.setNextAttemptAt(Instant.now());
        return repository.saveAndFlush(entity);
    }
}
