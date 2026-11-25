package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FarStoreTest {
    private final TestLooks looks = new TestLooks();

    @Test
    void tilesSurviveClosingAndOpening(@TempDir Path folder) throws IOException {
        FarStore store = new FarStore(folder);
        store.open();
        int stone = store.blocks().idOf("minecraft:stone");
        TileVoxels land = TestLooks.flatLand(384, 70);
        long here = TileKey.of(0, -3, 4);
        long there = TileKey.of(0, 40, -17);
        TileMeta written = store.write(here, land, looks);
        store.write(there, TestLooks.flatLand(384, 20), looks);
        store.write(TileKey.of(2, 1, 1), TestLooks.flatLand(96, 5), looks);
        store.flush();

        FarStore again = new FarStore(folder);
        again.open();
        assertEquals(3, again.tileCount());
        assertEquals(stone, again.blocks().idOf("minecraft:stone"));
        assertEquals("minecraft:stone", again.blocks().nameOf(stone));
        TileMeta meta = again.meta(here);
        assertNotNull(meta);
        assertEquals(written, meta);
        assertTrue(again.read(here).sameAs(land));
        assertTrue(again.read(there).sameAs(TestLooks.flatLand(384, 20)));
        assertNull(again.read(TileKey.of(0, 5, 5)));
        assertTrue(again.diskBytes() > 0);
    }

    @Test
    void presenceReachesEveryLevelAbove(@TempDir Path folder) throws IOException {
        FarStore store = new FarStore(folder);
        store.open();
        long tile = TileKey.of(0, 100, -100);
        store.write(tile, TestLooks.flatLand(64, 10), looks);
        long above = tile;
        for (int level = 1; level <= TileKey.MAX_LEVEL; level++) {
            above = TileKey.parent(above);
            assertTrue(store.present(above), "level " + level);
            assertNull(store.meta(above), "only present, not stored, at level " + level);
        }
        assertFalse(store.present(TileKey.of(1, 0, 0)));
    }

    @Test
    void rewritingKeepsTheOthersAndRaisesTheRevision(@TempDir Path folder) throws IOException {
        FarStore store = new FarStore(folder);
        store.open();
        long first = TileKey.of(0, 0, 0);
        long second = TileKey.of(0, 1, 0);
        TileMeta before = store.write(first, TestLooks.flatLand(64, 10), looks);
        store.write(second, TestLooks.flatLand(64, 20), looks);
        store.flush();
        store.trim(0);
        TileMeta after = store.write(first, TestLooks.flatLand(64, 30), looks);
        store.flush();
        assertTrue(after.revision() > before.revision());
        FarStore again = new FarStore(folder);
        again.open();
        assertTrue(again.read(second).sameAs(TestLooks.flatLand(64, 20)), "the untouched tile was read back from disk and kept");
        assertTrue(again.read(first).sameAs(TestLooks.flatLand(64, 30)));
        assertTrue(again.meta(first).revision() > again.meta(second).revision());
    }

    @Test
    void trimLetsGoOfDataAndReadsItAgain(@TempDir Path folder) throws IOException {
        FarStore store = new FarStore(folder);
        store.open();
        long key = TileKey.of(0, 2, 2);
        store.write(key, TestLooks.flatLand(384, 70), looks);
        store.flush();
        assertTrue(store.heldBytes() > 0);
        store.trim(0);
        assertEquals(0, store.heldBytes());
        assertTrue(store.read(key).sameAs(TestLooks.flatLand(384, 70)));
    }

    @Test
    void deleteRemovesTheFolder(@TempDir Path folder) throws IOException {
        Path dimension = folder.resolve("minecraft_overworld");
        FarStore store = new FarStore(dimension);
        store.open();
        store.write(TileKey.of(0, 0, 0), TestLooks.flatLand(64, 10), looks);
        store.flush();
        assertTrue(Files.exists(dimension));
        store.delete();
        assertFalse(Files.exists(dimension));
        assertEquals(0, store.tileCount());
    }

    @Test
    void namesKeepTheirIdsAcrossSaves(@TempDir Path folder) throws IOException {
        NameTable table = new NameTable("minecraft:air");
        int oak = table.idOf("minecraft:oak_log[axis=y]");
        int water = table.idOf("minecraft:water[level=0]");
        table.save(folder.resolve("blocks.txt"));
        NameTable again = new NameTable("minecraft:air");
        again.load(folder.resolve("blocks.txt"));
        assertEquals(oak, again.idOf("minecraft:oak_log[axis=y]"));
        assertEquals(water, again.idOf("minecraft:water[level=0]"));
        assertEquals(0, again.idOf("minecraft:air"));
        assertFalse(again.changedSinceSave());
        again.idOf("minecraft:stone");
        assertTrue(again.changedSinceSave());
    }
}
