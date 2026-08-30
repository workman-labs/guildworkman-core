package com.guildworkman.api.discovery.ranking;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code guildworkman.discovery.ranking.*} — the weights of the worker
 * discovery ranking blend. Configurable rather than baked into a comparator, so
 * an operator can retune the marketplace's ordering without a deploy of new
 * code.
 *
 * <p>The weights need not sum to 1: {@link WorkerRankingCalculator} normalises
 * the blended score by their total, so {@code rankScore} stays in {@code [0,1]}
 * for any non-negative, not-all-zero choice.
 */
@Component
@ConfigurationProperties(prefix = "guildworkman.discovery.ranking")
@Getter
@Setter
public class RankingWeights {

    /** How much closeness to the caller matters. Default 0.5. */
    private double proximityWeight = 0.5;

    /** How much the materialised on-chain reputation score matters. Default 0.3. */
    private double reputationWeight = 0.3;

    /** How much being marked available matters. Default 0.2. */
    private double availabilityWeight = 0.2;

    @PostConstruct
    void validate() {
        if (proximityWeight < 0 || reputationWeight < 0 || availabilityWeight < 0) {
            throw new IllegalStateException("guildworkman.discovery.ranking weights must be non-negative");
        }
        if (total() <= 0) {
            throw new IllegalStateException("guildworkman.discovery.ranking weights must not all be zero");
        }
    }

    public double total() {
        return proximityWeight + reputationWeight + availabilityWeight;
    }
}
