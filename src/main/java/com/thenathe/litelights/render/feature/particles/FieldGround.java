package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The land under the Mist field (fog098): a small height map around the camera, so the mist's layer
 * lies on the land at every step of its trace instead of on one flat sheet at the camera's own
 * ground. 64 x 64 cells of 4 x 4 blocks (256 blocks across), each the mean of four of its columns'
 * ground (leaves left out, so mist lies on a forest's floor; water's surface counts as ground).
 *
 * <p>Each texel (RGBA16F) carries the cell's ground above {@link #referenceY()}, how far it lies
 * below the land around it (the mist pools there), whether there is ground at all (the End's void
 * has none) and how far the layer's top rises there, in a slow bank of mist or in among trees. The
 * banks drift with the haze ({@link HazeDrift}). Unknown cells (chunks not loaded) take the camera's
 * ground.
 *
 * <p>Kept on the client tick, while the field is drawn: moving reads only the new edge, and two rows
 * are read again every tick, so placed and broken blocks show within about two seconds. The upload
 * waits for the next frame on the render thread.
 */
final class FieldGround implements AutoCloseable {
    static final int CELLS = 64;
    static final int CELL = 4;
    /** How many blocks the map spans from side to side. */
    static final float BLOCKS = CELLS * CELL;
    private static final int ROWS_PER_TICK = 2;
    /** Pooling: a cell's ground against the mean of the land this many cells round it (24 blocks across). */
    private static final int POOL_RADIUS = 3;
    /** The deepest pooling counted, in blocks: a ravine fills no deeper than this. */
    private static final float DEEPEST_POOL = 10.0f;
    /** Banks: noise cells this many blocks across, lifting the top by up to this many blocks. */
    private static final double BANK_CELL = 64.0;
    private static final long BANK_CELL_MASK = 31;
    private static final float BANK_LIFT = 3.0f;
    /**
     * Under trees the top rises by this share of the canopy's height over the ground, at most
     * {@link #HIGHEST_CANOPY_LIFT} blocks: the mist lies on the forest floor and rises in among the
     * trees, so from above it still shows between the crowns.
     */
    private static final float CANOPY_SHARE = 0.5f;
    private static final float HIGHEST_CANOPY_LIFT = 5.0f;
    /** The most banks, trees and pooling together raise the layer's top, in blocks. */
    static final float MOST_RAISED = BANK_LIFT + HIGHEST_CANOPY_LIFT + DEEPEST_POOL;

    private final float[] ground = new float[CELLS * CELLS];
    private final float[] presence = new float[CELLS * CELLS];
    private final float[] canopy = new float[CELLS * CELLS];
    private final float[] scratchCanopy = new float[CELLS * CELLS];
    private final boolean[] known = new boolean[CELLS * CELLS];
    private final float[] filled = new float[CELLS * CELLS];
    private final float[] rowSums = new float[CELLS * CELLS];
    private final float[] scratchGround = new float[CELLS * CELLS];
    private final float[] scratchPresence = new float[CELLS * CELLS];
    private final boolean[] scratchKnown = new boolean[CELLS * CELLS];
    private int originCellX;
    private int originCellZ;
    private boolean hasOrigin;
    private int refreshRow;
    private int referenceY;
    private float lowestGround;
    private float highestTop;

    private @Nullable ByteBuffer texels;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private boolean dirty;

    /**
     * Recentres on the camera, reads what is new or due again, and works out the texels. Client
     * tick only. {@code cameraGround} fills cells nothing is known of.
     */
    void update(ClientLevel level, double cameraX, double cameraY, double cameraZ, double cameraGround, double driftX, double driftZ) {
        int wantedX = Math.floorDiv((int) Math.floor(cameraX), CELL) - CELLS / 2;
        int wantedZ = Math.floorDiv((int) Math.floor(cameraZ), CELL) - CELLS / 2;
        if (!hasOrigin) {
            Arrays.fill(known, false);
            hasOrigin = true;
        } else if (wantedX != originCellX || wantedZ != originCellZ) {
            shift(wantedX - originCellX, wantedZ - originCellZ);
        }
        originCellX = wantedX;
        originCellZ = wantedZ;
        int minY = level.getMinY();
        for (int row = 0; row < CELLS; row++) {
            boolean rolling = row % (CELLS / ROWS_PER_TICK) == refreshRow;
            for (int column = 0; column < CELLS; column++) {
                int index = row * CELLS + column;
                if (rolling || !known[index]) {
                    read(level, index, originCellX + column, originCellZ + row, minY);
                }
            }
        }
        refreshRow = (refreshRow + 1) % (CELLS / ROWS_PER_TICK);
        referenceY = (int) Math.floor(cameraY);
        pack((float) (cameraGround - referenceY), driftX, driftZ);
    }

