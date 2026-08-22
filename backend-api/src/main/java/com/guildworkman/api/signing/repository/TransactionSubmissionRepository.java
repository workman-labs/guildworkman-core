package com.guildworkman.api.signing.repository;

import com.guildworkman.api.signing.model.SubmissionStatus;
import com.guildworkman.api.signing.model.TransactionSubmission;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TransactionSubmissionRepository extends JpaRepository<TransactionSubmission, Long> {

    Optional<TransactionSubmission> findByIdempotencyKey(String idempotencyKey);

    Optional<TransactionSubmission> findByTransactionHash(String transactionHash);

    List<TransactionSubmission> findByReference(String reference);

    long countByStatus(SubmissionStatus status);

    /**
     * Claims due rows for the given statuses via {@code SELECT … FOR UPDATE},
     * exactly as {@code EscrowOrchestrationRequestRepository.claimNext} does:
     * a second worker's select blocks until the first commits, then
     * re-evaluates the {@code WHERE} clause and skips the row it no longer
     * matches. Locking in {@code order by s.id} and claiming a single-row page
     * keeps concurrent callers from forming a wait-cycle.
     *
     * <p>Blocking is the right semantic here (unlike
     * {@code ChannelAccountRepository#claimLeasable}): two workers racing for
     * the same submission must not both process it, and there is no
     * "equivalent other row" to fall back to.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TransactionSubmission s where s.status in :statuses and s.nextAttemptAt <= :now order by s.id")
    List<TransactionSubmission> claimNext(@Param("statuses") Set<SubmissionStatus> statuses,
                                           @Param("now") Instant now, Pageable pageable);

    /** Non-terminal submissions holding a given channel account's lease, used by the lease sweeper. */
    @Query("select s from TransactionSubmission s where s.channelAccountId = :channelAccountId "
            + "and s.status not in (com.guildworkman.api.signing.model.SubmissionStatus.CONFIRMED, "
            + "com.guildworkman.api.signing.model.SubmissionStatus.FAILED, "
            + "com.guildworkman.api.signing.model.SubmissionStatus.DEAD_LETTER)")
    List<TransactionSubmission> findActiveByChannelAccountId(@Param("channelAccountId") Long channelAccountId);
}
