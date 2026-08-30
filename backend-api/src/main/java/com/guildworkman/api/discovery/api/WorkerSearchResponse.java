package com.guildworkman.api.discovery.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response body of {@code GET /api/v1/discovery/workers}: the ranked page,
 * facet counts for building filter UI, and the keyset pagination cursor.
 */
@Schema(description = "A ranked page of discovered workers, with facet counts and a pagination cursor.")
public record WorkerSearchResponse(
        List<WorkerResult> results,
        Facets facets,
        PageInfo pageInfo) {

    @Schema(description = "One worker in the result page.")
    public record WorkerResult(
            @Schema(example = "42") long workerId,
            @Schema(example = "Ada Okafor") String fullName,
            @Schema(example = "ada") String username,
            @Schema(example = "ELECTRICAL") String category,
            double latitude,
            double longitude,
            @Schema(description = "Exact great-circle distance from the search point, kilometres.", example = "1.84")
            double distanceKm,
            @Schema(description = "Whether the worker is marked available (a worker who hasn't stated it counts as available).")
            boolean available,
            @Schema(description = "Materialised on-chain reputation, 0..1. Never a live chain read.", example = "0.86")
            double reputationScore,
            @Schema(description = "Number of reviews on record (informational; not the reputation signal).", example = "12")
            long reviewCount,
            @Schema(description = "The blended score this ordering used, 0..1.", example = "0.79")
            double rankScore) {
    }

    @Schema(description = "Facet counts over the same filtered set, each ignoring its own dimension's active filter.")
    public record Facets(
            List<FacetCount> category,
            List<FacetCount> skill) {
    }

    @Schema(description = "One facet bucket.")
    public record FacetCount(
            @Schema(example = "ELECTRICAL") String value,
            @Schema(example = "7") long count) {
    }

    @Schema(description = "Keyset pagination state.")
    public record PageInfo(
            @Schema(description = "Page size actually applied.", example = "20") int size,
            @Schema(description = "True when another page exists.", example = "true") boolean hasMore,
            @Schema(description = "Pass as ?cursor= to fetch the next page. Null on the last page.", nullable = true)
            String nextCursor) {
    }
}
