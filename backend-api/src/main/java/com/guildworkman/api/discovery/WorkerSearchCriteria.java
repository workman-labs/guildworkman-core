package com.guildworkman.api.discovery;

import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.discovery.pagination.SearchCursor;

/**
 * A validated, normalised worker discovery request. Built by the controller
 * from request parameters (bounds already applied); consumed by
 * {@link WorkerDiscoveryService}.
 *
 * @param latitude   caller latitude, {@code [-90,90]}
 * @param longitude  caller longitude, {@code [-180,180]}
 * @param radiusKm   search radius in km, {@code (0, maxRadiusKm]}
 * @param skill      exact skill-name filter (case-insensitive), or {@code null} for no skill filter
 * @param category   category filter, or {@code null} for no category filter
 * @param available  availability filter, or {@code null} for no availability filter
 * @param size       page size, {@code [1, maxPageSize]}
 * @param cursor     keyset position to continue after, or {@code null} for the first page
 */
public record WorkerSearchCriteria(
        double latitude,
        double longitude,
        double radiusKm,
        String skill,
        Category category,
        Boolean available,
        int size,
        SearchCursor cursor) {

    public boolean hasSkillFilter() {
        return skill != null && !skill.isBlank();
    }

    public boolean hasCategoryFilter() {
        return category != null;
    }

    public boolean hasAvailabilityFilter() {
        return available != null;
    }
}
