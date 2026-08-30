package com.guildworkman.api.discovery.reputation;

/**
 * The reputation contract's {@code Rating} aggregate for one worker, as returned
 * by the read-model: how many ratings, and their mean on a 0–5 scale.
 *
 * @param ratingCount   number of on-chain ratings; {@code 0} means "unrated"
 * @param averageRating mean rating on {@code [0,5]}; meaningless when {@code ratingCount == 0}
 */
public record RatingAggregate(int ratingCount, double averageRating) {

    /**
     * Normalise to the {@code [0,1]} reputation score the ranking formula uses,
     * shrinking small samples toward {@code fallbackScore} (see
     * {@code WORKER_DISCOVERY.md} "Score formula").
     *
     * @param fallbackScore neutral score for an unrated worker, {@code [0,1]}
     * @param shrinkK       shrink constant; higher = more distrust of small samples
     */
    public double toReputationScore(double fallbackScore, int shrinkK) {
        if (ratingCount <= 0) {
            return clamp01(fallbackScore);
        }
        double norm = clamp01(averageRating / 5.0);
        double weight = (double) ratingCount / (ratingCount + Math.max(0, shrinkK));
        return clamp01(weight * norm + (1 - weight) * fallbackScore);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
