package com.guildworkman.api.signing.repository;

import com.guildworkman.api.signing.model.ChannelAccount;
import com.guildworkman.api.signing.model.ChannelAccountStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChannelAccountRepository extends JpaRepository<ChannelAccount, Long> {

    Optional<ChannelAccount> findByAccountId(String accountId);

    Optional<ChannelAccount> findByKeyRef(String keyRef);

    List<ChannelAccount> findByStatus(ChannelAccountStatus status);

    long countByStatus(ChannelAccountStatus status);

    /**
     * Takes the next leasable account with {@code SELECT … FOR UPDATE SKIP
     * LOCKED} — the whole reason concurrent submissions don't collide on a
     * sequence number.
     *
     * <p>Plain {@code FOR UPDATE} (what {@code EscrowOrchestrationRequestRepository.claimNext}
     * uses) would be wrong here. There, blocking is the desired behaviour:
     * competing workers want <em>that</em> row, and waiting for the winner to
     * commit costs nothing but a little latency. Here the workers want
     * <em>any</em> free account, so waiting on a locked row would serialize
     * the pool down to one submission at a time and throw away the parallelism
     * the pool exists to provide. {@code SKIP LOCKED} (the {@code -2} lock
     * timeout hint, which Hibernate renders as Postgres's {@code SKIP LOCKED})
     * makes each concurrent allocator step over accounts another transaction
     * is already holding and take the next one instead.
     *
     * <p>Postgres evaluates the {@code LIMIT} above the locking step, so a
     * one-row page still returns one <em>unlocked</em> row rather than
     * "nothing, because the first row was busy". With N accounts free, N
     * concurrent allocators get N different accounts — and therefore N
     * different sequence numbers.
     *
     * <p>Deadlock-free by construction: every caller locks exactly one row and
     * holds no other channel-account lock while doing so, so there is no
     * wait-cycle to form. Rows in {@code NEEDS_RESYNC} are included because
     * they are leasable — the lease just has to re-read the account's real
     * sequence from the network first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select c from ChannelAccount c where c.status in :statuses order by c.id")
    List<ChannelAccount> claimLeasable(@Param("statuses") List<ChannelAccountStatus> statuses, Pageable pageable);

    /**
     * Leases whose holder never came back — the process died between taking
     * the lease and finishing with it. Reclaimed by
     * {@code ChannelAccountLeaseService#sweepExpiredLeases()}, which checks the
     * holding submission's state before releasing anything.
     */
    @Query("select c from ChannelAccount c where c.status = com.guildworkman.api.signing.model.ChannelAccountStatus.LEASED "
            + "and c.leaseExpiresAt is not null and c.leaseExpiresAt <= :now order by c.leaseExpiresAt")
    List<ChannelAccount> findExpiredLeases(@Param("now") Instant now, Pageable pageable);
}
