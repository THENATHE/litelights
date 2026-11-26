package com.thenathe.litelights.clouds.volume;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The clouds' 3D noise atlas: it tiles, each tile's border wraps, and every channel uses its range. */
class CloudNoiseVolumeTest {
    private static final byte[] ATLAS = CloudNoiseVolume.build();

    @Test
    void theNoiseTilesAcrossItsPeriod() {
        for (int frequency : new int[] {4, 8, 16}) {
            for (double y = 0.05; y < 1.0; y += 0.3) {
                assertEquals(CloudNoiseVolume.gradient(0.0, y, 0.37, frequency, 7L), CloudNoiseVolume.gradient(1.0, y, 0.37, frequency, 7L), 1.0e-9);
                assertEquals(CloudNoiseVolume.cellular(0.21, 0.0, y, frequency, 7L), CloudNoiseVolume.cellular(0.21, 1.0, y, frequency, 7L), 1.0e-9);
            }
        }
    }

    @Test
    void eachTilesBorderIsTheOppositeEdge() {
        int tile = CloudNoiseVolume.TILE;
        for (int slice : new int[] {0, 9, 63}) {
            int originX = (slice % CloudNoiseVolume.TILES_ACROSS) * tile;
            int originY = (slice / CloudNoiseVolume.TILES_ACROSS) * tile;
            for (int j = 1; j < tile - 1; j += 5) {
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(texel(originX, originY + j, channel), texel(originX + tile - 2, originY + j, channel), "left border");
                    assertEquals(texel(originX + tile - 1, originY + j, channel), texel(originX + 1, originY + j, channel), "right border");
                    assertEquals(texel(originX + j, originY, channel), texel(originX + j, originY + tile - 2, channel), "top border");
                }
            }
        }
    }

    @Test
    void everyChannelUsesItsRange() {
        for (int channel = 0; channel < 4; channel++) {
            long sum = 0;
            int lowest = 255;
            int highest = 0;
            int count = 0;
            for (int i = channel; i < ATLAS.length; i += 4) {
                int value = ATLAS[i] & 0xFF;
                sum += value;
                lowest = Math.min(lowest, value);
                highest = Math.max(highest, value);
                count++;
            }
            double mean = sum / (double) count / 255.0;
            assertEquals(0, lowest, "channel " + channel);
            assertEquals(255, highest, "channel " + channel);
            assertTrue(mean > 0.25 && mean < 0.75, "channel " + channel + " mean " + mean);
        }
    }

    @Test
    void theLumpsTileAndUseTheirRange() {
        byte[] lumps = CloudNoiseVolume.buildLumps();
        for (int frequency : new int[] {4, 8, 16}) {
            for (double y = 0.05; y < 1.0; y += 0.3) {
                assertEquals(CloudNoiseVolume.roundedCells(0.0, y, 0.37, frequency, 7L), CloudNoiseVolume.roundedCells(1.0, y, 0.37, frequency, 7L),
                    1.0e-9);
            }
        }
        for (int channel = 0; channel < 4; channel++) {
            int lowest = 255;
            int highest = 0;
            for (int i = channel; i < lumps.length; i += 4) {
                lowest = Math.min(lowest, lumps[i] & 0xFF);
                highest = Math.max(highest, lumps[i] & 0xFF);
            }
            assertEquals(0, lowest, "channel " + channel);
            assertEquals(255, highest, "channel " + channel);
        }
        assertArrayEquals(lumps, CloudNoiseVolume.buildLumps());
    }

    @Test
    void theSameBytesEveryTime() {
        assertArrayEquals(ATLAS, CloudNoiseVolume.build());
    }

    private static int texel(int x, int y, int channel) {
        return ATLAS[(y * CloudNoiseVolume.ATLAS_SIZE + x) * 4 + channel] & 0xFF;
    }
}
