package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ocean waves' shore field (ShoreField), and its packing, which include/water/common.glsl (lite_shore_field) decodes. */
class ShoreFieldTest {
    private static final int N = ShoreField.SIZE;
    private static final int MIN_Y = -64;
    /** Sea level as in vanilla: water blocks at y 62 (surface 62.89), the beach's sand top at 63. */
    private static final double SEA = 62.0 + 8.0 / 9.0;
    private static final int OCEAN = OceanColumns.OCEAN_BIOME | OceanColumns.STILL | OceanColumns.SCANNED;

    private final int[] columns = new int[N * N];
    private final byte[] kinds = new byte[N * N];
    private final int[] texels = new int[N * N];

    private void water(int x, int z, int kind) {
        columns[x + z * N] = FluidColumnGrid.column(SEA, MIN_Y, 63, false, false, false, false);
        kinds[x + z * N] = (byte) kind;
    }

    private void land(int x, int z, int solidTop) {
        columns[x + z * N] = FluidColumnGrid.column(Double.NaN, MIN_Y, solidTop, false, false, false, false);
        kinds[x + z * N] = 0;
    }

    private void fill(int x0, int z0, int x1, int z1, boolean isWater, int kind, int solidTop) {
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (isWater) {
                    water(x, z, kind);
                } else {
                    land(x, z, solidTop);
                }
            }
        }
    }

    /** Land north of z 100 with its top at sea level, ocean south of it. */
    private void straightBeach(int solidTop) {
        fill(0, 0, N - 1, 99, false, 0, solidTop);
        fill(0, 100, N - 1, N - 1, true, OCEAN, 0);
    }

    private int at(int x, int z) {
        return texels[x + z * N];
    }

    private void compute() {
        new ShoreField().compute(columns, kinds, texels);
    }

    @Test
    void theDistanceIsSignedAndCrossesZeroAtTheWaterline() {
        straightBeach(63);
        compute();
        assertEquals(0.5f, ShoreField.distance(at(128, 100)), 0.13f, "first water column");
        assertEquals(-0.5f, ShoreField.distance(at(128, 99)), 0.13f, "first beach column");
        assertEquals(10.5f, ShoreField.distance(at(128, 110)), 0.13f);
        assertEquals(-3.5f, ShoreField.distance(at(128, 96)), 0.13f);
        assertEquals(ShoreField.FARTHEST, ShoreField.distance(at(128, 200)), 0.01f, "far out it saturates");
    }

    @Test
    void wavesComeInTowardTheShoreOnTheWaterAndUpTheBeach() {
        straightBeach(63);
        compute();
        for (int z : new int[] {97, 99, 100, 104, 112}) {
            assertEquals(0.0f, ShoreField.towardX(at(128, z)), 0.02f, "z " + z);
            assertEquals(-1.0f, ShoreField.towardZ(at(128, z)), 0.02f, "z " + z);
        }
    }

    @Test
    void openOceanGetsTheSwellAndABeachAtTheWaterlineGetsTheSwash() {
        straightBeach(63);
        compute();
        assertEquals(1.0f, ShoreField.swell(at(128, 160)), 0.01f);
        assertEquals(1.0f, ShoreField.swell(at(128, 100)), 0.01f, "right up to the shore");
        assertTrue(ShoreField.swell(at(128, 99)) > 0.95f, "the first beach column");
        assertTrue(ShoreField.swell(at(128, 97)) > 0.95f, "three blocks up the beach");
        assertEquals(0.0f, ShoreField.swell(at(128, 90)), "too far up the beach");
    }

    @Test
    void aBeachAboveTheWaterGetsNoSwash() {
        straightBeach(64);
        compute();
        assertEquals(0.0f, ShoreField.swell(at(128, 99)), "a block above the water");
        assertTrue(ShoreField.swell(at(128, 120)) > 0.99f, "the sea still has its swell");
    }

    @Test
    void aPondInAnOceanBiomeAndARiverStayCalm() {
        fill(0, 0, N - 1, N - 1, false, 0, 63);
        fill(40, 40, 46, 46, true, OCEAN, 0);
        int river = OceanColumns.STILL | OceanColumns.SCANNED;
        fill(100, 0, 109, N - 1, true, river, 0);
        compute();
        assertEquals(0.0f, ShoreField.swell(at(43, 43)), "pond");
        assertEquals(0.0f, ShoreField.swell(at(104, 128)), "river ten wide outside an ocean");
    }

    @Test
    void aLargeLakeGetsSwellOutsideAnOceanBiome() {
        fill(0, 0, N - 1, N - 1, false, 0, 63);
        fill(60, 60, 159, 159, true, OceanColumns.STILL | OceanColumns.SCANNED, 0);
        compute();
        assertTrue(ShoreField.swell(at(110, 110)) > 0.95f, "the middle of a hundred-block lake");
        assertTrue(ShoreField.swell(at(110, 62)) > 0.5f, "and near its shore");
    }

    @Test
    void flowingWaterStepsAside() {
        straightBeach(63);
        fill(0, 100, N - 1, N - 1, true, OceanColumns.OCEAN_BIOME | OceanColumns.SCANNED, 0);
        compute();
        assertEquals(0.0f, ShoreField.swell(at(128, 150)));
    }

    @Test
    void waterUnderAPierTakesTheSwellAroundIt() {
        straightBeach(63);
        // Covered water: found under the deck, never scanned for its kind.
        fill(120, 100, 122, 115, true, 0, 0);
        compute();
        assertTrue(ShoreField.swell(at(121, 110)) > 0.99f, "under the pier");
        assertTrue(ShoreField.swell(at(123, 110)) > 0.99f, "beside it, no calm edge");
    }

    @Test
    void aPostInTheSeaDoesNotTurnTheWavesButAnIslandDoes() {
        fill(0, 0, N - 1, N - 1, true, OCEAN, 0);
        land(60, 60, 70);
        fill(150, 150, 154, 154, false, 0, 63);
        compute();
        assertEquals(ShoreField.FARTHEST, ShoreField.distance(at(61, 60)), 0.01f, "beside the post");
        assertTrue(ShoreField.swell(at(61, 60)) > 0.99f, "no calm patch around it");
        assertTrue(ShoreField.swell(at(60, 60)) > 0.99f, "the post's own column, for the blend between centres");
        assertEquals(0.5f, ShoreField.distance(at(155, 152)), 0.13f, "beside a five-block island");
        assertEquals(-1.0f, ShoreField.towardX(at(158, 152)), 0.02f, "waves turn toward the island");
    }

    @Test
    void distancesAreEuclideanAlongADiagonalShore() {
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                if (x + z < 256) {
                    land(x, z, 63);
                } else {
                    water(x, z, OCEAN);
                }
            }
        }
        compute();
        double diagonal = Math.sqrt(0.5);
        for (int[] cell : new int[][] {{130, 135}, {140, 128}, {150, 125}, {141, 141}}) {
            int x = cell[0];
            int z = cell[1];
            // To the nearest land column's centre, along the diagonal.
            double exact = (x + z - 255) * diagonal;
            assertEquals(exact - 0.5, ShoreField.distance(at(x, z)), 0.3, x + "," + z);
            assertEquals(-diagonal, ShoreField.towardX(at(x, z)), 0.03, x + "," + z);
            assertEquals(-diagonal, ShoreField.towardZ(at(x, z)), 0.03, x + "," + z);
        }
    }

    @Test
    void unknownColumnsAreNeverAShoreAndGetNoSwell() {
        fill(0, 100, N - 1, N - 1, true, OCEAN, 0);
        compute();
        assertEquals(ShoreField.FARTHEST, ShoreField.distance(at(128, 110)), 0.01f, "water beside unloaded land");
        assertEquals(0.0f, ShoreField.swell(at(128, 50)));
    }

    @Test
    void texelsDecodeTheWayTheShaderReadsThem() {
        float[][] values = {{0.5f, 0.0f, -1.0f, 1.0f}, {-3.25f, 0.6f, 0.8f, 0.5f}, {40.0f, -0.70710677f, 0.70710677f, 0.0f}};
        for (float[] value : values) {
            int texel = ShoreField.texel(value[0], value[1], value[2], value[3]);
            int red = texel & 0xFF;
            int green = (texel >> 8) & 0xFF;
            int blue = (texel >> 16) & 0xFF;
            int alpha = texel >>> 24;
            green -= green > 127 ? 256 : 0;
            blue -= blue > 127 ? 256 : 0;
            assertEquals(value[0], red / 4.0f - 8.0f, 0.13f);
            assertEquals(value[1], green / 127.0f, 0.005f);
            assertEquals(value[2], blue / 127.0f, 0.005f);
            assertEquals(value[3], alpha / 255.0f, 0.002f);
        }
    }
}
