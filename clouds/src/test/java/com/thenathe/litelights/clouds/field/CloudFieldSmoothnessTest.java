package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Nothing changes in a step: watched from one place in cloud space, tick after tick, cloud grows
 * and fades gradually, in clear weather, as rain sets in and as thunderstorms build. A later stage
 * can then grow and fade cubes instead of popping them.
 */
class CloudFieldSmoothnessTest {
    private static final int POINTS = 150;
    private static final float LOWEST = TestSkies.BASE - 120.0f;
    private static final int BANDS = 46;
    private static final float BAND_HEIGHT = 32.0f;
    /**
     * The most cloud a 32-block band may gain or lose in one tick, in blocks of solid cloud: half
     * of one 8-block cube. A band then takes at least two seconds to fill.
     */
    private static final double MOST_CHANGE_PER_TICK = 4.0;

    @Test
    void aClearSkyChangesSmoothly() {
        Watch watch = watch(TestSkies.TICK, 600, tick -> new float[] {0.0f, 0.0f});
        report("clear", watch);
        assertTrue(watch.largestChange <= MOST_CHANGE_PER_TICK, "a band changed by " + watch.largestChange + " in one tick");
        assertTrue(watch.mostFlips <= 2, "a cell went solid and clear " + watch.mostFlips + " times");
        assertTrue(watch.totalChange > 1.0, "the sky should change at all over half a minute");
    }

    @Test
    void rainSettingInChangesSmoothly() {
        CloudWeather weather = new CloudWeather();
        weather.tick(0.0f, 0.0f);
        Watch watch = watch(TestSkies.TICK, 1_400, tick -> {
            weather.tick(1.0f, 0.0f);
            return new float[] {weather.rain(), weather.thunder()};
        });
        report("rain setting in", watch);
        assertTrue(watch.largestChange <= MOST_CHANGE_PER_TICK, "a band changed by " + watch.largestChange + " in one tick");
        assertTrue(watch.mostFlips <= 2, "a cell went solid and clear " + watch.mostFlips + " times");
    }

    @Test
    void thunderstormsBuildSmoothly() {
        CloudWeather weather = new CloudWeather();
        weather.tick(1.0f, 0.0f);
        Watch watch = watch(TestSkies.TICK, 1_400, tick -> {
            weather.tick(1.0f, 1.0f);
            return new float[] {weather.rain(), weather.thunder()};
        });
        report("thunder building", watch);
        assertTrue(watch.largestChange <= MOST_CHANGE_PER_TICK, "a band changed by " + watch.largestChange + " in one tick");
        assertTrue(watch.mostFlips <= 2, "a cell went solid and clear " + watch.mostFlips + " times");
    }

    @Test
    void aFormationGrowsFromNothingAndEndsInNothing() {
        CloudFormation formation = new CloudFormation(1L, CloudType.CUMULUS, 0.0, 0.0, 0.0, 0.0, 1_000.0, 10_000.0,
            0.2f, 0.3f, 1_000.0, 1.0f, 0.0f, 0.5f, 192.0f, 392.0f, 1.0f, 0.0f, Precipitation.NONE, 0.0f, 0.0f, 1.0f);
        assertEquals(0.0f, formation.lifeAt(1_000.0));
        assertEquals(0.0f, formation.lifeAt(11_000.0));
        assertEquals(1.0f, formation.lifeAt(5_000.0));
        float largestStep = 0.0f;
        for (double tick = 900.0; tick < 11_100.0; tick += 1.0) {
            largestStep = Math.max(largestStep, Math.abs(formation.lifeAt(tick + 1.0) - formation.lifeAt(tick)));
        }
        // Growing over 2,000 ticks, the steepest step of a smoothstep is 1.5 / 2,000.
        assertTrue(largestStep <= 1.5f / 2_000.0f + 1.0e-6f, "life stepped by " + largestStep);
    }

