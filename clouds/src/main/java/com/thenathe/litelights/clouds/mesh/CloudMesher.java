package com.thenathe.litelights.clouds.mesh;

import org.jspecify.annotations.Nullable;

/**
 * Turns a chunk's cells into cube faces, given the same chunk as the last build left it, so the
 * change between the two can be drawn as growth rather than a switch.
 *
 * <p>Each cell is one of:
 * <ul>
 *   <li><b>stable</b>, solid in both builds: drawn whole;</li>
 *   <li><b>appearing</b>, solid only now: it grows over the change, from 0 to its full size;</li>
 *   <li><b>disappearing</b>, solid only before: it shrinks to nothing.</li>
 * </ul>
 * A changing cell grows from the stable cloud next to it, so cloud seems to build out of cloud:
 * <ul>
 *   <li>a run of changing cells on top of stable cloud rises as one, cell after cell (a tower
 *   growing), and one hanging under stable cloud sinks the same way (a sagging base);</li>
 *   <li>otherwise a cell beside stable cloud grows out sideways from it (a cloud widening);</li>
 *   <li>a run with no stable cloud next to it grows from its middle, upward, downward and outward
 *   at once (a new puff).</li>
 * </ul>
 * Each changing cell's share of the change (its window) is in the face, so the shader moves every
 * run's front at an even pace. Disappearing cells do the same backwards.
 *
 * <p>A face is drawn wherever the cell on its other side isn't stable. Stable cells keep their
 * faces toward changing ones, so there is always a surface behind a growing or shrinking cell and
 * nothing opens up as it changes; the shader keeps a changing cell a hair thick at its start and
 * end so the two never fight for the same pixels. The side faces of stable cells stacked in a
 * column are joined into one tall face, which a cloud's walls are mostly made of.
 *
 * <p>Each face carries the cloud's shading normal and how high its cloud reaches, both smoothed over
 * the cells around ({@link CloudShading}): a cloud's top shades as the dome the field gives rather
 * than as flat steps, its walls round off at their corners, and the darkening under its top runs on
 * from one column's wall to the next.
 */
public final class CloudMesher {
    private static final int EMPTY = -1;
    /** Side neighbours, in the order a changing cell looks for stable cloud to grow from. */
    private static final int[] SIDES = {FaceFormat.WEST, FaceFormat.EAST, FaceFormat.NORTH, FaceFormat.SOUTH};
    /**
     * The tallest joined wall face, in cells: its shading is drawn between its corners, so a taller
     * face would lose how a rounded wall turns along it.
     */
    static final int MOST_JOINED = 4;

    private CloudMesher() {
    }

    /**
     * Adds a chunk's faces, with chunk-local positions (x and z from 0 to {@link CloudCells#SIZE}).
     *
     * @param before the chunk as the last build left it, or null if it wasn't built then (it came
     *               into reach): then nothing changes and every cell is stable
     * @param after  the chunk now
     * @param seams  the sides (bit {@code 1 << side}, north to east) whose apron lies in a chunk of
     *               another cube size: read as clear sky, so the chunk closes its own edge there
     */
    public static void mesh(@Nullable CloudCells before, CloudCells after, int seams, FaceList out) {
        if (before != null && (before.chunkX() != after.chunkX() || before.chunkZ() != after.chunkZ()
            || before.cubeBlocks() != after.cubeBlocks())) {
            throw new IllegalArgumentException("the two builds are of different chunks");
        }
        CloudCells previous = before != null ? before : after;
        if (after.isEmpty() && previous.isEmpty()) {
            return;
        }
        int lowest = Math.min(after.lowestCell(), previous.lowestCell());
        int highest = Math.max(after.highestCell(), previous.highestCell());
        int sizeShift = Integer.numberOfTrailingZeros(after.cubeBlocks() / FaceFormat.CELL_BLOCKS);
        States states = new States(previous, after, seams);
        CloudShading shading = CloudShading.of(after, lowest, highest);
        Walls walls = new Walls(shading);
        for (int z = 0; z < CloudCells.SIZE; z++) {
            for (int x = 0; x < CloudCells.SIZE; x++) {
                walls.start(x, z);
                for (int word = lowest >> 6; word <= highest >> 6; word++) {
                    // Only the cells solid in either build (most of a column is clear sky), and of
                    // those not the ones buried in stable cloud on all six sides, which draw nothing.
                    long cells = previous.word(x, z, word) | after.word(x, z, word);
                    cells &= ~buried(previous, after, seams, x, z, word);
                    while (cells != 0L) {
                        int y = word * 64 + Long.numberOfTrailingZeros(cells);
                        cells &= cells - 1L;
                        emitCell(states, shading, previous, after, x, z, y, sizeShift, walls, out);
                    }
                }
                walls.flushAll(out);
            }
        }
    }

