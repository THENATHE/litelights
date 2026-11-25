package com.thenathe.litelights.render.feature.weather;

/**
 * Where rain drips and wall streaks may form (drip and streak ideas by pigcart, Particle Rain;
 * concepts only). Pure rules over a small view of the world, so they are unit-tested without a
 * level; {@link RainDetails} asks them with the column grid and the client's blocks.
 *
 * <ul>
 *   <li><b>Drips</b> fall from the underside of a block that rain lands on (the top of its column)
 *   and that has open air under it at an edge, where the next column's rain falls past that air:
 *   eaves, ledges, the rims of bridges and overhangs. Under the middle of a roof nothing drips, and
 *   a roof flush with its wall drips nowhere (the water runs down the wall instead). Under tree
 *   canopies drips fall from the lowest leaves of the column wherever there is air beneath.</li>
 *   <li><b>Wall streaks</b> run down a hard face (stone, bricks, concrete and the like) or glass
 *   (blocks and panes) where the air in front of it is open to the rain: the wall rises at least
 *   two blocks above the ground in front, and the face lies above that ground. Glass gets
 *   {@link #GLASS_STREAK_SHARE} times as many as a wall, so rain on windows reads clearly.</li>
 * </ul>
 */
public final class RainDetailRules {
    /** Rain doesn't reach this column, or it isn't known. */
    public static final int UNKNOWN = Integer.MIN_VALUE;
    /** How far down through a canopy drips are looked for. */
    static final int CANOPY_DEPTH = 6;
    /** Walls lower than this above the ground in front of them get no streaks. */
    static final int LOWEST_WALL = 2;
    /** Streaks a glass face gets for each one a hard wall's face gets. */
    public static final float GLASS_STREAK_SHARE = 1.5f;
    /** A pane's faces lie this far inside its block, which it crosses in the middle. */
    static final double PANE_INSET = 7.0 / 16.0;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public enum Block {
        /** Rain and drips fall through it: air, plants, anything without collision or fluid. */
        OPEN,
        LEAVES,
        /** Anything else. Whether a face is hard enough for streaks is asked separately. */
        SOLID
    }

    /** The part of the world the rules look at. */
    public interface World {
        /** The world height rain stops at in a column (vanilla's MOTION_BLOCKING heightmap), or {@link #UNKNOWN}. */
        int rainTop(int x, int z);

        Block block(int x, int y, int z);

        /**
         * The face of block (x, y, z) looking along (dx, dz) that streaks can run down: a full face of
         * a hard material, glass, or a glass pane's face ({@link #paneFace}), or null for none.
         */
        StreakFace streakFace(int x, int y, int z, int dx, int dz);
    }

    /**
     * Where on a block's face a streak can run: how far the face lies inside the block from its
     * boundary (0 for a full face, a pane's 7/16), the part of the face's width it spans (0 to 1 along
     * x or z, whichever the face runs along), and whether it is glass.
     */
    public record StreakFace(double inset, double from, double to, boolean glass) {
        public static final StreakFace HARD_WALL = new StreakFace(0.0, 0.0, 1.0, false);
        public static final StreakFace GLASS_BLOCK = new StreakFace(0.0, 0.0, 1.0, true);
    }

    /** Where a drip forms: the block it hangs under, and the side it hangs toward (0, 0 for leaves). */
    public record DripSpot(int x, int y, int z, int towardX, int towardZ, boolean leaves) {
        /** The height of the underside the drip hangs from. */
        public double underside() {
            return y;
        }
    }

    private RainDetailRules() {
    }

    /** The drip spot in column (x, z), or null where nothing drips. */
    public static DripSpot dripSpot(World world, int x, int z) {
        int top = world.rainTop(x, z);
        if (top == UNKNOWN) {
            return null;
        }
        int y = top - 1;
        Block wet = world.block(x, y, z);
        if (wet == Block.LEAVES) {
            int lowest = y;
            while (lowest > y - CANOPY_DEPTH && world.block(x, lowest - 1, z) == Block.LEAVES) {
                lowest--;
            }
            return world.block(x, lowest - 1, z) == Block.OPEN ? new DripSpot(x, lowest, z, 0, 0, true) : null;
        }
        if (wet != Block.SOLID || world.block(x, y - 1, z) != Block.OPEN) {
            return null;
        }
        for (int[] side : SIDES) {
            int next = world.rainTop(x + side[0], z + side[1]);
            // The next column's rain falls past the air under this block: this is an edge.
            if (next != UNKNOWN && next <= y - 1) {
                return new DripSpot(x, y, z, side[0], side[1], false);
            }
        }
        return null;
    }

    /**
     * The height range of hard wall that faces open rain from column (airX, airZ) toward (dx, dz):
     * {lowest y, highest y} of the wall blocks above the ground in front, clipped to [minY, maxY],
     * or null where there is no such wall. Each block in it still needs {@link #streaks} to agree.
     */
    public static int[] wallRange(World world, int airX, int airZ, int dx, int dz, int minY, int maxY) {
        int ground = world.rainTop(airX, airZ);
        int wallTop = world.rainTop(airX + dx, airZ + dz);
        if (ground == UNKNOWN || wallTop == UNKNOWN || wallTop - ground < LOWEST_WALL) {
            return null;
        }
        int low = Math.max(ground, minY);
        int high = Math.min(wallTop - 1, maxY);
        return low <= high ? new int[] {low, high} : null;
    }

    /**
     * The face of the wall block at height y beside column (airX, airZ) along (dx, dz) that streaks
     * run down, or null where none do (a soft wall, or the face is below the ground in front).
     */
    public static StreakFace streakFace(World world, int airX, int airZ, int dx, int dz, int y) {
        int ground = world.rainTop(airX, airZ);
        if (ground == UNKNOWN || y < ground) {
            return null;
        }
        int wallX = airX + dx;
        int wallZ = airZ + dz;
        return world.block(wallX, y, wallZ) == Block.SOLID ? world.streakFace(wallX, y, wallZ, -dx, -dz) : null;
    }

    /**
     * The face of a glass pane looking along (dx, dz), from the sides it joins. A pane's faces look
     * across the way it runs and span its arms: one running north to south has faces looking east
     * and west across the whole block, one ending here only half of it. None where the pane doesn't
     * run across that way (its narrow end or a lone post) or where an arm sticks out of the face
     * toward the rain (a streak there would run through the arm).
     */
    public static StreakFace paneFace(boolean north, boolean east, boolean south, boolean west, int dx, int dz) {
        boolean low;
        boolean high;
        boolean outward;
        if (dx != 0) {
            low = north;
            high = south;
            outward = dx > 0 ? east : west;
        } else {
            low = west;
            high = east;
            outward = dz > 0 ? south : north;
        }
        if (!low && !high || outward) {
            return null;
        }
        return new StreakFace(PANE_INSET, low ? 0.0 : PANE_INSET, high ? 1.0 : 1.0 - PANE_INSET, true);
    }

    /**
     * How readily rain hits a wall facing out along (faceX, faceZ) with the wind blowing along
     * (windX, windZ): fully on faces turned into the wind, a third as much on faces sheltered from it,
     * evenly when there is hardly any wind.
     */
    public static float windShare(int faceX, int faceZ, float windX, float windZ) {
        float strength = (float) Math.sqrt(windX * windX + windZ * windZ);
        if (strength < 1.0e-3f) {
            return 0.66f;
        }
        float into = -(faceX * windX + faceZ * windZ) / strength;
        return 0.33f + 0.67f * Math.max(0.0f, into);
    }
}
