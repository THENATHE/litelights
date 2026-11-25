package com.thenathe.litelights.render.feature.weather.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.almanac.api.SnowLayers;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The visual snow's layers follow Almanac's rule: the same shares give the same layers at the same
 * column, held to a step of the share at most.
 */
class SnowLayerRuleTest {
    private static float[] exactShares(SnowLayers layers) {
        float[] shares = new float[layers.maxLayers()];
        for (int layer = 1; layer <= shares.length; layer++) {
            shares[layer - 1] = (float) layers.share(layer);
        }
        return shares;
    }

    private static float[] heldShares(SnowLayers layers) {
        float[] shares = new float[layers.maxLayers()];
        for (int layer = 1; layer <= shares.length; layer++) {
            shares[layer - 1] = SnowLayerRule.quantize(layers.share(layer));
        }
        return shares;
    }

    @Test
    void theSameSharesGiveAlmanacsLayersAtEveryPoint() {
        Random random = new Random(7);
        for (int trial = 0; trial < 20_000; trial++) {
            int maxLayers = 1 + random.nextInt(8);
            SnowLayers almanac = SnowLayers.of(random.nextDouble() * maxLayers, maxLayers);
            double point = random.nextDouble();
            // Almanac keeps doubles; a point exactly between a double share and its float can differ, so step off it.
            float[] shares = exactShares(almanac);
            boolean onAnEdge = false;
            for (float share : shares) {
                onAnEdge |= Math.abs(point - share) < 1e-6;
            }
            if (!onAnEdge) {
                assertEquals(almanac.layersAt(point), SnowLayerRule.layersAt(shares, point), almanac + " at " + point);
            }
        }
    }

    @Test
    void heldSharesNeverShowMoreAndLagAtMostAStep() {
        Random random = new Random(11);
        for (int trial = 0; trial < 20_000; trial++) {
            SnowLayers almanac = SnowLayers.of(random.nextDouble() * 3.0, 3);
            double point = random.nextDouble();
            int held = SnowLayerRule.layersAt(heldShares(almanac), point);
            assertTrue(held <= almanac.layersAt(point), "held shares never show snow Almanac hasn't laid");
            assertTrue(held >= almanac.layersAt(point + 1.0 / SnowLayerRule.STEPS) || point + 1.0 / SnowLayerRule.STEPS >= 1.0,
                "and are at most a step behind it");
        }
    }

    @Test
    void theNinetyPercentRuleShowsInTheLayers() {
        // At a depth of 1.0 with three layers allowed, Almanac's shares are 95 %, 5 % and 0.
        float[] shares = heldShares(SnowLayers.of(1.0, 3));
        assertEquals(3, shares.length);
        assertEquals(30.0f / 32.0f, shares[0], 1e-6);
        assertEquals(1.0f / 32.0f, shares[1], 1e-6);
        assertEquals(0.0f, shares[2]);
        assertEquals(2, SnowLayerRule.layersAt(shares, 0.01), "the lowest points hold a second layer first");
        assertEquals(1, SnowLayerRule.layersAt(shares, 0.5));
        assertEquals(0, SnowLayerRule.layersAt(shares, 0.97));
    }

    @Test
    void aColumnWithASecondLayerAlwaysHasAFirst() {
        Random random = new Random(3);
        for (int trial = 0; trial < 5_000; trial++) {
            float[] shares = heldShares(SnowLayers.of(random.nextDouble() * 3.0, 3));
            for (int layer = 1; layer < shares.length; layer++) {
                assertTrue(shares[layer] <= shares[layer - 1]);
            }
        }
    }

    @Test
    void theThawTakesTheSameLayersAway() {
        float[] deep = heldShares(SnowLayers.of(2.5, 3));
        float[] thawing = heldShares(SnowLayers.of(1.5, 3));
        for (double point = 0.0; point < 1.0; point += 0.001) {
            assertTrue(SnowLayerRule.layersAt(thawing, point) <= SnowLayerRule.layersAt(deep, point),
                "a column never gains snow as the depth falls");
        }
    }

    @Test
    void pointsAreEvenlySpreadSoTheShareOfColumnsIsTheShare() {
        int[] buckets = new int[10];
        int columns = 0;
        for (int x = -200; x < 200; x++) {
            for (int z = -200; z < 200; z++) {
                double point = SnowLayerRule.point(x, z);
                assertTrue(point >= 0.0 && point < 1.0);
                buckets[(int) (point * 10)]++;
                columns++;
            }
        }
        for (int bucket : buckets) {
            assertEquals(columns / 10.0, bucket, columns * 0.005);
        }
        float[] forty = {SnowLayerRule.quantize(0.40625)};
        int white = 0;
        for (int x = 0; x < 300; x++) {
            for (int z = 0; z < 300; z++) {
                white += SnowLayerRule.layersAt(forty, SnowLayerRule.point(x, z));
            }
        }
        assertEquals(0.40625, white / 90_000.0, 0.01, "40 % of the snowable ground white: about 40 % of the fake snow too");
    }

    @Test
    void changedBucketsAreThoseAShareMovedThrough() {
        float[] before = {SnowLayerRule.quantize(3.0 / 32), 0.0f};
        float[] after = {SnowLayerRule.quantize(5.0 / 32), SnowLayerRule.quantize(1.0 / 32)};
        assertEquals(0b11001, SnowLayerRule.changedBuckets(before, after));
        assertEquals(0, SnowLayerRule.changedBuckets(after, after));
        assertEquals(SnowLayerRule.changedBuckets(before, after), SnowLayerRule.changedBuckets(after, before), "thaw and build alike");
        for (int column = 0; column < 2000; column++) {
            double point = SnowLayerRule.point(column, 0);
            boolean changes = SnowLayerRule.layersAt(before, point) != SnowLayerRule.layersAt(after, point);
            boolean marked = (SnowLayerRule.changedBuckets(before, after) & 1 << SnowLayerRule.bucket(point)) != 0;
            assertTrue(!changes || marked, "every column whose layers change is in a marked bucket");
        }
    }

    @Test
    void withoutAlmanacOneLayerOnTheMeasuredShare() {
        SnowShares measured = SnowShares.oneLayer(0.5);
        assertEquals(1, measured.layersAt(0, 0.2));
        assertEquals(0, measured.layersAt(0, 0.7));
        assertEquals(1, measured.deepest());
        assertEquals(0, SnowShares.oneLayer(0.0).layersAt(0, 0.0));
    }
}
