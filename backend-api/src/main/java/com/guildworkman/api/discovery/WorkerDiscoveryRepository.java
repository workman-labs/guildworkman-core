package com.guildworkman.api.discovery;

import com.guildworkman.api.data.models.SkilledWorker;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The worker discovery query, entirely in SQL: bounding-box prefilter → exact
 * Haversine → composed skill/category/availability filters → the configurable
 * ranking blend → keyset pagination. Nothing is filtered, ranked or paged in
 * application memory.
 *
 * <p>Every optional filter is expressed as {@code (:filterX = FALSE OR <predicate>)}
 * with a companion non-null value parameter, so no query ever binds a
 * {@code null} (which Hibernate can't always type for a native statement). The
 * page limit comes from the {@link Pageable} (page 0), applied by Spring Data as
 * {@code setMaxResults}.
 *
 * <p>The SQL is assembled from single-line, space-padded {@code String} fragments
 * (not text blocks) so concatenation can't drop a separator — {@link #HAVERSINE}
 * is shared between the search and both facet queries and matches
 * {@code GeoDistance} exactly ({@code EARTH_RADIUS_KM = 6371}).
 */
public interface WorkerDiscoveryRepository extends Repository<SkilledWorker, Long> {

    /** Exact great-circle distance in km from {@code (:lat,:lon)} to {@code (w.latitude,w.longitude)}. Mirrors {@code GeoDistance}. */
    String HAVERSINE =
            " (2 * 6371 * asin(sqrt("
            + " power(sin(radians(:lat - w.latitude) / 2), 2)"
            + " + cos(radians(:lat)) * cos(radians(w.latitude))"
            + " * power(sin(radians(:lon - w.longitude) / 2), 2)"
            + " ))) ";

    String GEO_BOX =
            " w.latitude BETWEEN :minLat AND :maxLat"
            + " AND w.longitude BETWEEN :minLon AND :maxLon ";

    String SEARCH_SQL =
            " SELECT * FROM ("
            + "   SELECT w.id                         AS \"id\","
            + "          w.full_name                  AS \"fullName\","
            + "          w.username                   AS \"username\","
            + "          w.category                   AS \"category\","
            + "          w.latitude                   AS \"latitude\","
            + "          w.longitude                  AS \"longitude\","
            + "          COALESCE(w.available, TRUE)   AS \"available\","
            + "          (SELECT COUNT(*) FROM reviews r WHERE r.skilled_worker_id = w.id) AS \"reviewCount\","
            + "          COALESCE(rs.reputation_score, :fallbackRep) AS \"reputationScore\","
            + "          d.dist                       AS \"distanceKm\","
            + "          ( :wProx  * GREATEST(0.0, 1.0 - (d.dist / :radiusKm))"
            + "          + :wRep   * COALESCE(rs.reputation_score, :fallbackRep)"
            + "          + :wAvail * (CASE WHEN COALESCE(w.available, TRUE) THEN 1.0 ELSE 0.0 END)"
            + "          ) / (:wProx + :wRep + :wAvail) AS \"score\""
            + "   FROM skilled_workers w"
            + "   LEFT JOIN worker_reputation_snapshots rs ON rs.worker_id = w.id"
            + "   CROSS JOIN LATERAL (SELECT" + HAVERSINE + "AS dist) d"
            + "   WHERE" + GEO_BOX
            + "     AND (:filterCategory  = FALSE OR w.category = :category)"
            + "     AND (:filterAvailable = FALSE OR COALESCE(w.available, TRUE) = :availableValue)"
            + "     AND (:filterSkill     = FALSE OR EXISTS ("
            + "           SELECT 1 FROM skills s WHERE s.skilled_worker_id = w.id"
            + "             AND lower(s.skill_name) = lower(:skill)))"
            + "     AND d.dist <= :radiusKm"
            + " ) ranked"
            + " WHERE :hasCursor = FALSE"
            + "    OR ranked.score < :cursorScore"
            + "    OR (ranked.score = :cursorScore AND ranked.id > :cursorId)"
            + " ORDER BY ranked.score DESC, ranked.id ASC";

    String CATEGORY_FACET_SQL =
            " SELECT w.category AS \"value\", COUNT(*) AS \"count\""
            + " FROM skilled_workers w"
            + " CROSS JOIN LATERAL (SELECT" + HAVERSINE + "AS dist) d"
            + " WHERE" + GEO_BOX
            + "   AND (:filterAvailable = FALSE OR COALESCE(w.available, TRUE) = :availableValue)"
            + "   AND (:filterSkill     = FALSE OR EXISTS ("
            + "         SELECT 1 FROM skills s WHERE s.skilled_worker_id = w.id"
            + "           AND lower(s.skill_name) = lower(:skill)))"
            + "   AND d.dist <= :radiusKm"
            + "   AND w.category IS NOT NULL"
            + " GROUP BY w.category"
            + " ORDER BY COUNT(*) DESC, w.category ASC";

    String SKILL_FACET_SQL =
            " SELECT s.skill_name AS \"value\", COUNT(DISTINCT w.id) AS \"count\""
            + " FROM skilled_workers w"
            + " JOIN skills s ON s.skilled_worker_id = w.id"
            + " CROSS JOIN LATERAL (SELECT" + HAVERSINE + "AS dist) d"
            + " WHERE" + GEO_BOX
            + "   AND (:filterCategory = FALSE OR w.category = :category)"
            + "   AND (:filterAvailable = FALSE OR COALESCE(w.available, TRUE) = :availableValue)"
            + "   AND d.dist <= :radiusKm"
            + "   AND s.skill_name IS NOT NULL"
            + " GROUP BY s.skill_name"
            + " ORDER BY COUNT(DISTINCT w.id) DESC, s.skill_name ASC";

    @Query(value = SEARCH_SQL, nativeQuery = true)
    List<WorkerSearchRow> search(
            @Param("lat") double lat, @Param("lon") double lon,
            @Param("minLat") double minLat, @Param("maxLat") double maxLat,
            @Param("minLon") double minLon, @Param("maxLon") double maxLon,
            @Param("radiusKm") double radiusKm,
            @Param("filterSkill") boolean filterSkill, @Param("skill") String skill,
            @Param("filterCategory") boolean filterCategory, @Param("category") String category,
            @Param("filterAvailable") boolean filterAvailable, @Param("availableValue") boolean availableValue,
            @Param("fallbackRep") double fallbackRep,
            @Param("wProx") double wProx, @Param("wRep") double wRep, @Param("wAvail") double wAvail,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorScore") double cursorScore, @Param("cursorId") long cursorId,
            Pageable pageable);

    @Query(value = CATEGORY_FACET_SQL, nativeQuery = true)
    List<FacetRow> categoryFacets(
            @Param("lat") double lat, @Param("lon") double lon,
            @Param("minLat") double minLat, @Param("maxLat") double maxLat,
            @Param("minLon") double minLon, @Param("maxLon") double maxLon,
            @Param("radiusKm") double radiusKm,
            @Param("filterSkill") boolean filterSkill, @Param("skill") String skill,
            @Param("filterAvailable") boolean filterAvailable, @Param("availableValue") boolean availableValue);

    @Query(value = SKILL_FACET_SQL, nativeQuery = true)
    List<FacetRow> skillFacets(
            @Param("lat") double lat, @Param("lon") double lon,
            @Param("minLat") double minLat, @Param("maxLat") double maxLat,
            @Param("minLon") double minLon, @Param("maxLon") double maxLon,
            @Param("radiusKm") double radiusKm,
            @Param("filterCategory") boolean filterCategory, @Param("category") String category,
            @Param("filterAvailable") boolean filterAvailable, @Param("availableValue") boolean availableValue,
            Pageable pageable);
}