    private static void emitCell(States states, CloudShading shading, CloudCells previous, CloudCells after, int x, int z, int y,
                                 int sizeShift, Walls walls, FaceList out) {
        int state = states.at(x, z, y);
        if (state == EMPTY) {
            return;
        }
        int look = -1;
        float runTop = Float.NaN;
        int growFrom = -1;
        Growth growth = null;
        for (int side = 0; side < 6; side++) {
            int neighbour = states.at(x + dx(side), z + dz(side), y + dy(side));
            if (side == growFrom || neighbour == FaceFormat.STABLE) {
                if (state == FaceFormat.STABLE && side >= FaceFormat.NORTH) {
                    walls.interrupt(side, out);
                }
                continue;
            }
            if (look == -1) {
                // Worked out once a cell has a face to draw; buried cells never need it.
                CloudCells shaded = state == FaceFormat.DISAPPEARING ? previous : after;
                int storm = shaded.storm(x, z, y);
                runTop = state == FaceFormat.DISAPPEARING ? previous.cloudTop(x, z, y) : shading.runTop(x, z, y);
                if (state == FaceFormat.STABLE) {
                    look = FaceFormat.look(sizeShift, state, -1, false, 0.0f, 1.0f, storm, FaceFormat.FADE_NONE);
                } else {
                    growth = growth(states, x, z, y, state);
                    growFrom = growth.from;
                    look = FaceFormat.look(sizeShift, state, growth.from, growth.widens, growth.start, growth.end, storm, FaceFormat.FADE_NONE);
                    if (side == growFrom) {
                        continue;
                    }
                }
            }
            if (growth != null && neighbour == state && side >= FaceFormat.NORTH && sameRise(states, x + dx(side), z + dz(side), y, state, growth)) {
                // Side by side, two cells rising or sinking in step are always the same height: the face between them never shows.
                continue;
            }
            if (state == FaceFormat.STABLE && side >= FaceFormat.NORTH) {
                walls.add(side, y, look, runTop, out);
            } else {
                walls.face(x, z, y, side, 1, look, runTop, out);
            }
        }
    }

    /**
     * Writes faces with their corners' shading, and stacks the side faces of stable cells into one
     * per side of the column being meshed. A stack ends where the next cell up has no face on that
     * side, isn't stable, isn't the next cell, looks otherwise (stormier, say), takes its top from
     * elsewhere, or the stack is as tall as it may be.
     */
    private static final class Walls {
        private final CloudShading shading;
        private final int[] firstCell = new int[6];
        private final int[] lastCell = new int[6];
        private final int[] look = new int[6];
        private final float[] runTop = new float[6];
        private final int[] normals = new int[4];
        private int x;
        private int z;

        Walls(CloudShading shading) {
            this.shading = shading;
        }

        void start(int x, int z) {
            this.x = x;
            this.z = z;
            for (int side = FaceFormat.NORTH; side <= FaceFormat.EAST; side++) {
                firstCell[side] = -1;
            }
        }

        void add(int side, int y, int look, float runTop, FaceList out) {
            int first = firstCell[side];
            // Stacks break on the same heights in every column (multiples of MOST_JOINED), so the
            // faces of neighbouring columns share their corners and their shading runs on across them.
            if (first >= 0 && (lastCell[side] != y - 1 || this.look[side] != look
                || Float.compare(this.runTop[side], runTop) != 0 || y % MOST_JOINED == 0)) {
                flush(side, out);
                first = -1;
            }
            if (first < 0) {
                firstCell[side] = y;
                this.look[side] = look;
                this.runTop[side] = runTop;
            }
            lastCell[side] = y;
        }

