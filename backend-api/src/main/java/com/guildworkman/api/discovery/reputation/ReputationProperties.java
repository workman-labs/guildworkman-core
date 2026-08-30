package com.guildworkman.api.discovery.reputation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds {@code guildworkman.discovery.reputation.*} — how the on-chain
 * {@code Rating} aggregate is materialised for ranking.
 *
 * <p>{@code poll-delay-ms} is deliberately <b>not</b> here: {@code @Scheduled}
 * on {@link ReputationSnapshotService} reads it as a raw placeholder at
 * bean-creation time, the same pattern as {@code booking.expiry-sweep-delay-ms}.
 */
@Component
@ConfigurationProperties(prefix = "guildworkman.discovery.reputation")
@Getter
@Setter
public class ReputationProperties {

    /**
     * Base URL of the read-model / indexer that projects the reputation
     * contract's {@code Rating} aggregate. The refresher calls
     * {@code {read-model-url}/api/v1/reputation/ratings/{workerId}}. Never
     * called on the search request path.
     */
    private String readModelUrl = "http://localhost:8081";

    /** HTTP timeout for a single read-model call. */
    private Duration requestTimeout = Duration.ofSeconds(5);

    /**
     * A snapshot older than this is refreshed. Also the upper bound on how
     * stale a worker's reputation contribution to ranking can be, and the
     * window within which ranking is stable for a paginated scroll.
     */
    private Duration stalenessBound = Duration.ofMinutes(15);

    /**
     * Neutral score used when a worker has no ratings, or the read-model is
     * unavailable and the worker has no prior snapshot. {@code [0,1]}.
     */
    private double fallbackScore = 0.5;

    /**
     * Small-sample shrink constant: a worker's normalised rating is pulled
     * toward {@link #fallbackScore} with weight {@code shrinkK / (count + shrinkK)}.
     */
    private int shrinkK = 5;

    /** Worker snapshots refreshed per scheduler tick. */
    private int refreshBatchSize = 100;
}
