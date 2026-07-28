package com.guildworkman.api.chain.repository;

import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface OnChainEventRepository extends JpaRepository<OnChainEvent, Long> {
    Optional<OnChainEvent> findByEventKey(String eventKey);
    boolean existsByEventKey(String eventKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OnChainEvent e where e.status in :statuses and e.nextAttemptAt <= :now order by e.contractId, e.ledger, e.eventIndex, e.id")
    List<OnChainEvent> claimNext(@Param("statuses") Set<ChainEventStatus> statuses, @Param("now") Instant now, Pageable pageable);
    List<OnChainEvent> findByLedgerBetweenOrderByContractIdAscLedgerAscEventIndexAsc(long fromLedger, long toLedger);
    long countByStatus(ChainEventStatus status);
    List<OnChainEvent> findByContractIdAndTopicsContaining(String contractId, String topicFragment);
}
