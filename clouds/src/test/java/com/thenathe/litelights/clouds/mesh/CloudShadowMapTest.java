package com.thenathe.litelights.clouds.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The cloud shadow map: thicker cloud lets through less light, and every ring's columns land where they are. */
class CloudShadowMapTest {
    private static final int CORNER = -CloudShadowMap.SPAN_BLOCKS / 2;

    @Test
    void clearSkyLetsEverythingThrough() {
        byte[] map = new CloudShadowMap().build(List.of(CloudCells.empty(0, 0, 8)), CORNER, CORNER);
        for (byte texel : map) {
            assertEquals(255, texel & 0xFF);
        }
    }

    @Test
    void thickerCloudCastsADeeperShadow() {
        CloudCells thin = CloudCells.of(0, 0, 8, (x, z, y) -> x >= 0 && x < 16 && z >= 0 && z < 32 && y == 4);
        CloudCells thick = CloudCells.of(1, 0, 8, (x, z, y) -> y >= 4 && y < 30);
        byte[] map = new CloudShadowMap().build(List.of(thin, thick), CORNER, CORNER);
        int thinLight = at(map, 40, 40);
        int thickLight = at(map, 256 + 40, 40);
        int openLight = at(map, 200, 40);
        assertEquals(255, openLight, "the thin chunk's clear half");
        assertTrue(thinLight < 255 && thinLight > 150, "one cell lets most light through: " + thinLight);
        assertTrue(thickLight < 30, "a deck 200 blocks thick lets through almost none: " + thickLight);
    }

    @Test
    void farRingsCoverTheTexelsUnderThem() {
        // A 64-block-cube chunk two chunks east (blocks 4,096 to 6,143), solid everywhere.
        CloudCells far = CloudCells.of(2, 0, 64, (x, z, y) -> y >= 2 && y < 20);
        byte[] map = new CloudShadowMap().build(List.of(far), CORNER, CORNER);
        assertTrue(at(map, 4_100, 100) < 60);
        assertTrue(at(map, 6_100, 1_900) < 60);
        assertEquals(255, at(map, 4_080, 100), "just west of it is open");
        assertEquals(255, at(map, 6_160, 100), "and just east of it");
    }

    /** The light at a point in blocks of cloud space. */
    private static int at(byte[] map, int x, int z) {
        int texelX = (x - CORNER) / CloudShadowMap.TEXEL_BLOCKS;
        int texelZ = (z - CORNER) / CloudShadowMap.TEXEL_BLOCKS;
        return map[texelZ * CloudShadowMap.SIZE + texelX] & 0xFF;
    }
}
