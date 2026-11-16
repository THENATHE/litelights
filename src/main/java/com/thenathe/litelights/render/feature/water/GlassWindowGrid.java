package com.thenathe.litelights.render.feature.water;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * Where each window's edges are, for frost, snow and sand on glass (include/glass/weather.glsl,
 * lite_glass_window). A window is a connected area of glass in one plane: full glass blocks side by
 * side (any colour), or panes that join each other. For each glass block and each axis its faces
 * look along, the shader needs how many blocks of the same window lie on either side of it and
 * above and below it, and how big the window is. This works that out from a list of glass blocks
 * and lays it out as a hash table in its own region of the fluid data texture, under the rain tops,
 * so the shader finds a block's entry without another texture unit.
 *
 * <p>The table is {@link #CAPACITY} entries of two texels each, 128 entries to a row of
 * {@link #WIDTH} texels: the key, then the data, each a little-endian 32-bit value in the texel's
 * bytes. A slot starts at the top bits of the key times 0x9E3779B1 and probes linearly, at most
 * {@link #MAX_PROBES} slots; an empty slot (key 0) ends the search.
 * <ul>
 *   <li>Key: bit 0 set, bits 1-2 the axis, 3-10 x mod 256, 11-18 z mod 256, 19-30 y above the
 *   level's floor (the column map's window is 256 wide, so x and z mod 256 name one block there).</li>
 *   <li>Data: four 6-bit counts of window blocks beyond this one toward u low, u high, v low and v
 *   high (up to 63), then 8 bits of the window's smaller side in blocks (up to 255). On faces along
 *   x, u is z and v is y; along z, u is x and v is y; on top and bottom faces u is x and v is z.</li>
 * </ul>
 * Nearer blocks go in first; past {@link #MOST_ENTRIES}, the farthest are left out (the shader then
 * draws no edge weather on them). No Minecraft types, so it is unit-tested on its own.
 */
public final class GlassWindowGrid {
    /** Entries in the table: a power of two, since the slot is the top bits of a 32-bit hash. */
    public static final int CAPACITY = 1 << 14;
    private static final int SLOT_SHIFT = 32 - 14;
    private static final int HASH = 0x9E3779B1;
    public static final int WIDTH = 256;
    public static final int ROWS = CAPACITY * 2 / WIDTH;
    public static final int MAX_PROBES = 8;
    /** At most this share of the table is filled, so probes stay short. */
    public static final int MOST_ENTRIES = CAPACITY * 5 / 8;
    static final int LONGEST_RUN = 63;
    static final int LARGEST_SIZE = 255;

    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /** A full glass block. */
    public static final int FULL = 1;
    /** A glass pane; the four bits after it say which sides it joins. */
    public static final int PANE = 2;
    public static final int NORTH = 4;
    public static final int EAST = 8;
    public static final int SOUTH = 16;
    public static final int WEST = 32;

    // Per axis: the plane's u and v directions (x, y, z).
    private static final int[][] U = {{0, 0, 1}, {1, 0, 0}, {1, 0, 0}};
    private static final int[][] V = {{0, 1, 0}, {0, 0, 1}, {0, 1, 0}};

    /** The glass blocks to lay out: growable lists of positions and kinds. */
    public static final class Cells {
        private int[] xs = new int[256];
        private int[] ys = new int[256];
        private int[] zs = new int[256];
        private byte[] kinds = new byte[256];
        private int size;

        public void add(int x, int y, int z, int kind) {
            if (size == xs.length) {
                int grown = size * 2;
                xs = Arrays.copyOf(xs, grown);
                ys = Arrays.copyOf(ys, grown);
                zs = Arrays.copyOf(zs, grown);
                kinds = Arrays.copyOf(kinds, grown);
            }
            xs[size] = x;
            ys[size] = y;
            zs[size] = z;
            kinds[size] = (byte) kind;
            size++;
        }

        public void clear() {
            size = 0;
        }

        public int size() {
            return size;
        }
    }

    private final int[] texels = new int[CAPACITY * 2];
    private int entries;

    /** The table's texels, as 0xAABBGGRR, row by row ({@link #WIDTH} to a row). */
    public int[] texels() {
        return texels;
    }

    /** How many entries the last {@link #build} placed. */
    public int entries() {
        return entries;
    }

    public static int key(int x, int y, int z, int axis, int minY) {
        return 1 | axis << 1 | (x & 255) << 3 | (z & 255) << 11 | ((y - minY) & 4095) << 19;
    }

    public static int slot(int key) {
        return (key * HASH) >>> SLOT_SHIFT;
    }

    public static int data(int uLow, int uHigh, int vLow, int vHigh, int size) {
        return Math.min(uLow, LONGEST_RUN) | Math.min(uHigh, LONGEST_RUN) << 6 | Math.min(vLow, LONGEST_RUN) << 12
            | Math.min(vHigh, LONGEST_RUN) << 18 | Math.min(size, LARGEST_SIZE) << 24;
    }

    /** The data texel stored for a block and axis, or 0 when it isn't in the table (as the shader looks it up). */
    public int find(int x, int y, int z, int axis, int minY) {
        int key = key(x, y, z, axis, minY);
        int slot = slot(key);
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int stored = texels[slot * 2];
            if (stored == key) {
                return texels[slot * 2 + 1];
            }
            if (stored == 0) {
                return 0;
            }
            slot = (slot + 1) & (CAPACITY - 1);
        }
        return 0;
    }

    /** Works out every window among the cells and fills the table, nearest the camera first. */
    public void build(Cells cells, int cameraX, int cameraY, int cameraZ, int minY) {
        Arrays.fill(texels, 0);
        entries = 0;
        Layout layout = new Layout(cells);
        IntArrayList keys = new IntArrayList();
        IntArrayList datas = new IntArrayList();
        IntArrayList cellOf = new IntArrayList();
        for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
            layout.collect(axis, minY, keys, datas, cellOf);
        }
        long[] order = new long[keys.size()];
        for (int i = 0; i < order.length; i++) {
            int cell = cellOf.getInt(i);
            long dx = cells.xs[cell] - cameraX;
            long dy = cells.ys[cell] - cameraY;
            long dz = cells.zs[cell] - cameraZ;
            order[i] = Math.min(dx * dx + dy * dy + dz * dz, 1L << 38) << 24 | i;
        }
        Arrays.sort(order);
        for (long ranked : order) {
            if (entries == MOST_ENTRIES) {
                break;
            }
            int i = (int) (ranked & 0xFFFFFF);
            insert(keys.getInt(i), datas.getInt(i));
        }
    }

    private void insert(int key, int data) {
        int slot = slot(key);
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            if (texels[slot * 2] == 0) {
                texels[slot * 2] = key;
                texels[slot * 2 + 1] = data;
                entries++;
                return;
            }
            slot = (slot + 1) & (CAPACITY - 1);
        }
    }

    /** The windows of one set of cells, worked out an axis at a time. */
    private static final class Layout {
        private final Cells cells;
        /** Each cell's neighbour in each direction (-x, +x, -y, +y, -z, +z), or -1: looked up once. */
        private final int[][] neighbours;
        private final int[][] runs;
        private final int[] parent;
        private final int[] lowU;
        private final int[] highU;
        private final int[] lowV;
        private final int[] highV;
        private final IntArrayList chain = new IntArrayList();

        Layout(Cells cells) {
            this.cells = cells;
            int count = cells.size;
            Long2IntOpenHashMap index = new Long2IntOpenHashMap(count);
            index.defaultReturnValue(-1);
            for (int i = 0; i < count; i++) {
                index.put(position(cells.xs[i], cells.ys[i], cells.zs[i]), i);
            }
            neighbours = new int[6][count];
            for (int i = 0; i < count; i++) {
                int x = cells.xs[i];
                int y = cells.ys[i];
                int z = cells.zs[i];
                neighbours[0][i] = index.get(position(x - 1, y, z));
                neighbours[1][i] = index.get(position(x + 1, y, z));
                neighbours[2][i] = index.get(position(x, y - 1, z));
                neighbours[3][i] = index.get(position(x, y + 1, z));
                neighbours[4][i] = index.get(position(x, y, z - 1));
                neighbours[5][i] = index.get(position(x, y, z + 1));
            }
            runs = new int[4][count];
            parent = new int[count];
            lowU = new int[count];
            highU = new int[count];
            lowV = new int[count];
            highV = new int[count];
        }

        void collect(int axis, int minY, IntArrayList keys, IntArrayList datas, IntArrayList cellOf) {
            int count = cells.size;
            int[] u = U[axis];
            int[] v = V[axis];
            countRuns(axis, -u[0], -u[1], -u[2], runs[0]);
            countRuns(axis, u[0], u[1], u[2], runs[1]);
            countRuns(axis, -v[0], -v[1], -v[2], runs[2]);
            countRuns(axis, v[0], v[1], v[2], runs[3]);

            for (int i = 0; i < count; i++) {
                parent[i] = i;
            }
            for (int i = 0; i < count; i++) {
                int alongU = neighbour(i, u[0], u[1], u[2]);
                if (alongU >= 0 && joins(i, alongU, axis, u[1])) {
                    union(i, alongU);
                }
                int alongV = neighbour(i, v[0], v[1], v[2]);
                if (alongV >= 0 && joins(i, alongV, axis, v[1])) {
                    union(i, alongV);
                }
            }
            Arrays.fill(lowU, 0, count, Integer.MAX_VALUE);
            Arrays.fill(highU, 0, count, Integer.MIN_VALUE);
            Arrays.fill(lowV, 0, count, Integer.MAX_VALUE);
            Arrays.fill(highV, 0, count, Integer.MIN_VALUE);
            for (int i = 0; i < count; i++) {
                int root = find(i);
                int atU = along(i, u);
                int atV = along(i, v);
                lowU[root] = Math.min(lowU[root], atU);
                highU[root] = Math.max(highU[root], atU);
                lowV[root] = Math.min(lowV[root], atV);
                highV[root] = Math.max(highV[root], atV);
            }
            for (int i = 0; i < count; i++) {
                if (!member(i, axis)) {
                    continue;
                }
                int root = find(i);
                int size = Math.min(highU[root] - lowU[root], highV[root] - lowV[root]) + 1;
                keys.add(key(cells.xs[i], cells.ys[i], cells.zs[i], axis, minY));
                datas.add(data(runs[0][i], runs[1][i], runs[2][i], runs[3][i], size));
                cellOf.add(i);
            }
        }

        /** For every cell, how many cells of its window follow it along (dx, dy, dz), up to the longest run. */
        private void countRuns(int axis, int dx, int dy, int dz, int[] counts) {
            int count = cells.size;
            Arrays.fill(counts, 0, count, -1);
            for (int start = 0; start < count; start++) {
                if (counts[start] >= 0) {
                    continue;
                }
                // Walk along until the run ends or meets a cell already counted, then count back.
                chain.clear();
                int at = start;
                int beyond;
                while (true) {
                    chain.add(at);
                    int next = neighbour(at, dx, dy, dz);
                    if (next < 0 || !joins(at, next, axis, dy)) {
                        beyond = 0;
                        break;
                    }
                    if (counts[next] >= 0) {
                        beyond = Math.min(counts[next] + 1, LONGEST_RUN);
                        break;
                    }
                    at = next;
                }
                for (int link = chain.size() - 1; link >= 0; link--) {
                    counts[chain.getInt(link)] = beyond;
                    beyond = Math.min(beyond + 1, LONGEST_RUN);
                }
            }
        }

        /**
         * Whether a face of this cell looks along the axis: a full block with no full glass on at
         * least one side along it (the inside of a solid mass of glass shows no faces), or a pane
         * that runs across the axis (a pane joining nothing shows faces both ways).
         */
        private boolean member(int i, int axis) {
            int kind = cells.kinds[i];
            if ((kind & FULL) != 0) {
                int dx = axis == AXIS_X ? 1 : 0;
                int dy = axis == AXIS_Y ? 1 : 0;
                int dz = axis == AXIS_Z ? 1 : 0;
                return !isFull(neighbour(i, dx, dy, dz)) || !isFull(neighbour(i, -dx, -dy, -dz));
            }
            if ((kind & PANE) == 0 || axis == AXIS_Y) {
                return false;
            }
            int sides = kind & (NORTH | EAST | SOUTH | WEST);
            int across = axis == AXIS_X ? NORTH | SOUTH : EAST | WEST;
            return sides == 0 || (sides & across) != 0;
        }

        /**
         * Whether two neighbouring cells belong to the same window in the axis's plane: full blocks
         * always do; panes do when both show faces along the axis, and side by side only when they
         * join each other. dy is the step from i to j along y.
         */
        private boolean joins(int i, int j, int axis, int dy) {
            int a = cells.kinds[i];
            int b = cells.kinds[j];
            if ((a & FULL) != 0 && (b & FULL) != 0) {
                return true;
            }
            if ((a & PANE) == 0 || (b & PANE) == 0 || !member(i, axis) || !member(j, axis)) {
                return false;
            }
            if (dy != 0) {
                return true;
            }
            int dx = cells.xs[j] - cells.xs[i];
            int dz = cells.zs[j] - cells.zs[i];
            int toward = dx > 0 ? EAST : dx < 0 ? WEST : dz > 0 ? SOUTH : NORTH;
            int back = dx > 0 ? WEST : dx < 0 ? EAST : dz > 0 ? NORTH : SOUTH;
            return (a & toward) != 0 && (b & back) != 0;
        }

        private boolean isFull(int i) {
            return i >= 0 && (cells.kinds[i] & FULL) != 0;
        }

        /** The cell one step along a unit direction. */
        private int neighbour(int i, int dx, int dy, int dz) {
            int direction = dx != 0 ? (dx < 0 ? 0 : 1) : dy != 0 ? (dy < 0 ? 2 : 3) : (dz < 0 ? 4 : 5);
            return neighbours[direction][i];
        }

        private int along(int i, int[] direction) {
            return cells.xs[i] * direction[0] + cells.ys[i] * direction[1] + cells.zs[i] * direction[2];
        }

        private int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        private void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA != rootB) {
                parent[rootB] = rootA;
            }
        }

        private static long position(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
        }
    }
}
