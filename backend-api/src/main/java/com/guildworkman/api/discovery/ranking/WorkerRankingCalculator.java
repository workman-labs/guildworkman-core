package com.guildworkman.api.discovery.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The worker discovery ranking formula — the reference implementation of the
 * blend documented in {@code backend-api/docs/WORKER_DISCOVERY.md}.
 *
 * <pre>
 * proximity    = max(0, 1 - distanceKm / radiusKm)
 * reputation   = reputationScore                       (already 0..1)
 * availability = available ? 1 : 0
 *
 * rankScore = (wProximity*proximity + wReputation*reputation + wAvailability*availability)
 *             / (wProximity + wReputation + wAvailability)
 * </pre>
 *
 * <p>The search query computes this same expression in SQL so that ordering and
 * keyset pagination run in the database rather than in memory;
 * {@code WorkerRankingCalculatorTest} pins the two implementations together on
 * sample inputs. This class is used to derive the {@code rankScore} placed in
 * the API response and in the pagination cursor.
 */
@Component
@RequiredArgsConstructor
public class WorkerRankingCalculator {

    private final RankingWeights weights;

    /** The blended, normalised score in {@code [0,1]}. Higher ranks first. */
    public double score(RankingSignals signals) {
        double proximity = proximityComponent(signals.distanceKm(), signals.radiusKm());
        double reputation = clamp01(signals.reputationScore());
        double availability = signals.available() ? 1.0 : 0.0;

        double blended = weights.getProximityWeight() * proximity
                + weights.getReputationWeight() * reputation
                + weights.getAvailabilityWeight() * availability;
        return blended / weights.total();
    }

    /** 1 at the caller's location, 0 at (or beyond) the search edge. */
    public static double proximityComponent(double distanceKm, double radiusKm) {
        if (radiusKm <= 0) {
            return 0.0;
        }
        return clamp01(1.0 - distanceKm / radiusKm);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
