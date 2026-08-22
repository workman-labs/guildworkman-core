package com.guildworkman.api.signing.service;

import com.guildworkman.api.escrow.rpc.GetTransactionResult;
import com.guildworkman.api.escrow.rpc.SendTransactionResult;
import com.guildworkman.api.escrow.rpc.SimulateTransactionResult;
import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.escrow.rpc.SorobanRpcException;
import com.guildworkman.api.signing.SigningProperties;
import com.guildworkman.api.signing.api.SubmitTransactionRequest;
import com.guildworkman.api.signing.custody.SecretRedactor;
import com.guildworkman.api.signing.custody.SigningProviderException;
import com.guildworkman.api.signing.custody.UnknownKeyReferenceException;
import com.guildworkman.api.signing.model.SubmissionFailureReason;
import com.guildworkman.api.signing.model.SubmissionStatus;
import com.guildworkman.api.signing.model.TransactionSubmission;
import com.guildworkman.api.signing.repository.TransactionSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stellar.sdk.AbstractTransaction;
import org.stellar.sdk.FeeBumpTransaction;
import org.stellar.sdk.Transaction;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Signs, submits and sees through to a terminal state every transaction the
 * backend sends to Soroban.
 *
 * <h2>The pipeline</h2>
 * Three independent workers, each claiming its own rows, so a slow ledger
 * never blocks new signing and each phase keeps its own backoff:
 * <ol>
 *   <li>{@link #preparePending()} — {@code PENDING → SIGNED}: leases a channel
 *       account, rebuilds the caller's operations onto it, simulates (Soroban
 *       only), signs, and <b>commits the envelope and its hash</b>.</li>
 *   <li>{@link #broadcastSigned()} — {@code SIGNED → BROADCAST}: asks the
 *       network about the hash first, then sends.</li>
 *   <li>{@link #pollBroadcast()} — {@code BROADCAST → terminal}, fee-bumping
 *       a transaction that stalls.</li>
 * </ol>
 *
 * <h2>Restart safety</h2>
 * The envelope is durable before it is broadcast, never after. That ordering
 * is the whole trick: a process that dies anywhere in phase 2 comes back
 * holding the exact hash it may have sent, and phase 2 begins by asking the
 * network about that hash rather than signing something new. Even a genuine
 * resend cannot double-execute — the same envelope has the same hash, so
 * Soroban RPC answers {@code DUPLICATE}. What the pre-check adds on top is
 * accounting: a transaction that landed while we were away is recognised as
 * confirmed instead of being re-sent and re-polled.
 *
 * <h2>Failure handling</h2>
 * Each failure mode gets the recovery it needs rather than a blanket retry —
 * see {@link SubmissionFailureReason} for the mapping, and
 * {@link #handleSendFailure} for the branches. In particular a
 * {@code txBAD_SEQ} resyncs the channel account against the chain before
 * rebuilding (retrying it unchanged would only reproduce it), while a
 * {@code txINSUFFICIENT_FEE} goes straight to a fee bump.
 *
 * <h2>Why this always terminates</h2>
 * A stalled transaction is fee-bumped, and each bump multiplies the fee until
 * the next one would cross {@code stellar.signing.fee.max-total-stroops}, at
 * which point the submission fails terminally. A transaction whose time
 * bounds lapse can never be included, so the poller stops waiting for a
 * verdict and rebuilds instead. Rebuilds are bounded by
 * {@code stellar.signing.retry.max-attempts}, after which the submission is
 * dead-lettered for an operator. No path polls forever.
 */
@Service
@RequiredArgsConstructor
public class TransactionSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionSubmissionService.class);

    private final TransactionSubmissionRepository submissions;
    private final TransactionSubmissionInserter inserter;
    private final ChannelAccountLeaseService leases;
    private final TransactionAssembler assembler;
    private final TransactionSigner signer;
    private final FailureClassifier failureClassifier;
    private final SorobanRpcClient sorobanRpcClient;
    private final SigningProperties properties;
    private final SigningMetrics metrics;

    // --- API surface --------------------------------------------------------

    /** @param replayed true when {@code submission} already existed for this idempotency key. */
    public record SubmitOutcome(TransactionSubmission submission, boolean replayed) {
    }

    /**
     * Accepts a transaction for signing and submission.
     *
     * <p>The envelope is parsed and the extra signer references are resolved
     * here, synchronously, so a malformed envelope or an unknown key
     * reference comes back as a {@code 400} on the caller's own request
     * instead of surfacing minutes later as a dead-lettered row nobody is
     * watching.
     */
    @Transactional(readOnly = true)
    public SubmitOutcome submit(SubmitTransactionRequest request) {
        assembler.parse(request.unsignedTransactionXdr());
        for (String keyRef : request.extraSignerKeyRefsOrEmpty()) {
            signer.publicKey(keyRef);
        }
        SubmitOutcome outcome = submissions.findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> new SubmitOutcome(existing, true))
                .orElseGet(() -> insertIdempotently(request));
        metrics.submissionAccepted(outcome.replayed());
        return outcome;
    }

    @Transactional(readOnly = true)
    public TransactionSubmission get(Long id) {
        return submissions.findById(id).orElseThrow(() -> new SubmissionNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<TransactionSubmission> findByReference(String reference) {
        return submissions.findByReference(reference);
    }

    private SubmitOutcome insertIdempotently(SubmitTransactionRequest request) {
        try {
            TransactionSubmission created = inserter.insert(request);
            log.info("Transaction submission created id={} reference={}", created.getId(), created.getReference());
            return new SubmitOutcome(created, false);
        } catch (DataIntegrityViolationException ex) {
            // Lost the unique-key race; the nested insert rolled back on its
            // own, so the winner is readable here. This call is a replay too.
            TransactionSubmission winner = submissions.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Submission not found after idempotent-guard violation for key="
                                    + request.idempotencyKey(), ex));
            return new SubmitOutcome(winner, true);
        }
    }

    // --- Phase 1: lease, build, simulate, sign ------------------------------

    @Scheduled(fixedDelayString = "${stellar.signing.prepare-poll-delay-ms:1000}")
    @Transactional
    public void preparePending() {
        if (paused()) {
            return;
        }
        submissions.claimNext(EnumSet.of(SubmissionStatus.PENDING), Instant.now(), PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(entity -> {
                    metrics.phaseAttempt("prepare");
                    prepareOne(entity);
                });
    }

    void prepareOne(TransactionSubmission entity) {
        Transaction source;
        try {
            source = assembler.parse(entity.getUnsignedTransactionXdr());
        } catch (TransactionAssemblyException ex) {
            // The envelope was parseable when it was accepted, so this is a
            // stored-data problem, not a transient one. Terminal.
            fail(entity, SubmissionFailureReason.MALFORMED, ex.getMessage(), false);
            return;
        }

        SequenceLease lease;
        try {
            lease = leases.acquire(entity.getId());
        } catch (NoChannelAccountAvailableException ex) {
            scheduleRetry(entity, SubmissionFailureReason.NO_CHANNEL_ACCOUNT, ex.getMessage(), null);
            return;
        } catch (SorobanRpcException ex) {
            scheduleRetry(entity, SubmissionFailureReason.RPC_ERROR, ex.getMessage(), null);
            return;
        }

        try {
            Transaction draft = assembler.rebuild(source, lease, properties.getFee().getBaseStroops(), null);

            String sorobanData = null;
            if (draft.isSorobanTransaction()) {
                SimulateTransactionResult simulation = sorobanRpcClient.simulateTransaction(
                        draft.toEnvelopeXdrBase64());
                if (!simulation.isSuccess()) {
                    // A contract call that can't succeed must never be signed:
                    // submitting it would burn a fee and a sequence number to
                    // learn what simulation just told us for free.
                    String detail = simulation.requiresRestore()
                            ? "Simulation requires restoring archived ledger entries before this invocation can run"
                            : "Simulation failed: " + simulation.error();
                    fail(entity, SubmissionFailureReason.SIMULATION_FAILED, detail, false);
                    releaseLease(lease.channelAccountId(), false);
                    return;
                }
                sorobanData = simulation.transactionData();
                draft = assembler.rebuild(source, lease, properties.getFee().getBaseStroops(), sorobanData);
            }

            long totalFee = draft.getFee();
            if (totalFee > properties.getFee().getMaxTotalStroops()) {
                fail(entity, SubmissionFailureReason.FEE_CEILING_REACHED,
                        "Transaction fee " + totalFee + " stroops exceeds the ceiling of "
                                + properties.getFee().getMaxTotalStroops() + " stroops", false);
                releaseLease(lease.channelAccountId(), false);
                return;
            }

            signer.sign(draft, lease.keyRef());
            for (String extraKeyRef : extraSignerKeyRefs(entity)) {
                signer.sign(draft, extraKeyRef);
            }

            Instant now = Instant.now();
            entity.setChannelAccountId(lease.channelAccountId());
            entity.setSourceAccount(lease.accountId());
            entity.setKeyRef(lease.keyRef());
            entity.setSequenceNumber(lease.sequenceNumber());
            entity.setFeeStroops(totalFee);
            entity.setSignedEnvelopeXdr(draft.toEnvelopeXdrBase64());
            entity.setTransactionHash(draft.hashHex());
            entity.setInnerTransactionHash(draft.hashHex());
            entity.setValidUntil(maxTime(draft));
            entity.setSigningProvider(signer.providerId());
            entity.setStatus(SubmissionStatus.SIGNED);
            entity.setFailureReason(SubmissionFailureReason.NONE);
            entity.setLastError(null);
            entity.setSignedAt(now);
            entity.setNextAttemptAt(now);
            // THE DURABILITY BOUNDARY. This save is the last statement of the
            // @Transactional `preparePending` tick, so the envelope and its
            // hash are committed when this method returns — and `broadcastOne`
            // runs in a *later*, separate transaction. Nothing in this class
            // can call sendTransaction with an envelope that isn't already on
            // disk, because the only caller of sendTransaction is in phase 2
            // and phase 2 only ever reads rows that are already SIGNED.
            // Pinned by TransactionSubmissionIntegrationTest
            // #theEnvelopeIsCommittedBeforeAnythingIsEverBroadcast.
            submissions.save(entity);

            log.info("Transaction signed id={} sourceAccount={} sequence={} fee={} hash={} provider={}",
                    entity.getId(), entity.getSourceAccount(), entity.getSequenceNumber(), totalFee,
                    entity.getTransactionHash(), entity.getSigningProvider());
        } catch (UnknownKeyReferenceException | SigningProviderException ex) {
            scheduleRetry(entity, SubmissionFailureReason.SIGNING_FAILED, ex.getMessage(), lease.channelAccountId());
        } catch (SorobanRpcException ex) {
            scheduleRetry(entity, SubmissionFailureReason.RPC_ERROR, ex.getMessage(), lease.channelAccountId());
        } catch (TransactionAssemblyException ex) {
            fail(entity, SubmissionFailureReason.MALFORMED, ex.getMessage(), false);
            releaseLease(lease.channelAccountId(), false);
        }
    }

    // --- Phase 2: broadcast -------------------------------------------------

    @Scheduled(fixedDelayString = "${stellar.signing.broadcast-poll-delay-ms:1000}")
    @Transactional
    public void broadcastSigned() {
        if (paused()) {
            return;
        }
        submissions.claimNext(EnumSet.of(SubmissionStatus.SIGNED), Instant.now(), PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(entity -> {
                    metrics.phaseAttempt("broadcast");
                    broadcastOne(entity);
                });
    }

    void broadcastOne(TransactionSubmission entity) {
        try {
            // Restart safety: this row's envelope may already be on the
            // network — from a previous process, or from this one dying after
            // the RPC call and before the commit. Ask before sending.
            GetTransactionResult known = sorobanRpcClient.getTransaction(entity.getTransactionHash());
            if (known.isSuccess()) {
                log.info("Transaction id={} hash={} was already on-chain before broadcast; not resubmitting",
                        entity.getId(), entity.getTransactionHash());
                confirm(entity, known);
                return;
            }
            if (known.isFailed()) {
                onChainFailure(entity, known.resultXdr());
                return;
            }

            SendTransactionResult result = sorobanRpcClient.sendTransaction(entity.getSignedEnvelopeXdr());
            if (result.isAccepted()) {
                Instant now = Instant.now();
                entity.setStatus(SubmissionStatus.BROADCAST);
                entity.setBroadcastAt(now);
                entity.setNextAttemptAt(now);
                entity.setFailureReason(SubmissionFailureReason.NONE);
                entity.setLastError(null);
                submissions.save(entity);
                log.info("Transaction broadcast id={} hash={} rpcStatus={} feeBumps={}",
                        entity.getId(), entity.getTransactionHash(), result.status(), entity.getFeeBumpCount());
            } else if (result.isRetryable()) {
                scheduleRetry(entity, SubmissionFailureReason.RPC_ERROR,
                        "Soroban RPC busy (TRY_AGAIN_LATER)", null);
            } else {
                handleSendFailure(entity, result.errorResultXdr());
            }
        } catch (SorobanRpcException ex) {
            scheduleRetry(entity, SubmissionFailureReason.RPC_ERROR, ex.getMessage(), null);
        }
    }

    /**
     * Applies the recovery path for a transaction the network refused to
     * accept. A refusal at this stage means nothing landed, so no sequence
     * number was consumed — which is exactly why every rebuild here releases
     * its channel account for resync.
     */
    private void handleSendFailure(TransactionSubmission entity, String errorResultXdr) {
        SubmissionFailureReason reason = failureClassifier.classify(errorResultXdr);
        String codeName = failureClassifier.resultCodeName(errorResultXdr);
        String detail = "Soroban RPC rejected the transaction"
                + (codeName == null ? "" : " (" + codeName + ")");
        entity.setResultXdr(errorResultXdr);

        switch (failureClassifier.recoveryFor(reason)) {
            // REBUILD: the envelope is dead either way — its sequence number is
            // wrong, or its window has closed. Rebuilding is the only move that
            // can work, and releasing the account for resync is what stops the
            // next attempt reproducing a BAD_SEQUENCE.
            case REBUILD -> rebuild(entity, reason, detail);
            case FEE_BUMP -> feeBump(entity, detail);
            case TERMINAL -> fail(entity, reason, detail, false);
            case RETRY -> scheduleRetry(entity, reason, detail, null);
        }
    }

    // --- Phase 3: poll to a terminal state, fee-bumping a stall -------------

    @Scheduled(fixedDelayString = "${stellar.signing.confirm-poll-delay-ms:1000}")
    @Transactional
    public void pollBroadcast() {
        if (paused()) {
            return;
        }
        submissions.claimNext(EnumSet.of(SubmissionStatus.BROADCAST), Instant.now(), PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(entity -> {
                    metrics.phaseAttempt("poll");
                    pollOne(entity);
                });
    }

    /**
     * The operator kill switch ({@code stellar.signing.enabled=false}).
     *
     * <p>Checked per tick rather than by conditioning the beans on the
     * property, so it can be flipped by a config refresh without a restart —
     * and so flipping it back resumes from the durable row states rather than
     * from nothing. Submissions keep being accepted while paused; they simply
     * queue as {@code PENDING}.
     */
    private boolean paused() {
        if (properties.isEnabled()) {
            return false;
        }
        log.debug("Submission workers are paused (stellar.signing.enabled=false)");
        return true;
    }

    void pollOne(TransactionSubmission entity) {
        try {
            GetTransactionResult result = sorobanRpcClient.getTransaction(entity.getTransactionHash());
            if (result.isSuccess()) {
                confirm(entity, result);
                return;
            }
            if (result.isFailed()) {
                onChainFailure(entity, result.resultXdr());
                return;
            }

            // NOT_FOUND. After a fee bump there are two hashes in play and the
            // original may be the one that landed.
            if (entity.getFeeBumpCount() > 0 && entity.getInnerTransactionHash() != null
                    && !entity.getInnerTransactionHash().equals(entity.getTransactionHash())) {
                GetTransactionResult inner = sorobanRpcClient.getTransaction(entity.getInnerTransactionHash());
                if (inner.isSuccess()) {
                    log.info("Transaction id={} landed as its pre-fee-bump envelope hash={}",
                            entity.getId(), entity.getInnerTransactionHash());
                    confirm(entity, inner);
                    return;
                }
                if (inner.isFailed()) {
                    onChainFailure(entity, inner.resultXdr());
                    return;
                }
            }

            Instant now = Instant.now();
            if (entity.getValidUntil() != null && now.isAfter(entity.getValidUntil())) {
                // Past its time bounds the network can never include it, so
                // there is no verdict left to wait for. Rebuild rather than
                // poll something that is already dead.
                rebuild(entity, SubmissionFailureReason.TOO_LATE,
                        "Transaction validity window closed at " + entity.getValidUntil() + " without inclusion");
                return;
            }
            if (entity.getBroadcastAt() != null
                    && now.isAfter(entity.getBroadcastAt().plus(properties.getStallAfter()))) {
                feeBump(entity, "Stalled in the mempool for more than " + properties.getStallAfter());
                return;
            }
            entity.setNextAttemptAt(nextAttemptAt(1));
            submissions.save(entity);
        } catch (SorobanRpcException ex) {
            scheduleRetry(entity, SubmissionFailureReason.RPC_ERROR, ex.getMessage(), null);
        }
    }

    /**
     * Wraps the in-flight transaction in a fee bump paying more, and sends it
     * back round phase 2.
     *
     * <p>A fee bump leaves the inner transaction — sequence number,
     * operations, signatures — untouched and only raises what we're willing to
     * pay for inclusion, which is why it's safe to apply to a transaction that
     * may still be in the mempool: whichever envelope lands, the same
     * operations execute exactly once.
     *
     * <p>The fee doubles each time (configurable) and the first bump that
     * would cross the ceiling ends the submission instead. That bound is what
     * turns "stalled" into a terminal state rather than an open-ended one.
     */
    private void feeBump(TransactionSubmission entity, String cause) {
        long currentFee = entity.getFeeStroops() == null
                ? properties.getFee().getBaseStroops()
                : entity.getFeeStroops();
        long ceiling = properties.getFee().getMaxTotalStroops();
        long bumpedFee = Math.max(
                currentFee + properties.getFee().getBaseStroops(),
                Math.round(currentFee * properties.getFee().getBumpMultiplier()));

        if (bumpedFee > ceiling) {
            fail(entity, SubmissionFailureReason.FEE_CEILING_REACHED,
                    cause + "; a bump to " + bumpedFee + " stroops would exceed the ceiling of " + ceiling, false);
            return;
        }

        try {
            AbstractTransaction current = AbstractTransaction.fromEnvelopeXdr(
                    entity.getSignedEnvelopeXdr(), assembler.network());
            Transaction inner = current instanceof FeeBumpTransaction feeBumped
                    ? feeBumped.getInnerTransaction()
                    : (Transaction) current;

            String feeSourceKeyRef = properties.getFeeSourceKeyRef().isBlank()
                    ? entity.getKeyRef()
                    : properties.getFeeSourceKeyRef();
            FeeBumpTransaction bumped = assembler.feeBump(inner, signer.publicKey(feeSourceKeyRef), bumpedFee);
            signer.sign(bumped, feeSourceKeyRef);

            entity.setSignedEnvelopeXdr(bumped.toEnvelopeXdrBase64());
            entity.setTransactionHash(bumped.hashHex());
            entity.setFeeStroops(bumpedFee);
            entity.setFeeBumpCount(entity.getFeeBumpCount() + 1);
            // Back to SIGNED so phase 2 broadcasts it — including the
            // already-on-chain pre-check against the new hash.
            entity.setStatus(SubmissionStatus.SIGNED);
            entity.setFailureReason(SubmissionFailureReason.NONE);
            entity.setLastError(SecretRedactor.redact(cause));
            entity.setNextAttemptAt(Instant.now());
            submissions.save(entity);
            metrics.feeBumped();

            log.info("Transaction fee-bumped id={} innerHash={} newHash={} fee={} bumps={} cause={}",
                    entity.getId(), entity.getInnerTransactionHash(), entity.getTransactionHash(),
                    bumpedFee, entity.getFeeBumpCount(), cause);
        } catch (UnknownKeyReferenceException | SigningProviderException ex) {
            scheduleRetry(entity, SubmissionFailureReason.SIGNING_FAILED, ex.getMessage(), null);
        } catch (TransactionAssemblyException ex) {
            fail(entity, SubmissionFailureReason.MALFORMED, ex.getMessage(), false);
        }
    }

    // --- Terminal transitions ----------------------------------------------

    private void confirm(TransactionSubmission entity, GetTransactionResult result) {
        entity.setStatus(SubmissionStatus.CONFIRMED);
        entity.setFailureReason(SubmissionFailureReason.NONE);
        entity.setLedgerSequence(result.ledger());
        entity.setResultXdr(result.resultXdr());
        entity.setConfirmedAt(Instant.now());
        entity.setLastError(null);
        submissions.save(entity);
        metrics.terminal(SubmissionStatus.CONFIRMED, SubmissionFailureReason.NONE);
        // The transaction reached a ledger, so its sequence number is spent
        // exactly as the pool's counter assumes: the account can go straight
        // back to AVAILABLE without a resync.
        releaseLease(entity.getChannelAccountId(), true);
        log.info("Transaction confirmed id={} hash={} ledger={}",
                entity.getId(), entity.getTransactionHash(), result.ledger());
    }

    /** Included in a ledger and failed there — terminal, because the sequence number is spent. */
    private void onChainFailure(TransactionSubmission entity, String resultXdr) {
        String codeName = failureClassifier.resultCodeName(resultXdr);
        entity.setResultXdr(resultXdr);
        fail(entity, SubmissionFailureReason.ON_CHAIN_FAILED,
                "Transaction failed on-chain" + (codeName == null ? "" : " (" + codeName + ")"), true);
    }

    private void fail(TransactionSubmission entity, SubmissionFailureReason reason, String error,
                      boolean sequenceConsumed) {
        entity.setStatus(SubmissionStatus.FAILED);
        entity.setFailureReason(reason);
        entity.setLastError(SecretRedactor.redact(error));
        submissions.save(entity);
        metrics.terminal(SubmissionStatus.FAILED, reason);
        releaseLease(entity.getChannelAccountId(), sequenceConsumed);
        log.warn("Transaction submission failed id={} reason={} hash={}: {}",
                entity.getId(), reason, entity.getTransactionHash(), entity.getLastError());
    }

    /**
     * Throws the signed envelope away and starts again from
     * {@code PENDING} — a fresh channel account, a fresh sequence number and
     * fresh time bounds. Only ever used for envelopes the network has already
     * refused (or that expired unsent), which is what makes discarding them
     * safe: they can never be included later.
     */
    private void rebuild(TransactionSubmission entity, SubmissionFailureReason reason, String error) {
        entity.setAttempts(entity.getAttempts() + 1);
        if (entity.getAttempts() >= properties.getRetry().getMaxAttempts()) {
            deadLetter(entity, error + " (no attempts left to rebuild)");
            return;
        }
        Long channelAccountId = entity.getChannelAccountId();
        entity.setStatus(SubmissionStatus.PENDING);
        entity.setFailureReason(reason);
        entity.setLastError(SecretRedactor.redact(error));
        entity.setSignedEnvelopeXdr(null);
        entity.setTransactionHash(null);
        entity.setInnerTransactionHash(null);
        entity.setSequenceNumber(null);
        entity.setChannelAccountId(null);
        entity.setSourceAccount(null);
        entity.setFeeStroops(null);
        entity.setFeeBumpCount(0);
        entity.setValidUntil(null);
        entity.setSignedAt(null);
        entity.setBroadcastAt(null);
        entity.setNextAttemptAt(nextAttemptAt(entity.getAttempts()));
        submissions.save(entity);
        // Nothing landed, so the sequence number allocated to the old envelope
        // was skipped: the account has to be resynced before it's used again.
        releaseLease(channelAccountId, false);
        log.info("Transaction submission id={} will be rebuilt attempts={} reason={}: {}",
                entity.getId(), entity.getAttempts(), reason, error);
    }

    private void scheduleRetry(TransactionSubmission entity, SubmissionFailureReason reason, String error,
                               Long channelAccountIdToRelease) {
        entity.setAttempts(entity.getAttempts() + 1);
        if (entity.getAttempts() >= properties.getRetry().getMaxAttempts()) {
            deadLetter(entity, error);
            return;
        }
        entity.setFailureReason(reason);
        entity.setLastError(SecretRedactor.redact(error));
        entity.setNextAttemptAt(nextAttemptAt(entity.getAttempts()));
        submissions.save(entity);
        if (channelAccountIdToRelease != null) {
            releaseLease(channelAccountIdToRelease, false);
        }
        // NO_CHANNEL_ACCOUNT is backpressure, not a fault: under load it is the
        // pool doing its job, and at INFO it would drown the phase out. Every
        // other retryable reason means something outside us broke, so it stays
        // visible without turning on debug logging. The count is on
        // `stellar.signing.terminal`/`phase.attempts` either way.
        if (reason == SubmissionFailureReason.NO_CHANNEL_ACCOUNT) {
            log.debug("Transaction submission id={} waiting for a free channel account attempts={} nextAttemptAt={}",
                    entity.getId(), entity.getAttempts(), entity.getNextAttemptAt());
        } else {
            log.info("Transaction submission id={} retry scheduled attempts={} nextAttemptAt={} reason={}: {}",
                    entity.getId(), entity.getAttempts(), entity.getNextAttemptAt(), reason, entity.getLastError());
        }
    }

    private void deadLetter(TransactionSubmission entity, String error) {
        entity.setStatus(SubmissionStatus.DEAD_LETTER);
        entity.setLastError(SecretRedactor.redact(error));
        submissions.save(entity);
        metrics.terminal(SubmissionStatus.DEAD_LETTER, entity.getFailureReason());
        releaseLease(entity.getChannelAccountId(), false);
        log.warn("Transaction submission id={} moved to DEAD_LETTER after {} attempts: {}",
                entity.getId(), entity.getAttempts(), entity.getLastError());
    }

    private void releaseLease(Long channelAccountId, boolean sequenceConsumed) {
        if (channelAccountId == null) {
            return;
        }
        leases.release(channelAccountId, sequenceConsumed);
    }

    // --- Helpers ------------------------------------------------------------

    private List<String> extraSignerKeyRefs(TransactionSubmission entity) {
        if (entity.getExtraSignerKeyRefs() == null || entity.getExtraSignerKeyRefs().isBlank()) {
            return List.of();
        }
        return Arrays.stream(entity.getExtraSignerKeyRefs().split(","))
                .map(String::trim)
                .filter(ref -> !ref.isEmpty())
                .toList();
    }

    /** The built transaction's {@code maxTime} bound, as an instant. */
    private static Instant maxTime(Transaction transaction) {
        if (transaction.getTimeBounds() == null || transaction.getTimeBounds().getMaxTime() == null) {
            return null;
        }
        long maxTime = transaction.getTimeBounds().getMaxTime().longValue();
        return maxTime == 0 ? null : Instant.ofEpochSecond(maxTime);
    }

    /**
     * Capped exponential backoff with jitter, matching
     * {@code EscrowOrchestrationService}: a batch of submissions that failed
     * together (an RPC outage, say) must not come back in lockstep and
     * reproduce the outage.
     */
    private Instant nextAttemptAt(int attempts) {
        long baseMillis = Math.max(1, properties.getRetry().getBaseDelay().toMillis());
        long capMillis = Math.max(baseMillis, properties.getRetry().getMaxDelay().toMillis());
        int shift = Math.min(Math.max(attempts - 1, 0), 20); // guards against overflow on shift
        long raw = baseMillis << shift;
        long capped = (raw < 0 || raw > capMillis) ? capMillis : raw; // raw < 0 means it overflowed
        double jitter = Math.max(0, properties.getRetry().getJitter());
        double randomOffset = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        long delayMillis = Math.max(1, Math.round(capped * (1.0 + randomOffset)));
        return Instant.now().plusMillis(delayMillis);
    }
}
