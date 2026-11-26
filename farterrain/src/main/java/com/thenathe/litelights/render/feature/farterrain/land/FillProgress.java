package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.function.LongPredicate;

/**
 * How far seed generation's fill round the camera has come (docs/FAR-TERRAIN.md, "Seed
 * generation"): the tiles it wants, as {@link PredictedLand} plans them, and how many are done;
 * and, for the ground past the render distance, how much coarser than its own detail it would be
 * drawn now.
 *
 * <p><b>Wanted.</b> Every tile of the tree the fill ends with: each top-level tile within the far
 * distance, and the four children of every tile closer than {@code levelDistance << level} (all
 * four are predicted together), down to where the selection draws them. A tile of that tree that
 * isn't split is where its ground ends up: its <i>final</i> level.
 *
 * <p><b>Drawn now.</b> As the selection draws predicted land: a top tile once done, its four
 * children in its place once all four are done, and so on down; ground under a tile with a child
 * missing is drawn at that tile's level. The <i>excess</i> of a piece of ground is how many levels
 * coarser than its final level it is drawn: 0 at its own detail, 1 with voxels twice as wide,
 * {@link #NOT_DRAWN} where nothing is drawn yet. Ground the near terrain covers (within the render
 * distance) isn't counted.
 *
 * <p><b>The steps.</b> The fill goes in three steps ({@link PredictedLand#stepOf}): the ground near
 * the camera (within {@link PredictedLand#NEAR_RING_BLOCKS} past the render distance) to its own
 * detail; then all the rest as Detail Low draws it; then the rest refined. The walk says how far
 * each has come and which step the fill works on next, and the first set of levels that Wait for
 * detail waits for is the first two steps done ({@link PredictedLand#firstSetBuilt}).
 *
 * <p>Pure, so the unit tests can walk it without the game: {@code done} says which tiles exist.
 * About five thousand tiles at 4 km and Medium, walked twice a second at most.
 */
final class FillProgress {
    /** The excess of ground nothing is drawn on yet. */
    static final int NOT_DRAWN = 99;
    /** Excess counted per step up to this; coarser ground counts here too. */
    static final int MAX_EXCESS = 4;
    /** No level: nothing is drawn over this ground yet. */
    private static final int NOT_DRAWN_LEVEL = -1;

    /** What one walk found. Areas are in square blocks. */
    record Reading(int wanted, int done, int[] wantedPerLevel, int[] donePerLevel, double area, double[] areaByExcess, double areaNotDrawn,
                   int worstExcess, int nextLevel, Steps steps) {
        /** Every wanted tile is done. */
        boolean complete() {
            return done >= wanted;
        }

        /** Share of the ground past the near terrain drawn no more than {@code excess} levels coarser than its own detail. */
        double shareWithin(int excess) {
            if (area <= 0.0) {
                return 1.0;
            }
            double within = 0.0;
            for (int step = 0; step <= Math.min(excess, MAX_EXCESS); step++) {
                within += areaByExcess[step];
            }
            return within / area;
        }

        static Reading empty() {
            return new Reading(0, 0, new int[TileKey.MAX_LEVEL + 1], new int[TileKey.MAX_LEVEL + 1], 0.0, new double[MAX_EXCESS + 1], 0.0, 0, -1,
                new Steps(0.0, 0.0, 0, 0.0, 0.0, 0, 0, 0, PredictedLand.STEP_NEAR));
        }
    }

    /**
     * The steps' progress: the ground near the camera and how much of it is at its own detail, and
     * how far the worst of it is from that; the rest and how much of it is drawn at least as finely
     * as Detail Low draws it, and how many levels coarser the worst of it is; the tiles the first
     * two steps make and how many are done; the step the fill works on next
     * ({@link PredictedLand#STEP_NEAR} to {@code STEP_REFINE}).
     */
    record Steps(double nearArea, double nearAreaAtDetail, int nearWorstExcess, double restArea, double restAreaAtCoarse, int restWorstOverCoarse,
                 int firstTwoTiles, int firstTwoTilesDone, int nextStep) {
        /** The first two steps done: the near ground at its detail, the rest at least at Detail Low's. */
        boolean firstTwoDone() {
            return nearWorstExcess <= 0 && restWorstOverCoarse <= 0;
        }

        /**
         * How far along the first two steps are: their tiles done, of all they make. By tiles, not
         * ground: the ground counts as done only once a whole chain of levels over it is, so it
         * stood still through the first step and then leapt.
         */
        double firstTwoShare() {
            return firstTwoTiles == 0 ? 1.0 : firstTwoTilesDone / (double) firstTwoTiles;
        }
    }

