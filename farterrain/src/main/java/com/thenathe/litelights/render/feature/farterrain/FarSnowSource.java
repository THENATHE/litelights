package com.thenathe.litelights.render.feature.farterrain;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Where far terrain's seasonal snow comes from: Almanac's far-terrain lookups
 * ({@link AlmanacFarSnow}, the only implementation, loaded only when Almanac 1.0.0 or later is
 * installed). The lookups are safe from any thread, so FarSnow fills its grids on a background thread.
 *
 * <p>The season's snow lies between a column's bare line and its far snow line, on the share of
 * columns its climate's cover says, while it forms and while it melts (Almanac's docs/FAR-SNOW.md,
 * "Bare lines and the thaw"); nothing about it is timed or animated here.
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
     * so only that area is filled again, or what the season shows on the land, everywhere.
     */
    enum Kind { MAP, SEASON }

    /** Starts telling {@code listener} about changes. Called once. */
    void listen(Changes listener);

    /** Far snow lines of a grid of columns, row by row, x fastest; any thread. */
    void fillLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines);

    /** {@link #fillLines} for the bare lines: where snow lies whatever the cover; any thread. */
    void fillBareLines(ClientLevel level, int originX, int originZ, int spacing, int width, int height, int[] lines);

    /** The season's grass colour (0xRRGGBB) at a column, or -1 where it isn't known; any thread. */
    int grassColour(ClientLevel level, int x, int z);

    /** The season's foliage colour (0xRRGGBB) at a column, or -1 where it isn't known; any thread. */
    int foliageColour(ClientLevel level, int x, int z);

    /** Whether a column's biome is hot all year (deserts and badlands, whose sand stays as it is); any thread. */
    boolean hot(ClientLevel level, int x, int z);

    /**
     * Snow depth in layers of each climate (Almanac's HOT, TEMPERATE, COLD), -1 where unknown. The
     * first layer is the climate's cover: the share of its columns the season's snow lies on.
     */
    void snowDepths(ClientLevel level, double[] depths);

    /**
     * Which of the climates' layer shares each column of a grid follows (1 HOT, 2 TEMPERATE, 3 COLD:
     * its biome's base climate, Almanac's area), 0 where it isn't known; row by row, any thread.
     */
    default void fillAreas(ClientLevel level, int originX, int originZ, int spacing, int width, int height, byte[] areas) {
        java.util.Arrays.fill(areas, (byte) 0);
    }

    /**
     * Each climate's layer shares now (HOT, TEMPERATE, COLD; layer 1 first, as many as {@code shares}
     * has room for), held to SnowPoints' steps as the near snow holds them; 0 where unknown.
     */
    default void snowShares(ClientLevel level, float[][] shares) {
        for (float[] layers : shares) {
            java.util.Arrays.fill(layers, 0.0f);
        }
    }

    /**
     * Whether the season's snow differs by place (Almanac's regional weather), so far land takes
     * each place's shares ({@link #snowSharesAt}) rather than those where the player stands; any thread.
     */
    default boolean snowByPlace(ClientLevel level) {
        return false;
    }

    /**
     * {@link #snowShares} at a block column: each climate's layer shares there, held to the same
     * steps. False where the place isn't known, and then {@code shares} hold nothing to go by; any thread.
     */
    default boolean snowSharesAt(ClientLevel level, int x, int z, float[][] shares) {
        return false;
    }
}
