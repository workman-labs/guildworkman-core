package com.guildworkman.api.discovery.reputation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WorkerReputationSnapshotRepository extends JpaRepository<WorkerReputationSnapshot, Long> {

    /**
     * Worker ids whose reputation snapshot is missing entirely or older than
     * {@code threshold}, oldest (or never-refreshed) first, so the refresher
     * always makes progress on the most stale rows within its per-tick budget.
     */
    @Query(value = """
            SELECT w.id
            FROM skilled_workers w
            LEFT JOIN worker_reputation_snapshots rs ON rs.worker_id = w.id
            WHERE rs.worker_id IS NULL OR rs.refreshed_at < :threshold
            ORDER BY rs.refreshed_at ASC NULLS FIRST, w.id ASC
            """, nativeQuery = true)
    List<Long> findWorkerIdsNeedingRefresh(@Param("threshold") Instant threshold, Pageable pageable);
}
