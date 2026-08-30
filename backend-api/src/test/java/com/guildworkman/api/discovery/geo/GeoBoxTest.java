package com.guildworkman.api.discovery.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoBoxTest {

    @Test
    void boxContainsEveryPointInsideTheRadius() {
        double lat = 6.5244;
        double lon = 3.3792;
        double radiusKm = 10;
        GeoBox box = GeoBox.around(lat, lon, radiusKm);

        // A point due north, just inside the radius, must be inside the box.
        double northLat = lat + (radiusKm * 0.9) / 111.32;
        assertThat(box.contains(northLat, lon)).isTrue();

        // A point clearly outside (5x the radius east) must be outside the box.
        double farLon = lon + (radiusKm * 5) / (111.32 * Math.cos(Math.toRadians(lat)));
        assertThat(box.contains(lat, farLon)).isFalse();
    }

    @Test
    void boxIsASupersetOfTheCircle_neverDropsAnInRangeWorker() {
        double lat = 40.0, lon = -74.0, radiusKm = 25;
        GeoBox box = GeoBox.around(lat, lon, radiusKm);

        for (int bearing = 0; bearing < 360; bearing += 15) {
            double d = radiusKm * 0.99;
            double dLat = (d / 111.32) * Math.cos(Math.toRadians(bearing));
            double dLon = (d / (111.32 * Math.cos(Math.toRadians(lat)))) * Math.sin(Math.toRadians(bearing));
            assertThat(box.contains(lat + dLat, lon + dLon))
                    .as("bearing %d should be inside the box", bearing)
                    .isTrue();
        }
    }

    @Test
    void latitudeIsClampedToThePoles() {
        GeoBox box = GeoBox.around(89.5, 20.0, 200);
        assertThat(box.maxLat()).isLessThanOrEqualTo(90.0);
        assertThat(box.minLat()).isGreaterThanOrEqualTo(-90.0);
    }

    @Test
    void nearThePoleLongitudeWidensToTheFullRange() {
        GeoBox box = GeoBox.around(89.99, 0.0, 50);
        assertThat(box.minLon()).isEqualTo(-180.0);
        assertThat(box.maxLon()).isEqualTo(180.0);
    }

    @Test
    void antimeridianWrapDegradesToFullLongitudeRange() {
        GeoBox box = GeoBox.around(0.0, 179.9, 50);
        assertThat(box.minLon()).isEqualTo(-180.0);
        assertThat(box.maxLon()).isEqualTo(180.0);
    }

    @Test
    void nonPositiveRadiusYieldsADegenerateBoxAtThePoint() {
        GeoBox box = GeoBox.around(10.0, 10.0, 0);
        assertThat(box.contains(10.0, 10.0)).isTrue();
        assertThat(box.minLat()).isEqualTo(10.0);
        assertThat(box.maxLat()).isEqualTo(10.0);
    }

    @Test
    void haversineIsZeroForTheSamePointAndSymmetric() {
        assertThat(GeoDistance.kilometresBetween(1, 2, 1, 2)).isEqualTo(0.0);
        double ab = GeoDistance.kilometresBetween(6.52, 3.37, 9.05, 7.49);
        double ba = GeoDistance.kilometresBetween(9.05, 7.49, 6.52, 3.37);
        assertThat(ab).isEqualTo(ba);
        // Lagos -> Abuja is ~525 km.
        assertThat(ab).isBetween(490.0, 560.0);
    }
}
