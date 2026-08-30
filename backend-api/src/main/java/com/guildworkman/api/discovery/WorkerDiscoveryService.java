package com.guildworkman.api.discovery;

import com.guildworkman.api.discovery.api.WorkerSearchResponse;
import com.guildworkman.api.discovery.api.WorkerSearchResponse.FacetCount;
import com.guildworkman.api.discovery.api.WorkerSearchResponse.Facets;
import com.guildworkman.api.discovery.api.WorkerSearchResponse.PageInfo;
import com.guildworkman.api.discovery.api.WorkerSearchResponse.WorkerResult;
import com.guildworkman.api.discovery.geo.GeoBox;
import com.guildworkman.api.discovery.pagination.CursorCodec;
import com.guildworkman.api.discovery.pagination.SearchCursor;
import com.guildworkman.api.discovery.ranking.RankingWeights;
import com.guildworkman.api.discovery.reputation.ReputationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runs a worker discovery search: turns a {@link WorkerSearchCriteria} into the
 * bounding box, issues the single ranked SQL query (one extra row, to know
 * whether a next page exists), issues the two facet-count queries, and
 * assembles the {@link WorkerSearchResponse} including the next-page cursor.
 *
 * <p>All filtering, ranking and ordering happen in {@link WorkerDiscoveryRepository}
 * — this class composes queries and shapes the response, it does not rank.
 */
@Service
@RequiredArgsConstructor
public class WorkerDiscoveryService {

    private final WorkerDiscoveryRepository repository;
    private final RankingWeights weights;
    private final ReputationProperties reputationProperties;
    private final DiscoveryProperties discoveryProperties;
    private final CursorCodec cursorCodec;

    @Transactional(readOnly = true)
    public WorkerSearchResponse search(WorkerSearchCriteria criteria) {
        GeoBox box = GeoBox.around(criteria.latitude(), criteria.longitude(), criteria.radiusKm());

        boolean filterSkill = criteria.hasSkillFilter();
        String skill = filterSkill ? criteria.skill().trim() : "";
        boolean filterCategory = criteria.hasCategoryFilter();
        String category = filterCategory ? criteria.category().name() : "";
        boolean filterAvailable = criteria.hasAvailabilityFilter();
        boolean availableValue = Boolean.TRUE.equals(criteria.available());

        SearchCursor cursor = criteria.cursor();
        boolean hasCursor = cursor != null;

        // Fetch one more than the page size: its presence is exactly "there is a next page".
        int fetchLimit = criteria.size() + 1;

        List<WorkerSearchRow> rows = repository.search(
                criteria.latitude(), criteria.longitude(),
                box.minLat(), box.maxLat(), box.minLon(), box.maxLon(),
                criteria.radiusKm(),
                filterSkill, skill,
                filterCategory, category,
                filterAvailable, availableValue,
                reputationProperties.getFallbackScore(),
                weights.getProximityWeight(), weights.getReputationWeight(), weights.getAvailabilityWeight(),
                hasCursor,
                hasCursor ? cursor.rankScore() : 0.0,
                hasCursor ? cursor.workerId() : 0L,
                PageRequest.of(0, fetchLimit));

        boolean hasMore = rows.size() > criteria.size();
        List<WorkerSearchRow> pageRows = hasMore ? rows.subList(0, criteria.size()) : rows;

        List<WorkerResult> results = pageRows.stream().map(this::toResult).toList();

        String nextCursor = null;
        if (hasMore && !pageRows.isEmpty()) {
            WorkerSearchRow last = pageRows.get(pageRows.size() - 1);
            nextCursor = cursorCodec.encode(new SearchCursor(last.getScore(), last.getId()));
        }

        Facets facets = new Facets(categoryFacets(criteria, box), skillFacets(criteria, box));

        return new WorkerSearchResponse(results, facets,
                new PageInfo(criteria.size(), hasMore, nextCursor));
    }

    private WorkerResult toResult(WorkerSearchRow row) {
        return new WorkerResult(
                row.getId(),
                row.getFullName(),
                row.getUsername(),
                row.getCategory(),
                row.getLatitude(),
                row.getLongitude(),
                round(row.getDistanceKm(), 3),
                row.getAvailable(),
                round(row.getReputationScore(), 4),
                row.getReviewCount(),
                round(row.getScore(), 6));
    }

    private List<FacetCount> categoryFacets(WorkerSearchCriteria criteria, GeoBox box) {
        boolean filterSkill = criteria.hasSkillFilter();
        boolean filterAvailable = criteria.hasAvailabilityFilter();
        return repository.categoryFacets(
                        criteria.latitude(), criteria.longitude(),
                        box.minLat(), box.maxLat(), box.minLon(), box.maxLon(),
                        criteria.radiusKm(),
                        filterSkill, filterSkill ? criteria.skill().trim() : "",
                        filterAvailable, Boolean.TRUE.equals(criteria.available()))
                .stream().map(r -> new FacetCount(r.getValue(), r.getCount())).toList();
    }

    private List<FacetCount> skillFacets(WorkerSearchCriteria criteria, GeoBox box) {
        boolean filterCategory = criteria.hasCategoryFilter();
        boolean filterAvailable = criteria.hasAvailabilityFilter();
        return repository.skillFacets(
                        criteria.latitude(), criteria.longitude(),
                        box.minLat(), box.maxLat(), box.minLon(), box.maxLon(),
                        criteria.radiusKm(),
                        filterCategory, filterCategory ? criteria.category().name() : "",
                        filterAvailable, Boolean.TRUE.equals(criteria.available()),
                        PageRequest.of(0, discoveryProperties.getMaxSkillFacets()))
                .stream().map(r -> new FacetCount(r.getValue(), r.getCount())).toList();
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
