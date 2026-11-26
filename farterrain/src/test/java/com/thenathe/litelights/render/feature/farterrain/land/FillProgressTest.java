package com.thenathe.litelights.render.feature.farterrain.land;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The fill's progress walk (Doing now on the Far Terrain page) and the first set of levels that
 * Wait for detail waits for: at 4 km and Medium (levels split from 256 blocks), the camera at the
 * origin, the near terrain reaching 128 blocks (render distance 8).
 */
class FillProgressTest {
    private static final float FAR = 4096.0f;
    private static final float LEVEL_DISTANCE = 256.0f;
    private static final double NEAR = 128.0;

    private static FillProgress.Reading measure(java.util.function.LongPredicate done) {
        return FillProgress.measure(done, 0.0, 0.0, FAR, LEVEL_DISTANCE, NEAR);
    }

    @Test
    void nothingPredictedYet() {
        FillProgress.Reading reading = measure(key -> false);
        int top = PredictedLand.topLevel(FAR);
        assertTrue(reading.wanted() > 0);
        assertEquals(0, reading.done());
        assertEquals(measure(key -> true).wanted(), reading.wanted(), "the whole tree the fill ends with is wanted from the start");
        assertEquals(64, reading.wantedPerLevel()[top], "1,024-block tiles over 4 km round the camera");
        assertEquals(reading.area(), reading.areaNotDrawn(), 1.0e-6);
        assertEquals(FillProgress.NOT_DRAWN, reading.worstExcess());
        assertEquals(top, reading.nextLevel(), "the coarse cover comes first");
        assertEquals(0.0, reading.steps().firstTwoShare(), 1.0e-9);
        assertFalse(PredictedLand.firstSetBuilt(reading));
    }

    @Test
    void everythingPredicted() {
        FillProgress.Reading reading = measure(key -> true);
        assertTrue(reading.complete());
        assertEquals(0, reading.worstExcess());
        assertEquals(1.0, reading.shareWithin(0), 1.0e-9);
        assertTrue(reading.wantedPerLevel()[0] > 0, "full detail near the camera");
        assertTrue(PredictedLand.firstSetBuilt(reading));
        // About 3,000 tiles at these settings: the walk is cheap enough for twice a second.
        assertTrue(reading.wanted() > 1000 && reading.wanted() < 10_000, String.valueOf(reading.wanted()));
    }

    @Test
    void onlyTheCoarseCoverIsTheCoarsestStandInUpClose() {
        int top = PredictedLand.topLevel(FAR);
        FillProgress.Reading reading = measure(key -> TileKey.level(key) == top);
        assertEquals(0.0, reading.areaNotDrawn(), 1.0e-6);
        assertEquals(top, reading.worstExcess(), "full-detail ground drawn with the top level's voxels");
        assertEquals(top - 1, reading.nextLevel());
        assertFalse(PredictedLand.firstSetBuilt(reading));
    }

    @Test
    void theFirstSetIsTheNearGroundAtItsDetailAndTheRestAtLowDetail() {
        // Everything but the full-detail tiles: the ground near the camera, meant for them, is a level coarse.
        FillProgress.Reading oneCoarse = measure(key -> TileKey.level(key) >= 1);
        assertEquals(1, oneCoarse.steps().nearWorstExcess());
        assertEquals(0, oneCoarse.steps().restWorstOverCoarse(), "past the near ring, a level over Medium's is Low's");
        assertFalse(PredictedLand.firstSetBuilt(oneCoarse));
        assertEquals(PredictedLand.STEP_NEAR, oneCoarse.steps().nextStep());
        // Full detail near (all four of a tile's children, as the plan makes them), every other
        // tile two levels coarse: the rest isn't at Low's detail yet.
        double ring = PredictedLand.nearRing(NEAR);
        FillProgress.Reading restCoarse = measure(key -> TileKey.level(key) >= 2 || distance(TileKey.parent(key)) < ring);
        assertEquals(0, restCoarse.steps().nearWorstExcess());
        assertTrue(restCoarse.steps().restWorstOverCoarse() >= 1);
        assertFalse(PredictedLand.firstSetBuilt(restCoarse));
        assertEquals(PredictedLand.STEP_COARSE, restCoarse.steps().nextStep());
    }

