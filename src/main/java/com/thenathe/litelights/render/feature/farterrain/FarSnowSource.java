package com.thenathe.litelights.render.feature.farterrain;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Where far terrain's seasonal snow comes from: Almanac's far-terrain lookups
 * ({@link AlmanacFarSnow}, the only implementation, loaded only when Almanac is installed). The
 * lookups are safe from any thread, so FarSnow fills its grids on a background thread.
 */
interface FarSnowSource {
    /** {@code fillFarSnowLines}'s sentinels, the same values as Almanac's. */
    int UNKNOWN = Integer.MIN_VALUE;
    int EVERYWHERE = Integer.MIN_VALUE + 1;
    int NOWHERE = Integer.MAX_VALUE;

    /** Told what changed, on the client thread; the area is in block coordinates, both ends included. */
    interface Changes {
        void changed(ClientLevel level, Area area, boolean snowLines, boolean colours, Kind kind);
    }

    record Area(int minX, int minZ, int maxX, int maxZ) {
        static final Area EVERYWHERE = new Area(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        boolean everywhere() {
            return equals(EVERYWHERE);
        }

        Area union(Area other) {
            return new Area(Math.min(minX, other.minX), Math.min(minZ, other.minZ), Math.max(maxX, other.maxX), Math.max(maxZ, other.maxZ));
        }
    }

    /**
     * Why the answers changed: the map itself (tiles arriving or going, the map switched on or off),
     * so the land is only seen better, or what the season shows on it, which far terrain animates.
     */
    enum Kind { MAP, SEASON }

    /** Starts telling {@code listener} about changes. Called once. */
    void listen(Changes listener);

    /** Far snow lines of a grid of columns, row by row, x fastest; any thread. */
    void fillLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines);

    /** The season's grass colour (0xRRGGBB) at a column, or -1 where it isn't known; any thread. */
    int grassColour(ClientLevel level, int x, int z);

    /** The season's foliage colour (0xRRGGBB) at a column, or -1 where it isn't known; any thread. */
    int foliageColour(ClientLevel level, int x, int z);

    /** Whether a column's biome is hot all year (deserts and badlands, whose sand stays as it is); any thread. */
    boolean hot(ClientLevel level, int x, int z);

    /** How long a change takes in loaded chunks, in game ticks, or -1 if Almanac doesn't say. */
    long thawTicks(ClientLevel level);

    /**
     * The game tick at which what far terrain shows last changed, or -1 where Almanac doesn't say:
     * Almanac before 0.1.3 on the client or the server, outside the overworld, before it arrives.
     * A server that sends it (0.1.3 on) also forms near snow in patches over the thaw time.
     */
    long changedTick(ClientLevel level);

    /** Snow depth in layers of each climate (Almanac's HOT, TEMPERATE, COLD), -1 where unknown. */
    void snowDepths(ClientLevel level, double[] depths);

    /** The sub-season's index, or -1 without a season. */
    int subSeason(ClientLevel level);
}
