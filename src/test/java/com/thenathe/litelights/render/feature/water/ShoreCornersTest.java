package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The waves' distance to the waterline round block corners (corners): the shore field read the way
 * include/water/common.glsl reads it (ShoreDistanceMirror), on the island the QA builds.
 */
class ShoreCornersTest {
    private static final int N = ShoreField.SIZE;
    private static final int OCEAN = OceanColumns.OCEAN_BIOME | OceanColumns.STILL | OceanColumns.SCANNED;
    /** The island's corner in the window. */
    private static final int X = 120;
    private static final int Z = 120;

    private final int[] columns = new int[N * N];
    private final byte[] kinds = new byte[N * N];
    private final int[] texels = new int[N * N];
    private ShoreDistanceMirror field;

    private void fill(int x0, int z0, int x1, int z1, boolean water) {
        fill(x0, z0, x1, z1, water, 63);
    }

    private void fill(int x0, int z0, int x1, int z1, boolean water, int solidTop) {
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int i = (X + x) + (Z + z) * N;
                columns[i] = water
                    ? FluidColumnGrid.column(62.0 + 8.0 / 9.0, -64, 60, false, false, false, false)
                    : FluidColumnGrid.column(Double.NaN, -64, solidTop, false, false, false, false);
                kinds[i] = (byte) (water ? OCEAN : 0);
            }
        }
    }

    /**
     * OceanWavesQa's island, at the beach's level in the open sea: a body with a notch cut from one
     * corner, a bump on one side, a ledge on another and two stair steps.
     */
    @BeforeEach
    void island() {
        fill(-X, -Z, N - 1 - X, N - 1 - Z, true);
        fill(2, 2, 13, 9, false);
        fill(10, 2, 13, 4, true);
        fill(5, 0, 8, 1, false);
        fill(0, 5, 1, 9, false);
        fill(14, 6, 14, 9, false);
        fill(15, 8, 15, 9, false);
        new ShoreField().compute(columns, kinds, texels);
        field = new ShoreDistanceMirror(texels);
    }

    private float distance(double x, double z) {
        return field.distance(X + x, Z + z);
    }

    private float blended(double x, double z) {
        return field.blended(X + x, Z + z);
    }

    /** OceanWavesQa's raised L of stone beside the island: a wall, a block above the water. */
    private void raisedL() {
        fill(18, 13, 25, 15, false, 64);
        fill(18, 16, 20, 20, false, 64);
        new ShoreField().compute(columns, kinds, texels);
        field = new ShoreDistanceMirror(texels);
    }

    @Test
    void aStraightShoreKeepsTheDistanceItHad() {
        // The island's south side runs straight along z 10 from x 0 to 16.
        for (double x = 3.0; x <= 13.0; x += 0.125) {
            for (double z = 8.5; z <= 11.5; z += 0.0625) {
                assertEquals(blended(x, z), distance(x, z), 1.0e-5f, x + "," + z);
                assertEquals(z - 10.0, distance(x, z), 1.0e-4, x + "," + z);
                assertEquals(field.blendedStrength(X + x, Z + z), field.strength(X + x, Z + z), 0.0f, x + "," + z);
            }
        }
    }

    @Test
    void anOuterCornerIsRound() {
        // Land at (9, 2), water on its other three sides of the point (10, 2).
        assertTrue(blended(10.0, 2.0) > 0.3f, "the blend put the corner a third of a block out on the water");
        assertEquals(0.0f, distance(10.0, 2.0), 1.0e-4f);
        for (double t = 0.05; t < 0.7; t += 0.05) {
            double out = t * Math.sqrt(0.5);
            assertEquals(t, distance(10.0 + out, 2.0 - out), 1.0e-4, "out on the water " + t);
            assertEquals(-out, distance(10.0 - out, 2.0 + out), 1.0e-4, "on the land " + t);
        }
    }

    @Test
    void anInnerCornerIsSquare() {
        // Water at (10, 4), land west, south and south-west of it, meeting at the point (10, 5).
        assertTrue(blended(10.0, 5.0) < -0.3f, "the blend put the corner a third of a block up the land");
        assertEquals(0.0f, distance(10.0, 5.0), 1.0e-4f);
        for (double t = 0.05; t < 0.5; t += 0.05) {
            assertEquals(t, distance(10.0 + t, 5.0 - t), 1.0e-4, "out on the water " + t);
            assertEquals(-t * Math.sqrt(2.0), distance(10.0 - t, 5.0 + t), 1.0e-4, "on the land " + t);
        }
    }

    /**
     * The waterline runs along the blocks' edges, round every corner. Land the waves pass by (thin
     * land: here the outer stair step, a column two blocks from the island's body) is on the water's
     * side, as it always was.
     */
    @Test
    void theWaterlineFollowsTheBlocksAllRoundTheIsland() {
        for (double x = -3.0; x <= 19.0; x += 0.05) {
            for (double z = -3.0; z <= 13.0; z += 0.05) {
                int column = (X + (int) Math.floor(x)) + (Z + (int) Math.floor(z)) * N;
                boolean land = ShoreField.distance(texels[column]) < 0.0f;
                float d = distance(x, z);
                assertTrue(land ? d <= 0.0f : d >= 0.0f, x + "," + z + ": " + d);
            }
        }
    }

    @Test
    void theDistanceJoinsTheBlendWithoutASeam() {
        double step = 0.01;
        float steepest = 0.0f;
        for (double x = -3.0; x <= 19.0; x += step) {
            for (double z = -3.0; z <= 13.0; z += step) {
                float here = distance(x, z);
                steepest = Math.max(steepest, Math.abs(distance(x + step, z) - here));
                steepest = Math.max(steepest, Math.abs(distance(x, z + step) - here));
            }
        }
        // The exact distance changes by at most a step per step, the blend a little more; a seam
        // would jump by far more than this between two points a hundredth of a block apart.
        assertTrue(steepest < 3.0 * step, "steepest change " + steepest);
    }

    @Test
    void besideAWallTheSwellFadesRoundCornersAsAlongIt() {
        raisedL();
        float alongTheWall = field.strength(X + 23.5, Z + 16.0);
        assertTrue(alongTheWall > 0.4f, "the swell at the wall's face, half the water's: " + alongTheWall);
        // The inner corner at (21, 16) and the outer one at (26, 13).
        assertTrue(field.blendedStrength(X + 21.0, Z + 16.0) < 0.6f * alongTheWall, "the blend halved it in the inner corner");
        assertEquals(alongTheWall, field.strength(X + 21.0, Z + 16.0), 0.03f, "inner corner");
        assertEquals(alongTheWall, field.strength(X + 26.0, Z + 13.0), 0.03f, "outer corner");
        assertEquals(alongTheWall, field.strength(X + 21.3, Z + 16.0), 0.03f, "along the inner corner's face");
        float step = 0.01f;
        float steepest = 0.0f;
        for (double x = 15.0; x <= 28.0; x += step) {
            for (double z = 10.0; z <= 23.0; z += step) {
                float here = field.strength(X + x, Z + z);
                steepest = Math.max(steepest, Math.abs(field.strength(X + x + step, Z + z) - here));
                steepest = Math.max(steepest, Math.abs(field.strength(X + x, Z + z + step) - here));
            }
        }
        assertTrue(steepest < 3.0f * step, "steepest change " + steepest);
    }

    @Test
    void onABeachLevelIslandTheSwellIsTheSameRoundCorners() {
        for (double[] point : new double[][] {{10.0, 2.0}, {10.0, 5.0}, {10.3, 4.7}, {9.7, 2.3}}) {
            assertEquals(field.strength(X + 12.0, Z + 10.0), field.strength(X + point[0], Z + point[1]), 0.02f,
                point[0] + "," + point[1]);
        }
    }

    @Test
    void theOuterStairStepIsThinLand() {
        assertTrue(ShoreField.distance(texels[(X + 15) + (Z + 9) * N]) > 0.0f);
        assertTrue(ShoreField.distance(texels[(X + 14) + (Z + 9) * N]) < 0.0f);
    }

    @Test
    void farFromTheIslandNothingChanges() {
        for (double x = -9.0; x <= 25.0; x += 0.25) {
            for (double z : new double[] {-9.0, -6.5, 15.0, 18.25}) {
                assertEquals(blended(x, z), distance(x, z), 0.0f, x + "," + z);
                assertEquals(field.blendedStrength(X + x, Z + z), field.strength(X + x, Z + z), 0.0f, x + "," + z);
            }
        }
    }
}
