package com.guildworkman.api.discovery.reputation;

import com.guildworkman.api.discovery.reputation.WorkerReputationSnapshot.ReputationSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Keeps {@code worker_reputation_snapshots} fresh so worker discovery can rank
 * by reputation without ever calling chain infrastructure on the request path.
 *
 * <p>Each tick takes a bounded batch of the most-stale workers (missing snapshot,
 * or one older than {@link ReputationProperties#getStalenessBound()}), reads
 * each worker's {@code Rating} aggregate through {@link ReputationContractClient},
 * and upserts the snapshot.
 *
 * <p><b>Failure handling.</b> If the read fails for a worker:
 * <ul>
 *   <li>with an existing snapshot — it is left untouched (a stale real score
 *       beats a neutral guess) and retried next tick;</li>
 *   <li>with no snapshot — a {@link ReputationSource#FALLBACK} row is written
 *       with the neutral {@link ReputationProperties#getFallbackScore()} so the
 *       worker still ranks and search still has a value.</li>
 * </ul>
 * One worker's failure never aborts the batch.
 */
@Service
@RequiredArgsConstructor
public class ReputationSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ReputationSnapshotService.class);

    private final WorkerReputationSnapshotRepository snapshots;
    private final ReputationContractClient reputationClient;
    private final ReputationProperties properties;

    @Scheduled(fixedDelayString = "${guildworkman.discovery.reputation.poll-delay-ms:60000}")
    public void refreshStaleSnapshots() {
        Instant threshold = Instant.now().minus(properties.getStalenessBound());
        List<Long> workerIds = snapshots.findWorkerIdsNeedingRefresh(
                threshold, PageRequest.of(0, properties.getRefreshBatchSize()));
        if (workerIds.isEmpty()) {
            return;
        }
        int ok = 0;
        int fellBack = 0;
        for (Long workerId : workerIds) {
            try {
                refreshOne(workerId);
                ok++;
            } catch (ReputationReadException ex) {
                if (writeFallbackIfAbsent(workerId)) {
                    fellBack++;
                }
                log.warn("reputation refresh deferred for worker {}: {}", workerId, ex.getMessage());
            } catch (RuntimeException ex) {
                log.error("reputation refresh failed unexpectedly for worker {}", workerId, ex);
            }
        }
        log.debug("reputation refresh: {} updated, {} fell back, {} examined", ok, fellBack, workerIds.size());
    }

    @Transactional
    public void refreshOne(long workerId) {
        Optional<RatingAggregate> aggregate = reputationClient.fetchRating(workerId);
        WorkerReputationSnapshot snapshot = snapshots.findById(workerId)
                .orElseGet(() -> new WorkerReputationSnapshot(workerId));

        if (aggregate.isEmpty()) {
            // Known-unrated: a real answer, just an empty one. Neutral score,
            // marked ONCHAIN because the read itself succeeded.
            snapshot.setRatingCount(0);
            snapshot.setAverageRating(0.0);
            snapshot.setReputationScore(properties.getFallbackScore());
            snapshot.setSource(ReputationSource.ONCHAIN);
        } else {
            RatingAggregate agg = aggregate.get();
            snapshot.setRatingCount(agg.ratingCount());
            snapshot.setAverageRating(agg.averageRating());
            snapshot.setReputationScore(
                    agg.toReputationScore(properties.getFallbackScore(), properties.getShrinkK()));
            snapshot.setSource(ReputationSource.ONCHAIN);
        }
        snapshot.setRefreshedAt(Instant.now());
        snapshots.save(snapshot);
    }

    @Transactional
    public boolean writeFallbackIfAbsent(long workerId) {
        if (snapshots.existsById(workerId)) {
            return false;
        }
        WorkerReputationSnapshot snapshot = new WorkerReputationSnapshot(workerId);
        snapshot.setRatingCount(0);
        snapshot.setAverageRating(0.0);
        snapshot.setReputationScore(properties.getFallbackScore());
        snapshot.setSource(ReputationSource.FALLBACK);
        snapshot.setRefreshedAt(Instant.now());
        snapshots.save(snapshot);
        return true;
    }
}
