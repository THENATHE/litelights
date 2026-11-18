package com.thenathe.litelights.render.feature.farterrain;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * FarSnowGrid as it was in 0.9.10, when the render thread merged and encoded the lines: the
 * reference FarSnowFillTest holds the background fill to, byte for byte. Not to be changed.
 *
 * <p>One ring of far snow lines around the camera: {@link #SIZE} x {@link #SIZE} texels, each the
 * line of the block column in the middle of its {@code spacing} x {@code spacing} cell, from a first
 * cell that moves in whole steps as the camera does.
 * It holds the lines Almanac gives now and the lines from before the season's last change, which
 * the far pass animates between (shaders/include/farterrain/far_snow.glsl). Client thread only.
 */
final class OldFarSnowGrid {
    static final int SIZE = 512;

    /** A rectangle of texels, both ends included. */
    record Rect(int minX, int minZ, int maxX, int maxZ) {
        static final Rect ALL = new Rect(0, 0, SIZE - 1, SIZE - 1);

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxZ - minZ + 1;
        }

        Rect union(Rect other) {
            return new Rect(Math.min(minX, other.minX), Math.min(minZ, other.minZ), Math.max(maxX, other.maxX), Math.max(maxZ, other.maxZ));
        }
    }

    final int spacing;
    /** The camera may get this far from the ring's middle, on either axis, before the ring moves. */
    private final int step;
    final int[] now = new int[SIZE * SIZE];
    final int[] before = new int[SIZE * SIZE];
    private boolean placed;
    private int originX;
    private int originZ;
    /** Texels whose lines may have changed since they were last filled; null when none. */
    private @Nullable Rect dirty;

    OldFarSnowGrid(int spacing, int step) {
        this.spacing = spacing;
        this.step = step;
        clear();
    }

    void clear() {
        Arrays.fill(now, FarSnowSource.UNKNOWN);
        Arrays.fill(before, FarSnowSource.UNKNOWN);
        placed = false;
        dirty = null;
    }

    boolean placed() {
        return placed;
    }

    int originX() {
        return originX;
    }

    int originZ() {
        return originZ;
    }

    /** Blocks the ring covers on a side. */
    int span() {
        return SIZE * spacing;
    }

    /** The first column of a ring whose middle is the camera's, in whole steps. */
    static int originFor(double camera, int step, int span) {
        return (int) Math.round(camera / step) * step - span / 2;
    }

    /** Whether the ring should move for a camera here: never placed, or the camera a step from its middle. */
    boolean wantsMove(double cameraX, double cameraZ) {
        if (!placed) {
            return true;
        }
        double middleX = originX + span() / 2.0;
        double middleZ = originZ + span() / 2.0;
        return Math.abs(cameraX - middleX) >= step || Math.abs(cameraZ - middleZ) >= step;
    }

    int wantedOriginX(double cameraX) {
        return originFor(cameraX, step, span());
    }

    int wantedOriginZ(double cameraZ) {
        return originFor(cameraZ, step, span());
    }

    /** Marks the texels of an area of block columns (both ends included) to fill again. */
    void markDirty(FarSnowSource.Area area) {
        if (area.everywhere()) {
            dirty = Rect.ALL;
            return;
        }
        Rect rect = texels(area.minX(), area.minZ(), area.maxX(), area.maxZ());
        if (rect != null) {
            dirty = dirty == null ? rect : dirty.union(rect);
        }
    }

    void markAllDirty() {
        dirty = Rect.ALL;
    }

    /** What to fill next, then clean; null if nothing is dirty. */
    @Nullable Rect takeDirty() {
        Rect taken = dirty;
        dirty = null;
        return taken;
    }

    /** The column a texel's line is read at, on one axis: the middle of its cell. */
    int sampleOffset() {
        return spacing / 2;
    }

    /** The texels whose columns lie in an area of block columns, or null if it misses the ring. */
    @Nullable Rect texels(int minX, int minZ, int maxX, int maxZ) {
        int firstX = originX + sampleOffset();
        int firstZ = originZ + sampleOffset();
        int x0 = Math.max(0, Math.floorDiv(minX - firstX + spacing - 1, spacing));
        int z0 = Math.max(0, Math.floorDiv(minZ - firstZ + spacing - 1, spacing));
        int x1 = Math.min(SIZE - 1, Math.floorDiv(maxX - firstX, spacing));
        int z1 = Math.min(SIZE - 1, Math.floorDiv(maxZ - firstZ, spacing));
        return x0 > x1 || z0 > z1 ? null : new Rect(x0, z0, x1, z1);
    }

    /**
     * Takes lines that are only seen better (a map tile arrived or went): a column whose line is new
     * takes it at once, before and now, and a column whose line is the same keeps any change it is
     * showing.
     */
    void accept(Rect rect, int[] lines) {
        int width = rect.width();
        for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
            int from = (z - rect.minZ()) * width - rect.minX();
            for (int x = rect.minX(), at = z * SIZE + x; x <= rect.maxX(); x++, at++) {
                int line = lines[from + x];
                if (line != now[at]) {
                    now[at] = line;
                    before[at] = line;
                }
            }
        }
    }

    /** How many columns the season changes: known before and after, with another line. */
    int countChanges(Rect rect, int[] lines) {
        int count = 0;
        int width = rect.width();
        for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
            int from = (z - rect.minZ()) * width - rect.minX();
            for (int x = rect.minX(), at = z * SIZE + x; x <= rect.maxX(); x++, at++) {
                int line = lines[from + x];
                if (line != now[at] && line != FarSnowSource.UNKNOWN && now[at] != FarSnowSource.UNKNOWN) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Takes the lines after a change of season: a column whose line changed shows its line until
     * now as "before" (none if it wasn't known); the rest keep what they show.
     */
    void acceptChange(Rect rect, int[] lines) {
        int width = rect.width();
        for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
            int from = (z - rect.minZ()) * width - rect.minX();
            for (int x = rect.minX(), at = z * SIZE + x; x <= rect.maxX(); x++, at++) {
                int line = lines[from + x];
                if (line != now[at]) {
                    before[at] = now[at] == FarSnowSource.UNKNOWN ? line : now[at];
                    now[at] = line;
                }
            }
        }
    }

    /**
     * Writes a rectangle's texels for the far pass, row by row: per texel the code of its line
     * before (low 16 bits) and now (high), little-endian, as RG16 wants them.
     */
    void encode(Rect rect, int floor, ByteBuffer out) {
        int index = 0;
        for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
            for (int x = rect.minX(), at = z * SIZE + x; x <= rect.maxX(); x++, at++) {
                out.putInt(index, code(before[at], floor) | code(now[at], floor) << 16);
                index += 4;
            }
        }
        out.limit(index);
    }

    /** Whether any column has a known line now. */
    boolean hasKnown() {
        for (int line : now) {
            if (line != FarSnowSource.UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves the ring to a new first column with a full fill. Where the old and new rings overlap,
     * {@link #before} keeps its lines if a change is still being shown; everywhere else nothing
     * animates.
     */
    void moveTo(int newOriginX, int newOriginZ, int[] lines, boolean keepBefore) {
        int[] oldBefore = keepBefore && placed ? before.clone() : null;
        int shiftX = (newOriginX - originX) / spacing;
        int shiftZ = (newOriginZ - originZ) / spacing;
        System.arraycopy(lines, 0, now, 0, now.length);
        System.arraycopy(lines, 0, before, 0, before.length);
        if (oldBefore != null) {
            for (int z = 0; z < SIZE; z++) {
                int oldZ = z + shiftZ;
                if (oldZ < 0 || oldZ >= SIZE) {
                    continue;
                }
                for (int x = 0; x < SIZE; x++) {
                    int oldX = x + shiftX;
                    int held = oldX >= 0 && oldX < SIZE ? oldBefore[oldZ * SIZE + oldX] : FarSnowSource.UNKNOWN;
                    if (held != FarSnowSource.UNKNOWN && now[z * SIZE + x] != FarSnowSource.UNKNOWN) {
                        before[z * SIZE + x] = held;
                    }
                }
            }
        }
        originX = newOriginX;
        originZ = newOriginZ;
        placed = true;
    }

    /** A change has finished showing, or is cut short by the next: every column shows its line now. */
    void settle() {
        System.arraycopy(now, 0, before, 0, now.length);
    }

    /** How many columns have a known line now. */
    int known() {
        int count = 0;
        for (int line : now) {
            if (line != FarSnowSource.UNKNOWN) {
                count++;
            }
        }
        return count;
    }

    /** How many known columns change their line in the change being shown. */
    int changing() {
        int count = 0;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != before[i] && now[i] != FarSnowSource.UNKNOWN && before[i] != FarSnowSource.UNKNOWN) {
                count++;
            }
        }
        return count;
    }

    /**
     * A texel's code for the far pass: 0 unknown, 1 everywhere, 65535 nowhere, else the line's
     * height above the level's floor plus 2, so "a surface at height y has snow" is
     * {@code y - floor + 2 >= code} for every code but 0.
     */
    static int code(int line, int floor) {
        if (line == FarSnowSource.UNKNOWN) {
            return 0;
        }
        if (line == FarSnowSource.EVERYWHERE) {
            return 1;
        }
        if (line == FarSnowSource.NOWHERE) {
            return 65535;
        }
        return (int) Math.clamp((long) line - floor + 2L, 1L, 65535L);
    }
}