    @Test
    void aMissingChildKeepsItsParentAsTheStandInForAllFour() {
        // Everything except one full-detail tile next to the near terrain: its three siblings are
        // done but not drawn, since the parent splits only with all four.
        long missing = TileKey.of(0, 5, 0);
        FillProgress.Reading reading = measure(key -> key != missing);
        assertEquals(reading.wanted() - 1, reading.done());
        assertEquals(1, reading.worstExcess());
        assertEquals(0, reading.nextLevel(), "the missing tile is what the fill works on next");
        double siblings = 4.0 * TileKey.span(0) * TileKey.span(0);
        assertEquals(siblings, reading.area() * (1.0 - reading.shareWithin(0)), 1.0e-6);
    }

    @Test
    void groundTheNearTerrainCoversIsNotCounted() {
        FillProgress.Reading all = measure(key -> true);
        FillProgress.Reading nearer = FillProgress.measure(key -> true, 0.0, 0.0, FAR, LEVEL_DISTANCE, 0.0);
        assertTrue(nearer.area() > all.area());
        FillProgress.Reading insideNear = FillProgress.measure(key -> false, 0.0, 0.0, FAR, LEVEL_DISTANCE, FAR * 2.0);
        assertEquals(0.0, insideNear.area(), 1.0e-6);
        assertTrue(PredictedLand.firstSetBuilt(insideNear), "no ground past the near terrain: nothing to wait for");
    }

    @Test
    void theFirstTwoStepsBuildTheFirstSetAndHigherDetailWaitsForNoMore() {
        java.util.Set<Long> medium = firstTwoSteps(LEVEL_DISTANCE);
        FillProgress.Reading reading = measure(medium::contains);
        assertTrue(PredictedLand.firstSetBuilt(reading), "near " + reading.steps().nearWorstExcess() + ", rest " + reading.steps().restWorstOverCoarse());
        assertEquals(PredictedLand.STEP_REFINE, reading.steps().nextStep());
        assertEquals(1.0, reading.steps().firstTwoShare(), 1.0e-9, "every tile the first two steps make is done");
        assertTrue(reading.steps().firstTwoTiles() > 0 && reading.steps().firstTwoTiles() <= medium.size());
        assertTrue(reading.done() < reading.wanted() / 2, reading.done() + " of " + reading.wanted());
        FillProgress.Reading half = measure(key -> medium.contains(key) && (TileKey.level(key) > 0 || TileKey.x(key) % 2 == 0));
        assertTrue(half.steps().firstTwoShare() > 0.5 && half.steps().firstTwoShare() < 1.0, String.valueOf(half.steps().firstTwoShare()));
        // Ultra (1,024 blocks a level) refines far more, but its first set is the same tiles.
        java.util.Set<Long> ultra = firstTwoSteps(1024.0f);
        assertEquals(medium, ultra);
        FillProgress.Reading atUltra = FillProgress.measure(ultra::contains, 0.0, 0.0, FAR, 1024.0f, NEAR);
        assertTrue(PredictedLand.firstSetBuilt(atUltra));
        assertTrue(atUltra.wanted() > reading.wanted() * 4, atUltra.wanted() + " against " + reading.wanted());
    }

    /** The tiles the plan makes in its first two steps: every split but those of the third. */
    private static java.util.Set<Long> firstTwoSteps(float levelDistance) {
        int top = PredictedLand.topLevel(FAR);
        double ring = PredictedLand.nearRing(NEAR);
        java.util.Set<Long> made = new java.util.HashSet<>();
        java.util.ArrayDeque<Long> drawn = new java.util.ArrayDeque<>();
        int span = TileKey.span(top);
        int reach = (int) Math.ceil(FAR / span);
        for (int z = -reach - 1; z <= reach; z++) {
            for (int x = -reach - 1; x <= reach; x++) {
                long key = TileKey.of(top, x, z);
                if (distance(key) <= FAR) {
                    made.add(key);
                    drawn.add(key);
                }
            }
        }
        while (!drawn.isEmpty()) {
            long key = drawn.poll();
            int level = TileKey.level(key);
            double distance = distance(key);
            boolean splits = level > 0 && distance < levelDistance * (1 << level);
            if (splits && PredictedLand.stepOf(level, distance, false, ring) != PredictedLand.STEP_REFINE) {
                // All four, as the plan predicts them, past the far distance too.
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    long child = TileKey.child(key, quadrant);
                    made.add(child);
                    drawn.add(child);
                }
            }
        }
        return made;
    }

    private static double distance(long key) {
        int span = TileKey.span(TileKey.level(key));
        double dx = Math.max(Math.max(TileKey.originX(key) - 0.0, 0.0), 0.0 - (TileKey.originX(key) + span));
        double dz = Math.max(Math.max(TileKey.originZ(key) - 0.0, 0.0), 0.0 - (TileKey.originZ(key) + span));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
