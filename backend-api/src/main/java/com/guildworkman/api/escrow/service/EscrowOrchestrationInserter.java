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
 *
 * <p><b>Why a unique constraint + {@code REQUIRES_NEW} instead of optimistic
 * compare-and-swap?</b> A CAS (read-then-conditional-insert-if-absent, or an
 * application-level "check then insert") has a race window between the read
 * and the write: two concurrent requests for the same new
 * {@code idempotencyKey} can both observe "not present" and both attempt to
 * insert, defeating the whole point of idempotency. The database's unique
 * index is the only thing that can atomically decide a single winner across
 * concurrent transactions. {@code REQUIRES_NEW} is what makes losing that
 * race *cheap*: without it, the {@link org.springframework.dao.DataIntegrityViolationException}
 * would poison the caller's own (potentially larger) transaction, forcing a
 * full rollback of unrelated work just to read the winning row back.
 *
 * <p><b>Deadlocks / lock contention.</b> This method only ever inserts a new
 * row — Postgres does not need to acquire a row lock on anything already
 * committed, so it cannot deadlock against {@link EscrowOrchestrationRequestRepository#claimNext}
 * (which pessimistically locks *existing* {@code PENDING}/{@code SUBMITTED}
 * rows). The only contention here is the unique index itself, which
 * Postgres resolves by blocking the second inserter until the first commits
 * or rolls back, then failing it with a unique-violation rather than
 * deadlocking. Callers of {@link EscrowOrchestrationService#submit} don't
 * need to retry on that failure themselves — it's caught and turned into a
 * lookup of the winning row inside {@code submit} itself.
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
