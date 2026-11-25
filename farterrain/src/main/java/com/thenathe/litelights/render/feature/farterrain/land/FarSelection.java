package com.thenathe.litelights.render.feature.farterrain.land;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongConsumer;

/**
 * Picks, each frame, which tile at which level is drawn: one tile for every piece of known land
 * within the far distance, never two over the same ground. A tile is split into its four
 * children while it is closer than {@code levelDistance << level}, so voxels on screen stay about
 * the same size at every distance (Detail sets levelDistance). While the children of a tile that
 * should split aren't all meshed, the tile itself is drawn instead; while a tile isn't meshed,
 * whatever of its children is. So land never has a hole while meshes catch up, it only shows
 * coarser for a moment.
 *
 * <p>Tiles are chosen, and wanted (to mesh, and to keep in video memory), by distance alone; only
 * drawing looks at the view, so turning round never waits for meshes and never starts a fade.
 * Under a tile drawn coarser than it should be (a stand-in), every tile that will be drawn there
 * once meshed is wanted at once, not a level at a time, so the mesh workers can take them nearest
 * first (FarLandSession sorts what is wanted); the stand-in's own children are marked as the next
 * step, since it can't split until all four are meshed, however far off the farthest is.
 *
 * <p>A tile drawn split stays split while a child of it waits for a mesh: a child that has just
 * come into the store (new land at the edge of what is loaded, a coarse tile newly built over
 * finer ones already drawn) would otherwise put the coarse tile back in their place until it is
 * meshed, a frame or two of big boxes where the land was drawn at full detail. Its land shows
 * once meshed; it wasn't drawn before either.
 */
public final class FarSelection {
    /** What the selection asks about the tiles. */
    public interface Tiles {
        /** The tile or any tile under it holds data. */
        boolean present(long key);

        /** The tile has data of its own (a meta); coarse tiles are built a moment after their children. */
        boolean stored(long key);

        /** The tile has a mesh on the GPU (perhaps out of date), or nothing to draw at all. */
        boolean ready(long key);

        /** World y of its land's bottom and top, or the world's when not stored. */
        int lowestY(long key);

        int highestY(long key);

        /**
         * Whether the tile's own data covers all of its ground, children or not (a tile predicted
         * from the seed), so it must not split while a child is missing. Visited coarse tiles are
         * built from their children and cover only theirs.
         */
        default boolean coversWhole(long key) {
            return false;
        }
    }

    /** Whether a box (camera-relative) can be seen. */
    public interface View {
        boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
    }

    /** Hears each tile that should be meshed and kept; {@code next} for a stand-in's child, which it waits for to split. */
    public interface Wanted {
        void accept(long key, boolean next);
    }

    /** Hears each tile chosen to stand for its ground this frame, and whether the view can see it. */
    public interface Chosen {
        void accept(long key, boolean visible);
    }

    private Tiles tiles;
    private View view;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float farDistance;
    private float levelDistance;
    private Chosen draw;
    private Wanted want;
    private int standIns;
    /** The tiles drawn split in the last walk, and in this one. */
    private LongOpenHashSet splitBefore = new LongOpenHashSet();
    private LongOpenHashSet splitNow = new LongOpenHashSet();

    /**
     * Walks the tiles around the camera. {@code draw} hears each tile chosen for its ground this
     * frame (ready), and whether it can be seen; {@code want} each tile that should be meshed and kept.
     */
    public void select(Tiles tiles, View view, double cameraX, double cameraY, double cameraZ, float farDistance,
                       float levelDistance, Chosen draw, LongConsumer want) {
        select(tiles, view, cameraX, cameraY, cameraZ, farDistance, levelDistance, draw, (key, next) -> want.accept(key));
    }

