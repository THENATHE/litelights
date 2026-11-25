package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.util.Hashing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Bricks edge style: overlays made of whole bricks, cut along a texture's mortar lines
 * ({@link BrickGrid}), so a brick wall merges into its neighbour in a stepped, running-bond edge
 * instead of an organic one.
 *
 * <p>Along an edge that crosses the courses (a wall's left or right edge), every course first
 * finishes the brick the block border cuts in two, then may reach one or more whole bricks
 * further. Along an edge that runs with the courses (above or below), the course cut by the
 * border is finished, then bricks of the next courses join it one by one, never floating: a brick
 * further in only shows where one nearer the edge does. Every texture is cut along its own grid,
 * so a brick is never drawn over the border of the face it lies on.
 *
 * <p>Neighbouring blocks must agree about a brick or course that spans the corner between them.
 * Those take their reach from the class of that corner, which both blocks share (see
 * {@link VarietyHash}); everything else takes it from the edge's wave and the source.
 *
 * <p>Each brick is drawn with the mortar around it, so it shows a whole outline against the
 * neighbour, whatever that neighbour's texture.
 *
 * <p>Blended seams (two brick sources merging into each other) share the edge out: each half of
 * the edge, and each brick or course that spans a corner, belongs to one side, picked from the
 * position alone so both blocks agree, and only that side's bricks reach across there.
 */
public final class BrickShape implements OverlayShape {
    private static final long BLEND_SEED = 0xB41C5L;
    private static final long CORNER_SALT = 0xC0A4L;

    private final BrickGrid grid;
    private final int size;
    private final boolean blend;
    private final long seed;
    /** The most bricks (or courses) a band may reach past the one the border cuts. */
    private final int deepest;
    /** The chance of reaching each further brick. */
    private final double further;
    /** Mortar is one sixteenth of a block thick; outlines reach this far from a brick. */
    private final int outline;

    /**
     * @param grid            the brick pattern to cut along, at the size of the source texture
     * @param reachSixteenths the edge reach setting: 1 to 2 lets a band reach one brick past the
     *                        cut one, 3 to 5 two, 6 and up three
     * @param roughness       0 to 1: how often a band reaches further, from a third of the time to
     *                        seven in ten
     * @param seed            varies the reach between sources; which half of a blended seam is
     *                        whose comes from the position alone
     */
    public BrickShape(BrickGrid grid, int reachSixteenths, double roughness, boolean blend, long seed) {
        this.grid = grid;
        this.size = grid.size();
        this.blend = blend;
        this.seed = Hashing.mix64(seed * 31 + EdgeStyle.BRICKS.ordinal());
        this.deepest = reachSixteenths <= 2 ? 1 : reachSixteenths <= 5 ? 2 : 3;
        this.further = 0.33 + 0.37 * Math.clamp(roughness, 0.0, 1.0);
        this.outline = Math.max(1, (int) Math.round(size / 16.0));
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isBlend() {
        return blend;
    }

    @Override
    public byte[] cover(int tile) {
        boolean[] covered = new boolean[grid.bricks().size()];
        int edgeBits = TileLayout.edges(tile);
        if (TileLayout.corner(tile) != 0) {
            return new byte[size * size];
        }
        for (int direction = 0; direction < 4; direction++) {
            if ((edgeBits & (1 << direction)) != 0) {
                coverBand(covered, direction, 0, 0, 0, 1);
            }
        }
        if (!blend) {
            for (int corner = 0; corner < 4; corner++) {
                int next = (corner + 1) % 4;
                if ((edgeBits & (1 << corner)) != 0 && (edgeBits & (1 << next)) != 0) {
                    coverCorner(covered, corner);
                }
            }
        }
        return mask(covered);
    }

    @Override
    public byte[] band(int direction, int startClass, int endClass, int wave, int classes) {
        boolean[] covered = new boolean[grid.bricks().size()];
        coverBand(covered, direction, startClass, endClass, wave, classes);
        return mask(covered);
    }

    /** The brick at the corner, when it lies wholly on this face: boxed in by two spilling edges, it joins them. */
    @Override
    public byte[] innerCorner(int corner, int cornerClass, int classes) {
        boolean[] covered = new boolean[grid.bricks().size()];
        if (!blend) {
            coverCorner(covered, corner);
        }
        return mask(covered);
    }

    // Choosing bricks

    private void coverBand(boolean[] covered, int direction, int startClass, int endClass, int wave, int classes) {
        if (direction == EdgeShape.LEFT || direction == EdgeShape.RIGHT) {
            coverAcrossCourses(covered, direction, startClass, endClass, wave, classes);
        } else {
            coverAlongCourses(covered, direction, startClass, endClass, wave, classes);
        }
    }

    /**
     * A left or right edge. Each course finishes the brick the border cuts, then reaches a number
     * of whole bricks further. A course that carries on into the block above or below takes that
     * number from the shared corner.
     */
    private void coverAcrossCourses(boolean[] covered, int direction, int startClass, int endClass, int wave, int classes) {
        boolean fromLeft = direction == EdgeShape.LEFT;
        List<List<BrickGrid.Brick>> rows = grid.rows();
        for (int index = 0; index < rows.size(); index++) {
            List<BrickGrid.Brick> row = new ArrayList<>(rows.get(index));
            if (!fromLeft) {
                Collections.reverse(row);
            }
            BrickGrid.Brick first = row.getFirst();
            if (blend && !ownsSpan(direction, first.top(), first.bottom(), first.continuesUp(), first.continuesDown(),
                startClass, endClass, wave, classes)) {
                continue;
            }
            int reach;
            if (first.continuesUp()) {
                reach = cornerReach(startClass, classes);
            } else if (first.continuesDown()) {
                reach = cornerReach(endClass, classes);
            } else {
                reach = reach(unit(seed, wave, direction, index));
            }
            int next = 0;
            if (fromLeft ? first.continuesLeft() : first.continuesRight()) {
                covered[first.id()] = true;
                next = 1;
            }
            for (int step = 0; step < reach && next + step < row.size(); step++) {
                BrickGrid.Brick brick = row.get(next + step);
                if (fromLeft ? brick.continuesRight() : brick.continuesLeft()) {
                    break;
                }
                covered[brick.id()] = true;
            }
        }
    }

    /**
     * A top or bottom edge. The course the border cuts is finished; then each further course's
     * bricks join if their own reach is that deep and a brick nearer the edge holds them up. A
     * brick that spans the corner into the next block along the edge goes by that corner's class,
     * and only ever in the first course, so both blocks always agree on it.
     */
    private void coverAlongCourses(boolean[] covered, int direction, int startClass, int endClass, int wave, int classes) {
        boolean fromTop = direction == EdgeShape.UP;
        List<List<BrickGrid.Brick>> rows = new ArrayList<>(grid.rows());
        if (!fromTop) {
            Collections.reverse(rows);
        }
        List<BrickGrid.Brick> nearer = List.of();
        int depth = 0;
        for (List<BrickGrid.Brick> row : rows) {
            BrickGrid.Brick sample = row.getFirst();
            boolean cut = fromTop ? sample.continuesUp() : sample.continuesDown();
            boolean beyond = fromTop ? sample.continuesDown() : sample.continuesUp();
            if (!cut) {
                depth++;
            }
            if (depth > deepest || beyond) {
                break;
            }
            List<BrickGrid.Brick> joined = new ArrayList<>();
            for (BrickGrid.Brick brick : row) {
                if (blend && !ownsSpan(direction, brick.left(), brick.right(), brick.continuesLeft(), brick.continuesRight(),
                    startClass, endClass, wave, classes)) {
                    continue;
                }
                if (!cut && !joins(brick, depth, nearer, direction, startClass, endClass, wave, classes)) {
                    continue;
                }
                covered[brick.id()] = true;
                joined.add(brick);
            }
            if (joined.isEmpty() && !cut) {
                break;
            }
            nearer = joined;
        }
    }

    private boolean joins(BrickGrid.Brick brick, int depth, List<BrickGrid.Brick> nearer, int direction, int startClass,
                          int endClass, int wave, int classes) {
        int reach;
        if (brick.continuesLeft() || brick.continuesRight()) {
            if (depth > 1) {
                return false;
            }
            reach = cornerReach(brick.continuesLeft() ? startClass : endClass, classes);
        } else {
            reach = reach(unit(seed, wave, direction, depth, brick.left()));
        }
        if (reach < depth) {
            return false;
        }
        if (depth == 1) {
            return true;
        }
        for (BrickGrid.Brick below : nearer) {
            if (below.overlapsColumns(brick.left(), brick.right())) {
                return true;
            }
        }
        return false;
    }

    private void coverCorner(boolean[] covered, int corner) {
        int x = corner == EdgeShape.BOTTOM_LEFT || corner == EdgeShape.TOP_LEFT ? 0 : size - 1;
        int y = corner == EdgeShape.TOP_LEFT || corner == EdgeShape.TOP_RIGHT ? 0 : size - 1;
        BrickGrid.Brick brick = grid.brickAt(x, y);
        for (int direction = 0; direction < 4; direction++) {
            boolean spilling = direction == corner || direction == (corner + 1) % 4;
            if (!spilling && continues(brick, direction)) {
                return;
            }
        }
        covered[brick.id()] = true;
    }

    private static boolean continues(BrickGrid.Brick brick, int direction) {
        return switch (direction) {
            case EdgeShape.LEFT -> brick.continuesLeft();
            case EdgeShape.DOWN -> brick.continuesDown();
            case EdgeShape.RIGHT -> brick.continuesRight();
            default -> brick.continuesUp();
        };
    }

    /** How many bricks further a band reaches, from a number in [0, 1): each further brick is less likely. */
    private int reach(double chance) {
        int reach = 0;
        double threshold = further;
        while (reach < deepest && chance < threshold) {
            reach++;
            threshold *= further;
        }
        return reach;
    }

    /** The reach of a brick or course that spans a block corner, the same for every block around that corner. */
    private int cornerReach(int cornerClass, int classes) {
        return reach(unit(seed, CORNER_SALT, classes, cornerClass));
    }

    /**
     * In a blended seam, whether this side owns a stretch of the edge. Each half of the edge has an
     * owner picked from what both sides share (the axis, the corner classes and the wave); a
     * stretch carrying on past a corner goes by that corner's class instead.
     */
    private boolean ownsSpan(int direction, int from, int to, boolean pastStart, boolean pastEnd, int startClass, int endClass,
                             int wave, int classes) {
        long side = direction == EdgeShape.LEFT || direction == EdgeShape.DOWN ? 0 : 1;
        if (pastStart || pastEnd) {
            int cornerClass = pastStart ? startClass : endClass;
            return (Hashing.mix64(BLEND_SEED * 7 + classes * 131L + cornerClass) & 1) == side;
        }
        for (int half = 0; half < 2; half++) {
            int halfFrom = half * size / 2;
            int halfTo = (half + 1) * size / 2 - 1;
            if (to < halfFrom || from > halfTo) {
                continue;
            }
            long owner = Hashing.mix64(BLEND_SEED + direction % 2 * 1_000_003L + startClass * 7919L + endClass * 104_729L
                + wave * 15_485_863L + half) & 1;
            if (owner != side) {
                return false;
            }
        }
        return true;
    }

    // Drawing the mask

    /**
     * Every covered brick and the mortar that outlines it, as {@link EdgeShape#FROM_SIDE} texels so
     * the source texture is laid on the face as it is: a brick texture tiles both ways, so its
     * bricks carry on across the border.
     */
    private byte[] mask(boolean[] covered) {
        int count = size * size;
        boolean[] coveredBody = new boolean[count];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                coveredBody[y * size + x] = !grid.isMortar(x, y) && covered[grid.brickAt(x, y).id()];
            }
        }
        boolean[] outlined = dilate(coveredBody);
        byte[] mask = new byte[count];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                if (covered[grid.brickAt(x, y).id()] || grid.isMortar(x, y) && outlined[index]) {
                    mask[index] = EdgeShape.FROM_SIDE;
                }
            }
        }
        return mask;
    }

    /** Texels within the mortar's thickness of a set texel (a square neighbourhood), inside the tile. */
    private boolean[] dilate(boolean[] set) {
        boolean[] across = new boolean[set.length];
        for (int y = 0; y < size; y++) {
            int[] running = new int[size + 1];
            for (int x = 0; x < size; x++) {
                running[x + 1] = running[x] + (set[y * size + x] ? 1 : 0);
            }
            for (int x = 0; x < size; x++) {
                across[y * size + x] = running[Math.min(size, x + outline + 1)] - running[Math.max(0, x - outline)] > 0;
            }
        }
        boolean[] near = new boolean[set.length];
        for (int x = 0; x < size; x++) {
            int[] running = new int[size + 1];
            for (int y = 0; y < size; y++) {
                running[y + 1] = running[y] + (across[y * size + x] ? 1 : 0);
            }
            for (int y = 0; y < size; y++) {
                near[y * size + x] = running[Math.min(size, y + outline + 1)] - running[Math.max(0, y - outline)] > 0;
            }
        }
        return near;
    }

    private static double unit(long... parts) {
        long h = 0x2545F4914F6CDD1DL;
        for (long part : parts) {
            h = Hashing.mix64(h ^ part);
        }
        return (h >>> 11) * 0x1.0p-53;
    }
}
