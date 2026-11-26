package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A weather mod's storms drawn where they are: each storm's formations travel with it, grow and
 * shrink with it, are solid cloud over its rain core at any tick and end at its edge, reach over
 * its cloud top, and a thunderstorm towers. (Against Almanac's own storms: core's
 * AlmanacStormsTest.)
 */
class RegionalSkyTest {
    private static final float FLOOR = 192.0f;
    private static final double GROWTH = 0.29;
    /** Higher than any deck or lower tower: where a thunderstorm's anvil is. */
    static final float ANVIL_HEIGHT = FLOOR + 600.0f;

    /** A storm born at (1000, -2000) on tick 10,000, drifting south-east, an ellipse twice as long as wide. */
    private static RegionalStorm storm(boolean thunder) {
        return new RegionalStorm(0xC0FFEEL, 1_000.0, -2_000.0, 10_000.0, 30_000.0, 0.06, 0.03, 700.0, 2.0, 0.6, 0.7f, thunder,
            thunder ? 290 : 220, GROWTH, 0.8, 1.0f);
    }

    private static CloudField fieldOf(RegionalStorm storm) {
        return new CloudField(new CloudSky(5L, FLOOR, CloudWind.CALM, RegionalSky.formations(storm, FLOOR)));
    }

    /** A point at a share of the storm's size from its centre at a tick, along a heading. */
    private static double[] at(RegionalStorm storm, double tick, double share, double heading) {
        double cos = Math.cos(storm.angle());
        double sin = Math.sin(storm.angle());
        double along = Math.cos(heading) * share * storm.size(tick) * storm.radius() * Math.sqrt(storm.stretch());
        double across = Math.sin(heading) * share * storm.size(tick) * storm.radius() / Math.sqrt(storm.stretch());
        return new double[] {storm.centreX(tick) + along * cos - across * sin, storm.centreZ(tick) + along * sin + across * cos};
    }

    @Test
    void eachFormationTravelsAndGrowsWithItsStorm() {
        RegionalStorm storm = storm(true);
        List<CloudFormation> formations = RegionalSky.formations(storm, FLOOR);
        assertEquals(List.of(CloudType.NIMBOSTRATUS, CloudType.CUMULONIMBUS), formations.stream().map(CloudFormation::type).toList());
        for (CloudFormation formation : formations) {
            assertEquals(CloudFormation.Edge.STORM, formation.edge());
            for (double tick = 9_000.0; tick <= 41_000.0; tick += 731.0) {
                assertEquals(storm.centreX(tick), formation.centreXAt(tick), 1.0e-9);
                assertEquals(storm.centreZ(tick), formation.centreZAt(tick), 1.0e-9);
                assertEquals(storm.size(tick), formation.lifeAt(tick), 1.0e-6, "size at " + tick);
            }
        }
    }

    @Test
    void theCloudIsSolidOverTheRainCoreAtAnyTick() {
        RegionalStorm storm = storm(false);
        CloudField field = fieldOf(storm);
        for (double tick : new double[] {12_000.0, 16_000.0, 25_000.0, 36_000.0, 39_000.0}) {
            int solid = 0;
            int columns = 0;
            for (double share = 0.0; share <= 0.78; share += 0.13) {
                for (double heading = 0.0; heading < 2.0 * Math.PI; heading += Math.PI / 6.0) {
                    double[] point = at(storm, tick, share, heading);
                    CloudColumn column = TestSkies.column(field, tick, point[0], point[1], 8.0);
                    columns++;
                    if (column.densityAt(column.highestTop() - 1.0f) >= 0.5f || maxDensity(column) >= 0.5f) {
                        solid++;
                    }
                }
            }
            assertTrue(solid >= columns * 0.9, "at tick " + tick + " only " + solid + " of " + columns + " columns over the rain core are cloud");
        }
    }

    @Test
    void thereIsNoStormCloudPastItsEdgeOrOutsideItsLife() {
        for (boolean thunder : new boolean[] {false, true}) {
            RegionalStorm storm = storm(thunder);
            CloudField field = fieldOf(storm);
            for (double tick : new double[] {12_000.0, 25_000.0, 39_000.0}) {
                for (double heading = 0.0; heading < 2.0 * Math.PI; heading += Math.PI / 8.0) {
                    // The outline wanders in lobes out to about 1.5 radii (Footprint, clouds-look-118); never past 1.6.
                    double[] point = at(storm, tick, 1.6, heading);
                    CloudColumn column = TestSkies.column(field, tick, point[0], point[1], 8.0);
                    // A thunderstorm's anvil spreads past its edge high up, as real ones do; nothing lower does.
                    assertTrue(column.isClear() || thunder && column.lowestBottom() > ANVIL_HEIGHT,
                        "cloud past the edge at tick " + tick + " from " + column.lowestBottom());
                }
            }
        }
        RegionalStorm storm = storm(true);
        CloudField field = fieldOf(storm);
        assertTrue(TestSkies.column(field, 9_999.0, storm.birthX(), storm.birthZ(), 8.0).isClear(), "cloud before its birth");
        assertTrue(TestSkies.column(field, 40_000.0, storm.centreX(40_000.0), storm.centreZ(40_000.0), 8.0).isClear(), "cloud after its end");
    }