    /** As above, telling the tiles a stand-in waits for to split ({@link Wanted}). */
    public void select(Tiles tiles, View view, double cameraX, double cameraY, double cameraZ, float farDistance,
                       float levelDistance, Chosen draw, Wanted want) {
        this.tiles = tiles;
        this.view = view;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.farDistance = farDistance;
        this.levelDistance = levelDistance;
        this.draw = draw;
        this.want = want;
        standIns = 0;
        splitNow.clear();
        int top = TileKey.MAX_LEVEL;
        int span = TileKey.span(top);
        int minX = (int) Math.floor((cameraX - farDistance) / span);
        int maxX = (int) Math.floor((cameraX + farDistance) / span);
        int minZ = (int) Math.floor((cameraZ - farDistance) / span);
        int maxZ = (int) Math.floor((cameraZ + farDistance) / span);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                visit(TileKey.of(top, x, z));
            }
        }
        LongOpenHashSet swap = splitBefore;
        splitBefore = splitNow;
        splitNow = swap;
    }


    private void visit(long key) {
        if (!tiles.present(key)) {
            return;
        }
        int level = TileKey.level(key);
        double distance = distance(key);
        if (distance > farDistance) {
            return;
        }
        boolean split = level > 0 && distance < (double) levelDistance * (1 << level);
        boolean ready = tiles.stored(key) && tiles.ready(key);
        if (split && (childrenReady(key) || (splitBefore.contains(key) && !childMissing(key)))) {
            splitNow.add(key);
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                visit(TileKey.child(key, quadrant));
            }
            return;
        }
        if (ready) {
            want.accept(key, false);
            draw.accept(key, visible(key));
            if (split) {
                // Drawn coarser for now: get what will be drawn here meshed, so it can split.
                standIns++;
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    prefetch(TileKey.child(key, quadrant), true);
                }
            }
            return;
        }
        if (tiles.stored(key)) {
            want.accept(key, false);
        }
        if (level > 0) {
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                visit(TileKey.child(key, quadrant));
            }
        }
    }

    /** Wants a tile under a stand-in, and the tiles under it down to the level its ground will be drawn at. */
    private void prefetch(long key, boolean next) {
        if (!tiles.present(key)) {
            return;
        }
        double distance = distance(key);
        if (distance > farDistance) {
            return;
        }
        if (tiles.stored(key)) {
            want.accept(key, next);
        }
        int level = TileKey.level(key);
        if (level > 0 && distance < (double) levelDistance * (1 << level)) {
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                prefetch(TileKey.child(key, quadrant), false);
            }
        }
    }

    /** How many tiles the last walk drew coarser than their distance asks for, while finer ones are meshed. */
    public int standIns() {
        return standIns;
    }

    /** Every child with data can be drawn, or has no data of its own yet (its own children stand in). */
    private boolean childrenReady(long key) {
        if (childMissing(key)) {
            return false;
        }
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            long child = TileKey.child(key, quadrant);
            if (tiles.present(child) && tiles.stored(child) && !tiles.ready(child)) {
                return false;
            }
        }
        return true;
    }

    /** A tile covering all its ground with a child that has nothing yet: splitting would leave that quarter empty. */
    private boolean childMissing(long key) {
        if (!tiles.coversWhole(key)) {
            return false;
        }
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            if (!tiles.present(TileKey.child(key, quadrant))) {
                return true;
            }
        }
        return false;
    }

    private boolean visible(long key) {
        int span = TileKey.span(TileKey.level(key));
        float minX = (float) (TileKey.originX(key) - cameraX);
        float minZ = (float) (TileKey.originZ(key) - cameraZ);
        float minY = (float) (tiles.lowestY(key) - cameraY);
        float maxY = (float) (tiles.highestY(key) - cameraY);
        return view.visible(minX, minY, minZ, minX + span, maxY, minZ + span);
    }

    /** From the camera to the nearest point of the tile's box. */
    double distance(long key) {
        int span = TileKey.span(TileKey.level(key));
        double dx = axisDistance(cameraX, TileKey.originX(key), TileKey.originX(key) + span);
        double dz = axisDistance(cameraZ, TileKey.originZ(key), TileKey.originZ(key) + span);
        double dy = axisDistance(cameraY, tiles.lowestY(key), tiles.highestY(key));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double camera, double low, double high) {
        return camera < low ? low - camera : camera > high ? camera - high : 0.0;
    }

    /**
     * Which face directions of a tile's box can face the camera (bit per Direction ordinal): a
     * face toward -y is seen only from below its plane, and so on.
     */
    public static int facingDirections(double cameraX, double cameraY, double cameraZ, double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        int bits = 0;
        if (cameraY < maxY) {
            bits |= 1 << FarQuad.DOWN;
        }
        if (cameraY > minY) {
            bits |= 1 << FarQuad.UP;
        }
        if (cameraZ < maxZ) {
            bits |= 1 << FarQuad.NORTH;
        }
        if (cameraZ > minZ) {
            bits |= 1 << FarQuad.SOUTH;
        }
        if (cameraX < maxX) {
            bits |= 1 << FarQuad.WEST;
        }
        if (cameraX > minX) {
            bits |= 1 << FarQuad.EAST;
        }
        return bits;
    }
}
