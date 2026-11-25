package com.thenathe.litelights.clouds.mesh;

import java.util.Arrays;

/**
 * How much sunlight the clouds let through, column by column, for their shadows on the land: a
 * square of {@link #SIZE} by {@link #SIZE} texels of {@link #TEXEL_BLOCKS} blocks in cloud space,
 * centred on a generation's origin, one byte a texel (255 all the light, 0 none). Worked out from
 * the cells, not the faces: a column lets through less the more solid cloud it holds, as light
 * through a cloud does ({@link #SHADOW_DEPTH}). Near columns are narrower than a texel and are
 * averaged into it; far ones cover several.
 *
 * <p>One per builder: its sums are kept between builds. Not for sharing between threads.
 */
public final class CloudShadowMap {
    public static final int SIZE = 1_024;
    public static final int TEXEL_BLOCKS = 16;
    /** How far across the map reaches, in blocks. */
    public static final int SPAN_BLOCKS = SIZE * TEXEL_BLOCKS;
    /** Blocks of solid cloud that let through 1/e of the sunlight. */
    static final double SHADOW_DEPTH = 90.0;

    private final float[] light = new float[SIZE * SIZE];
    private final float[] weight = new float[SIZE * SIZE];

    /**
     * The map for these chunks.
     *
     * @param cornerX the map's corner (its least x and z) in blocks of cloud space
     */
    public byte[] build(Iterable<CloudCells> chunks, int cornerX, int cornerZ) {
        Arrays.fill(light, 0.0f);
        Arrays.fill(weight, 0.0f);
        for (CloudCells cells : chunks) {
            if (cells.isEmpty()) {
                continue;
            }
            int cube = cells.cubeBlocks();
            long chunkX = (long) cells.chunkX() * CloudCells.SIZE * cube - cornerX;
            long chunkZ = (long) cells.chunkZ() * CloudCells.SIZE * cube - cornerZ;
            for (int z = 0; z < CloudCells.SIZE; z++) {
                for (int x = 0; x < CloudCells.SIZE; x++) {
                    int solid = cells.solidCount(x, z);
                    float through = solid == 0 ? 1.0f : (float) Math.exp(-solid * FaceFormat.CELL_BLOCKS / SHADOW_DEPTH);
                    add(chunkX + (long) x * cube, chunkZ + (long) z * cube, cube, through);
                }
            }
        }
        byte[] map = new byte[SIZE * SIZE];
        for (int i = 0; i < map.length; i++) {
            // Texels no chunk reached are open sky.
            float through = weight[i] > 0.0f ? light[i] / weight[i] : 1.0f;
            map[i] = (byte) Math.round(Math.clamp(through, 0.0f, 1.0f) * 255.0f);
        }
        return map;
    }

    /** A column's light into every texel it covers, by the share of the texel it covers. */
    private void add(long minX, long minZ, int width, float through) {
        long lastX = minX + width - 1;
        long lastZ = minZ + width - 1;
        if (lastX < 0 || lastZ < 0 || minX >= SPAN_BLOCKS || minZ >= SPAN_BLOCKS) {
            return;
        }
        int firstTexelX = (int) Math.max(0, Math.floorDiv(minX, TEXEL_BLOCKS));
        int lastTexelX = (int) Math.min(SIZE - 1, Math.floorDiv(lastX, TEXEL_BLOCKS));
        int firstTexelZ = (int) Math.max(0, Math.floorDiv(minZ, TEXEL_BLOCKS));
        int lastTexelZ = (int) Math.min(SIZE - 1, Math.floorDiv(lastZ, TEXEL_BLOCKS));
        float share = Math.min(width, TEXEL_BLOCKS) * (float) Math.min(width, TEXEL_BLOCKS);
        for (int texelZ = firstTexelZ; texelZ <= lastTexelZ; texelZ++) {
            for (int texelX = firstTexelX; texelX <= lastTexelX; texelX++) {
                int index = texelZ * SIZE + texelX;
                light[index] += through * share;
                weight[index] += share;
            }
        }
    }
}
