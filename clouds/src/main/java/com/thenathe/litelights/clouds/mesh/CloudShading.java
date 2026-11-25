package com.thenathe.litelights.clouds.mesh;

import java.util.Arrays;

/**
 * How a chunk's cloud is shaded: its surface's normal at each cell, and how high the cloud over
 * each column reaches, both smoothed over the columns around so neither shows the cubes' steps.
 *
 * <p>The normal is the slope of how full the cells are ({@link CloudCells#fill}, with the field's
 * fine tops and bases), blurred over 3 by 3 by 3 cells first: in effect the cloud's shape seen
 * through a window five cells wide, so a dome's top turns smoothly from cube to cube and a wall's
 * corners round off, while a single cube's step changes it only a little. The cloud's top is
 * averaged over the 3 by 3 columns around, so the darkening under it doesn't change from one
 * column's wall to the next.
 *
 * <p>One per worker thread: its arrays are kept between chunks.
 */
final class CloudShading {
    private static final ThreadLocal<CloudShading> PER_THREAD = ThreadLocal.withInitial(CloudShading::new);
    /** Columns the blurred fill covers along a side: the chunk and one apron column each way. */
    private static final int BLURRED = CloudCells.SIZE + 2;
    /** Columns the fill itself covers: two apron columns each way. */
    private static final int FILLED = CloudCells.SIZE + 4;
    /** The most the smoothed top may take from a neighbour's, in cells (a tower beside a deck keeps its own). */
    private static final int TOP_REACH_CELLS = 8;

    /** Corners along a side: one more than the columns. */
    private static final int CORNERS = CloudCells.SIZE + 1;

    private float[] fill = new float[0];
    private float[] blurred = new float[0];
    private float[] scratch = new float[0];
    /** Each corner's slope once worked out (x, y, z), valid where its stamp is this chunk's: most corners are shared by three faces or more. */
    private float[] cornerSlopes = new float[0];
    private int[] cornerStamps = new int[0];
    private int stamp;
    private final float[] smoothTop = new float[BLURRED * BLURRED];
    private CloudCells cells;
    private int lowest;
    private int layers;
    private double width;

    private CloudShading() {
    }

    /** The thread's shading, made ready for one chunk's cells between two heights (in cells). */
    static CloudShading of(CloudCells cells, int lowestCell, int highestCell) {
        CloudShading shading = PER_THREAD.get();
        shading.prepare(cells, lowestCell, highestCell);
        return shading;
    }

    private void prepare(CloudCells cells, int lowestCell, int highestCell) {
        this.cells = cells;
        // Two cells of margin each way: one the blur reaches into, one the slope reads.
        lowest = lowestCell - 2;
        layers = highestCell - lowestCell + 5;
        width = cells.cubeBlocks() / (double) FaceFormat.CELL_BLOCKS;
        int filledSize = FILLED * FILLED * layers;
        if (fill.length < filledSize) {
            fill = new float[filledSize];
            scratch = new float[filledSize];
            blurred = new float[filledSize];
        }
        int cornerCount = CORNERS * CORNERS * (layers + 1);
        if (cornerStamps.length < cornerCount) {
            cornerStamps = new int[cornerCount];
            cornerSlopes = new float[cornerCount * 3];
            stamp = 0;
        }
        stamp++;
        for (int z = 0; z < FILLED; z++) {
            for (int x = 0; x < FILLED; x++) {
                int column = (z * FILLED + x) * layers;
                if (cells.topCell(x - 2, z - 2) < 0) {
                    Arrays.fill(fill, column, column + layers, 0.0f);
                    continue;
                }
                for (int layer = 0; layer < layers; layer++) {
                    fill[column + layer] = cells.fill(x - 2, z - 2, lowest + layer);
                }
            }
        }
        // A 3-wide box blur along each axis in turn; the outer ring of columns only feeds the blur.
        blurAlong(fill, scratch, true);
        blurAlong(scratch, blurred, false);
        blurVertically(blurred, scratch);
        System.arraycopy(scratch, 0, blurred, 0, filledSize);
        smoothTops();
    }

    /** One pass of the box blur along x or z, kept within the filled square. */
    private void blurAlong(float[] from, float[] to, boolean alongX) {
        int step = alongX ? layers : FILLED * layers;
        for (int z = 0; z < FILLED; z++) {
            for (int x = 0; x < FILLED; x++) {
                int along = alongX ? x : z;
                int base = (z * FILLED + x) * layers;
                for (int layer = 0; layer < layers; layer++) {
                    int at = base + layer;
                    float sum = from[at];
                    sum += along > 0 ? from[at - step] : from[at];
                    sum += along < FILLED - 1 ? from[at + step] : from[at];
                    to[at] = sum / 3.0f;
                }
            }
        }
    }

