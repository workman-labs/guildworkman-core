package com.guildworkman.api.escrow.service;

import com.guildworkman.api.escrow.api.SubmitOrchestrationRequest;
import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.model.ReconciliationStatus;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Submits and confirms escrow-contract operations over Soroban RPC.
 *
 * <p><b>Idempotency</b> — {@link #submit} dedupes on {@code idempotencyKey}:
 * resubmitting the same key returns the original request instead of creating
 * a second one, so a client-side retry of the REST call never double-submits.
 * See {@link EscrowOrchestrationInserter} for why the insert itself needs a
 * nested transaction, and how that compares to an optimistic
 * compare-and-swap.
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
 *
 * <p><b>Backoff</b> — retry delay doubles from {@code retryProperties.baseDelay}
 * each attempt up to {@code retryProperties.maxDelay}, then is randomized by
 * {@code +/- retryProperties.jitter} so a batch of requests that failed
 * together don't all retry in the same instant and hammer Soroban RPC again
 * (a thundering herd). A request that exhausts {@code retryProperties.maxAttempts}
 * moves to {@link OrchestrationStatus#DEAD_LETTER} — an operator needs to
 * look at {@code lastError} and either fix the underlying cause and requeue
 * it, or discard it.
 */
@Service
@RequiredArgsConstructor
public class EscrowOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(EscrowOrchestrationService.class);

    private final EscrowOrchestrationRequestRepository repository;
    private final EscrowOrchestrationInserter inserter;
    private final SorobanRpcClient sorobanRpcClient;
    private final EscrowOrchestrationRetryProperties retryProperties;

    /**
     * @return the (possibly pre-existing) request for {@code request.idempotencyKey()},
     * tagged with whether this call created it or returned an existing one
     * (see {@link SubmitOutcome#replayed()}). Callers that only need the entity
     * can use {@link SubmitOutcome#request()}.
     */
    @Transactional(readOnly = true)
    public SubmitOutcome submit(SubmitOrchestrationRequest request) {
        return repository.findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> new SubmitOutcome(existing, true))
                .orElseGet(() -> insertIdempotently(request));
    }

    /** @param replayed true if {@code request} already existed for this idempotency key. */
    public record SubmitOutcome(EscrowOrchestrationRequest request, boolean replayed) {
    }

    @Transactional(readOnly = true)
    public EscrowOrchestrationRequest get(Long id) {
        return repository.findById(id).orElseThrow(() -> new EscrowOrchestrationNotFoundException(id));
    }

    /**
     * Resets a {@link com.guildworkman.api.escrow.model.ReconciliationStatus#MISMATCHED}
     * request back to {@code PENDING} so {@link EscrowReconciliationService#reconcilePending()}
     * reconsiders it on its next sweep — e.g. after confirming the missing
     * on-chain event has now been ingested. Wraps the manual SQL documented
     * in {@code docs/ESCROW_ORCHESTRATION.md} ("Operations") in an
     * ADMIN-gated endpoint; see {@code EscrowOrchestrationController}.
     */
    @Transactional
    public EscrowOrchestrationRequest requeueReconciliation(Long id) {
        EscrowOrchestrationRequest entity = repository.findById(id)
                .orElseThrow(() -> new EscrowOrchestrationNotFoundException(id));
        if (entity.getReconciliationStatus() != ReconciliationStatus.MISMATCHED) {
            throw new ReconciliationRequeueNotAllowedException(id, entity.getReconciliationStatus());
        }
        entity.setReconciliationStatus(ReconciliationStatus.PENDING);
        entity.setReconciledAt(null);
        repository.save(entity);
        log.info("Escrow orchestration request id={} reconciliation requeued (was MISMATCHED)", id);
        return entity;
    }

    private SubmitOutcome insertIdempotently(SubmitOrchestrationRequest request) {
        try {
            EscrowOrchestrationRequest created = inserter.insert(request);
            log.info("Escrow orchestration request created id={} operationType={} operationRef={}",
                    created.getId(), created.getOperationType(), created.getOperationRef());
            return new SubmitOutcome(created, false);
        } catch (DataIntegrityViolationException ex) {
            // Lost the unique-key race: another concurrent call for the same
            // idempotency key won. The nested REQUIRES_NEW insert rolled back
            // on its own, so the outer (read-only) transaction can still read
            // the winner — this call is a replay too, just one that raced.
            EscrowOrchestrationRequest winner = repository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Orchestration request not found after idempotent-guard violation for key="
                                    + request.idempotencyKey(), ex));
            return new SubmitOutcome(winner, true);
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
                log.info("Escrow orchestration request id={} submitted sorobanTxHash={} attempts={}",
                        entity.getId(), entity.getSorobanTxHash(), entity.getAttempts());
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
                log.info("Escrow orchestration request id={} confirmed sorobanTxHash={} ledger={}",
                        entity.getId(), entity.getSorobanTxHash(), result.ledger());
            } else if (result.isFailed()) {
                // The on-chain transaction was rejected; the signed envelope's
                // sequence number is consumed, so resubmitting it can never
                // succeed. Terminal, not retried.
                entity.setStatus(OrchestrationStatus.FAILED);
                entity.setLastError("Soroban transaction failed on-chain: " + result.resultXdr());
                repository.save(entity);
                log.warn("Escrow orchestration request id={} failed on-chain sorobanTxHash={}",
                        entity.getId(), entity.getSorobanTxHash());
            } else {
                // NOT_FOUND: not yet ingested by RPC's ledger view. Keep polling.
                entity.setAttempts(entity.getAttempts() + 1);
                if (entity.getAttempts() >= retryProperties.getMaxAttempts()) {
                    deadLetter(entity, "Gave up waiting for transaction confirmation after "
                            + retryProperties.getMaxAttempts() + " polls");
                } else {
                    entity.setNextAttemptAt(nextAttemptAt(entity.getAttempts()));
                    repository.save(entity);
                }
            }
        } catch (SorobanRpcException ex) {
            log.warn("Soroban RPC poll failed for orchestration id={} errorClass={}: {}",
                    entity.getId(), ex.getClass().getSimpleName(), ex.getMessage());
            entity.setAttempts(entity.getAttempts() + 1);
            entity.setLastError(ex.getMessage());
            if (entity.getAttempts() >= retryProperties.getMaxAttempts()) {
                deadLetter(entity, ex.getMessage());
            } else {
                entity.setNextAttemptAt(nextAttemptAt(entity.getAttempts()));
                repository.save(entity);
            }
        }
    }

    private void scheduleRetry(EscrowOrchestrationRequest entity, String error) {
        if (entity.getAttempts() >= retryProperties.getMaxAttempts()) {
            deadLetter(entity, error);
            return;
        }
        entity.setLastError(error);
        entity.setStatus(OrchestrationStatus.PENDING);
        entity.setNextAttemptAt(nextAttemptAt(entity.getAttempts()));
        repository.save(entity);
        log.info("Escrow orchestration request id={} retry scheduled attempts={} nextAttemptAt={} cause={}",
                entity.getId(), entity.getAttempts(), entity.getNextAttemptAt(), error);
    }

    private void fail(EscrowOrchestrationRequest entity, String error) {
        entity.setStatus(OrchestrationStatus.FAILED);
        entity.setLastError(error);
        repository.save(entity);
        log.warn("Escrow orchestration request id={} failed: {}", entity.getId(), error);
    }

    /**
     * Terminal: persists the entity itself (unlike {@link #fail}/{@link #scheduleRetry}'s
     * non-terminal branches, this has no caller that does anything further with
     * {@code entity} afterward, so saving here — rather than requiring every
     * call site to remember to — removes a class of "forgot to persist"
     * bugs). {@code nextAttemptAt} is left at its last computed value rather
     * than cleared: it's inert once {@code DEAD_LETTER}, since
     * {@link EscrowOrchestrationRequestRepository#claimNext} only ever
     * selects {@code PENDING}/{@code SUBMITTED} rows, and keeping it records
     * "when the next attempt would have been" for diagnostics.
     */
    private void deadLetter(EscrowOrchestrationRequest entity, String error) {
        entity.setStatus(OrchestrationStatus.DEAD_LETTER);
        entity.setLastError(error);
        repository.save(entity);
        log.warn("Escrow orchestration request id={} moved to DEAD_LETTER after {} attempts: {}",
                entity.getId(), entity.getAttempts(), error);
    }

    /**
     * Backoff doubles from {@code baseDelay} per attempt, capped at
     * {@code maxDelay}, then jittered by {@code +/- jitter} (a fraction of the
     * capped delay) to avoid a thundering herd of retries hitting Soroban RPC
     * at the same instant.
     */
    private Instant nextAttemptAt(int attempts) {
        long baseMillis = Math.max(1, retryProperties.getBaseDelay().toMillis());
        long capMillis = Math.max(baseMillis, retryProperties.getMaxDelay().toMillis());
        int shift = Math.min(Math.max(attempts - 1, 0), 20); // guards against overflow on shift
        long raw = baseMillis << shift;
        long capped = (raw < 0 || raw > capMillis) ? capMillis : raw; // raw < 0 means it overflowed
        double jitter = Math.max(0, retryProperties.getJitter());
        double randomOffset = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        long delayMillis = Math.max(1, Math.round(capped * (1.0 + randomOffset)));
        return Instant.now().plusMillis(delayMillis);
    }
}
