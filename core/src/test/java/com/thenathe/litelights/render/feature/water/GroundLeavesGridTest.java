package com.thenathe.litelights.render.feature.water;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where leaves lie under trees, as include/vegetation/ground_leaves.glsl reads it. */
class GroundLeavesGridTest {
    private static final int MIN_Y = -64;
    private static final int OAK = 0x4A7A28;
    private static final int GRASS_TINT = 0x80B050;
    private static final GroundLeavesGrid.Tints GRASS = (x, y, z) -> GRASS_TINT;
    private static final Path SHADERS = Path.of("src/main/resources/assets/litelights/shaders/include");

    private static FluidColumnGrid columns(int canopyX, int canopyZ) {
        FluidColumnGrid columns = new FluidColumnGrid();
        columns.setWindow(-128, -128);
        for (int z = -8; z <= 8; z++) {
            for (int x = -8; x <= 8; x++) {
                boolean canopy = x == canopyX && z == canopyZ;
                columns.set(x, z, FluidColumnGrid.column(Double.NaN, MIN_Y, 71, false, false, canopy, false));
            }
        }
        return columns;
    }

    private static GroundLeavesGrid groundAround(int canopyX, int canopyZ, int canopyColour) {
        GroundLeavesGrid ground = new GroundLeavesGrid();
        for (int z = -8; z <= 8; z++) {
            for (int x = -8; x <= 8; x++) {
                boolean canopy = x == canopyX && z == canopyZ;
                ground.set(x, z, canopy ? canopyColour : -1, 70, MIN_Y);
            }
        }
        return ground;
    }

    @Test
    void groundUnderACanopyGetsItsColourOverTheGroundsTintAndItsHeight() {
        int texel = groundAround(0, 0, OAK).texel(0, 0, MIN_Y, columns(0, 0), GRASS);
        assertEquals(Math.round(0x4A / (double) 0x80 * 64), texel & 0xFF);
        assertEquals(Math.round(0x7A / (double) 0xB0 * 64), (texel >> 8) & 0xFF);
        assertEquals(Math.round(0x28 / (double) 0x50 * 64), (texel >> 16) & 0xFF);
        int alpha = texel >>> 24;
        assertEquals(1, alpha & 15, "one canopy column nearby, the floating leaves' density");
        assertEquals(70 & 15, alpha >> 4);
    }

    @Test
    void theCanopyReachesTwoColumnsAndNoFurther() {
        GroundLeavesGrid ground = groundAround(0, 0, OAK);
        FluidColumnGrid columns = columns(0, 0);
        assertTrue(ground.texel(2, -2, MIN_Y, columns, GRASS) != 0);
        assertEquals(0, ground.texel(3, 0, MIN_Y, columns, GRASS));
    }

    @Test
    void groundLeavesCantLieOnHasNone() {
        GroundLeavesGrid ground = groundAround(0, 0, OAK);
        ground.set(1, 0, -1, Integer.MIN_VALUE, MIN_Y);
        assertEquals(0, ground.texel(1, 0, MIN_Y, columns(0, 0), GRASS));
    }

    @Test
    void theNearestCanopyCountsMostAndTwoKindsBlend() {
        FluidColumnGrid columns = columns(0, 0);
        columns.set(2, 0, FluidColumnGrid.column(Double.NaN, MIN_Y, 71, false, false, true, false));
        GroundLeavesGrid ground = groundAround(0, 0, 0xFF0000);
        ground.set(2, 0, 0x0000FF, 70, MIN_Y);
        GroundLeavesGrid.Tints white = (x, y, z) -> 0xFFFFFF;
        int nearRed = ground.texel(0, 0, MIN_Y, columns, white);
        assertTrue((nearRed & 0xFF) > ((nearRed >> 16) & 0xFF), "the column's own canopy weighs most");
        int between = ground.texel(1, 0, MIN_Y, columns, white);
        assertEquals(between & 0xFF, (between >> 16) & 0xFF, "halfway, both count the same");
    }

    @Test
    void ratiosAreCappedAndDarkTintsDontBlowThemUp() {
        assertEquals(255, GroundLeavesGrid.ratio(255, 1, 0));
        assertEquals(64, GroundLeavesGrid.ratio(200, 1, 200));
    }

    @Test
    void columnsOutsideTheWindowHaveNone() {
        FluidColumnGrid columns = columns(0, 0);
        columns.setWindow(1, 1);
        assertEquals(0, groundAround(0, 0, OAK).texel(0, 0, MIN_Y, columns, GRASS));
    }

    @Test
    void theShaderReadsTheRegionAndFlagTheJavaSideWrites() throws IOException {
        String groundLeaves = Files.readString(SHADERS.resolve("vegetation/ground_leaves.glsl"));
        assertTrue(groundLeaves.contains("const int LITE_GROUND_LEAVES_Y = " + FluidDataTexture.GROUND_LEAVES_Y + ";"));
        String uniforms = Files.readString(SHADERS.resolve("water/uniforms.glsl"));
        assertTrue(uniforms.contains("const int LITE_WATER_GROUND_LEAVES = " + WaterFlags.GROUND_LEAVES + ";"));
    }
}
