package com.guildworkman.api.discovery.reputation;

import java.util.Optional;

/**
 * Reads the {@code reputation} contract's {@code Rating} aggregate for a worker.
 *
 * <p>Only ever called by {@link ReputationSnapshotService} on its background
 * schedule — never on the search request path. Implementations are expected to
 * be slow and failure-prone (a network hop to chain infrastructure); the caller
 * treats {@link ReputationReadException} as "try again next tick".
 */
public interface ReputationContractClient {

    /**
     * @return the aggregate, or {@link Optional#empty()} if the worker is not
     *         known to the reputation contract yet (distinct from a failure)
     * @throws ReputationReadException if the aggregate could not be read
     */
    Optional<RatingAggregate> fetchRating(long workerId);
}