    private void blurVertically(float[] from, float[] to) {
        for (int column = 0; column < FILLED * FILLED; column++) {
            int base = column * layers;
            for (int layer = 0; layer < layers; layer++) {
                float below = layer > 0 ? from[base + layer - 1] : 0.0f;
                float above = layer < layers - 1 ? from[base + layer + 1] : 0.0f;
                to[base + layer] = (below + from[base + layer] + above) / 3.0f;
            }
        }
    }

    private void smoothTops() {
        for (int z = -1; z <= CloudCells.SIZE; z++) {
            for (int x = -1; x <= CloudCells.SIZE; x++) {
                float own = cells.fineTop(x, z);
                float sum = 0.0f;
                int count = 0;
                if (!Float.isNaN(own)) {
                    for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                        for (int offsetX = -1; offsetX <= 1; offsetX++) {
                            float top = cells.fineTop(x + offsetX, z + offsetZ);
                            if (!Float.isNaN(top) && Math.abs(top - own) <= TOP_REACH_CELLS * FaceFormat.CELL_BLOCKS) {
                                sum += top;
                                count++;
                            }
                        }
                    }
                }
                smoothTop[(z + 1) * BLURRED + x + 1] = count > 0 ? sum / count : own;
            }
        }
    }

    private float blurredAt(int x, int z, int y) {
        int layer = y - lowest;
        if (layer < 0 || layer >= layers) {
            return 0.0f;
        }
        return blurred[((z + 2) * FILLED + x + 2) * layers + layer];
    }

    /**
     * The cloud's surface normal at a corner of the cell lattice (x and z in columns, y in cells),
     * as a {@link FaceFormat#normal} code: down the slope of the blurred fill across the eight cells
     * sharing the corner, the sideways differences weighed by the cubes' width against their
     * height. Every face meeting at a corner gets the same normal there, so the shading runs on
     * across faces. Coded in the frame of the face it shades ({@link FaceFormat#normal}); the face's
     * own direction where the slope is too flat to say (deep inside, a lone cube).
     */
    int cornerNormal(int x, int y, int z, int side) {
        int corner = ((z * CORNERS) + x) * (layers + 1) + y - lowest;
        if (cornerStamps[corner] != stamp) {
            float slopeX = 0.0f;
            float slopeY = 0.0f;
            float slopeZ = 0.0f;
            for (int a = -1; a <= 0; a++) {
                for (int b = -1; b <= 0; b++) {
                    slopeX += blurredAt(x, z + b, y + a) - blurredAt(x - 1, z + b, y + a);
                    slopeY += blurredAt(x + a, z + b, y) - blurredAt(x + a, z + b, y - 1);
                    slopeZ += blurredAt(x + a, z, y + b) - blurredAt(x + a, z - 1, y + b);
                }
            }
            cornerSlopes[corner * 3] = (float) (slopeX / width);
            cornerSlopes[corner * 3 + 1] = slopeY;
            cornerSlopes[corner * 3 + 2] = (float) (slopeZ / width);
            cornerStamps[corner] = stamp;
        }
        double slopeX = cornerSlopes[corner * 3];
        double slopeY = cornerSlopes[corner * 3 + 1];
        double slopeZ = cornerSlopes[corner * 3 + 2];
        double length = Math.sqrt(slopeX * slopeX + slopeY * slopeY + slopeZ * slopeZ);
        if (length < 0.04) {
            return FaceFormat.normal(side, CloudMesher.dx(side), CloudMesher.dy(side), CloudMesher.dz(side));
        }
        return FaceFormat.normal(side, -slopeX, -slopeY, -slopeZ);
    }

    /**
     * How high the cloud reaches over a corner of the column lattice, in blocks above the floor:
     * the smoothed tops of the four columns around it that belong to the same cloud as
     * {@code own} (within a few cells of it), averaged.
     */
    float cornerTop(int x, int z, float own) {
        float sum = 0.0f;
        int count = 0;
        for (int b = -1; b <= 0; b++) {
            for (int a = -1; a <= 0; a++) {
                float top = smoothTop[(z + b + 1) * BLURRED + x + a + 1];
                if (!Float.isNaN(top) && Math.abs(top - own) <= TOP_REACH_CELLS * FaceFormat.CELL_BLOCKS) {
                    sum += top;
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : own;
    }

    /**
     * How high the run of cloud holding a solid cell reaches, in blocks above the floor, for the
     * darkening under it: NaN for the column's highest run, which takes its top from the smoothed
     * tops around each corner ({@link #cornerTop}); the run's own top for one under it.
     */
    float runTop(int x, int z, int y) {
        int runTop = y + cells.cellsBelowTop(x, z, y);
        if (runTop == cells.topCell(x, z)) {
            return Float.NaN;
        }
        return (runTop + 1) * FaceFormat.CELL_BLOCKS;
    }

    /** The smoothed top of a column (-1 to {@link CloudCells#SIZE}), NaN if it holds no cloud. */
    float smoothTop(int x, int z) {
        return smoothTop[(z + 1) * BLURRED + x + 1];
    }
}
