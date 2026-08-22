package com.guildworkman.api.signing.service;

import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.escrow.rpc.SorobanRpcException;
import com.guildworkman.api.signing.SigningProperties;
import com.guildworkman.api.signing.model.ChannelAccount;
import com.guildworkman.api.signing.model.ChannelAccountStatus;
import com.guildworkman.api.signing.model.TransactionSubmission;
import com.guildworkman.api.signing.repository.ChannelAccountRepository;
import com.guildworkman.api.signing.repository.TransactionSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Allocates sequence numbers safely under concurrency, by leasing channel
 * accounts.
 *
 * <h2>The invariant</h2>
 * A channel account is leased to <b>at most one in-flight submission at a
 * time</b>, and its sequence number is incremented when the lease is taken.
 * Those two facts together give the property the issue asks for: N concurrent
 * submissions get N distinct sequence numbers, because they are necessarily
 * working on N distinct accounts, and each account hands out each number once.
 *
 * <h2>Why leasing, rather than "increment a counter"</h2>
 * Stellar validates a transaction's sequence number as <em>exactly</em> the
 * source account's current sequence plus one. Handing out {@code n} and
 * {@code n+1} concurrently on one account doesn't parallelise anything: the
 * network accepts whichever arrives first and rejects the other with
 * {@code txBAD_SEQ}. Real parallelism has to come from having more accounts,
 * which is what the pool is. {@code SELECT … FOR UPDATE SKIP LOCKED}
 * (see {@link ChannelAccountRepository#claimLeasable}) is what makes taking
 * "any free account" contention-free.
 *
 * <h2>The correction path</h2>
 * A sequence number allocated for a transaction that never lands is
 * <em>skipped</em>, and every later transaction on that account would then be
 * rejected — the local counter has run ahead of the chain. So a lease is only
 * released back to {@link ChannelAccountStatus#AVAILABLE} when the
 * transaction actually reached a ledger (success or on-chain failure both
 * consume the number). Anything else — a fee ceiling hit, expired time
 * bounds, a dead-lettered submission — releases to
 * {@link ChannelAccountStatus#NEEDS_RESYNC}, and the next lease re-reads the
 * account's real sequence from the network before using it. That single rule
 * is what stops one lost transaction from poisoning an account forever.
 */
@Service
@RequiredArgsConstructor
public class ChannelAccountLeaseService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAccountLeaseService.class);

    private static final List<ChannelAccountStatus> LEASABLE =
            List.of(ChannelAccountStatus.AVAILABLE, ChannelAccountStatus.NEEDS_RESYNC);

    private final ChannelAccountRepository channelAccounts;
    private final TransactionSubmissionRepository submissions;
    private final SorobanRpcClient sorobanRpcClient;
    private final SigningProperties properties;
    private final SigningMetrics metrics;

    /**
     * Takes an exclusive lease on a free channel account and allocates its
     * next sequence number.
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}) so the lease is
     * durable the moment it's granted, independently of whatever the caller
     * does next. If the caller's transaction then rolls back, the lease is
     * still committed and simply looks like a crashed holder — reclaimed by
     * {@link #sweepExpiredLeases()} once its TTL passes. The alternative
     * (leasing inside the caller's transaction) would let a rollback silently
     * un-allocate a sequence number that a signed envelope already carries.
     *
     * @throws NoChannelAccountAvailableException if the pool has nothing free, or the chosen account isn't on-chain
     * @throws SorobanRpcException                if a resync couldn't reach the network
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SequenceLease acquire(Long submissionId) {
        ChannelAccount account = channelAccounts.claimLeasable(LEASABLE, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new NoChannelAccountAvailableException(
                        "No channel account is free; " + channelAccounts.countByStatus(ChannelAccountStatus.LEASED)
                                + " leased, " + channelAccounts.countByStatus(ChannelAccountStatus.DISABLED)
                                + " disabled"));

        if (account.getStatus() == ChannelAccountStatus.NEEDS_RESYNC) {
            resyncFromChain(account);
        }

        long sequenceNumber = account.getNextSequence();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getLeaseTtl());

        account.setNextSequence(sequenceNumber + 1);
        account.setStatus(ChannelAccountStatus.LEASED);
        account.setLeasedBySubmissionId(submissionId);
        account.setLeasedAt(now);
        account.setLeaseExpiresAt(expiresAt);
        channelAccounts.save(account);

        metrics.lease(SigningMetrics.LeaseEvent.ACQUIRED);
        log.debug("Channel account leased channelAccountId={} accountId={} sequence={} submissionId={} "
                        + "leaseExpiresAt={} previousStatus=leasable",
                account.getId(), account.getAccountId(), sequenceNumber, submissionId, expiresAt);
        return new SequenceLease(account.getId(), account.getAccountId(), account.getKeyRef(), sequenceNumber,
                expiresAt);
    }

    /**
     * Releases a lease.
     *
     * @param sequenceConsumed whether the transaction reached a ledger. {@code true} for a confirmed transaction
     *                         <em>and</em> for one that failed on-chain (both spend the sequence number);
     *                         {@code false} for anything that never landed, which leaves the account's local
     *                         sequence ahead of the chain and therefore needing a resync.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long channelAccountId, boolean sequenceConsumed) {
        if (channelAccountId == null) {
            return;
        }
        channelAccounts.findById(channelAccountId).ifPresent(account -> {
            if (account.getStatus() != ChannelAccountStatus.LEASED) {
                // Already released — a lease sweep and a worker can both reach
                // this for the same account; releasing twice is a no-op, not a
                // problem, so long as we don't overwrite a fresher lease.
                return;
            }
            // NEEDS_RESYNC is not a diagnostic label — it is the mechanism.
            // `acquire` re-reads the chain for exactly this status before it
            // hands out a number (see the `if (NEEDS_RESYNC) resyncFromChain`
            // above), so releasing unconsumed here is what guarantees the next
            // lease on this account starts from what the network actually
            // believes rather than from a counter that ran ahead of it.
            // Proven end to end by ChannelAccountLeaseIntegrationTest
            // #anUnconsumedSequenceForcesAResyncAndRewindsTheCounter.
            account.setStatus(sequenceConsumed
                    ? ChannelAccountStatus.AVAILABLE
                    : ChannelAccountStatus.NEEDS_RESYNC);
            account.setLeasedBySubmissionId(null);
            account.setLeasedAt(null);
            account.setLeaseExpiresAt(null);
            channelAccounts.save(account);
            metrics.lease(sequenceConsumed
                    ? SigningMetrics.LeaseEvent.RELEASED_CONSUMED
                    : SigningMetrics.LeaseEvent.RELEASED_NEEDS_RESYNC);
            log.debug("Channel account released channelAccountId={} accountId={} status={} sequenceConsumed={} "
                            + "nextSequence={}",
                    account.getId(), account.getAccountId(), account.getStatus(), sequenceConsumed,
                    account.getNextSequence());
        });
    }

    /**
     * Reclaims leases whose holder never came back.
     *
     * <p>Deliberately conservative: a lease whose submission is still
     * non-terminal is <b>not</b> reclaimed, because that transaction may be
     * sitting in the mempool right now and reusing its sequence number would
     * race it. Such a lease only has its TTL pushed out — the submission
     * workers will drive it to a terminal state and release it properly. Only
     * a lease with no active submission behind it (the process died between
     * committing the lease and committing the submission) is released here,
     * and always to {@code NEEDS_RESYNC}, since we can't know whether its
     * sequence number was used.
     */
    @Scheduled(fixedDelayString = "${stellar.signing.lease-sweep-delay-ms:30000}")
    @Transactional
    public void sweepExpiredLeases() {
        Instant now = Instant.now();
        List<ChannelAccount> expired = channelAccounts.findExpiredLeases(now, PageRequest.of(0, 20));
        for (ChannelAccount account : expired) {
            List<TransactionSubmission> active = submissions.findActiveByChannelAccountId(account.getId());
            if (!active.isEmpty()) {
                account.setLeaseExpiresAt(now.plus(properties.getLeaseTtl()));
                channelAccounts.save(account);
                metrics.lease(SigningMetrics.LeaseEvent.EXTENDED);
                log.debug("Channel account lease extended channelAccountId={} activeSubmissions={}",
                        account.getId(), active.size());
                continue;
            }
            account.setStatus(ChannelAccountStatus.NEEDS_RESYNC);
            account.setLeasedBySubmissionId(null);
            account.setLeasedAt(null);
            account.setLeaseExpiresAt(null);
            channelAccounts.save(account);
            metrics.lease(SigningMetrics.LeaseEvent.RECLAIMED);
            log.warn("Reclaimed an orphaned channel-account lease channelAccountId={} accountId={}; "
                    + "sequence will be resynced from the network before reuse",
                    account.getId(), account.getAccountId());
        }
    }

    /**
     * Operator-triggered resync of one account against the chain (see the ADMIN
     * endpoint).
     *
     * <p>Refused while the account is leased. A resync answers "what does the
     * network think this account's sequence is", and mid-lease the honest
     * answer is stale: the leased transaction hasn't landed yet, so the chain
     * still reports the sequence <em>before</em> it — writing that back would
     * hand the next lease a number the in-flight transaction is already using.
     */
    @Transactional
    public ChannelAccount resync(Long channelAccountId) {
        ChannelAccount account = channelAccounts.findById(channelAccountId)
                .orElseThrow(() -> new ChannelAccountNotFoundException(channelAccountId));
        if (account.getStatus() == ChannelAccountStatus.LEASED) {
            throw new ChannelAccountBusyException("Channel account " + channelAccountId
                    + " is currently leased by submission " + account.getLeasedBySubmissionId()
                    + "; a resync now would reuse that submission's sequence number");
        }
        resyncFromChain(account);
        account.setStatus(ChannelAccountStatus.AVAILABLE);
        return channelAccounts.save(account);
    }

    /**
     * Replaces the account's local sequence with the chain's, +1 — the number
     * the next transaction from this account must carry.
     */
    private void resyncFromChain(ChannelAccount account) {
        Long onChainSequence = sorobanRpcClient.getAccountSequence(account.getAccountId());
        if (onChainSequence == null) {
            throw new NoChannelAccountAvailableException("Channel account " + account.getAccountId()
                    + " does not exist on this network — fund it before it can be used");
        }
        account.setNextSequence(onChainSequence + 1);
        account.setLastSyncedAt(Instant.now());
        metrics.lease(SigningMetrics.LeaseEvent.RESYNCED);
        log.info("Channel account resynced channelAccountId={} accountId={} nextSequence={}",
                account.getId(), account.getAccountId(), account.getNextSequence());
    }
}