    /** Forgets the land, as when leaving a world. */
    void forget() {
        hasOrigin = false;
        Arrays.fill(known, false);
    }

    /** Block coordinates of the map's north-west corner. */
    int originX() {
        return originCellX * CELL;
    }

    int originZ() {
        return originCellZ * CELL;
    }

    /** The block height the texels' ground is measured from. */
    int referenceY() {
        return referenceY;
    }

    /** The lowest ground on the map, above {@link #referenceY()}. */
    float lowestGround() {
        return lowestGround;
    }

    /** The highest ground on the map with its rise and pooling added, above {@link #referenceY()}. */
    float highestTop() {
        return highestTop;
    }

    /** The texture, its texels uploaded if they changed. Render thread only. */
    GpuTextureView view() {
        if (texture == null || texture.isClosed()) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture("LiteLights mist field ground", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA16_FLOAT, CELLS, CELLS, 1, 1);
            view = device.createTextureView(texture);
            dirty = true;
        }
        if (texels == null) {
            // Nothing read yet (the Nether, where the field doesn't follow the land): flat, present ground.
            texels = MemoryUtil.memCalloc(CELLS * CELLS * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < CELLS * CELLS; index++) {
                texels.putShort(index * 8 + 4, Float.floatToFloat16(1.0f));
            }
            dirty = true;
        }
        if (dirty) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, 0, 0, CELLS, CELLS);
            dirty = false;
        }
        return view;
    }

    private void shift(int deltaX, int deltaZ) {
        System.arraycopy(ground, 0, scratchGround, 0, ground.length);
        System.arraycopy(presence, 0, scratchPresence, 0, presence.length);
        System.arraycopy(canopy, 0, scratchCanopy, 0, canopy.length);
        System.arraycopy(known, 0, scratchKnown, 0, known.length);
        Arrays.fill(known, false);
        for (int row = 0; row < CELLS; row++) {
            int oldRow = row + deltaZ;
            if (oldRow < 0 || oldRow >= CELLS) {
                continue;
            }
            for (int column = 0; column < CELLS; column++) {
                int oldColumn = column + deltaX;
                if (oldColumn >= 0 && oldColumn < CELLS) {
                    int from = oldRow * CELLS + oldColumn;
                    int to = row * CELLS + column;
                    ground[to] = scratchGround[from];
                    presence[to] = scratchPresence[from];
                    canopy[to] = scratchCanopy[from];
                    known[to] = scratchKnown[from];
                }
            }
        }
    }

    /**
     * One cell: the mean ground of four of its sixteen columns, the share of them that have ground,
     * and how high the trees over them reach above that ground, on average.
     */
    private void read(ClientLevel level, int index, int cellX, int cellZ, int minY) {
        int blockX = cellX * CELL;
        int blockZ = cellZ * CELL;
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) {
            known[index] = false;
            return;
        }
        LevelChunk chunk = level.getChunk(blockX >> 4, blockZ >> 4);
        float sum = 0.0f;
        float trees = 0.0f;
        int present = 0;
        for (int sample = 0; sample < 4; sample++) {
            int x = (blockX + 1 + 2 * (sample & 1)) & 15;
            int z = (blockZ + 1 + (sample & 2)) & 15;
            // The top surface: one above the highest block, as Level.getHeight gives it.
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
            if (top > minY) {
                sum += top;
                trees += chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1 - top;
                present++;
            }
        }
        known[index] = true;
        presence[index] = present / 4.0f;
        canopy[index] = present > 0 ? trees / present : 0.0f;
        // A cell over the void keeps no height of its own; pack() fills it from the camera's ground.
        ground[index] = present > 0 ? sum / present : Float.NaN;
    }

    private void pack(float cameraGround, double driftX, double driftZ) {
        for (int index = 0; index < filled.length; index++) {
            filled[index] = known[index] && !Float.isNaN(ground[index]) ? ground[index] - referenceY : cameraGround;
        }
        boxRows();
        ByteBuffer buffer = texels;
        if (buffer == null) {
            buffer = MemoryUtil.memAlloc(CELLS * CELLS * 8).order(ByteOrder.LITTLE_ENDIAN);
            texels = buffer;
        }
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        for (int row = 0; row < CELLS; row++) {
            for (int column = 0; column < CELLS; column++) {
                int index = row * CELLS + column;
                float around = 0.0f;
                int count = 0;
                for (int offset = -POOL_RADIUS; offset <= POOL_RADIUS; offset++) {
                    int sumRow = row + offset;
                    if (sumRow >= 0 && sumRow < CELLS) {
                        around += rowSums[sumRow * CELLS + column];
                        count += rowWidth(column);
                    }
                }
                float height = filled[index];
                float pool = Math.clamp(around / Math.max(count, 1) - height, 0.0f, DEEPEST_POOL);
                float trees = known[index] ? Math.min(CANOPY_SHARE * canopy[index], HIGHEST_CANOPY_LIFT) : 0.0f;
                float lift = bankLift(originCellX + column, originCellZ + row, driftX, driftZ) + trees;
                float there = known[index] ? presence[index] : 1.0f;
                buffer.putShort(index * 8, Float.floatToFloat16(height));
                buffer.putShort(index * 8 + 2, Float.floatToFloat16(pool));
                buffer.putShort(index * 8 + 4, Float.floatToFloat16(there));
                buffer.putShort(index * 8 + 6, Float.floatToFloat16(lift));
                if (there > 0.0f) {
                    lowest = Math.min(lowest, height);
                    highest = Math.max(highest, height + lift + pool);
                }
            }
        }
        if (lowest > highest) {
            lowest = cameraGround;
            highest = cameraGround;
        }
        lowestGround = lowest;
        highestTop = highest;
        dirty = true;
    }

    /** Sums of the filled heights across each row's window of cells, for the pooling's mean. */
    private void boxRows() {
        for (int row = 0; row < CELLS; row++) {
            for (int column = 0; column < CELLS; column++) {
                float sum = 0.0f;
                for (int offset = -POOL_RADIUS; offset <= POOL_RADIUS; offset++) {
                    int sumColumn = column + offset;
                    if (sumColumn >= 0 && sumColumn < CELLS) {
                        sum += filled[row * CELLS + sumColumn];
                    }
                }
                rowSums[row * CELLS + column] = sum;
            }
        }
    }

    /** How many cells a row's window at this column holds (fewer at the map's edges). */
    private static int rowWidth(int column) {
        return Math.min(column + POOL_RADIUS, CELLS - 1) - Math.max(column - POOL_RADIUS, 0) + 1;
    }

    /**
     * How far a bank of mist lifts the layer's top over a cell: a slow value noise 64 blocks to a
     * cell, drifting with the haze, rising in about a third of the land. Repeats every 2048 blocks,
     * as the drift wraps.
     */
    static float bankLift(int cellX, int cellZ, double driftX, double driftZ) {
        double u = ((cellX + 0.5) * CELL - driftX) / BANK_CELL;
        double v = ((cellZ + 0.5) * CELL - driftZ) / BANK_CELL;
        double cellU = Math.floor(u);
        double cellV = Math.floor(v);
        float fu = smooth((float) (u - cellU));
        float fv = smooth((float) (v - cellV));
        long i = (long) cellU;
        long j = (long) cellV;
        float bottom = lerp(hash(i, j), hash(i + 1, j), fu);
        float top = lerp(hash(i, j + 1), hash(i + 1, j + 1), fu);
        float noise = lerp(bottom, top, fv);
        return BANK_LIFT * smooth(Math.clamp((noise - 0.45f) / 0.4f, 0.0f, 1.0f));
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float hash(long i, long j) {
        long h = (i & BANK_CELL_MASK) * 0x9E3779B97F4A7C15L ^ (j & BANK_CELL_MASK) * 0xC2B2AE3D27D4EB4FL ^ 0x5DEECE66DL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (texels != null) {
            MemoryUtil.memFree(texels);
            texels = null;
        }
    }
}
