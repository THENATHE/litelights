package com.thenathe.litelights.clouds.mesh;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;

/**
 * One chunk of the cloud field turned into cubes: which cells of each column are solid cloud, and
 * how stormy each is. A chunk is {@link #SIZE} by {@link #SIZE} columns of cloud space, with two
 * more columns all round (the apron) so the mesher can tell a face on the chunk's edge, and shade
 * it, without its neighbour. Columns are a cube wide (8 blocks near the camera, up to 64 far
 * away); cells are {@link FaceFormat#CELL_BLOCKS} blocks tall at every width, counted up from the
 * floor, so a far cloud keeps its heights.
 *
 * <p>Each column also keeps where its cloud's top and base really are, finer than a cell: how far
 * the field's top lies above or below its highest cell's top, and its base from its lowest cell's
 * bottom. The cells draw the cubes; the fine heights shade them ({@link #fill}, {@link #cloudTop}).
 *
 * <p>Immutable once {@link #sample sampled}; safe to read from any thread.
 */
public final class CloudCells {
    public static final int SIZE = 32;
    /** Columns of apron on each side. */
    public static final int APRON = 2;
    /** Columns along a side, apron included. */
    static final int SPAN = SIZE + 2 * APRON;
    /** Cells a column holds above the floor: enough for the tallest thunderstorm. */
    public static final int HEIGHT = 192;
    static final int WORDS = HEIGHT / 64;
    /** A cell is solid where the cloud there is at least this dense. */
    public static final float SOLID = 0.5f;

    private final int chunkX;
    private final int chunkZ;
    private final int cubeBlocks;
    private final long[] bits = new long[SPAN * SPAN * WORDS];
    /** Per column and per 8 cells, how stormy its solid cloud is (finer than storminess ever changes). */
    private final byte[] storm = new byte[SPAN * SPAN * HEIGHT / 8];
    /** Per column, how far the field's top lies above its highest cell's top, in cells (-0.5..0.5). */
    private final float[] topOffset = new float[SPAN * SPAN];
    /** Per column, how far the field's base lies below its lowest cell's bottom, in cells (-0.5..0.5). */
    private final float[] baseOffset = new float[SPAN * SPAN];
    /** Per column, its highest and lowest solid cells (-1 and {@link #HEIGHT} if none), once sampled. */
    private final short[] columnTop = new short[SPAN * SPAN];
    private final short[] columnBase = new short[SPAN * SPAN];
    private int lowest = HEIGHT;
    private int highest = -1;
    private int solidCells;

