package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The field is a pure function of its inputs: same sky, place and tick, same column, anywhere. */
class CloudFieldDeterminismTest {
    private static final int SIDE = 64;
    private static final double STEP = 16.0;

    @Test
    void sameInputsGiveTheSameColumns() {
        float[] first = sweep(TestSkies.vanilla(TestSkies.TICK, 0.3f, 0.1f), TestSkies.TICK, 0.0, 0.0, false);
        float[] second = sweep(TestSkies.vanilla(TestSkies.TICK, 0.3f, 0.1f), TestSkies.TICK, 0.0, 0.0, false);
        assertArrayEquals(first, second);
        assertTrue(cloudyShare(first) > 0.05, "the sweep should cross some cloud");
    }

    @Test
    void theOrderColumnsAreAskedInChangesNothing() {
        CloudField field = TestSkies.vanilla(TestSkies.TICK, 0.0f, 0.0f);
        assertArrayEquals(sweep(field, TestSkies.TICK, -500.0, 300.0, false), sweep(field, TestSkies.TICK, -500.0, 300.0, true));
    }

    @Test
    void anAreaOfOneColumnAgreesWithALargeArea() {
        CloudField field = TestSkies.vanilla(TestSkies.TICK, 0.6f, 0.4f);
        double tick = TestSkies.TICK;
        CloudField.Area large = field.area(tick, -4_000.0, -4_000.0, 4_000.0, 4_000.0);
        CloudColumn fromLarge = new CloudColumn();
        CloudColumn fromSmall = new CloudColumn();
        for (int i = 0; i < 400; i++) {
            double cloudX = -3_900.0 + (i * 977.0) % 7_800.0;
            double cloudZ = -3_900.0 + (i * 613.0) % 7_800.0;
            large.column(cloudX, cloudZ, 8.0, fromLarge);
            field.area(tick, cloudX, cloudZ, cloudX, cloudZ).column(cloudX, cloudZ, 8.0, fromSmall);
            assertArrayEquals(values(fromLarge), values(fromSmall), "column " + i);
        }
    }

    @Test
    void playersInDifferentPlacesAgreeOnTheSkyBetweenThem() {
        double tick = TestSkies.TICK;
        CloudField aroundA = new CloudField(VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, tick, 0.0f, 0.0f, -2_000.0, 0.0, TestSkies.REACH));
        CloudField aroundB = new CloudField(VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, tick, 0.0f, 0.0f, 2_500.0, 1_000.0, TestSkies.REACH));
        int cloudy = 0;
        for (int i = 0; i < 2_000; i++) {
            double x = -3_000.0 + (i * 211.0) % 6_000.0;
            double z = -3_000.0 + (i * 157.0) % 6_000.0;
            CloudColumn a = TestSkies.column(aroundA, tick, x, z, 8.0);
            CloudColumn b = TestSkies.column(aroundB, tick, x, z, 8.0);
            assertArrayEquals(values(a), values(b), "at " + x + ", " + z);
            cloudy += a.isClear() ? 0 : 1;
        }
        assertTrue(cloudy > 50, "the shared stretch should hold some cloud, had " + cloudy);
    }

    @Test
    void workerThreadsGetWhatOneThreadGets() throws Exception {
        CloudField field = TestSkies.vanilla(TestSkies.TICK, 1.0f, 1.0f);
        float[] alone = sweep(field, TestSkies.TICK, 0.0, 0.0, false);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<float[]>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> sweep(field, TestSkies.TICK, 0.0, 0.0, false)));
            }
            for (Future<float[]> result : results) {
                assertArrayEquals(alone, result.get());
            }
        } finally {
            workers.shutdown();
        }
    }

    @Test
    void theSkyIsTheSameEachTimeItIsMade() {
        CloudSky first = VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, TestSkies.TICK, 0.5f, 0.2f, 100.0, -40.0, TestSkies.REACH);
        CloudSky second = VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, TestSkies.TICK, 0.5f, 0.2f, 100.0, -40.0, TestSkies.REACH);
        assertEquals(first, second);
        CloudSky otherSeed = VanillaWeatherSky.sky(TestSkies.SEED + 1, TestSkies.BASE, TestSkies.TICK, 0.5f, 0.2f, 100.0, -40.0, TestSkies.REACH);
        assertTrue(!first.formations().equals(otherSeed.formations()), "another seed should give another sky");
    }

    /** Every column of a square, as flat numbers, in order or backwards. */
    private static float[] sweep(CloudField field, double tick, double originX, double originZ, boolean backwards) {
        CloudField.Area area = field.area(tick, originX, originZ, originX + SIDE * STEP, originZ + SIDE * STEP);
        CloudColumn column = new CloudColumn();
        int perColumn = 3 + CloudColumn.MAX_SPANS * 4;
        float[] out = new float[SIDE * SIDE * perColumn];
        for (int n = 0; n < SIDE * SIDE; n++) {
            int index = backwards ? SIDE * SIDE - 1 - n : n;
            area.column(originX + (index % SIDE) * STEP, originZ + (index / SIDE) * STEP, 8.0, column);
            float[] flat = values(column);
            System.arraycopy(flat, 0, out, index * perColumn, flat.length);
        }
        return out;
    }

    private static float[] values(CloudColumn column) {
        float[] flat = new float[3 + CloudColumn.MAX_SPANS * 4];
        flat[0] = column.spanCount();
        flat[1] = column.precipitationRate();
        flat[2] = column.precipitation().ordinal();
        for (int span = 0; span < column.spanCount(); span++) {
            flat[3 + span * 4] = column.bottom(span);
            flat[4 + span * 4] = column.top(span);
            flat[5 + span * 4] = column.density(span);
            flat[6 + span * 4] = column.storminess(span) + 10.0f * column.type(span).ordinal();
        }
        return flat;
    }

    private static double cloudyShare(float[] sweep) {
        int perColumn = 3 + CloudColumn.MAX_SPANS * 4;
        int cloudy = 0;
        for (int i = 0; i < sweep.length; i += perColumn) {
            cloudy += sweep[i] > 0 ? 1 : 0;
        }
        return cloudy / (double) (sweep.length / perColumn);
    }
}
