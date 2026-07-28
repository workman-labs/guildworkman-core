package com.guildworkman.api.escrow.service;

import com.guildworkman.api.escrow.api.SubmitOrchestrationRequest;
import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.repository.EscrowOrchestrationRequestRepository;
import com.guildworkman.api.escrow.rpc.GetTransactionResult;
import com.guildworkman.api.escrow.rpc.SendTransactionResult;
import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.escrow.rpc.SorobanRpcException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;

/**
 * Submits and confirms escrow-contract operations over Soroban RPC.
 *
 * <p><b>Idempotency</b> — {@link #submit} dedupes on {@code idempotencyKey}:
 * resubmitting the same key returns the original request instead of creating
 * a second one, so a client-side retry of the REST call never double-submits.
 *
 * <p><b>Exactly-once</b> — is achieved compositely, not by any single lock:
 * <ol>
 *   <li>the idempotency key stops duplicate rows being created for the same
 *       logical request;</li>
 *   <li>{@link #submitPending()} only ever claims {@code PENDING} rows (via a
 *       {@code SELECT ... FOR UPDATE}-backed query), so a given row's signed
 *       envelope is handed to {@code sendTransaction} at most once per
 *       process attempt;</li>
 *   <li>even if a crash happens between the RPC call succeeding and the row
 *       being committed, Soroban RPC itself dedupes by the envelope's own
 *       hash — resubmitting identical XDR comes back {@code DUPLICATE} with
 *       the same hash rather than executing twice.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class EscrowOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(EscrowOrchestrationService.class);

    static final int MAX_ATTEMPTS = 5;

    private final EscrowOrchestrationRequestRepository repository;
    private final EscrowOrchestrationInserter inserter;
    private final SorobanRpcClient sorobanRpcClient;

    @Transactional(readOnly = true)
    public EscrowOrchestrationRequest submit(SubmitOrchestrationRequest request) {
        return repository.findByIdempotencyKey(request.idempotencyKey())
                .orElseGet(() -> insertIdempotently(request));
    }

    @Transactional(readOnly = true)
    public EscrowOrchestrationRequest get(Long id) {
        return repository.findById(id).orElseThrow(() -> new EscrowOrchestrationNotFoundException(id));
    }

    private EscrowOrchestrationRequest insertIdempotently(SubmitOrchestrationRequest request) {
        try {
            return inserter.insert(request);
        } catch (DataIntegrityViolationException ex) {
            // Nested REQUIRES_NEW insert rolled back; outer TX can still read the winner.
            return repository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Orchestration request not found after idempotent-guard violation for key="
                                    + request.idempotencyKey(), ex));
        }
    }

    @Scheduled(fixedDelayString = "${escrow.orchestration.submit-poll-delay-ms:1000}")
    @Transactional
    public void submitPending() {
        repository.claimNext(EnumSet.of(OrchestrationStatus.PENDING), Instant.now(), PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(this::submitOne);
    }

    void submitOne(EscrowOrchestrationRequest entity) {
        entity.setAttempts(entity.getAttempts() + 1);
        try {
            SendTransactionResult result = sorobanRpcClient.sendTransaction(entity.getSignedTransactionXdr());
            if (result.isAccepted()) {
                entity.setSorobanTxHash(result.hash());
                entity.setStatus(OrchestrationStatus.SUBMITTED);
                entity.setSubmittedAt(Instant.now());
                entity.setNextAttemptAt(Instant.now());
                entity.setLastError(null);
                repository.save(entity);
            } else if (result.isRetryable()) {
                scheduleRetry(entity, "Soroban RPC busy (TRY_AGAIN_LATER)");
            } else {
                fail(entity, "Soroban RPC rejected transaction: " + result.errorResultXdr());
            }
        } catch (SorobanRpcException ex) {
            scheduleRetry(entity, ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${escrow.orchestration.confirm-poll-delay-ms:1000}")
    @Transactional
    public void pollSubmitted() {
        repository.claimNext(EnumSet.of(OrchestrationStatus.SUBMITTED), Instant.now(), PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(this::pollOne);
    }

    void pollOne(EscrowOrchestrationRequest entity) {
        try {
            GetTransactionResult result = sorobanRpcClient.getTransaction(entity.getSorobanTxHash());
            if (result.isSuccess()) {
                entity.setStatus(OrchestrationStatus.CONFIRMED);
                entity.setConfirmedAt(Instant.now());
                entity.setLastError(null);
                repository.save(entity);
            } else if (result.isFailed()) {
                // The on-chain transaction was rejected; the signed envelope's
                // sequence number is consumed, so resubmitting it can never
                // succeed. Terminal, not retried.
                entity.setStatus(OrchestrationStatus.FAILED);
                entity.setLastError("Soroban transaction failed on-chain: " + result.resultXdr());
                repository.save(entity);
            } else {
                // NOT_FOUND: not yet ingested by RPC's ledger view. Keep polling.
                entity.setAttempts(entity.getAttempts() + 1);
                if (entity.getAttempts() >= MAX_ATTEMPTS) {
                    entity.setStatus(OrchestrationStatus.DEAD_LETTER);
                    entity.setLastError("Gave up waiting for transaction confirmation after " + MAX_ATTEMPTS + " polls");
                } else {
                    entity.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(entity.getAttempts())));
                }
                repository.save(entity);
            }
        } catch (SorobanRpcException ex) {
            log.warn("Soroban RPC poll failed for orchestration id={}: {}", entity.getId(), ex.getMessage());
            entity.setAttempts(entity.getAttempts() + 1);
            entity.setLastError(ex.getMessage());
            if (entity.getAttempts() >= MAX_ATTEMPTS) {
                entity.setStatus(OrchestrationStatus.DEAD_LETTER);
            } else {
                entity.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(entity.getAttempts())));
            }
            repository.save(entity);
        }
    }

    private void scheduleRetry(EscrowOrchestrationRequest entity, String error) {
        entity.setLastError(error);
        if (entity.getAttempts() >= MAX_ATTEMPTS) {
            entity.setStatus(OrchestrationStatus.DEAD_LETTER);
        } else {
            entity.setStatus(OrchestrationStatus.PENDING);
            entity.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(entity.getAttempts())));
        }
        repository.save(entity);
    }

    private void fail(EscrowOrchestrationRequest entity, String error) {
        entity.setStatus(OrchestrationStatus.FAILED);
        entity.setLastError(error);
        repository.save(entity);
    }

    private static long backoffSeconds(int attempts) {
        return 1L << Math.min(attempts, 6);
    }
}