    private CloudCells(int chunkX, int chunkZ, int cubeBlocks) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.cubeBlocks = cubeBlocks;
    }

    /**
     * Samples one chunk of cloud space: a column at each cube's middle, a cell solid where the
     * densest cloud over the cell's middle height is {@link #SOLID} or more.
     *
     * @param area       the field around this chunk at the tick being built
     * @param chunkX     the chunk, in chunks of cloud space ({@link #SIZE} cubes)
     * @param floor      the lowest a cloud may reach, in world y
     * @param cubeBlocks the cube size, in blocks
     */
    public static CloudCells sample(CloudField.Area area, int chunkX, int chunkZ, float floor, int cubeBlocks, CloudColumn column) {
        CloudCells cells = new CloudCells(chunkX, chunkZ, cubeBlocks);
        double originX = (double) chunkX * SIZE * cubeBlocks;
        double originZ = (double) chunkZ * SIZE * cubeBlocks;
        for (int z = -APRON; z < SIZE + APRON; z++) {
            for (int x = -APRON; x < SIZE + APRON; x++) {
                area.column(originX + (x + 0.5) * cubeBlocks, originZ + (z + 0.5) * cubeBlocks, cubeBlocks, column);
                if (!column.isClear()) {
                    cells.fill(x, z, column, floor);
                }
            }
        }
        cells.findColumnEnds();
        return cells;
    }

    /** An empty chunk, for tests and for chunks the field leaves clear. */
    public static CloudCells empty(int chunkX, int chunkZ, int cubeBlocks) {
        CloudCells cells = new CloudCells(chunkX, chunkZ, cubeBlocks);
        cells.findColumnEnds();
        return cells;
    }

    /** Which cells are solid, by column (-1 to {@link #SIZE}: the apron's inner ring included) and height. */
    @FunctionalInterface
    public interface Solid {
        boolean at(int x, int z, int y);
    }

    /** A chunk with exactly these cells solid and no storminess, for tests and tools. */
    public static CloudCells of(int chunkX, int chunkZ, int cubeBlocks, Solid solid) {
        CloudCells cells = new CloudCells(chunkX, chunkZ, cubeBlocks);
        for (int z = -1; z <= SIZE; z++) {
            for (int x = -1; x <= SIZE; x++) {
                int index = columnIndex(x, z);
                for (int y = 0; y < HEIGHT; y++) {
                    if (!solid.at(x, z, y)) {
                        continue;
                    }
                    cells.bits[index * WORDS + (y >> 6)] |= 1L << (y & 63);
                    if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
                        cells.solidCells++;
                        cells.lowest = Math.min(cells.lowest, y);
                        cells.highest = Math.max(cells.highest, y);
                    }
                }
            }
        }
        cells.findColumnEnds();
        return cells;
    }

    private void fill(int x, int z, CloudColumn column, float floor) {
        int index = columnIndex(x, z);
        int columnTop = -1;
        int columnBase = HEIGHT;
        for (int span = 0; span < column.spanCount(); span++) {
            if (column.density(span) < SOLID) {
                continue;
            }
            // Cells whose middle height lies inside the span.
            double base = (column.bottom(span) - floor) / FaceFormat.CELL_BLOCKS;
            double top = (column.top(span) - floor) / FaceFormat.CELL_BLOCKS;
            int first = (int) Math.ceil(base - 0.5);
            int last = (int) Math.floor(top - 0.5);
            first = Math.max(0, first);
            last = Math.min(HEIGHT - 1, last);
            if (first <= last && last >= columnTop) {
                float offset = (float) Math.clamp(top - (last + 1), -0.5, 0.5);
                topOffset[index] = last > columnTop ? offset : Math.max(topOffset[index], offset);
                columnTop = last;
            }
            if (first <= last && first <= columnBase) {
                float offset = (float) Math.clamp(first - base, -0.5, 0.5);
                baseOffset[index] = first < columnBase ? offset : Math.max(baseOffset[index], offset);
                columnBase = first;
            }
            int stormCode = Math.round(Math.clamp(column.storminess(span), 0.0f, 1.0f) * FaceFormat.MOST_SHADE);
            for (int y = first; y <= last; y++) {
                if (!solidAt(index, y)) {
                    bits[index * WORDS + (y >> 6)] |= 1L << (y & 63);
                    if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
                        solidCells++;
                    }
                }
                setStorm(index, y, Math.max(stormAt(index, y), stormCode));
            }
            if (first <= last && x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
                lowest = Math.min(lowest, first);
                highest = Math.max(highest, last);
            }
        }
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int cubeBlocks() {
        return cubeBlocks;
    }

    /** Whether no cell of the chunk itself (the apron aside) is solid. */
    public boolean isEmpty() {
        return solidCells == 0;
    }

    public int solidCells() {
        return solidCells;
    }

    /** The lowest solid cell of the chunk itself, or {@link #HEIGHT} if none. */
    public int lowestCell() {
        return lowest;
    }

    /** The highest solid cell of the chunk itself, or -1 if none. */
    public int highestCell() {
        return highest;
    }

    /**
     * Whether a cell is solid. {@code x} and {@code z} run from -{@link #APRON} to {@link #SIZE} +
     * {@link #APRON} - 1 (the apron
     * included); heights outside the column are empty.
     */
    public boolean solid(int x, int z, int y) {
        if (y < 0 || y >= HEIGHT) {
            return false;
        }
        return solidAt(columnIndex(x, z), y);
    }

    /** How stormy a solid cell's cloud is, 0..{@link FaceFormat#MOST_SHADE}. */
    public int storm(int x, int z, int y) {
        return stormAt(columnIndex(x, z), y);
    }

    /** How many solid cells lie above this one before the column's run of cloud ends. */
    public int cellsBelowTop(int x, int z, int y) {
        int index = columnIndex(x, z);
        int above = 0;
        for (int cell = y + 1; cell < HEIGHT && solidAt(index, cell); cell++) {
            above++;
        }
        return above;
    }

    /** How many of a column's cells are solid. */
    public int solidCount(int x, int z) {
        int index = columnIndex(x, z);
        int count = 0;
        for (int word = 0; word < WORDS; word++) {
            count += Long.bitCount(bits[index * WORDS + word]);
        }
        return count;
    }

    /** The column's highest solid cell, or -1 if it has none. */
    public int topCell(int x, int z) {
        return columnTop[columnIndex(x, z)];
    }

    /** The column's lowest solid cell, or {@link #HEIGHT} if it has none. */
    public int baseCell(int x, int z) {
        return columnBase[columnIndex(x, z)];
    }

    private void findColumnEnds() {
        for (int index = 0; index < SPAN * SPAN; index++) {
            int top = -1;
            int base = HEIGHT;
            for (int word = WORDS - 1; word >= 0; word--) {
                long bitsHere = bits[index * WORDS + word];
                if (bitsHere != 0L) {
                    top = word * 64 + 63 - Long.numberOfLeadingZeros(bitsHere);
                    break;
                }
            }
            for (int word = 0; word < WORDS; word++) {
                long bitsHere = bits[index * WORDS + word];
                if (bitsHere != 0L) {
                    base = word * 64 + Long.numberOfTrailingZeros(bitsHere);
                    break;
                }
            }
            columnTop[index] = (short) top;
            columnBase[index] = (short) base;
        }
    }

    /**
     * How much of a cell the cloud fills, 0..1, with the column's fine top and base: a cell inside
     * the cloud is 1 and one outside 0, but the column's highest cell is only partly full where
     * the field's top lies inside it, and the empty cell over it partly full where the top lies
     * above it (the same for the base). Smooth across the field's heights, for shading.
     */
    public float fill(int x, int z, int y) {
        if (y < -1 || y > HEIGHT) {
            return 0.0f;
        }
        int index = columnIndex(x, z);
        int top = columnTop[index];
        if (top < 0) {
            return 0.0f;
        }
        int base = columnBase[index];
        boolean solid = y >= 0 && y < HEIGHT && solidAt(index, y);
        if (solid) {
            float full = 1.0f;
            if (y == top) {
                full = Math.min(full, 1.0f + Math.min(0.0f, topOffset[index]));
            }
            if (y == base) {
                full = Math.min(full, 1.0f + Math.min(0.0f, baseOffset[index]));
            }
            return full;
        }
        if (y == top + 1) {
            return Math.max(0.0f, topOffset[index]);
        }
        if (y == base - 1) {
            return Math.max(0.0f, baseOffset[index]);
        }
        return 0.0f;
    }

    /** How high the column's cloud reaches, finer than a cell, in blocks above the floor; NaN for a clear column. */
    public float fineTop(int x, int z) {
        int index = columnIndex(x, z);
        int top = columnTop[index];
        return top < 0 ? Float.NaN : (top + 1 + topOffset[index]) * FaceFormat.CELL_BLOCKS;
    }

    /**
     * How high the run of cloud holding a solid cell reaches, in blocks above the floor: the
     * field's own top for the column's highest run, the top of the run's highest cell for one
     * under it.
     */
    public float cloudTop(int x, int z, int y) {
        int runTop = y + cellsBelowTop(x, z, y);
        float top = (runTop + 1) * FaceFormat.CELL_BLOCKS;
        if (runTop == topCell(x, z)) {
            top += topOffset[columnIndex(x, z)] * FaceFormat.CELL_BLOCKS;
        }
        return top;
    }

    /** The column's solid cells in one 64-cell word, bit {@code y & 63} for cell {@code y}. */
    long word(int x, int z, int word) {
        return bits[columnIndex(x, z) * WORDS + word];
    }

    private boolean solidAt(int index, int y) {
        return (bits[index * WORDS + (y >> 6)] >>> (y & 63) & 1L) != 0;
    }

    private int stormAt(int index, int y) {
        return storm[index * (HEIGHT / 8) + (y >> 3)];
    }

    private void setStorm(int index, int y, int value) {
        storm[index * (HEIGHT / 8) + (y >> 3)] = (byte) value;
    }

    private static int columnIndex(int x, int z) {
        return (x + APRON) + (z + APRON) * SPAN;
    }
}
