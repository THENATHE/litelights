package com.thenathe.litelights.render.feature.clouds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.almanac.api.Storm;
import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.CloudFormation;
import com.thenathe.litelights.clouds.field.CloudSky;
import com.thenathe.litelights.clouds.field.CloudWind;
import com.thenathe.litelights.clouds.field.RegionalSky;
import com.thenathe.litelights.clouds.field.RegionalStorm;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Almanac's storms as the Dynamic clouds draw them, against Almanac's own closed-form answers
 * (its {@code Storm} record, from the Almanac 1.0.0 jar): at any tick the clouds are where the
 * storm is, as big as it is, alive when it is, solid where it rains and gone past its edge.
 */
class AlmanacStormsTest {
    private static final float FLOOR = 192.0f;

    /** Storms like a year of Almanac's: every season's sizes, stretches, lives and speeds, some thunderstorms, and still ones. */
    private static List<Storm> storms() {
        Random random = new Random(0x57A2L);
        List<Storm> storms = new ArrayList<>();
        for (int n = 0; n < 24; n++) {
            double radius = 300.0 * Math.pow(6.0, random.nextDouble());
            double stretch = 1.0 + 2.0 * random.nextDouble() * random.nextDouble();
            int life = 8_000 + random.nextInt(44_000);
            double speed = n % 6 == 0 ? 0.0 : 0.04 + 0.08 * random.nextDouble();
            double heading = random.nextDouble() * Math.PI * 2.0;
            boolean thunder = random.nextBoolean();
            storms.add(new Storm(random.nextLong(), random.nextDouble() * 20_000.0 - 10_000.0, random.nextDouble() * 20_000.0 - 10_000.0,
                100_000L + random.nextInt(50_000), life, Math.cos(heading) * speed, Math.sin(heading) * speed, radius, stretch,
                random.nextDouble() * Math.PI * 2.0, (float) (0.35 + 0.65 * random.nextDouble()), thunder,
                thunder ? 250 + random.nextInt(70) : 200 + random.nextInt(40), random.nextFloat()));
        }
        return storms;
    }

    private static double[] ticksOf(Storm storm) {
        double[] ticks = new double[21];
        for (int i = 0; i < ticks.length; i++) {
            ticks[i] = storm.birthTick() - 500.0 + (storm.lifeTicks() + 1_000.0) * i / (ticks.length - 1);
        }
        return ticks;
    }

    @Test
    void theGrowthShareIsAlmanacsOwn() {
        assertEquals(Storm.GROWTH, AlmanacStorms.growShare(storms().getFirst()), 1.0e-9);
    }

    @Test
    void placeSizeAndLifeAreAlmanacsAtAnyTick() {
        for (Storm storm : storms()) {
            RegionalStorm cloud = AlmanacStorms.of(storm);
            List<CloudFormation> formations = RegionalSky.formations(cloud, FLOOR);
            for (double tick : ticksOf(storm)) {
                assertEquals(storm.aliveAt(tick), cloud.aliveAt(tick), "alive at " + tick);
                assertEquals(storm.centreX(tick), cloud.centreX(tick), 1.0e-9);
                assertEquals(storm.centreZ(tick), cloud.centreZ(tick), 1.0e-9);
                assertEquals(storm.size(tick), cloud.size(tick), 1.0e-9, "size at " + tick);
                for (CloudFormation formation : formations) {
                    assertEquals(storm.centreX(tick), formation.centreXAt(tick), 1.0e-9);
                    assertEquals(storm.centreZ(tick), formation.centreZAt(tick), 1.0e-9);
                    assertEquals(storm.size(tick), formation.lifeAt(tick), 1.0e-6, "the formation's growth at " + tick);
                }
            }
        }
    }

    @Test
    void theCloudIsSolidWhereItRainsAndGonePastTheStormsEdge() {
        Random random = new Random(7L);
        int rainy = 0;
        int rainyUnderCloud = 0;
        for (Storm storm : storms()) {
            CloudField field = new CloudField(new CloudSky(3L, FLOOR, CloudWind.CALM, RegionalSky.formations(AlmanacStorms.of(storm), FLOOR)));
            CloudColumn column = new CloudColumn();
            for (double tick : ticksOf(storm)) {
                for (int sample = 0; sample < 24; sample++) {
                    double reach = storm.majorAxis() * 1.4;
                    double x = storm.centreX(tick) + (random.nextDouble() * 2.0 - 1.0) * reach;
                    double z = storm.centreZ(tick) + (random.nextDouble() * 2.0 - 1.0) * reach;
                    field.sampleWorld(tick, x, z, 8.0, column);
                    if (storm.rainsOn(x, z, tick)) {
                        rainy++;
                        rainyUnderCloud += solid(column) ? 1 : 0;
                    }
                    double size = storm.size(tick);
                    // The cloud's outline wanders in lobes out to about 1.5 of the storm's size (Footprint, clouds-look-118).
                    if (size <= 0.0 || storm.distance(x, z, tick) > 1.6 * size) {
                        // A thunderstorm's anvil spreads past its edge high up, as real ones do; nothing lower does.
                        assertTrue(column.isClear() || storm.thunder() && column.lowestBottom() > FLOOR + 600.0f,
                            "storm cloud past the storm's edge at " + tick + " (" + storm.distance(x, z, tick) / size + ")");
                    }
                }
            }
        }
        assertTrue(rainy > 500, "too few rainy samples: " + rainy);
        assertTrue(rainyUnderCloud >= rainy * 0.95, rainyUnderCloud + " of " + rainy + " columns Almanac rains on are under solid cloud");
    }

    @Test
    void theCloudComesBeforeTheRainOverAPlaceTheStormPasses() {
        for (Storm storm : storms()) {
            double speed = Math.hypot(storm.velocityX(), storm.velocityZ());
            if (speed == 0.0) {
                continue;
            }
            CloudField field = new CloudField(new CloudSky(3L, FLOOR, CloudWind.CALM, RegionalSky.formations(AlmanacStorms.of(storm), FLOOR)));
            CloudColumn column = new CloudColumn();
            // A place on the storm's path, at its middle at mid-life.
            double middle = storm.birthTick() + storm.lifeTicks() * 0.5;
            double x = storm.centreX(middle);
            double z = storm.centreZ(middle);
            double firstCloud = Double.NaN;
            double firstRain = Double.NaN;
            for (double tick = storm.birthTick(); tick < middle; tick += 20.0) {
                if (Double.isNaN(firstCloud)) {
                    field.sampleWorld(tick, x, z, 8.0, column);
                    firstCloud = solid(column) ? tick : Double.NaN;
                }
                if (Double.isNaN(firstRain) && storm.rainsOn(x, z, tick)) {
                    firstRain = tick;
                }
            }
            assertTrue(!Double.isNaN(firstRain), "the place is never rained on");
            assertTrue(!Double.isNaN(firstCloud) && firstCloud <= firstRain,
                "the cloud came at " + firstCloud + ", the rain at " + firstRain);
        }
    }

    private static boolean solid(CloudColumn column) {
        for (int span = 0; span < column.spanCount(); span++) {
            if (column.density(span) >= 0.5f) {
                return true;
            }
        }
        return false;
    }
}
