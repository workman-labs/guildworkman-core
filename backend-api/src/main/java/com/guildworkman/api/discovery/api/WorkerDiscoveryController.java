package com.guildworkman.api.discovery.api;

import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.discovery.DiscoveryProperties;
import com.guildworkman.api.discovery.WorkerDiscoveryService;
import com.guildworkman.api.discovery.WorkerSearchCriteria;
import com.guildworkman.api.discovery.pagination.CursorCodec;
import com.guildworkman.api.discovery.pagination.SearchCursor;
import com.guildworkman.api.exceptions.GuildWorkmanException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketplace's front door: discover skilled workers near a location,
 * filtered by skill / category / availability and ranked by a documented blend
 * of proximity, on-chain reputation and availability, with stable cursor
 * pagination and facet counts.
 *
 * <p>Public (no bearer token), matching {@code /api/v1/skilledWorker/**} and
 * {@code /api/v1/booking/**} — see {@code SecurityConfig}. Full request/response
 * contract in {@code backend-api/docs/WORKER_DISCOVERY.md}.
 */
@RestController
@RequestMapping("/api/v1/discovery")
@Validated
@RequiredArgsConstructor
@Tag(name = "Worker Discovery", description = "Find and rank skilled workers by location, skill and reputation.")
public class WorkerDiscoveryController {

    private final WorkerDiscoveryService discoveryService;
    private final CursorCodec cursorCodec;
    private final DiscoveryProperties properties;

    @GetMapping("/workers")
    @Operation(
            summary = "Search for workers",
            description = """
                    Returns a ranked page of workers within radiusKm of (latitude, longitude),
                    optionally filtered by skill, category and availability, plus facet counts
                    and a keyset cursor for the next page. Reputation is served from a
                    materialised snapshot and never triggers a chain call on this request.""")
    @ApiResponse(responseCode = "200", description = "A ranked page with facets and pagination cursor")
    @ApiResponse(responseCode = "400", description = "A parameter is out of range, or the cursor is invalid")
    public ResponseEntity<WorkerSearchResponse> searchWorkers(
            @Parameter(description = "Caller latitude", required = true, example = "6.5244")
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,

            @Parameter(description = "Caller longitude", required = true, example = "3.3792")
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,

            @Parameter(description = "Search radius in kilometres (default from config, capped by config)")
            @RequestParam(required = false) Double radiusKm,

            @Parameter(description = "Exact skill-name filter, case-insensitive", example = "wiring")
            @RequestParam(required = false) String skill,

            @Parameter(description = "Category filter", example = "ELECTRICAL")
            @RequestParam(required = false) Category category,

            @Parameter(description = "Availability filter; omit for no filter")
            @RequestParam(required = false) Boolean available,

            @Parameter(description = "Page size (default and cap from config)")
            @RequestParam(required = false) Integer size,

            @Parameter(description = "Opaque cursor from a previous response's pageInfo.nextCursor")
            @RequestParam(required = false) String cursor) {

        double resolvedRadius = radiusKm != null ? radiusKm : properties.getDefaultRadiusKm();
        if (resolvedRadius <= 0 || resolvedRadius > properties.getMaxRadiusKm()) {
            throw new GuildWorkmanException(
                    "radiusKm must be greater than 0 and at most " + properties.getMaxRadiusKm());
        }

        int resolvedSize = size != null ? size : properties.getDefaultPageSize();
        if (resolvedSize < 1 || resolvedSize > properties.getMaxPageSize()) {
            throw new GuildWorkmanException(
                    "size must be between 1 and " + properties.getMaxPageSize());
        }

        SearchCursor decodedCursor = cursor != null ? cursorCodec.decode(cursor) : null;

        WorkerSearchCriteria criteria = new WorkerSearchCriteria(
                latitude, longitude, resolvedRadius,
                skill, category, available,
                resolvedSize, decodedCursor);

        return ResponseEntity.ok(discoveryService.search(criteria));
    }
}