        void interrupt(int side, FaceList out) {
            if (firstCell[side] >= 0) {
                flush(side, out);
            }
        }

        void flushAll(FaceList out) {
            for (int side = FaceFormat.NORTH; side <= FaceFormat.EAST; side++) {
                interrupt(side, out);
            }
        }

        private void flush(int side, FaceList out) {
            int first = firstCell[side];
            face(x, z, first, side, lastCell[side] - first + 1, look[side], runTop[side], out);
            firstCell[side] = -1;
        }

        /**
         * One face, {@code cells} tall, with each corner's normal and how high the cloud reaches over
         * each of its two edges: {@code runTop} if given, else the smoothed tops round its corners.
         */
        void face(int x, int z, int y, int side, int cells, int look, float runTop, FaceList out) {
            float ownTop = Float.isNaN(runTop) ? shading.smoothTop(x, z) : runTop;
            float[] edgeTops = {0.0f, 0.0f};
            int[] edgeCorners = {0, 0};
            for (int corner = 0; corner < 4; corner++) {
                int[] offset = FaceFormat.CORNERS[side * 4 + corner];
                int cornerX = x + offset[0];
                int cornerY = y + offset[1] * cells;
                int cornerZ = z + offset[2];
                normals[corner] = shading.cornerNormal(cornerX, cornerY, cornerZ, side);
                float top = Float.isNaN(runTop) && !Float.isNaN(ownTop) ? shading.cornerTop(cornerX, cornerZ, ownTop) : ownTop;
                int edge = FaceFormat.edge(side, corner);
                edgeTops[edge] += Float.isNaN(top) ? 0.0f : top;
                edgeCorners[edge]++;
            }
            out.add(FaceFormat.position(x, z, y, side), look, FaceFormat.shadeWord2(normals),
                FaceFormat.shadeWord3(normals, edgeTops[0] / edgeCorners[0], edgeTops[1] / edgeCorners[1], cells));
        }
    }

    private record Growth(int from, boolean widens, float start, float end) {
    }

    /**
     * Whether a neighbouring changing cell rises or sinks in step with this one: grown up or down
     * from stable cloud in its own column (which the apron holds even on a chunk's edge) over the
     * same window.
     */
    private static boolean sameRise(States states, int x, int z, int y, int state, Growth growth) {
        if (growth.widens || growth.from != FaceFormat.DOWN && growth.from != FaceFormat.UP) {
            return false;
        }
        if (x < 0 || x >= CloudCells.SIZE || z < 0 || z >= CloudCells.SIZE) {
            // An apron column's growth can still depend on cells beyond it (a side anchor), so only its vertical run is read.
            Growth other = verticalGrowth(states, x, z, y, state);
            return other != null && other.equals(growth);
        }
        return growth(states, x, z, y, state).equals(growth);
    }

    /** The growth of a cell anchored on stable cloud in its own column, or null if it isn't. */
    private static Growth verticalGrowth(States states, int x, int z, int y, int state) {
        int bottom = y;
        while (states.at(x, z, bottom - 1) == state) {
            bottom--;
        }
        int top = y;
        while (states.at(x, z, top + 1) == state) {
            top++;
        }
        int count = top - bottom + 1;
        if (states.at(x, z, bottom - 1) == FaceFormat.STABLE) {
            return window(FaceFormat.DOWN, false, y - bottom, count, state);
        }
        if (states.at(x, z, top + 1) == FaceFormat.STABLE) {
            return window(FaceFormat.UP, false, top - y, count, state);
        }
        return null;
    }

