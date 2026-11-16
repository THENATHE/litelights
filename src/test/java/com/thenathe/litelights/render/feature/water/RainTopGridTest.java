package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rain tops' packing, which include/glass/weather.glsl (lite_glass_rain_top) decodes. */
class RainTopGridTest {
    @Test
    void packsHeightsAboveTheLevelFloorWithZeroForUnknown() {
        int packed = RainTopGrid.pack(65, -64);
        assertEquals(130, packed);
        assertTrue(RainTopGrid.known(packed));
        assertEquals(65, RainTopGrid.rainTopY(packed, -64));
        assertFalse(RainTopGrid.known(0));
        assertTrue(RainTopGrid.known(RainTopGrid.pack(-64, -64)), "rain all the way to the floor is still known");
    }

    @Test
    void texelsHoldTheValueInRedAndGreen() {
        RainTopGrid grid = new RainTopGrid();
        grid.set(3, 5, RainTopGrid.pack(400, -64));
        int texel = grid.texel(3, 5);
        int red = texel & 0xFF;
        int green = (texel >> 8) & 0xFF;
        assertEquals(465, red + green * 256);
        assertEquals(0, texel >>> 16, "blue and alpha are left at 0");
    }

    @Test
    void wrapsLikeTheColumnMapAndClearsChunks() {
        RainTopGrid grid = new RainTopGrid();
        grid.set(-1, 0, 7);
        assertEquals(7, grid.get(255, 0));
        grid.clearChunk(-1, 0);
        assertEquals(0, grid.get(-1, 0));
    }
}
