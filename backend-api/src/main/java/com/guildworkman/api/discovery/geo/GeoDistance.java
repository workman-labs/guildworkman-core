package com.guildworkman.api.discovery.geo;

/**
 * Great-circle distance between two {@code (latitude, longitude)} points, in
 * kilometres, via the Haversine formula.
 *
 * <p>This is the reference implementation of the exact-distance step of worker
 * discovery. The search query computes the identical expression in SQL so
 * filtering and ordering stay in the database (see
 * {@code WorkerDiscoveryRepository}); this class is what the unit tests pin the
 * maths to, and what {@code WorkerRankingCalculator} would use if a caller ever
 * needs a distance outside a query.
 *
 * <p>Uses a spherical Earth of radius {@link #EARTH_RADIUS_KM}. That is accurate
 * to roughly 0.5% — far tighter than the precision a "workers near me" radius
 * needs, and the same approximation the SQL uses.
 */
public final class GeoDistance {

    /** Mean Earth radius in kilometres (IUGG). The SQL Haversine uses the same constant. */
    public static final double EARTH_RADIUS_KM = 6371.0;

    private GeoDistance() {
    }

    /**
     * @return distance in kilometres between the two points; {@code 0} for the
     *         same point, never negative or NaN for valid coordinates
     */
    public static double kilometresBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double a = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        // asin form rather than atan2: a is clamped to [0,1] so the sqrt is real,
        // and this is the exact expression mirrored in the SQL query.
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_RADIUS_KM * c;
    }
}
