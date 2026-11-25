package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seed generation's predictions against visited land: kept in stores of their own, put together
 * column by column with visited land always on top, replaced as visited land comes in, gone when
 * predictions stop; and a predicted tile never splits into children that aren't there.
 */
class LandViewTest {
    private final TestLooks looks = new TestLooks();

    /** A predicted store read as the session reads one (here its ids are already the visited store's). */
    private static LandView.Predictions over(FarStore store) {
        return new LandView.Predictions() {
            @Override
            public boolean present(long key) {
                return store.present(key);
            }

            @Override
            public TileMeta meta(long key) {
                return store.meta(key);
            }

            @Override
            public TileVoxels read(long key) throws IOException {
                return store.read(key);
            }
        };
    }

    @Test
    void predictionsShowOnlyWhereNothingWasVisited(@TempDir Path folder) throws IOException {
        FarStore visited = new FarStore(folder.resolve("visited"));
        FarStore predicted = new FarStore(folder.resolve("predicted"));
        visited.open();
        predicted.open();
        long key = TileKey.of(0, 3, -2);
        predicted.write(key, TestLooks.flatLand(96, 40), looks);
        LandView land = new LandView(visited, looks);
        land.predictions(over(predicted));

        // Nothing visited: the prediction is the tile.
        assertTrue(land.present(key));
        assertEquals(predicted.meta(key), land.meta(key));
        assertTrue(land.read(key).sameAs(TestLooks.flatLand(96, 40)));

        // Half the tile visited, its ground lower: those columns are the visited ones, the rest predicted.
        TileVoxels seen = new TileVoxels(0, -64, 96);
        TileVoxels low = TestLooks.flatLand(96, 30);
        for (int z = 0; z < TileVoxels.SIZE; z++) {
            for (int x = 0; x < TileVoxels.SIZE / 2; x++) {
                seen.setKnown(x, z);
                for (int y = 0; y < 96; y++) {
                    int index = TileVoxels.index(x, y, z);
                    seen.states[index] = low.states[index];
                    seen.light[index] = low.light[index];
                }
            }
        }
        TileMeta before = land.meta(key);
        visited.write(key, seen, looks);
        TileMeta after = land.meta(key);
        assertTrue(after.revision() > before.revision(), "visited land coming in makes the tile's mesh out of date");
        TileVoxels drawn = land.read(key);
        assertEquals(30, drawn.top(0, 0, looks), "a visited column wins");
        assertEquals(30, drawn.top(15, 31, looks));
        assertEquals(40, drawn.top(16, 0, looks), "a column never visited is predicted");
        assertEquals(40, drawn.top(31, 31, looks));
        // Edges: the west edge was visited, the east edge comes from the prediction.
        assertEquals(30, after.edges().top(EdgeTops.WEST, 5));
        assertEquals(40, after.edges().top(EdgeTops.EAST, 5));

        // Predictions stopped: only what was visited is left.
        land.predictions(null);
        assertSame(visited.meta(key), land.meta(key));
        assertFalse(land.read(key).known(20, 20));
        long onlyPredicted = TileKey.of(0, 50, 50);
        predicted.write(onlyPredicted, TestLooks.flatLand(96, 40), looks);
        assertFalse(land.present(onlyPredicted));
        assertNull(land.meta(onlyPredicted));
    }

    @Test
    void wholeVisitedTilesNeverShowThePrediction(@TempDir Path folder) throws IOException {
        FarStore visited = new FarStore(folder.resolve("visited"));
        FarStore predicted = new FarStore(folder.resolve("predicted"));
        visited.open();
        predicted.open();
        long key = TileKey.of(0, 0, 0);
        visited.write(key, TestLooks.flatLand(96, 30), looks);
        predicted.write(key, TestLooks.flatLand(96, 50), looks);
        LandView land = new LandView(visited, looks);
        land.predictions(over(predicted));
        assertTrue(land.read(key).sameAs(TestLooks.flatLand(96, 30)));
    }

    @Test
    void aPredictedTileDoesNotSplitIntoMissingChildren() {
        long parent = TileKey.of(3, 0, 0);
        Set<Long> stored = new HashSet<>();
        stored.add(parent);
        FarSelection.Tiles tiles = new FarSelection.Tiles() {
            @Override
            public boolean present(long key) {
                for (long tile : stored) {
                    long up = tile;
                    while (TileKey.level(up) < TileKey.level(key)) {
                        up = TileKey.parent(up);
                    }
                    if (up == key) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean stored(long key) {
                return stored.contains(key);
            }

            @Override
            public boolean ready(long key) {
                return true;
            }

            @Override
            public int lowestY(long key) {
                return 60;
            }

            @Override
            public int highestY(long key) {
                return 70;
            }

            @Override
            public boolean coversWhole(long key) {
                return true;
            }
        };
        Set<Long> drawn = new HashSet<>();
        FarSelection selection = new FarSelection();
        selection.select(tiles, (a, b, c, d, e, f) -> true, 10, 100, 10, 4000, 128, (key, visible) -> drawn.add(key), key -> { });
        assertEquals(Set.of(parent), drawn, "no children yet: the parent stands for all its ground");

        // Three of four children: still the parent, never three quarters and a hole.
        stored.add(TileKey.child(parent, 0));
        stored.add(TileKey.child(parent, 1));
        stored.add(TileKey.child(parent, 2));
        drawn.clear();
        selection.select(tiles, (a, b, c, d, e, f) -> true, 10, 100, 10, 4000, 128, (key, visible) -> drawn.add(key), key -> { });
        assertEquals(Set.of(parent), drawn);

        stored.add(TileKey.child(parent, 3));
        drawn.clear();
        selection.select(tiles, (a, b, c, d, e, f) -> true, 10, 100, 10, 4000, 128, (key, visible) -> drawn.add(key), key -> { });
        assertFalse(drawn.contains(parent), "all four there: it splits");
        assertEquals(4, drawn.size());
    }

    @Test
    void theFirstCoverIsAboutAQuarterOfTheDistanceAcross() {
        assertEquals(5, PredictedLand.topLevel(4096));
        assertEquals(6, PredictedLand.topLevel(8192));
        assertEquals(7, PredictedLand.topLevel(16384));
        assertEquals(3, PredictedLand.topLevel(1024));
    }
}
