package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.api.SubmitTransactionRequest;
import com.guildworkman.api.signing.model.SubmissionFailureReason;
import com.guildworkman.api.signing.model.SubmissionStatus;
import com.guildworkman.api.signing.model.TransactionSubmission;
import com.guildworkman.api.signing.repository.TransactionSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Isolated insert so a unique-key race on {@code idempotency_key} aborts only
 * this nested transaction, leaving the caller's able to re-read the winner.
 * Same pattern, and the same reasoning, as {@code EscrowOrchestrationInserter}
 * and {@code ChainEventInserter}: only a database unique index can atomically
 * pick one winner among concurrent requests carrying the same key, and
 * {@code REQUIRES_NEW} is what makes losing that race cost nothing but a
 * re-read.
 *
 * <p>Idempotency matters more here than it does for those two. A duplicate
 * row in this table wouldn't merely duplicate bookkeeping — it would lease a
 * second channel account, consume a second sequence number and put a second
 * transaction on-chain for one logical request.
 */
@Service
@RequiredArgsConstructor
public class TransactionSubmissionInserter {

    private final TransactionSubmissionRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionSubmission insert(SubmitTransactionRequest request) {
        TransactionSubmission entity = new TransactionSubmission();
        entity.setIdempotencyKey(request.idempotencyKey());
        entity.setReference(request.reference());
        entity.setUnsignedTransactionXdr(request.unsignedTransactionXdr());
        if (!request.extraSignerKeyRefsOrEmpty().isEmpty()) {
            entity.setExtraSignerKeyRefs(String.join(",", request.extraSignerKeyRefsOrEmpty()));
        }
        entity.setStatus(SubmissionStatus.PENDING);
        entity.setFailureReason(SubmissionFailureReason.NONE);
        entity.setNextAttemptAt(Instant.now());
        return repository.saveAndFlush(entity);
    }
}