    @Test
    void theCloudsTopIsOverTheStormsOwnTopAndAThunderstormTowers() {
        double tick = 25_000.0;
        for (boolean thunder : new boolean[] {false, true}) {
            RegionalStorm storm = storm(thunder);
            CloudField field = fieldOf(storm);
            float highest = 0.0f;
            for (double share = 0.0; share <= 0.5; share += 0.1) {
                double[] point = at(storm, tick, share, 1.0);
                CloudColumn column = TestSkies.column(field, tick, point[0], point[1], 8.0);
                highest = Math.max(highest, column.highestTop());
            }
            assertTrue(highest > storm.cloudTop(), "top " + highest + " under the storm's " + storm.cloudTop());
            // A rain deck reaches up through the third level, a thunderstorm into the fourth.
            if (thunder) {
                assertTrue(highest > FLOOR + CloudType.levelBottom(4), "a thunderstorm's towers reach only " + highest);
            } else {
                assertTrue(highest < FLOOR + CloudType.levelTop(3) + 100.0f, "a rain deck towers to " + highest);
            }
        }
    }

    @Test
    void itRainsFromTheCloudOverTheRainCoreOnly() {
        RegionalStorm storm = storm(false);
        CloudField field = fieldOf(storm);
        double tick = 25_000.0;
        double[] inside = at(storm, tick, 0.3, 0.5);
        assertTrue(TestSkies.column(field, tick, inside[0], inside[1], 8.0).precipitationRate() > 0.4f);
        double[] shield = at(storm, tick, 0.95, 0.5);
        assertEquals(0.0f, TestSkies.column(field, tick, shield[0], shield[1], 8.0).precipitationRate(), 1.0e-6f);
    }

    @Test
    void aStormFadingInIsThinnerCloud() {
        RegionalStorm whole = storm(false);
        RegionalStorm fading = whole.withPresence(0.3f);
        double tick = 25_000.0;
        int wholeCloud = 0;
        int fadingCloud = 0;
        for (double share = 0.0; share <= 0.8; share += 0.05) {
            double[] point = at(whole, tick, share, 2.0);
            wholeCloud += maxDensity(TestSkies.column(fieldOf(whole), tick, point[0], point[1], 8.0)) >= 0.5f ? 1 : 0;
            fadingCloud += maxDensity(TestSkies.column(fieldOf(fading), tick, point[0], point[1], 8.0)) >= 0.5f ? 1 : 0;
        }
        assertTrue(fadingCloud < wholeCloud, fadingCloud + " solid columns fading in against " + wholeCloud + " whole");
    }

    @Test
    void theSkyHasFairWeatherBetweenTheStormsFromTheSeedAlone() {
        RegionalStorm storm = storm(false);
        CloudSky one = RegionalSky.sky(42L, FLOOR, 25_000.0, List.of(storm), CloudWind.CALM, 0.0, 0.0, 8_192.0);
        CloudSky other = RegionalSky.sky(42L, FLOOR, 25_000.0, List.of(storm), CloudWind.CALM, 3_000.0, 0.0, 8_192.0);
        assertTrue(one.formations().size() > 2, "no fair-weather formations");
        // Two players 3 km apart agree on every formation both can see.
        for (CloudFormation formation : one.formations()) {
            double distance = Math.hypot(formation.centreXAt(25_000.0) - 1_500.0, formation.centreZAt(25_000.0));
            if (distance < 2_000.0) {
                assertTrue(other.formations().contains(formation), "the other player lacks " + formation.id());
            }
        }
        assertEquals(1, one.formations().stream().filter(formation -> formation.edge() == CloudFormation.Edge.STORM).count());
    }

    private static float maxDensity(CloudColumn column) {
        float most = 0.0f;
        for (int span = 0; span < column.spanCount(); span++) {
            most = Math.max(most, column.density(span));
        }
        return most;
    }
}
