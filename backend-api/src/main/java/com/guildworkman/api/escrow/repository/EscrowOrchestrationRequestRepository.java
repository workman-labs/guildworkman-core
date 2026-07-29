package com.guildworkman.api.escrow.repository;

import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.model.ReconciliationStatus;
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

public interface EscrowOrchestrationRequestRepository extends JpaRepository<EscrowOrchestrationRequest, Long> {

    Optional<EscrowOrchestrationRequest> findByIdempotencyKey(String idempotencyKey);

    /**
     * Claims up to {@code pageable}'s page size of due rows for the given
     * statuses via {@code SELECT ... FOR UPDATE}, so two schedulers (e.g. two
     * app instances polling the same table) can never claim the same row: the
     * second transaction's {@code SELECT} simply blocks until the first
     * commits or rolls back, then re-evaluates the {@code WHERE} clause and
     * (having missed the now-claimed row) returns nothing for it — no
     * exception, no explicit retry needed by the caller.
     *
     * <p>This can't deadlock against {@link EscrowOrchestrationInserter#insert},
     * which only ever inserts new rows and never locks an existing one. It
     * also can't deadlock against itself: every caller locks rows in the same
     * {@code order by r.id}, so two concurrent callers each waiting on a
     * single-row page (as {@link com.guildworkman.api.escrow.service.EscrowOrchestrationService}
     * uses it) can never form a wait-cycle. A caller that widens the page
     * size and processes rows out of id order would reintroduce that risk.
     *
     * <p>A stuck row (its transaction holding the lock indefinitely — e.g. a
     * hung Soroban RPC call inside the same transaction) would simply be
     * skipped by other callers until Postgres's own
     * {@code lock_timeout}/{@code statement_timeout} intervenes; the
     * scheduled callers here don't set an explicit lock-wait timeout and rely
     * on {@link com.guildworkman.api.escrow.rpc.SorobanRpcProperties#getRequestTimeout()}
     * to bound how long that transaction can stay open in the first place.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EscrowOrchestrationRequest r where r.status in :statuses and r.nextAttemptAt <= :now order by r.id")
    List<EscrowOrchestrationRequest> claimNext(@Param("statuses") Set<OrchestrationStatus> statuses,
                                                @Param("now") Instant now, Pageable pageable);

    List<EscrowOrchestrationRequest> findByStatusAndReconciliationStatus(
            OrchestrationStatus status, ReconciliationStatus reconciliationStatus, Pageable pageable);

    long countByStatus(OrchestrationStatus status);
}
