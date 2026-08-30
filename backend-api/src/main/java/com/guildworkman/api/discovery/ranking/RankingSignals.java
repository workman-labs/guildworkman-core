package com.guildworkman.api.discovery.ranking;

/**
 * The raw per-worker inputs to the ranking blend, as they come out of the
 * search query.
 *
 * @param distanceKm      exact great-circle distance from the caller, kilometres ({@code >= 0})
 * @param radiusKm        the search radius the request used, kilometres ({@code > 0})
 * @param reputationScore materialised reputation, already normalised to {@code [0,1]}
 * @param available       whether the worker is marked available
 */
public record RankingSignals(double distanceKm, double radiusKm, double reputationScore, boolean available) {
}
