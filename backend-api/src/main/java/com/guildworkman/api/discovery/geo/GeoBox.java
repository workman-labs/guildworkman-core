package com.guildworkman.api.discovery.geo;

/**
 * An axis-aligned latitude/longitude bounding box around a point — the
 * index-backed prefilter for geo-radius search.
 *
 * <p>The search query filters on
 * {@code latitude BETWEEN minLat AND maxLat AND longitude BETWEEN minLon AND maxLon}
 * first (served by the composite B-tree index on
 * {@code skilled_workers(latitude, longitude)}), and only then runs the exact
 * {@link GeoDistance} check on the far smaller set of rows the box let through.
 * Without the box, the distance predicate is a per-row computation over the
 * whole table.
 *
 * <p>The box is a superset of the true circle — it includes the four corners
 * that are further than the radius — so it never drops a worker that is
 * genuinely in range; the exact-distance step removes the corners.
 *
 * <h2>Edge behaviour</h2>
 * <ul>
 *   <li><b>Latitude</b> is clamped to {@code [-90, 90]}.</li>
 *   <li><b>Longitude</b> degrees are widened by {@code 1/cos(latitude)} because
 *       a degree of longitude shrinks toward the poles.</li>
 *   <li>If the widened longitude span reaches or exceeds 360° (near the poles),
 *       or the box would wrap past ±180° (antimeridian), longitude degrades to
 *       the full {@code [-180, 180]} range. That makes the box less selective in
 *       those rare cases but keeps it correct without split-range query logic.</li>
 * </ul>
 */
public record GeoBox(double minLat, double maxLat, double minLon, double maxLon) {

    private static final double MAX_LAT = 90.0;
    private static final double MAX_LON = 180.0;
    private static final double KM_PER_LAT_DEGREE = 111.32;

    /**
     * @param centerLat centre latitude in degrees
     * @param centerLon centre longitude in degrees
     * @param radiusKm  radius in kilometres; values {@code <= 0} yield a degenerate box at the point
     */
    public static GeoBox around(double centerLat, double centerLon, double radiusKm) {
        double safeRadius = Math.max(0.0, radiusKm);
        double latDelta = safeRadius / KM_PER_LAT_DEGREE;

        double minLat = clampLat(centerLat - latDelta);
        double maxLat = clampLat(centerLat + latDelta);

        // A degree of longitude shrinks toward the poles (≈ 111.32·cos(lat) km),
        // so covering the radius east-west needs MORE degrees the closer you are
        // to a pole. Size the box from whichever edge latitude is nearer a pole
        // — the larger |lat| — so the box stays a superset of the circle.
        double widestLat = Math.max(Math.abs(minLat), Math.abs(maxLat));
        double cos = Math.cos(Math.toRadians(widestLat));

        if (cos < 1e-9) {
            // On/adjacent to a pole every meridian is "near": widen fully.
            return new GeoBox(minLat, maxLat, -MAX_LON, MAX_LON);
        }

        double lonDelta = latDelta / cos;
        if (lonDelta >= MAX_LON) {
            return new GeoBox(minLat, maxLat, -MAX_LON, MAX_LON);
        }

        double minLon = centerLon - lonDelta;
        double maxLon = centerLon + lonDelta;
        if (minLon < -MAX_LON || maxLon > MAX_LON) {
            // Antimeridian wrap — a split [minLon,180] ∪ [-180,maxLon] range is
            // more selective but needs branching query SQL. Full range is correct
            // and the exact-distance step still does the real work.
            return new GeoBox(minLat, maxLat, -MAX_LON, MAX_LON);
        }
        return new GeoBox(minLat, maxLat, minLon, maxLon);
    }

    private static double clampLat(double lat) {
        return Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
    }

    /** True if the point lies within the box (inclusive). Convenience for tests and callers not going through SQL. */
    public boolean contains(double lat, double lon) {
        return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
    }
}