    @Test
    void theWindsDriftHasNoStepWhereItsTurnStartsAgain() {
        CloudWind wind = VanillaWeatherSky.wind(CloudNoise.hash(TestSkies.SEED, 0x0C10D5L));
        double turn = wind.turnTicks();
        for (int turns = 1; turns < 5_000; turns *= 7) {
            double at = turn * turns;
            double before = wind.driftX(at - 0.5);
            double after = wind.driftX(at + 0.5);
            assertEquals(wind.velocityX(at), after - before, 1.0e-6, "drift across turn " + turns);
            assertEquals(wind.velocityZ(at), wind.driftZ(at + 0.5) - wind.driftZ(at - 0.5), 1.0e-6);
        }
    }

    /** Weather for a tick, as {rain, thunder}. */
    private interface WeatherAt {
        float[] at(double tick);
    }

    private static final class Watch {
        double largestChange;
        double totalChange;
        int mostFlips;
        int solidCells;
    }

    /**
     * Watches {@link #POINTS} places of cloud space for a number of ticks, the sky made afresh each
     * tick from the weather, recording the largest change of any band in a tick and how often any
     * 8-block cell turned solid or clear.
     */
    private static Watch watch(double firstTick, int ticks, WeatherAt weather) {
        Watch watch = new Watch();
        double[][] previous = new double[POINTS][];
        boolean[][] solid = new boolean[POINTS][BANDS * 4];
        int[][] flips = new int[POINTS][BANDS * 4];
        CloudColumn column = new CloudColumn();
        double[] pointX = new double[POINTS];
        double[] pointZ = new double[POINTS];
        CloudField first = TestSkies.vanilla(firstTick, 0.0f, 0.0f);
        for (int i = 0; i < POINTS; i++) {
            // Spread over 6 km around the camera's place in cloud space at the start.
            pointX[i] = -3_000.0 + (i * 2_741.0) % 6_000.0 - first.driftX(firstTick);
            pointZ[i] = -3_000.0 + (i * 1_913.0) % 6_000.0 - first.driftZ(firstTick);
        }
        for (int step = 0; step <= ticks; step++) {
            double tick = firstTick + step;
            float[] levels = weather.at(tick);
            CloudField field = TestSkies.vanilla(tick, levels[0], levels[1]);
            CloudField.Area area = field.area(tick, -3_100.0 - field.driftX(firstTick), -3_100.0 - field.driftZ(firstTick),
                3_100.0 - field.driftX(firstTick), 3_100.0 - field.driftZ(firstTick));
            for (int i = 0; i < POINTS; i++) {
                area.column(pointX[i], pointZ[i], 8.0, column);
                double[] bands = TestSkies.bands(column, LOWEST, BANDS, BAND_HEIGHT);
                if (previous[i] != null) {
                    for (int band = 0; band < BANDS; band++) {
                        double change = Math.abs(bands[band] - previous[i][band]);
                        watch.largestChange = Math.max(watch.largestChange, change);
                        watch.totalChange += change;
                    }
                }
                previous[i] = bands;
                for (int cell = 0; cell < BANDS * 4; cell++) {
                    boolean isSolid = column.densityAt(LOWEST + cell * 8.0f + 4.0f) >= 0.5f;
                    if (step > 0 && isSolid != solid[i][cell]) {
                        flips[i][cell]++;
                        watch.mostFlips = Math.max(watch.mostFlips, flips[i][cell]);
                    }
                    solid[i][cell] = isSolid;
                    watch.solidCells += isSolid ? 1 : 0;
                }
            }
        }
        return watch;
    }

    private static void report(String name, Watch watch) {
        System.out.println(String.format(Locale.ROOT,
            "cloud field smoothness, %s: largest change of a 32-block band in one tick %.3f blocks, most flips of an 8-block cell %d, total change %.0f, solid cell-ticks %d",
            name, watch.largestChange, watch.mostFlips, watch.totalChange, watch.solidCells));
    }
}
