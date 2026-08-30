package com.guildworkman.api.discovery.reputation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * A per-worker materialised copy of the {@code reputation} contract's
 * {@code Rating} aggregate, so worker discovery can rank by reputation without a
 * Soroban RPC call on the request path.
 *
 * <p>{@link #workerId} is the {@code SkilledWorker} id — assigned, not
 * generated: there is exactly one snapshot per worker and it is upserted by
 * {@link ReputationSnapshotService}. Because the id is assigned, the entity
 * implements {@link Persistable} so a freshly-built instance is {@code persist}ed
 * (INSERT) rather than {@code merge}d (SELECT-then-UPDATE) by Spring Data.
 *
 * <p>The search query {@code LEFT JOIN}s this table and treats a missing row, or
 * a {@link ReputationSource#FALLBACK} row, as the neutral fallback score.
 *
 * <p>{@link #reputationScore} is the already-normalised {@code [0,1]} value the
 * ranking formula consumes (see {@code WORKER_DISCOVERY.md} "Score formula");
 * {@link #ratingCount} / {@link #averageRating} are kept for observability and
 * so the score can be recomputed if the formula changes.
 */
@Entity
@Table(name = "worker_reputation_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class WorkerReputationSnapshot implements Persistable<Long> {

    @Id
    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "average_rating", nullable = false)
    private double averageRating;

    /** Normalised {@code [0,1]}; what {@code WorkerRankingCalculator} and the SQL score expression consume. */
    @Column(name = "reputation_score", nullable = false)
    private double reputationScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ReputationSource source = ReputationSource.FALLBACK;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    @Transient
    private boolean persisted;

    public WorkerReputationSnapshot(Long workerId) {
        this.workerId = workerId;
    }

    @Override
    public Long getId() {
        return workerId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    /** Where the current values came from. */
    public enum ReputationSource {
        /** Read from the reputation contract's Rating aggregate (via the read-model). */
        ONCHAIN,
        /** The read-model was unavailable and the worker had no prior snapshot; neutral score. */
        FALLBACK
    }
}
