package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TileCodecTest {
    @Test
    void landReadsBackExactly() throws IOException {
        TileVoxels tile = TestLooks.flatLand(384, 70);
        tile.states[TileVoxels.index(3, 75, 9)] = TestLooks.LEAVES;
        tile.light[TileVoxels.index(4, 75, 9)] = (byte) 0xA7;
        tile.biomes[TileVoxels.column(5, 5)] = 12;
        TileVoxels back = TileCodec.decode(TileCodec.encode(tile));
        assertTrue(back.sameAs(tile));
    }

    @Test
    void randomVoxelsReadBackExactly() throws IOException {
        Random random = new Random(42);
        TileVoxels tile = new TileVoxels(3, -64, TileVoxels.heightFor(384, 3));
        for (int i = 0; i < tile.states.length; i++) {
            tile.states[i] = random.nextInt(5000);
            tile.light[i] = (byte) random.nextInt(256);
        }
        for (int i = 0; i < tile.biomes.length; i++) {
            tile.biomes[i] = (short) random.nextInt(80);
        }
        for (int x = 0; x < 32; x += 3) {
            tile.setKnown(x, x);
        }
        TileVoxels back = TileCodec.decode(TileCodec.encode(tile));
        assertTrue(back.sameAs(tile));
        assertEquals(3, back.level);
        assertEquals(-64, back.minY);
    }

    @Test
    void unseenTileIsSmall() {
        byte[] data = TileCodec.encode(new TileVoxels(0, -64, 384));
        assertTrue(data.length < 400, "an empty tile took " + data.length + " bytes");
    }

    @Test
    void ordinaryLandIsAFewKilobytesPerChunk() {
        TileVoxels tile = TestLooks.flatLand(384, 70);
        Random random = new Random(7);
        // Ores and caves break the stone's runs, as real land does.
        for (int i = 0; i < 6000; i++) {
            int index = TileVoxels.index(random.nextInt(32), random.nextInt(70), random.nextInt(32));
            tile.states[index] = random.nextBoolean() ? TestLooks.AIR : TestLooks.DIRT;
        }
        int perChunk = TileCodec.encode(tile).length / 4;
        assertTrue(perChunk < 8 * 1024, "a chunk took " + perChunk + " bytes");
    }

    @Test
    void firstFormatStillReadsWithoutInsets() throws IOException {
        TileVoxels coarse = new TileVoxels(2, -64, 96);
        coarse.states[TileVoxels.index(1, 30, 1)] = TestLooks.STONE;
        coarse.light[TileVoxels.index(1, 30, 1)] = (byte) 0xF3;
        byte[] data = TileCodec.encode(coarse);
        assertEquals(TileCodec.VERSION, data[0]);
        data[0] = (byte) TileCodec.VERSION_WITHOUT_INSETS;
        TileVoxels old = TileCodec.decode(data);
        assertTrue(old.sameAs(coarse), "every voxel as it was");
        assertEquals(0, TileVoxels.inset(old, TileVoxels.index(1, 30, 1)), "a first-format light byte is light, not an inset");
        assertEquals(3, TileVoxels.inset(coarse, TileVoxels.index(1, 30, 1)));
    }

    @Test
    void newerVersionIsRefusedNotMisread() {
        byte[] data = TileCodec.encode(TestLooks.flatLand(64, 10));
        data[0] = (byte) (TileCodec.VERSION + 1);
        assertThrows(IOException.class, () -> TileCodec.decode(data));
    }

    @Test
    void damagedDataIsRefused() {
        byte[] data = TileCodec.encode(TestLooks.flatLand(64, 10));
        byte[] cut = java.util.Arrays.copyOf(data, data.length / 2);
        assertThrows(IOException.class, () -> TileCodec.decode(cut));
    }

    @Test
    void tileKeysPackBothSigns() {
        for (int[] coordinates : new int[][] {{0, 0}, {-1, 1}, {937_500, -937_500}, {-5, -7}}) {
            for (int level = 0; level <= TileKey.MAX_LEVEL; level++) {
                long key = TileKey.of(level, coordinates[0], coordinates[1]);
                assertArrayEquals(new int[] {level, coordinates[0], coordinates[1]}, new int[] {TileKey.level(key), TileKey.x(key), TileKey.z(key)});
            }
        }
        long key = TileKey.of(2, -3, 5);
        assertEquals(TileKey.of(3, -2, 2), TileKey.parent(key));
        assertEquals(key, TileKey.child(TileKey.parent(key), 1 | 1 << 1));
        assertEquals(TileKey.of(0, -1, 0), TileKey.ofChunk(-1, 1));
    }
}
