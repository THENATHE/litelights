package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.Arrays;

/**
 * Which chunk columns the game's own terrain covers, and how far far terrain has faded out of
 * each: the seam mask (a 256 by 256 texture of chunks, wrapping, around the camera). A column the
 * near renderer has drawn fades far terrain out over {@link #FADE_MILLIS}, dithered, as the near
 * chunk fades in; one it no longer draws (unloaded, past the render distance) shows far terrain
 * again at once, so nothing opens a hole while chunks stream in or out. Loaded chunks are the
 * world's full height, so a column is all a mask needs. Render thread only.
 *
 * <p>Two channels: the land's (red), and the water's (green). Far land goes once the near terrain
 * draws the column's top and, under water, the bed beneath it, so far land never leaves a hole the
 * near water would show; far water goes as soon as the near terrain draws the water's surface,
 * whatever lies under it. Under open sea the near bed can stay undrawn for good (its sections are
 * compiled only once they come into view, and from just over the water the bed below the camera
 * never does), and far water left there drew under the near water.
 */
public final class SeamCoverage {
    public static final int SIZE = 256;
    public static final long FADE_MILLIS = 500L;
    /** Bytes per column in {@link #values()}: land, then water. */
    public static final int CHANNELS = 2;

    /** How much of a column the near terrain draws. */
    public enum Cover {
        /** Nothing yet, or nothing at all: far terrain shows. */
        NONE,
        /** The water's surface, but not yet the bed under it: far water goes, far land stays. */
        WATER,
        /** Everything far terrain would draw there. */
        ALL;

        boolean land() {
            return this == ALL;
        }

        boolean water() {
            return this != NONE;
        }
    }

    /** The near terrain's sections in one chunk column, for {@link #cover}. */
    public interface Column {
        /** Whether the near renderer draws this section (compiled and faded in). */
        boolean drawn(int sectionY);

        /** Whether this section holds nothing but water and air, so it has nothing of its own to draw. */
        boolean onlyWater(int sectionY);
    }

    /**
     * What the near terrain covers in a column whose land (or water) reaches up into
     * {@code topSection}: nothing until that section is drawn; then the water, and the land too
     * once the first section below with anything but water is drawn. Water deeper than
     * {@code bedSections} sections hides its bed anyway, so it counts as covered without it.
     */
    public static Cover cover(Column column, int topSection, int lowestSection, int bedSections) {
        if (!column.drawn(topSection)) {
            return Cover.NONE;
        }
        for (int below = topSection - 1; below >= lowestSection && below >= topSection - bedSections; below--) {
            if (!column.onlyWater(below)) {
                return column.drawn(below) ? Cover.ALL : Cover.WATER;
            }
        }
        return Cover.ALL;
    }

    /**
     * Whether the near renderer draws this chunk at all, by its own render distance rule: vanilla
     * draws a chunk whose distance from the camera's chunk, less one on each axis, is under the
     * render distance (ChunkTrackingView.isInViewDistance); Sodium one whose box, a block wider on
     * each side, comes within the render distance in blocks of the camera itself. Both reach past
     * the render distance's circle in places, so near and far water overlap there unless far
     * water steps aside by the same rule.
     */
    public static boolean nearDrawsChunk(boolean sodium, double cameraX, double cameraZ, int chunkX, int chunkZ, int radius) {
        if (sodium) {
            double dx = distanceOutside(cameraX, chunkX * 16 - 1, chunkX * 16 + 17);
            double dz = distanceOutside(cameraZ, chunkZ * 16 - 1, chunkZ * 16 + 17);
            double reach = radius * 16.0;
            return dx * dx + dz * dz < reach * reach;
        }
        long dx = Math.max(0, Math.abs(chunkX - ((int) Math.floor(cameraX) >> 4)) - 1);
        long dz = Math.max(0, Math.abs(chunkZ - ((int) Math.floor(cameraZ) >> 4)) - 1);
        return dx * dx + dz * dz < (long) radius * radius;
    }

    /**
     * Whether the near renderer draws sections this high or low at all: vanilla none more than the
     * render distance in sections above or below the camera's (SectionOcclusionGraph), Sodium none
     * whose box, a block taller each way, lies further above or below the camera than the render
     * distance in blocks. A section compiled while the camera stood nearer it is still compiled,
     * so the compiled check alone would hide far land and water the near renderer has stopped drawing.
     */
    public static boolean nearDrawsHeight(boolean sodium, double cameraY, int sectionY, int radius) {
        if (sodium) {
            return distanceOutside(cameraY, sectionY * 16 - 1, sectionY * 16 + 17) < radius * 16.0;
        }
        return Math.abs(sectionY - ((int) Math.floor(cameraY) >> 4)) <= radius;
    }

