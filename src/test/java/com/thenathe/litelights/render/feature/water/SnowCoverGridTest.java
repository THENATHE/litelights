package com.thenathe.litelights.render.feature.water;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where snow gathers on leaves and under trees, as include/weather/snow_cover.glsl reads it. */
class SnowCoverGridTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/litelights/shaders/include");
    private static final int CANOPY_Y = 78;
    private static final int GROUND_Y = 70;
    private static final int SNOWY_CANOPY = SnowCoverGrid.CANOPY | SnowCoverGrid.SNOWS | SnowCoverGrid.GROUND;

    private static FluidColumnGrid window() {
        FluidColumnGrid columns = new FluidColumnGrid();
        columns.setWindow(-128, -128);
        return columns;
    }

    /** A five by five canopy round a trunk at the origin, in a snowy biome. */
    private static SnowCoverGrid tree() {
        SnowCoverGrid snow = new SnowCoverGrid();
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                boolean trunk = x == 0 && z == 0;
                int flags = trunk ? SnowCoverGrid.CANOPY | SnowCoverGrid.SNOWS | SnowCoverGrid.TRUNK : SNOWY_CANOPY;
                snow.set(x, z, flags, CANOPY_Y, trunk ? GROUND_Y + 6 : GROUND_Y);
            }
        }
        return snow;
    }

    @Test
    void aCanopyColumnCarriesItsFlagsAndHeights() {
        int texel = tree().texel(2, 2, window());
        assertEquals(SNOWY_CANOPY, texel & 15);
        assertEquals(2, (texel >> 4) & 3, "the trunk is two columns away");
        assertEquals(CANOPY_Y & 255, (texel >> 8) & 0xFF);
        assertEquals(GROUND_Y & 255, (texel >> 16) & 0xFF);
        assertEquals(9 * 10, texel >>> 24, "nine of the twenty-five columns around are under canopy");
    }

    @Test
    void theTrunkIsFoundUpToTwoColumnsAway() {
        SnowCoverGrid snow = tree();
        FluidColumnGrid columns = window();
        assertEquals(0, (snow.texel(0, 0, columns) >> 4) & 3, "the trunk's own column");
        assertEquals(1, (snow.texel(1, -1, columns) >> 4) & 3);
        snow.set(5, 0, SNOWY_CANOPY, CANOPY_Y, GROUND_Y);
        assertEquals(3, (snow.texel(5, 0, columns) >> 4) & 3, "none within two");
    }

    @Test
    void theTrunkFlagStaysOutOfTheTexel() {
        int texel = tree().texel(0, 0, window());
        assertEquals(SnowCoverGrid.CANOPY | SnowCoverGrid.SNOWS, texel & 15);
        assertEquals(0, (texel >> 16) & 0xFF, "no ground for a dusting on a trunk");
    }

    @Test
    void heightsWrapAtTwoHundredAndFiftySix() {
        SnowCoverGrid snow = new SnowCoverGrid();
        snow.set(0, 0, SNOWY_CANOPY, 300, -40);
        int texel = snow.texel(0, 0, window());
        assertEquals(300 & 255, (texel >> 8) & 0xFF);
        assertEquals(-40 & 255, (texel >> 16) & 0xFF);
    }

    @Test
    void columnsWithoutCanopyOrOutsideTheWindowHaveNone() {
        SnowCoverGrid snow = tree();
        assertEquals(0, snow.texel(3, 0, window()));
        snow.set(3, 0, SnowCoverGrid.TRUNK, 0, GROUND_Y);
        assertEquals(0, snow.texel(3, 0, window()), "a trunk with no leaves on top");
        FluidColumnGrid moved = window();
        moved.setWindow(1, 1);
        assertEquals(0, snow.texel(0, 0, moved));
        snow.clearChunk(0, 0);
        assertEquals(0, snow.texel(1, 1, window()));
    }

    @Test
    void theShaderReadsTheRegionAndFlagTheJavaSideWrites() throws IOException {
        String snow = Files.readString(SHADERS.resolve("weather/snow_cover.glsl"));
        assertTrue(snow.contains("const int LITE_SNOW_COVER_Y = " + FluidDataTexture.SNOW_COVER_Y + ";"));
        assertTrue(snow.contains("const int LITE_SNOW_CANOPY = " + SnowCoverGrid.CANOPY + ";"));
        assertTrue(snow.contains("const int LITE_SNOW_SNOWS = " + SnowCoverGrid.SNOWS + ";"));
        assertTrue(snow.contains("const int LITE_SNOW_CONIFER = " + SnowCoverGrid.CONIFER + ";"));
        assertTrue(snow.contains("const int LITE_SNOW_GROUND = " + SnowCoverGrid.GROUND + ";"));
        String uniforms = Files.readString(SHADERS.resolve("water/uniforms.glsl"));
        assertTrue(uniforms.contains("const int LITE_WATER_SNOW_COVER = " + WaterFlags.SNOW_COVER + ";"));
    }
}