    private final LongPredicate done;
    private final double cameraX;
    private final double cameraZ;
    private final float farDistance;
    private final float levelDistance;
    private final double nearDistance;
    private final double nearRing;
    private int wanted;
    private int doneCount;
    private final int[] wantedPerLevel = new int[TileKey.MAX_LEVEL + 1];
    private final int[] donePerLevel = new int[TileKey.MAX_LEVEL + 1];
    private double area;
    private final double[] areaByExcess = new double[MAX_EXCESS + 1];
    private double areaNotDrawn;
    private int worstExcess;
    /** The finest level with a wanted tile missing whose parent is done: where the fill is working. */
    private int nextLevel = -1;
    private double nextPriority = Double.MAX_VALUE;
    private int nextStep = PredictedLand.STEP_REFINE;
    private double nearArea;
    private double nearAreaAtDetail;
    private int nearWorstExcess;
    private double restArea;
    private double restAreaAtCoarse;
    private int restWorstOverCoarse;
    private int firstTwoTiles;
    private int firstTwoTilesDone;

    private FillProgress(LongPredicate done, double cameraX, double cameraZ, float farDistance, float levelDistance, double nearDistance) {
        this.done = done;
        this.nearRing = PredictedLand.nearRing(nearDistance);
        this.cameraX = cameraX;
        this.cameraZ = cameraZ;
        this.farDistance = farDistance;
        this.levelDistance = levelDistance;
        this.nearDistance = nearDistance;
    }

    /**
     * Walks the fill round the camera. {@code nearDistance} is how far the near terrain reaches
     * (blocks); ground wholly within it is left out of the areas.
     */
    static Reading measure(LongPredicate done, double cameraX, double cameraZ, float farDistance, float levelDistance, double nearDistance) {
        FillProgress walk = new FillProgress(done, cameraX, cameraZ, farDistance, levelDistance, nearDistance);
        walk.walkTop();
        Steps steps = new Steps(walk.nearArea, walk.nearAreaAtDetail, walk.nearWorstExcess, walk.restArea, walk.restAreaAtCoarse,
            walk.restWorstOverCoarse, walk.firstTwoTiles, walk.firstTwoTilesDone, walk.nextStep);
        return new Reading(walk.wanted, walk.doneCount, walk.wantedPerLevel, walk.donePerLevel, walk.area, walk.areaByExcess, walk.areaNotDrawn,
            walk.worstExcess, walk.nextLevel, steps);
    }