    /** How a changing cell grows: from which side, and over which part of the change. */
    private static Growth growth(States states, int x, int z, int y, int state) {
        Growth vertical = verticalGrowth(states, x, z, y, state);
        if (vertical != null) {
            return vertical;
        }
        for (int side : SIDES) {
            if (states.at(x + dx(side), z + dz(side), y) == FaceFormat.STABLE) {
                return new Growth(side, false, 0.0f, 1.0f);
            }
        }
        // Nothing stable to grow from: the run grows from its middle, outward as well as up and down.
        int bottom = y;
        while (states.at(x, z, bottom - 1) == state) {
            bottom--;
        }
        int top = y;
        while (states.at(x, z, top + 1) == state) {
            top++;
        }
        int count = top - bottom + 1;
        int steps = (count + 1) / 2;
        int index = y - bottom;
        if ((count & 1) == 1 && index == count / 2) {
            return window(-1, true, 0, steps, state);
        }
        if (index >= count / 2) {
            int fromMiddle = index - count / 2 - (count & 1);
            return window(FaceFormat.DOWN, true, fromMiddle + (count & 1), steps, state);
        }
        int fromMiddle = count / 2 - 1 - index;
        return window(FaceFormat.UP, true, fromMiddle + (count & 1), steps, state);
    }

    /**
     * The window of the {@code index}th cell out from where a run grows, of {@code count} in turn.
     * Appearing cells nearest the start fill first; disappearing cells farthest from it empty first.
     */
    private static Growth window(int from, boolean widens, int index, int count, int state) {
        int turn = state == FaceFormat.APPEARING ? index : count - 1 - index;
        return new Growth(from, widens, turn / (float) count, (turn + 1) / (float) count);
    }

    /**
     * The cells of one 64-cell word of a column that are stable and have stable cells on all six
     * sides. A side across a seam counts as open.
     */
    private static long buried(CloudCells previous, CloudCells after, int seams, int x, int z, int word) {
        long here = stable(previous, after, x, z, word);
        if (here == 0L) {
            return 0L;
        }
        long sides = here
            & (x == 0 && (seams & 1 << FaceFormat.WEST) != 0 ? 0L : stable(previous, after, x - 1, z, word))
            & (x == CloudCells.SIZE - 1 && (seams & 1 << FaceFormat.EAST) != 0 ? 0L : stable(previous, after, x + 1, z, word))
            & (z == 0 && (seams & 1 << FaceFormat.NORTH) != 0 ? 0L : stable(previous, after, x, z - 1, word))
            & (z == CloudCells.SIZE - 1 && (seams & 1 << FaceFormat.SOUTH) != 0 ? 0L : stable(previous, after, x, z + 1, word));
        // The cell above: bit y of (here >>> 1), with the next word's lowest cell above bit 63.
        long above = here >>> 1 | (word + 1 < CloudCells.WORDS ? (stable(previous, after, x, z, word + 1) & 1L) << 63 : 0L);
        long below = here << 1 | (word > 0 ? stable(previous, after, x, z, word - 1) >>> 63 : 0L);
        return sides & above & below;
    }

    private static long stable(CloudCells previous, CloudCells after, int x, int z, int word) {
        return previous.word(x, z, word) & after.word(x, z, word);
    }

    static int dx(int side) {
        return side == FaceFormat.WEST ? -1 : side == FaceFormat.EAST ? 1 : 0;
    }

    static int dy(int side) {
        return side == FaceFormat.DOWN ? -1 : side == FaceFormat.UP ? 1 : 0;
    }

    static int dz(int side) {
        return side == FaceFormat.NORTH ? -1 : side == FaceFormat.SOUTH ? 1 : 0;
    }

    /**
     * Each cell's state, from the two builds; heights outside, the apron beyond it and the apron on
     * a seam (another cube size's chunk) are read as empty.
     */
    private record States(CloudCells before, CloudCells after, int seams) {
        int at(int x, int z, int y) {
            if (x < -1 || x > CloudCells.SIZE || z < -1 || z > CloudCells.SIZE) {
                return EMPTY;
            }
            if (seams != 0 && (x < 0 && (seams & 1 << FaceFormat.WEST) != 0 || x >= CloudCells.SIZE && (seams & 1 << FaceFormat.EAST) != 0
                || z < 0 && (seams & 1 << FaceFormat.NORTH) != 0 || z >= CloudCells.SIZE && (seams & 1 << FaceFormat.SOUTH) != 0)) {
                return EMPTY;
            }
            boolean was = before.solid(x, z, y);
            boolean is = after.solid(x, z, y);
            if (was && is) {
                return FaceFormat.STABLE;
            }
            if (is) {
                return FaceFormat.APPEARING;
            }
            return was ? FaceFormat.DISAPPEARING : EMPTY;
        }
    }
}