    private static double distanceOutside(double point, double min, double max) {
        return point < min ? min - point : point > max ? point - max : 0.0;
    }

    private static final long NOT_COVERED = Long.MIN_VALUE;

    private final byte[] values = new byte[SIZE * SIZE * CHANNELS];
    /** When each column's land and water were first seen covered; {@link #NOT_COVERED} while they aren't. */
    private final long[] landSince = new long[SIZE * SIZE];
    private final long[] waterSince = new long[SIZE * SIZE];
    private final long[] chunkAt = new long[SIZE * SIZE];
    private boolean changed;

    public SeamCoverage() {
        Arrays.fill(chunkAt, Long.MIN_VALUE);
        Arrays.fill(landSince, NOT_COVERED);
        Arrays.fill(waterSince, NOT_COVERED);
    }

    private static int slot(int chunkX, int chunkZ) {
        return (chunkX & (SIZE - 1)) + (chunkZ & (SIZE - 1)) * SIZE;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    /** Records whether the near terrain covers this column now, land and water alike. */
    public void update(int chunkX, int chunkZ, boolean covered, long nowMillis) {
        update(chunkX, chunkZ, covered ? Cover.ALL : Cover.NONE, nowMillis, false);
    }

    /** As above, for a column covered or not as a whole. */
    public void update(int chunkX, int chunkZ, boolean covered, long nowMillis, boolean already) {
        update(chunkX, chunkZ, covered ? Cover.ALL : Cover.NONE, nowMillis, already);
    }

    /**
     * Records how much of this column the near terrain covers now. {@code already} for a column the
     * near terrain drew before the mask knew of it (the mask was just cleared, after a jump or as
     * the world opens): far terrain goes from it at once, as a fade would show far land over near
     * land already there, coarse boxes standing above it.
     */
    public void update(int chunkX, int chunkZ, Cover cover, long nowMillis, boolean already) {
        int slot = slot(chunkX, chunkZ);
        long key = chunkKey(chunkX, chunkZ);
        if (chunkAt[slot] != key) {
            chunkAt[slot] = key;
            landSince[slot] = NOT_COVERED;
            waterSince[slot] = NOT_COVERED;
            setValue(slot, 0, 0);
            setValue(slot, 1, 0);
        }
        setValue(slot, 0, faded(landSince, slot, cover.land(), nowMillis, already));
        setValue(slot, 1, faded(waterSince, slot, cover.water(), nowMillis, already));
    }

    /** One channel's fade, 0 to 255, starting its clock when it is first covered. */
    private static int faded(long[] since, int slot, boolean covered, long nowMillis, boolean already) {
        if (!covered) {
            since[slot] = NOT_COVERED;
            return 0;
        }
        if (since[slot] == NOT_COVERED) {
            since[slot] = already ? nowMillis - FADE_MILLIS : nowMillis;
        }
        float faded = Math.min(1.0f, (nowMillis - since[slot]) / (float) FADE_MILLIS);
        return Math.round(faded * 255.0f);
    }

    private void setValue(int slot, int channel, int value) {
        byte stored = (byte) value;
        int index = slot * CHANNELS + channel;
        if (values[index] != stored) {
            values[index] = stored;
            changed = true;
        }
    }

    /** How far far land has faded out of the column, 0 (shown) to 255 (gone). */
    public int value(int chunkX, int chunkZ) {
        return channel(chunkX, chunkZ, 0);
    }

    /** How far far water has faded out of the column, 0 (shown) to 255 (gone). */
    public int waterValue(int chunkX, int chunkZ) {
        return channel(chunkX, chunkZ, 1);
    }

    private int channel(int chunkX, int chunkZ, int channel) {
        int slot = slot(chunkX, chunkZ);
        return chunkAt[slot] == chunkKey(chunkX, chunkZ) ? values[slot * CHANNELS + channel] & 0xFF : 0;
    }

    /**
     * Whether far terrain is wholly gone from the column: the near terrain draws it all. Its water
     * goes no later than its land, so this covers both.
     */
    public boolean fullyCovered(int chunkX, int chunkZ) {
        return value(chunkX, chunkZ) == 255;
    }

    /** Whether far water is wholly gone from the column. */
    public boolean waterCovered(int chunkX, int chunkZ) {
        return waterValue(chunkX, chunkZ) == 255;
    }

    /** Whether anything changed since the last call, for the texture upload. */
    public boolean takeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }

    /** Two bytes a column, land then water, row by row: the texture's own layout. */
    public byte[] values() {
        return values;
    }

    public void clear() {
        Arrays.fill(values, (byte) 0);
        Arrays.fill(landSince, NOT_COVERED);
        Arrays.fill(waterSince, NOT_COVERED);
        Arrays.fill(chunkAt, Long.MIN_VALUE);
        changed = true;
    }
}