    private void walkTop() {
        int top = PredictedLand.topLevel(farDistance);
        int span = TileKey.span(top);
        int minX = (int) Math.floor((cameraX - farDistance) / span);
        int maxX = (int) Math.floor((cameraX + farDistance) / span);
        int minZ = (int) Math.floor((cameraZ - farDistance) / span);
        int maxZ = (int) Math.floor((cameraZ + farDistance) / span);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                long key = TileKey.of(top, x, z);
                double distance = distance(key);
                if (distance > farDistance) {
                    continue;
                }
                boolean isDone = count(key);
                firstTwoTiles++;
                firstTwoTilesDone += isDone ? 1 : 0;
                if (!isDone) {
                    int step = PredictedLand.stepOf(top, distance, true, nearRing);
                    working(top, step, PredictedLand.priority(step, true, distance, farDistance, 0.0));
                }
                walk(key, isDone ? top : NOT_DRAWN_LEVEL, -1);
            }
        }
    }

    /**
     * A tile of the final tree, already counted, with the level its ground is drawn at now and the
     * level Detail Low would draw it at, once known (-1 above it).
     */
    private void walk(long key, int drawnLevel, int coarseLevel) {
        int level = TileKey.level(key);
        double distance = distance(key);
        if (coarseLevel < 0 && !(level > 0 && distance < PredictedLand.COARSE_LEVEL_DISTANCE * (1 << level))) {
            coarseLevel = level;
        }
        boolean splits = level > 0 && distance < (double) levelDistance * (1 << level);
        if (!splits) {
            ground(key, level, drawnLevel, coarseLevel < 0 ? level : coarseLevel);
            return;
        }
        long[] children = new long[4];
        boolean allDone = true;
        int step = PredictedLand.stepOf(level, distance, false, nearRing);
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            children[quadrant] = TileKey.child(key, quadrant);
            boolean childDone = count(children[quadrant]);
            allDone &= childDone;
            if (step != PredictedLand.STEP_REFINE) {
                firstTwoTiles++;
                firstTwoTilesDone += childDone ? 1 : 0;
            }
        }
        // PredictedLand plans a tile's children once the tile is drawn (done, and its ancestors split).
        if (drawnLevel == level && !allDone) {
            working(level - 1, step, PredictedLand.priority(step, false, distance, farDistance, (double) levelDistance * (1 << level)));
        }
        int childDrawn = drawnLevel == level && allDone ? level - 1 : drawnLevel;
        for (long child : children) {
            if (distance(child) <= farDistance) {
                walk(child, childDrawn, coarseLevel);
            }
        }
    }

    /** Counts a wanted tile; true when it is done. */
    private boolean count(long key) {
        int level = TileKey.level(key);
        wanted++;
        wantedPerLevel[level]++;
        if (done.test(key)) {
            doneCount++;
            donePerLevel[level]++;
            return true;
        }
        return false;
    }

    /** Where PredictedLand would work next: its most urgent wanted task (the same order as its plan). */
    private void working(int level, int step, double priority) {
        if (priority < nextPriority) {
            nextPriority = priority;
            nextLevel = level;
            nextStep = step;
        }
    }

    /** Ground at its final level: how much coarser it is drawn now, and how it stands in the steps. */
    private void ground(long key, int finalLevel, int drawnLevel, int coarseLevel) {
        int span = TileKey.span(finalLevel);
        if (farthest(key) <= nearDistance) {
            return;
        }
        double tileArea = (double) span * span;
        area += tileArea;
        boolean near = distance(key) < nearRing;
        if (near) {
            nearArea += tileArea;
        } else {
            restArea += tileArea;
        }
        if (drawnLevel == NOT_DRAWN_LEVEL) {
            areaNotDrawn += tileArea;
            worstExcess = NOT_DRAWN;
            if (near) {
                nearWorstExcess = NOT_DRAWN;
            } else {
                restWorstOverCoarse = NOT_DRAWN;
            }
            return;
        }
        int excess = drawnLevel - finalLevel;
        areaByExcess[Math.min(excess, MAX_EXCESS)] += tileArea;
        worstExcess = Math.max(worstExcess, excess);
        if (near) {
            nearWorstExcess = Math.max(nearWorstExcess, excess);
            if (excess == 0) {
                nearAreaAtDetail += tileArea;
            }
        } else {
            restWorstOverCoarse = Math.max(restWorstOverCoarse, drawnLevel - coarseLevel);
            if (drawnLevel <= coarseLevel) {
                restAreaAtCoarse += tileArea;
            }
        }
    }

    /** From the camera to the tile's nearest point, across the ground. */
    private double distance(long key) {
        int span = TileKey.span(TileKey.level(key));
        double dx = axis(cameraX, TileKey.originX(key), TileKey.originX(key) + span);
        double dz = axis(cameraZ, TileKey.originZ(key), TileKey.originZ(key) + span);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double axis(double camera, double low, double high) {
        return camera < low ? low - camera : camera > high ? camera - high : 0.0;
    }

    /** From the camera to the tile's farthest corner, across the ground. */
    private double farthest(long key) {
        int span = TileKey.span(TileKey.level(key));
        double dx = Math.max(Math.abs(cameraX - TileKey.originX(key)), Math.abs(cameraX - (TileKey.originX(key) + span)));
        double dz = Math.max(Math.abs(cameraZ - TileKey.originZ(key)), Math.abs(cameraZ - (TileKey.originZ(key) + span)));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
