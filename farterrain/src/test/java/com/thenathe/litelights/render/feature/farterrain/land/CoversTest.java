package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoversTest {
    private static final int SNOW = 9;
    private final TestLooks looks = new TestLooks();

    @Test
    void snowTakesTheGrassPlaceAndStopsThere() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        tile.states[TileVoxels.index(3, 71, 3)] = SNOW;
        int top = Covers.fold(tile, 3, 3, 128, state -> state == SNOW, looks);
        assertEquals(70, top);
        assertEquals(SNOW, tile.state(3, 70, 3), "the snow lies where the grass was");
        assertEquals(TestLooks.STONE, tile.state(3, 69, 3), "and sinks no further");
        assertEquals(TestLooks.AIR, tile.state(3, 71, 3));
    }

    @Test
    void coverOverAirStaysWhereItIs() {
        TileVoxels tile = TestLooks.flatLand(128, 70);
        tile.states[TileVoxels.index(4, 90, 4)] = SNOW;
        int top = Covers.fold(tile, 4, 4, 128, state -> state == SNOW, looks);
        assertEquals(90, top);
        assertEquals(SNOW, tile.state(4, 90, 4));
        assertEquals(TestLooks.GRASS, tile.state(4, 70, 4));
    }
}
