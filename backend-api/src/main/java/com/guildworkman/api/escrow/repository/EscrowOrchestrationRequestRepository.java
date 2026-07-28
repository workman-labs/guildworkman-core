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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EscrowOrchestrationRequest r where r.status in :statuses and r.nextAttemptAt <= :now order by r.id")
    List<EscrowOrchestrationRequest> claimNext(@Param("statuses") Set<OrchestrationStatus> statuses,
                                                @Param("now") Instant now, Pageable pageable);

    List<EscrowOrchestrationRequest> findByStatusAndReconciliationStatus(
            OrchestrationStatus status, ReconciliationStatus reconciliationStatus, Pageable pageable);

    long countByStatus(OrchestrationStatus status);
}
