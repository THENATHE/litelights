package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns a tile's voxels into quads. A face is drawn where a whole voxel meets something that
 * doesn't cover it (water where water meets air), and faces alike (same appearance, tint and
 * light) are merged greedily into rectangles, slice by slice, one direction at a time.
 *
 * <p><b>Full detail.</b> At level 0 a block that isn't a whole voxel is drawn in its own shape
 * ({@link FarShape}): each of its boxes' faces, merged along an axis only where they cover the
 * whole block across it (a row of slab tops is one quad, a row of fence posts is not), and a
 * plant as two crossed cards in a bucket of their own ({@link FarMesh#CARDS}). Coarser levels
 * are whole voxels only.
 *
 * <p><b>Under water.</b> Water is see-through, so the land under water open to the sky is drawn
 * too (not under ice), lit as the water over it is: sky light fades a level a block down through water, so the floor of deep water goes
 * dark and is left out like a closed cave, where the water's own depth colour hides it anyway.
 * Water's faces go in a bucket of their own ({@link FarMesh#WATER}).
 *
 * <p><b>Lights.</b> Every block that gives off a strong light, and at coarse levels (where torches
 * and lanterns are gone) every voxel lit almost fully by one, is gathered into cells of four voxels
 * a side, and each cell becomes one point ({@link FarMesh#LIGHTS}) at its brightest light, as
 * strong as the lights in it together, so a village, a lava lake or a lit cave mouth shows at
 * night at any distance.
 *
 * <p><b>Caves.</b> In a dimension with sky light, a face whose air has no light at all (no sky
 * light, no block light) belongs to a closed cave nobody far away can see, and is left out. That
 * keeps the quads to the land's outer shell.
 *
 * <p><b>Borders.</b> A face on a tile's edge looks across at the neighbour tile's edge column
 * ({@link EdgeTops}). It is drawn from the neighbour's top up (the neighbour's own top row
 * included, which the neighbour covers when both are drawn at this level and which closes the
 * crack when the neighbour is drawn coarser); water's only above the neighbour's top, as a skirt
 * under see-through water would show through it. Toward land never seen, the edge is a wall down
 * to {@link #UNSEEN_WALL_BLOCKS} under the column's top, like the edge of the game's own land, and
 * below that a curtain of rock down past the tile's lowest lit cave: the column's own blocks where
 * they are solid and the tile's commonest rock across a cave's mouth, so no lit cave deeper than
 * the wall shows from outside.
 */
public final class FarMesher {
    /** How far down a wall toward land never seen reaches, in blocks. */
    static final int UNSEEN_WALL_BLOCKS = 16;
    private static final long FACE = 1L << 62;
    private static final int[] OPPOSITE = {FarQuad.UP, FarQuad.DOWN, FarQuad.SOUTH, FarQuad.NORTH, FarQuad.EAST, FarQuad.WEST};
    /** A whole plant card: corner to corner, the block's full height. */
    private static final int CARD_PART = FarQuad.detailPart(0, 16, 0, 16, 0);

    private int[] output = new int[4096 * FarQuad.INTS];
    private int quads;
    private long[] mask = new long[0];
    /** Per mask cell, a detail face's place within its block ({@link FarQuad#detailPart}). */
    private int[] parts = new int[0];
    /** The tile being meshed: each voxel's kind. */
    private byte[] kinds = new byte[0];
    /** At level 0, each voxel's shape type ({@link FarShape}); null at coarser levels. */
    private byte[] shapes;
    /** At level 0, the voxels drawn as boxes, in index order. */
    private int[] partials = new int[0];
    private int partialCount;
    private int[] sliceStarts = new int[0];
    private int[] bySlice = new int[0];
    /** The lowest row the curtain toward land never seen reaches; above every row when there is none. */
    private int curtainBottom;
    /** The rock the curtain shows across a cave's mouth: the tile's commonest solid block underground. */
    private int curtainRock;
    /**
     * Per column, bit sets over its rows ({@link #words} longs a column): where a whole voxel is,
     * where water is, where anything is that isn't air; and the faces one direction may have, so
     * only those are looked at. Most voxels are buried, and testing each in six directions was
     * nearly all of meshing's time.
     */
    private int words;
    private long[] wholeBits = new long[0];
    private long[] waterBits = new long[0];
    private long[] filledBits = new long[0];
    private long[] candidates = new long[0];

    /**
     * @param outside      per edge (EdgeTops order), the tops of the columns across it, in this
     *                     tile's rows; {@link EdgeTops#UNKNOWN} where nothing is known
     * @param caveCulling  leave out faces on unlit air (dimensions with sky light)
     */
    public FarMesh mesh(TileVoxels tile, EdgeTops outside, FaceLooks looks, boolean caveCulling) {
        quads = 0;
        int[] firstQuad = new int[FarMesh.BUCKETS];
        int[] quadCount = new int[FarMesh.BUCKETS];
        kinds = tile.kinds(looks);
        shapes = tile.level == 0 ? shapeTypes(tile, looks) : null;
        int[] tops = columnTops(tile, kinds);
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        partialCount = 0;
        words = (tile.height + 63) >>> 6;
        int bitCount = TileVoxels.COLUMNS * words;
        if (wholeBits.length < bitCount) {
            wholeBits = new long[bitCount];
            waterBits = new long[bitCount];
            filledBits = new long[bitCount];
            candidates = new long[bitCount];
        } else {
            Arrays.fill(wholeBits, 0, bitCount, 0L);
            Arrays.fill(waterBits, 0, bitCount, 0L);
            Arrays.fill(filledBits, 0, bitCount, 0L);
        }
        for (int y = 0; y < tile.height; y++) {
            int word = y >>> 6;
            long bit = 1L << (y & 63);
            for (int column = 0; column < TileVoxels.COLUMNS; column++) {
                int index = column + y * TileVoxels.COLUMNS;
                byte kind = kinds[index];
                if (kind != BlockKinds.AIR || (shapes != null && shapes[index] != FarShape.NONE)) {
                    lowest = Math.min(lowest, y);
                    highest = Math.max(highest, y);
                }
                if (kind != BlockKinds.AIR) {
                    int at = column * words + word;
                    filledBits[at] |= bit;
                    if (kind == BlockKinds.WATER) {
                        waterBits[at] |= bit;
                    } else if (shapes == null || shapes[index] == FarShape.FULL) {
                        wholeBits[at] |= bit;
                    }
                }
                if (shapes != null && shapes[index] == FarShape.BOXES) {
                    if (partialCount == partials.length) {
                        partials = Arrays.copyOf(partials, Math.max(256, partialCount * 2));
                    }
                    partials[partialCount++] = index;
                }
            }
        }
        if (lowest == Integer.MAX_VALUE) {
            return new FarMesh(new int[0], firstQuad, quadCount, 0, 0);
        }
        // The quad layout holds 1,024 rows; a taller world (a data pack's) loses its top at level 0.
        highest = Math.min(highest, FarQuad.MAX_ROWS - 1);
        lowest = Math.min(lowest, highest);
        int kept = 0;
        for (int i = 0; i < partialCount; i++) {
            if (partials[i] / TileVoxels.COLUMNS <= highest) {
                partials[kept++] = partials[i];
            }
        }
        partialCount = kept;
        prepareCurtain(tile, outside, tops);
        for (int face = 0; face < 6; face++) {
            firstQuad[face] = quads;
            meshDirection(tile, outside, looks, caveCulling, tops, face, lowest, highest, false);
            if (partialCount > 0) {
                meshPartials(tile, outside, looks, caveCulling, tops, face, lowest, highest);
            }
            quadCount[face] = quads - firstQuad[face];
        }
        firstQuad[FarMesh.CARDS] = quads;
        if (shapes != null) {
            meshCards(tile, looks, caveCulling, lowest, highest);
        }
        quadCount[FarMesh.CARDS] = quads - firstQuad[FarMesh.CARDS];
        firstQuad[FarMesh.WATER] = quads;
        for (int face = 0; face < 6; face++) {
            meshDirection(tile, outside, looks, caveCulling, tops, face, lowest, highest, true);
        }
        quadCount[FarMesh.WATER] = quads - firstQuad[FarMesh.WATER];
        firstQuad[FarMesh.LIGHTS] = quads;
        meshLights(tile, looks, lowest, highest);
        quadCount[FarMesh.LIGHTS] = quads - firstQuad[FarMesh.LIGHTS];
        return new FarMesh(Arrays.copyOf(output, quads * FarQuad.INTS), firstQuad, quadCount, lowest, highest);
    }

    /** Each voxel's shape type, for code that asks about each voxel many times. */
    private static byte[] shapeTypes(TileVoxels tile, FaceLooks looks) {
        byte[] result = new byte[tile.states.length];
        int lastState = -1;
        byte lastType = FarShape.NONE;
        for (int i = 0; i < result.length; i++) {
            int state = tile.states[i];
            if (state != lastState) {
                lastState = state;
                lastType = state == 0 ? FarShape.NONE : (byte) looks.shape(state).type();
            }
            result[i] = lastType;
        }
        return result;
    }

    /** Each column's top row, as {@link TileVoxels#top} gives it. */
    private static int[] columnTops(TileVoxels tile, byte[] kinds) {
        int[] tops = new int[TileVoxels.COLUMNS];
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            int x = column % TileVoxels.SIZE;
            int z = column / TileVoxels.SIZE;
            int top = TileVoxels.EMPTY_TOP;
            if (!tile.known(x, z)) {
                top = TileVoxels.UNKNOWN_TOP;
            } else {
                for (int y = tile.height - 1; y >= 0; y--) {
                    if (kinds[column + y * TileVoxels.COLUMNS] != BlockKinds.AIR) {
                        top = y;
                        break;
                    }
                }
            }
            tops[column] = top;
        }
        return tops;
    }

    private void ensureMask(int cells) {
        if (mask.length < cells) {
            mask = new long[cells];
            parts = new int[cells];
        }
    }

    private void meshDirection(TileVoxels tile, EdgeTops outside, FaceLooks looks, boolean caveCulling, int[] tops, int face,
                               int lowest, int highest, boolean water) {
        int size = TileVoxels.SIZE;
        boolean vertical = face == FarQuad.DOWN || face == FarQuad.UP;
        // Slices along the face's normal; within a slice, a is the first in-plane axis, b the second.
        int sliceCount = vertical ? highest - lowest + 1 : size;
        int extentA = size;
        int extentB = vertical ? size : highest - lowest + 1;
        ensureMask(extentA * extentB);
        findCandidates(tile, outside, face, water, lowest, highest);
        for (int slice = 0; slice < sliceCount; slice++) {
            int minB = Integer.MAX_VALUE;
            int maxB = -1;
            if (vertical) {
                int y = lowest + slice;
                int word = y >>> 6;
                long bit = 1L << (y & 63);
                for (int column = 0; column < TileVoxels.COLUMNS; column++) {
                    if ((candidates[column * words + word] & bit) == 0) {
                        continue;
                    }
                    int x = column % size;
                    int z = column / size;
                    long key = faceKey(tile, outside, looks, caveCulling, tops, face, x, y, z, water);
                    if (key != 0) {
                        mask[x + z * extentA] = key;
                        minB = Math.min(minB, z);
                        maxB = Math.max(maxB, z);
                    }
                }
            } else {
                boolean northSouth = face == FarQuad.NORTH || face == FarQuad.SOUTH;
                for (int a = 0; a < extentA; a++) {
                    int x = northSouth ? a : slice;
                    int z = northSouth ? slice : a;
                    int base = TileVoxels.column(x, z) * words;
                    for (int word = lowest >>> 6; word <= highest >>> 6; word++) {
                        long bits = candidates[base + word];
                        while (bits != 0) {
                            int y = (word << 6) + Long.numberOfTrailingZeros(bits);
                            bits &= bits - 1;
                            if (y < lowest || y > highest) {
                                continue;
                            }
                            long key = faceKey(tile, outside, looks, caveCulling, tops, face, x, y, z, water);
                            if (key != 0) {
                                int b = y - lowest;
                                mask[a + b * extentA] = key;
                                minB = Math.min(minB, b);
                                maxB = Math.max(maxB, b);
                            }
                        }
                    }
                }
            }
            if (maxB >= 0) {
                merge(face, slice, vertical, lowest, extentA, minB, maxB);
            }
        }
    }

    /**
     * Each column's rows that may have a face toward {@code face}: a whole voxel (or water) whose
     * neighbour that way isn't one that surely hides it. A column beside land never seen, or at
     * the tile's edge, takes every row, since its border faces, wall and curtain follow other
     * rules ({@link #faceKey} decides them all).
     */
    private void findCandidates(TileVoxels tile, EdgeTops outside, int face, boolean water, int lowest, int highest) {
        long[] source = water ? waterBits : wholeBits;
        long[] hiding = water ? filledBits : wholeBits;
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            int base = column * words;
            int x = column % TileVoxels.SIZE;
            int z = column / TileVoxels.SIZE;
            int neighbour = -1;
            if (face >= FarQuad.NORTH) {
                int nx = x + (face == FarQuad.WEST ? -1 : face == FarQuad.EAST ? 1 : 0);
                int nz = z + (face == FarQuad.NORTH ? -1 : face == FarQuad.SOUTH ? 1 : 0);
                boolean inside = nx >= 0 && nx < TileVoxels.SIZE && nz >= 0 && nz < TileVoxels.SIZE && tile.known(nx, nz);
                if (!inside) {
                    Arrays.fill(candidates, base, base + words, -1L);
                    continue;
                }
                neighbour = TileVoxels.column(nx, nz) * words;
            }
            for (int word = 0; word < words; word++) {
                long hidden;
                if (face == FarQuad.UP) {
                    hidden = hiding[base + word] >>> 1 | (word + 1 < words ? hiding[base + word + 1] << 63 : 0L);
                } else if (face == FarQuad.DOWN) {
                    hidden = hiding[base + word] << 1 | (word > 0 ? hiding[base + word - 1] >>> 63 : 0L);
                } else {
                    hidden = hiding[neighbour + word];
                }
                candidates[base + word] = source[base + word] & ~hidden;
            }
        }
    }

    /**
     * The face of voxel (x, y, z) toward {@code face} as a merge key, or 0 when there is none: of a
     * whole voxel in the solid pass, of water in the water pass.
     */
    private long faceKey(TileVoxels tile, EdgeTops outside, FaceLooks looks, boolean caveCulling, int[] tops, int face,
                         int x, int y, int z, boolean water) {
        int index = TileVoxels.index(x, y, z);
        byte kind = kinds[index];
        boolean whole = kind == BlockKinds.SOLID && (shapes == null || shapes[index] == FarShape.FULL);
        if (!water && face >= FarQuad.NORTH && y >= curtainBottom && towardUnseen(tile, outside, face, x, z)
            && y <= tops[TileVoxels.column(x, z)] - wallRows(tile)) {
            return curtainKey(tile, looks, face, x, y, z, whole ? tile.states[index] : curtainRock, tops);
        }
        if (water ? kind != BlockKinds.WATER : !whole) {
            return 0;
        }
        int state = tile.states[index];
        int nx = x;
        int ny = y;
        int nz = z;
        switch (face) {
            case FarQuad.DOWN -> ny--;
            case FarQuad.UP -> ny++;
            case FarQuad.NORTH -> nz--;
            case FarQuad.SOUTH -> nz++;
            case FarQuad.WEST -> nx--;
            default -> nx++;
        }
        int light;
        if (ny < 0) {
            return 0;
        }
        int ownTop = tops[TileVoxels.column(x, z)];
        if (nx < 0 || nx >= TileVoxels.SIZE || nz < 0 || nz >= TileVoxels.SIZE) {
            int neighbourTop = outside.top(edgeOf(face), alongEdge(face, x, z));
            if (!borderFaceShown(tile, y, ownTop, neighbourTop) || (water && y == neighbourTop)) {
                return 0;
            }
            light = surfaceLight(tile, x, z, ownTop);
        } else if (ny >= tile.height) {
            light = 0xF0;
        } else if (!tile.known(nx, nz)) {
            if (!borderFaceShown(tile, y, ownTop, TileVoxels.UNKNOWN_TOP)) {
                return 0;
            }
            light = surfaceLight(tile, x, z, ownTop);
        } else {
            int neighbourIndex = TileVoxels.index(nx, ny, nz);
            if (covers(tile, looks, neighbourIndex, OPPOSITE[face], water)) {
                return 0;
            }
            if (kinds[neighbourIndex] == BlockKinds.WATER && !openWater(tile, nx, nz, tops)) {
                // Under ice or anything else over the water: nobody sees this floor.
                return 0;
            }
            light = kinds[neighbourIndex] == BlockKinds.WATER ? underWaterLight(tile, nx, ny, nz, tops) : tile.light[neighbourIndex] & 0xFF;
            if (caveCulling && light == 0) {
                return 0;
            }
        }
        int appearance = looks.appearance(state, face);
        if (appearance == 0) {
            return 0;
        }
        int biome = tile.biomes[TileVoxels.column(x, z)];
        int tint = looks.tint(state, face, biome) & 0xFFFFFF;
        // A coarse voxel's top is drawn where its land really ends: the top face, and the top edge
        // of the side faces of a column's top voxel.
        int inset = face == FarQuad.DOWN || (face != FarQuad.UP && y != ownTop) ? 0 : TileVoxels.inset(tile, index);
        int depth = water && face == FarQuad.UP ? FarQuad.depthClass(waterDepth(tile, x, y, z)) : 0;
        return FACE | (long) depth << 56 | (long) inset << 48 | (long) appearance << 32 | (long) tint << 8 | light;
    }

    /**
     * How deep the water under a top face at (x, y, z) is, in blocks: the voxels of water down to
     * the floor (for far water's Plain look, which has no scene copy to measure it by). Water that
     * reaches the tile's bottom counts as deep.
     */
    private int waterDepth(TileVoxels tile, int x, int y, int z) {
        int deepest = FarQuad.WATER_DEPTHS[FarQuad.WATER_DEPTHS.length - 1];
        int size = 1 << tile.level;
        int voxels = 0;
        for (int below = y; below >= 0; below--) {
            if (kinds[TileVoxels.index(x, below, z)] != BlockKinds.WATER) {
                return Math.min(voxels * size, deepest);
            }
            voxels++;
            if (voxels * size >= deepest) {
                break;
            }
        }
        return deepest;
    }

    /**
     * Whether the voxel at {@code index} hides the face of its neighbour that lies against its
     * {@code side}: a whole voxel does, a block drawn in its own shape where that shape covers the
     * side, and for water anything that isn't air.
     */
    private boolean covers(TileVoxels tile, FaceLooks looks, int index, int side, boolean water) {
        byte kind = kinds[index];
        if (water) {
            return kind != BlockKinds.AIR;
        }
        if (kind == BlockKinds.WATER) {
            return false;
        }
        if (shapes == null) {
            return kind == BlockKinds.SOLID;
        }
        return switch (shapes[index]) {
            case FarShape.FULL -> true;
            case FarShape.BOXES -> looks.shape(tile.states[index]).coversSide(side);
            default -> false;
        };
    }

    private static int edgeOf(int face) {
        return face == FarQuad.NORTH ? EdgeTops.NORTH : face == FarQuad.SOUTH ? EdgeTops.SOUTH
            : face == FarQuad.WEST ? EdgeTops.WEST : EdgeTops.EAST;
    }

    private static int alongEdge(int face, int x, int z) {
        return face == FarQuad.NORTH || face == FarQuad.SOUTH ? x : z;
    }

    /** Whether a border face at row y shows, with the column across the border topped at {@code neighbourTop}. */
    private static boolean borderFaceShown(TileVoxels tile, int y, int ownTop, int neighbourTop) {
        if (neighbourTop == TileVoxels.UNKNOWN_TOP) {
            return y > ownTop - wallRows(tile);
        }
        return y >= neighbourTop;
    }

    /** How many voxel rows the wall toward land never seen takes. */
    private static int wallRows(TileVoxels tile) {
        return Math.max(2, UNSEEN_WALL_BLOCKS >> tile.level);
    }

    // --- The curtain toward land never seen ---

    /**
     * Finds how deep this tile's curtain must reach (one row under its lowest lit cave) and its
     * rock, for a tile with land never seen beside it; none otherwise.
     */
    private void prepareCurtain(TileVoxels tile, EdgeTops outside, int[] tops) {
        curtainBottom = Integer.MAX_VALUE;
        curtainRock = 0;
        if (!bordersUnseen(tile, outside)) {
            return;
        }
        int lowestLit = Integer.MAX_VALUE;
        Map<Integer, Integer> rock = new HashMap<>();
        for (int column = 0; column < TileVoxels.COLUMNS; column++) {
            int top = tops[column];
            for (int y = 0; y < top && y < tile.height; y++) {
                int index = column + y * TileVoxels.COLUMNS;
                if (kinds[index] == BlockKinds.AIR) {
                    if (y < lowestLit && tile.light[index] != 0) {
                        lowestLit = y;
                    }
                } else if (kinds[index] == BlockKinds.SOLID && (y & 3) == 0) {
                    rock.merge(tile.states[index], 1, Integer::sum);
                }
            }
        }
        if (lowestLit == Integer.MAX_VALUE || rock.isEmpty()) {
            return;
        }
        curtainBottom = Math.max(0, lowestLit - 1);
        curtainRock = Collections.max(rock.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    /** Whether any column of the tile, or across its edges, was never seen. */
    private static boolean bordersUnseen(TileVoxels tile, EdgeTops outside) {
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < TileVoxels.SIZE; i++) {
                if (outside.top(edge, i) == TileVoxels.UNKNOWN_TOP) {
                    return true;
                }
            }
        }
        for (long word : tile.known) {
            if (word != -1L) {
                return true;
            }
        }
        return false;
    }

    /** Whether the column beside (x, z) toward {@code face} (a side) was never seen. */
    private static boolean towardUnseen(TileVoxels tile, EdgeTops outside, int face, int x, int z) {
        int nx = x + (face == FarQuad.WEST ? -1 : face == FarQuad.EAST ? 1 : 0);
        int nz = z + (face == FarQuad.NORTH ? -1 : face == FarQuad.SOUTH ? 1 : 0);
        if (nx < 0 || nx >= TileVoxels.SIZE || nz < 0 || nz >= TileVoxels.SIZE) {
            return outside.top(edgeOf(face), alongEdge(face, x, z)) == TileVoxels.UNKNOWN_TOP;
        }
        return !tile.known(nx, nz);
    }

    /** A face of the curtain: this rock, lit as the wall above it. */
    private long curtainKey(TileVoxels tile, FaceLooks looks, int face, int x, int y, int z, int rock, int[] tops) {
        if (rock == 0) {
            return 0;
        }
        int appearance = looks.appearance(rock, face);
        if (appearance == 0) {
            return 0;
        }
        int column = TileVoxels.column(x, z);
        int tint = looks.tint(rock, face, tile.biomes[column]) & 0xFFFFFF;
        return FACE | (long) appearance << 32 | (long) tint << 8 | surfaceLight(tile, x, z, tops[column]);
    }

    /** Whether the column's top is water, open to the sky (not frozen over or built over). */
    private boolean openWater(TileVoxels tile, int x, int z, int[] tops) {
        int top = tops[TileVoxels.column(x, z)];
        return top >= 0 && kinds[TileVoxels.index(x, top, z)] == BlockKinds.WATER;
    }

    /**
     * The light in a water voxel, for the land under it. At level 0 the engine's own; a coarse
     * voxel's light byte holds its inset instead, so there the sky light over the water's surface
     * fades a level a block down, as the engine's does.
     */
    private static int underWaterLight(TileVoxels tile, int x, int y, int z, int[] tops) {
        if (tile.level == 0) {
            return tile.light[TileVoxels.index(x, y, z)] & 0xFF;
        }
        int top = tops[TileVoxels.column(x, z)];
        int surfaceSky = surfaceLight(tile, x, z, top) >> 4;
        int sky = Math.max(0, surfaceSky - (top - y) * (1 << tile.level));
        return sky << 4;
    }

    /** The light over a column's top: what a face in the open beside it gets. */
    private static int surfaceLight(TileVoxels tile, int x, int z, int top) {
        int above = top + 1;
        if (top < 0 || above >= tile.height) {
            return 0xF0;
        }
        int light = tile.light[TileVoxels.index(x, above, z)] & 0xFF;
        return light == 0 ? 0xF0 : light;
    }

    // --- Full detail: blocks in their own shapes, and plants ---

    /**
     * The light a block drawn in its own shape (or a plant) takes: its own cell's, or the cell's
     * above when the engine kept none in it. -1 for one in a closed cave, which is left out.
     */
    private int detailLight(TileVoxels tile, int index, boolean caveCulling) {
        int light = tile.light[index] & 0xFF;
        if (light != 0 || !caveCulling) {
            return light;
        }
        int above = index + TileVoxels.COLUMNS;
        if (above < kinds.length && kinds[above] != BlockKinds.SOLID) {
            light = tile.light[above] & 0xFF;
        }
        return light != 0 ? light : -1;
    }

    /** The faces toward {@code face} of every block drawn in its own shape, box by box. */
    private void meshPartials(TileVoxels tile, EdgeTops outside, FaceLooks looks, boolean caveCulling, int[] tops, int face,
                              int lowest, int highest) {
        boolean vertical = face == FarQuad.DOWN || face == FarQuad.UP;
        int sliceCount = vertical ? highest - lowest + 1 : TileVoxels.SIZE;
        int extentA = TileVoxels.SIZE;
        ensureMask(extentA * (vertical ? TileVoxels.SIZE : highest - lowest + 1));
        sortPartialsBySlice(face, vertical, lowest, sliceCount);
        for (int slice = 0; slice < sliceCount; slice++) {
            int first = sliceStarts[slice];
            int end = sliceStarts[slice + 1];
            if (first == end) {
                continue;
            }
            int minA = Integer.MAX_VALUE;
            int maxA = -1;
            int minB = Integer.MAX_VALUE;
            int maxB = -1;
            int boxes = 0;
            for (int i = first; i < end; i++) {
                int index = bySlice[i];
                int a = cellA(index, face);
                int b = cellB(index, face, lowest);
                minA = Math.min(minA, a);
                maxA = Math.max(maxA, a);
                minB = Math.min(minB, b);
                maxB = Math.max(maxB, b);
                boxes = Math.max(boxes, looks.shape(tile.states[index]).boxCount());
            }
            for (int box = 0; box < boxes; box++) {
                boolean any = false;
                for (int i = first; i < end; i++) {
                    int index = bySlice[i];
                    FarShape shape = looks.shape(tile.states[index]);
                    if (box >= shape.boxCount()) {
                        continue;
                    }
                    int cell = cellA(index, face) + cellB(index, face, lowest) * extentA;
                    long key = boxFaceKey(tile, outside, looks, caveCulling, tops, face, index, shape, box);
                    if (key != 0) {
                        mask[cell] = key;
                        parts[cell] = boxPart(shape.boxes(), box * 6, face);
                        any = true;
                    }
                }
                if (any) {
                    mergeDetail(face, slice, vertical, lowest, extentA, minA, maxA, minB, maxB);
                }
            }
        }
    }

    /** Groups the partial voxels by their slice along {@code face}'s normal (a counting sort). */
    private void sortPartialsBySlice(int face, boolean vertical, int lowest, int sliceCount) {
        if (sliceStarts.length < sliceCount + 1) {
            sliceStarts = new int[sliceCount + 1];
        }
        if (bySlice.length < partialCount) {
            bySlice = new int[partials.length];
        }
        Arrays.fill(sliceStarts, 0, sliceCount + 1, 0);
        for (int i = 0; i < partialCount; i++) {
            sliceStarts[sliceOf(partials[i], face, vertical, lowest) + 1]++;
        }
        for (int slice = 0; slice < sliceCount; slice++) {
            sliceStarts[slice + 1] += sliceStarts[slice];
        }
        int[] next = Arrays.copyOf(sliceStarts, sliceCount);
        for (int i = 0; i < partialCount; i++) {
            int index = partials[i];
            bySlice[next[sliceOf(index, face, vertical, lowest)]++] = index;
        }
    }

    private static int sliceOf(int index, int face, boolean vertical, int lowest) {
        if (vertical) {
            return index / TileVoxels.COLUMNS - lowest;
        }
        int column = index % TileVoxels.COLUMNS;
        return face == FarQuad.NORTH || face == FarQuad.SOUTH ? column / TileVoxels.SIZE : column % TileVoxels.SIZE;
    }

    private static int cellA(int index, int face) {
        int column = index % TileVoxels.COLUMNS;
        return face == FarQuad.WEST || face == FarQuad.EAST ? column / TileVoxels.SIZE : column % TileVoxels.SIZE;
    }

    private static int cellB(int index, int face, int lowest) {
        if (face == FarQuad.DOWN || face == FarQuad.UP) {
            return index % TileVoxels.COLUMNS / TileVoxels.SIZE;
        }
        return index / TileVoxels.COLUMNS - lowest;
    }

    /** One box's face toward {@code face} as a merge key, or 0 when hidden or in a closed cave. */
    private long boxFaceKey(TileVoxels tile, EdgeTops outside, FaceLooks looks, boolean caveCulling, int[] tops, int face,
                            int index, FarShape shape, int box) {
        int light = detailLight(tile, index, caveCulling);
        if (light < 0) {
            return 0;
        }
        int column = index % TileVoxels.COLUMNS;
        int x = column % TileVoxels.SIZE;
        int z = column / TileVoxels.SIZE;
        int y = index / TileVoxels.COLUMNS;
        if (FarShape.touchesSide(shape.boxes(), box * 6, face)) {
            // On the block's own side: hidden where the neighbour covers it.
            int nx = x + (face == FarQuad.WEST ? -1 : face == FarQuad.EAST ? 1 : 0);
            int ny = y + (face == FarQuad.DOWN ? -1 : face == FarQuad.UP ? 1 : 0);
            int nz = z + (face == FarQuad.NORTH ? -1 : face == FarQuad.SOUTH ? 1 : 0);
            if (ny < 0) {
                return 0;
            }
            if (nx < 0 || nx >= TileVoxels.SIZE || nz < 0 || nz >= TileVoxels.SIZE) {
                int neighbourTop = outside.top(edgeOf(face), alongEdge(face, x, z));
                if (neighbourTop != TileVoxels.UNKNOWN_TOP && y < neighbourTop) {
                    return 0;
                }
            } else if (ny < tile.height && tile.known(nx, nz) && covers(tile, looks, TileVoxels.index(nx, ny, nz), OPPOSITE[face], false)) {
                return 0;
            }
        }
        int state = tile.states[index];
        int appearance = looks.appearance(state, face);
        if (appearance == 0) {
            return 0;
        }
        int tint = looks.tint(state, face, tile.biomes[column]) & 0xFFFFFF;
        return FACE | (long) appearance << 32 | (long) tint << 8 | light;
    }

    /** Where one box's face toward {@code face} lies within its block ({@link FarQuad#detailPart}). */
    static int boxPart(int[] boxes, int at, int face) {
        int x0 = boxes[at];
        int y0 = boxes[at + 1];
        int z0 = boxes[at + 2];
        int x1 = boxes[at + 3];
        int y1 = boxes[at + 4];
        int z1 = boxes[at + 5];
        return switch (face) {
            case FarQuad.DOWN -> FarQuad.detailPart(x0, x1, z0, z1, y0);
            case FarQuad.UP -> FarQuad.detailPart(x0, x1, z0, z1, 16 - y1);
            case FarQuad.NORTH -> FarQuad.detailPart(x0, x1, y0, y1, z0);
            case FarQuad.SOUTH -> FarQuad.detailPart(x0, x1, y0, y1, 16 - z1);
            case FarQuad.WEST -> FarQuad.detailPart(z0, z1, y0, y1, x0);
            default -> FarQuad.detailPart(z0, z1, y0, y1, 16 - x1);
        };
    }

    /** Every plant as two crossed cards. */
    private void meshCards(TileVoxels tile, FaceLooks looks, boolean caveCulling, int lowest, int highest) {
        for (int y = lowest; y <= highest; y++) {
            for (int column = 0; column < TileVoxels.COLUMNS; column++) {
                int index = column + y * TileVoxels.COLUMNS;
                if (shapes[index] != FarShape.CARDS) {
                    continue;
                }
                int light = detailLight(tile, index, caveCulling);
                int state = tile.states[index];
                int appearance = looks.appearance(state, FarQuad.CARD);
                if (light < 0 || appearance == 0) {
                    continue;
                }
                int tint = looks.tint(state, FarQuad.CARD, tile.biomes[column]) & 0xFFFFFF;
                int x = column % TileVoxels.SIZE;
                int z = column / TileVoxels.SIZE;
                emitDetail(x, y, z, FarQuad.CARD, 1, 1, light, appearance, tint, CARD_PART);
                emitDetail(x, y, z, FarQuad.CARD_CROSSED, 1, 1, light, appearance, tint, CARD_PART);
            }
        }
    }

    // --- Lights ---

    /** A light this strong (0 to 15) or more makes a point: torches, lanterns, glowstone, lava, not redstone. */
    static final int LIGHT_POINT_EMISSION = 10;
    /** At coarse levels, an air voxel lit this brightly by blocks stands for the light beside it. */
    static final int COARSE_LIGHT_LEVEL = 13;
    /** The colour of a light inferred from the block light it spreads: a warm torch light. */
    static final int WARM_LIGHT = 0xFFB46A;
    /** A cell of lights is this many voxels a side. */
    private static final int LIGHT_CELL = 4;
    private static final int MAX_LIGHT_POINTS = 512;

    /** Gathers the tile's lights into cells, a point per cell at its brightest light. */
    private void meshLights(TileVoxels tile, FaceLooks looks, int lowest, int highest) {
        int cellsAcross = TileVoxels.SIZE / LIGHT_CELL;
        int cellRows = (highest + 1) / LIGHT_CELL + 1;
        int[] best = null;
        int lastState = 0;
        int lastEmission = 0;
        // A row more than the land's: a coarse voxel lit by a torch on the tile's highest ground lies over it.
        int lightRows = Math.min(Math.min(highest + 1, tile.height - 1), FarQuad.MAX_ROWS - 1);
        for (int y = lowest; y <= lightRows; y++) {
            for (int column = 0; column < TileVoxels.COLUMNS; column++) {
                int index = column + y * TileVoxels.COLUMNS;
                int state = tile.states[index];
                if (state != lastState) {
                    lastState = state;
                    lastEmission = state == 0 ? 0 : looks.lightEmission(state);
                }
                int emission = lastEmission;
                int brightness;
                int colour;
                if (emission >= LIGHT_POINT_EMISSION) {
                    brightness = emission;
                    colour = looks.lightColour(state);
                } else if (tile.level > 0 && kinds[index] == BlockKinds.AIR && (tile.light[index] & 15) >= COARSE_LIGHT_LEVEL) {
                    brightness = (tile.light[index] & 15) - 2;
                    colour = WARM_LIGHT;
                } else {
                    continue;
                }
                if (best == null) {
                    best = new int[cellsAcross * cellsAcross * cellRows * 4];
                }
                int x = column % TileVoxels.SIZE;
                int z = column / TileVoxels.SIZE;
                int cell = ((y / LIGHT_CELL) * cellsAcross + z / LIGHT_CELL) * cellsAcross + x / LIGHT_CELL;
                int at = cell * 4;
                // Per cell: the brightest light's voxel and colour, and the lights' total strength.
                if (brightness > (best[at + 2] >>> 24)) {
                    best[at] = index;
                    best[at + 2] = brightness << 24 | colour & 0xFFFFFF;
                }
                best[at + 3] += brightness;
            }
        }
        if (best == null) {
            return;
        }
        int emitted = 0;
        for (int at = 0; at < best.length && emitted < MAX_LIGHT_POINTS; at += 4) {
            if (best[at + 3] == 0) {
                continue;
            }
            int index = best[at];
            int column = index % TileVoxels.COLUMNS;
            room();
            // A cell of many lights glows stronger, up to about four torches' worth.
            int strength = Math.min(255, best[at + 3] * 4);
            FarQuad.writeLight(output, quads * FarQuad.INTS, column % TileVoxels.SIZE, index / TileVoxels.COLUMNS, column / TileVoxels.SIZE,
                strength, best[at + 2] & 0xFFFFFF);
            quads++;
            emitted++;
        }
    }

    // --- Merging ---

    /** Greedy rectangles over the slice's mask, within rows minB to maxB; each emits one quad. */
    private void merge(int face, int slice, boolean vertical, int lowest, int extentA, int minB, int maxB) {
        for (int b = minB; b <= maxB; b++) {
            for (int a = 0; a < extentA; a++) {
                long key = mask[a + b * extentA];
                if (key == 0) {
                    continue;
                }
                int width = 1;
                while (a + width < extentA && mask[a + width + b * extentA] == key) {
                    width++;
                }
                int rows = 1;
                grow:
                while (b + rows <= maxB) {
                    int row = (b + rows) * extentA;
                    for (int i = 0; i < width; i++) {
                        if (mask[a + i + row] != key) {
                            break grow;
                        }
                    }
                    rows++;
                }
                for (int r = 0; r < rows; r++) {
                    Arrays.fill(mask, a + (b + r) * extentA, a + width + (b + r) * extentA, 0L);
                }
                emit(cellX(face, vertical, slice, a), cellY(vertical, slice, lowest, b), cellZ(face, vertical, slice, a, b), face, width, rows, key);
            }
        }
    }

    /**
     * Greedy rectangles of detail faces within a window of the slice: alike faces with the same
     * place in their blocks, grown only along an axis the place covers whole.
     */
    private void mergeDetail(int face, int slice, boolean vertical, int lowest, int extentA, int minA, int maxA, int minB, int maxB) {
        for (int b = minB; b <= maxB; b++) {
            for (int a = minA; a <= maxA; a++) {
                int cell = a + b * extentA;
                long key = mask[cell];
                if (key == 0) {
                    continue;
                }
                int part = parts[cell];
                int width = 1;
                if (FarQuad.partSpansA(part)) {
                    while (a + width <= maxA && width < FarQuad.MAX_DETAIL_EXTENT && mask[cell + width] == key && parts[cell + width] == part) {
                        width++;
                    }
                }
                int rows = 1;
                if (FarQuad.partSpansB(part)) {
                    grow:
                    while (b + rows <= maxB && rows < FarQuad.MAX_DETAIL_EXTENT) {
                        int row = cell + rows * extentA;
                        for (int i = 0; i < width; i++) {
                            if (mask[row + i] != key || parts[row + i] != part) {
                                break grow;
                            }
                        }
                        rows++;
                    }
                }
                for (int r = 0; r < rows; r++) {
                    Arrays.fill(mask, cell + r * extentA, cell + r * extentA + width, 0L);
                }
                emitDetail(cellX(face, vertical, slice, a), cellY(vertical, slice, lowest, b), cellZ(face, vertical, slice, a, b), face, width, rows,
                    (int) (key & 0xFF), (int) ((key >>> 32) & 0xFFFF), (int) ((key >>> 8) & 0xFFFFFF), part);
            }
        }
    }

    private static int cellX(int face, boolean vertical, int slice, int a) {
        return vertical || face == FarQuad.NORTH || face == FarQuad.SOUTH ? a : slice;
    }

    private static int cellY(boolean vertical, int slice, int lowest, int b) {
        return vertical ? lowest + slice : lowest + b;
    }

    private static int cellZ(int face, boolean vertical, int slice, int a, int b) {
        if (vertical) {
            return b;
        }
        return face == FarQuad.NORTH || face == FarQuad.SOUTH ? slice : a;
    }

    private void room() {
        if ((quads + 1) * FarQuad.INTS > output.length) {
            output = Arrays.copyOf(output, output.length * 2);
        }
    }

    private void emit(int x, int y, int z, int face, int width, int rows, long key) {
        room();
        int light = (int) (key & 0xFF);
        int tint = (int) ((key >>> 8) & 0xFFFFFF);
        int appearance = (int) ((key >>> 32) & 0xFFFF);
        int inset = (int) ((key >>> 48) & 0xFF);
        int depth = (int) ((key >>> 56) & 0xF);
        FarQuad.write(output, quads * FarQuad.INTS, x, y, z, face, width, rows, light, appearance, tint, inset, depth);
        quads++;
    }

    private void emitDetail(int x, int y, int z, int face, int width, int rows, int light, int appearance, int tint, int part) {
        room();
        FarQuad.writeDetail(output, quads * FarQuad.INTS, x, y, z, face, width, rows, light, appearance, tint, part);
        quads++;
    }
}
