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
    /**
     * Used by {@code EscrowReconciliationService} to find events tagged with
     * a given operation ref. {@code topics} is a JSON-encoded string column
     * (see {@code ChainEventInserter}), not a normalized list, so this is a
     * {@code contract_id = ? AND topics LIKE '%' || ? || '%'} scan — the
     * {@code idx_chain_event_stream_order}/{@code idx_chain_event_status}
     * indexes narrow by {@code contract_id} but can't help with the
     * {@code LIKE} itself, so it's a sequential scan over every event for
     * that contract. Fine at today's volumes; if a single contract's event
     * count grows large enough for this to show up in slow-query logs, the
     * fix is a normalized child table (one row per {@code (event_id, topic)}
     * pair, indexed on {@code topic}) or a Postgres {@code jsonb} column with
     * a GIN index — not a bigger {@code LIKE} index, which Postgres can't use
     * for a leading-wildcard search anyway.
     */
    List<OnChainEvent> findByContractIdAndTopicsContaining(String contractId, String topicFragment);
}
