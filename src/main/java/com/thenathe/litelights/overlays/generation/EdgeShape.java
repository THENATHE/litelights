package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.util.Hashing;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Decides, texel by texel, which part of an overlay tile is covered.
 *
 * <p>Every edge is a band whose depth wanders along the edge. Three rules keep tiles joining up
 * wherever they're drawn side by side, on one face or across a top-to-side fold:
 * <ul>
 *   <li>each edge's wander is pinned at both ends to the depth of the block corner it ends at, so
 *       two blocks that share a corner meet there without a step;</li>
 *   <li>inner corners (two edges meeting on one face) are shaped around that same corner depth;</li>
 *   <li>grain and dither come from the texel's position in the tile, never from the tile number,
 *       so two pieces sharing an edge agree pixel for pixel.</li>
 * </ul>
 *
 * <p>Shapes come in two forms. {@link #cover(int)} gives the 17 tiles of Continuity's plain
 * {@code method=overlay}, where every corner has the same depth and every edge the same wave. The
 * variety pieces ({@link #band}, {@link #innerCorner}) break that rhythm:
 * each block corner in the world gets one of a few depth classes and each edge one of a few waves,
 * picked by position when the overlay is drawn. Because a corner's class belongs to the corner, not
 * to either block, neighbours still meet at the same depth.
 *
 * <p>Depths are worked out in texels at the texture's own size from a reach given in sixteenths of
 * a block, so every resolution gets the same shape at its own pixel scale.
 *
 * <p>There are no outer corner pieces. A block whose only softer neighbour touches it at a corner,
 * with neither edge beside that corner spilling, gets nothing: the user found those lone diagonal
 * corners odd, so 0.1.7 dropped them (see DECISIONS, overlays17). Continuity's four corner-only
 * tiles are left empty for the same reason.
 */
public final class EdgeShape implements OverlayShape {
    /** A texel of a mask: empty. */
    public static final byte EMPTY = 0;
    /** The texel is covered and belongs to a left or right edge. */
    public static final byte FROM_SIDE = 1;
    /** The texel is covered and belongs to a top or bottom edge. */
    public static final byte FROM_END = 2;

    /** The styles this class draws. {@link EdgeStyle#BRICKS} follows a texture's mortar lines instead; see {@link BrickShape}. */
    public static final List<EdgeStyle> STYLES = List.of(EdgeStyle.ORGANIC, EdgeStyle.JAGGED, EdgeStyle.CLEAN, EdgeStyle.CLUMPS,
        EdgeStyle.DITHERED);

    /** Edges, in the order Continuity lists a face's neighbours: left, down, right, up. */
    public static final int LEFT = 0;
    public static final int DOWN = 1;
    public static final int RIGHT = 2;
    public static final int UP = 3;

    /** Corners. Corner {@code c} sits between edge {@code c} and edge {@code (c + 1) % 4}. */
    public static final int BOTTOM_LEFT = 0;
    public static final int BOTTOM_RIGHT = 1;
    public static final int TOP_RIGHT = 2;
    public static final int TOP_LEFT = 3;

    /** The corner each edge's profile starts at (texel 0 along it) and the one it ends at. */
    public static final int[] EDGE_START = {TOP_LEFT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT};
    public static final int[] EDGE_END = {BOTTOM_LEFT, BOTTOM_RIGHT, BOTTOM_RIGHT, TOP_RIGHT};

    private static final int[] BAYER_4 = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final double NONE = 1e9;
    private static final long BLEND_SEED = 0x5EA4L;

    private final EdgeStyle style;
    private final int size;
    private final double reach;
    private final double roughness;
    private final boolean blend;
    private final long seed;

    private final double endDepth;
    private final double filletRadius;
    private final double grain;
    /** Strength of the lumpy 2D noise that gives organic edges their lobes and bays. */
    private final double lumpiness;
    private final double waveAmplitude;
    private final int harmonics;
    /** For the Clean style: how likely a loose speck is right at the edge, before it thins out. */
    private final double debrisDensity;
    /** For the Clean style: how far past the rim its hard-pixel dither reaches, in texels. */
    private final double ditherWidth;
    /**
     * Strength of the fine noise that only textures of 32x and up get, in sixteenths of a block per
     * octave; see {@link #detail}.
     */
    private final double detailStrength;

    /**
     * One edge of a piece: its depth at each texel along it, and what varies it.
     *
     * @param pinned whether the wander fades fully out before the ends, so the end texels sit at
     *               exactly the corner depth; variety pieces need that, because the piece next door
     *               may wander differently
     */
    private record Edge(double[] depth, double[][] clumps, long noiseSeed, boolean pinned) {
    }

    /**
     * Everything one mask is made of.
     *
     * @param innerDepths per corner, the depth of an inner-corner fill there, or {@code NaN} for none
     */
    private record Piece(Edge[] edges, double[] innerDepths, long noiseSeed) {
    }

    /**
     * @param size            texture width in texels
     * @param reachSixteenths how far the band reaches, in sixteenths of a block
     * @param roughness       0 for a calm edge, 1 for a wild one
     * @param blend           draw this source's half of a blended seam rather than a full overlay
     * @param seed            varies the wander between source blocks; blended seams ignore it so both
     *                        halves of a seam interlock
     */
    public EdgeShape(EdgeStyle style, int size, int reachSixteenths, double roughness, boolean blend, long seed) {
        this.style = style;
        this.size = size;
        this.reach = Math.max(1.0, reachSixteenths * size / 16.0);
        this.roughness = Math.clamp(roughness, 0.0, 1.0);
        this.blend = blend;
        this.seed = blend ? BLEND_SEED : seed * 31 + style.ordinal();

        if (blend) {
            endDepth = 0;
            filletRadius = 0;
            grain = 0;
            lumpiness = reach * 0.4;
            waveAmplitude = 0;
            harmonics = 0;
            debrisDensity = 0;
            ditherWidth = 0;
            detailStrength = 0;
            return;
        }
        switch (style) {
            case ORGANIC -> {
                endDepth = reach;
                filletRadius = reach * 0.7;
                grain = 1.2 * this.roughness;
                lumpiness = reach * 0.5 * (0.4 + this.roughness);
                waveAmplitude = reach * (0.3 + 0.7 * this.roughness);
                harmonics = 4;
                debrisDensity = 0;
                ditherWidth = 0;
                detailStrength = 0.9 * (0.3 + this.roughness);
            }
            case JAGGED -> {
                endDepth = Math.max(1, Math.round(reach));
                filletRadius = 0;
                grain = 0;
                lumpiness = 0;
                waveAmplitude = 0;
                harmonics = 0;
                debrisDensity = 0;
                ditherWidth = 0;
                // Jagged is a pixel-art staircase on purpose, so it gets no fine noise.
                detailStrength = 0;
            }
            case CLEAN -> {
                // A solid, chunky rim that wanders a little, then a short, dense dither that hugs
                // it, and only the odd loose speck beyond.
                endDepth = Math.max(1.0, reach * 0.85);
                filletRadius = endDepth * 0.8;
                grain = 0;
                lumpiness = endDepth * 0.3 * (0.4 + this.roughness);
                waveAmplitude = endDepth * 0.35 * (0.4 + this.roughness);
                harmonics = 5;
                debrisDensity = 0.06 * (0.4 + this.roughness);
                ditherWidth = Math.max(texelsPerStep(), endDepth * 0.55);
                detailStrength = 0.5 * (0.3 + this.roughness);
            }
            case CLUMPS -> {
                // The band must always be there, at least one sixteenth of a block deep, with the
                // clumps growing out of it.
                endDepth = Math.max(1.5 * texelsPerStep(), reach * 0.5);
                filletRadius = endDepth;
                grain = 0.25 * this.roughness;
                lumpiness = 0;
                waveAmplitude = endDepth * 0.25 * this.roughness;
                harmonics = 3;
                debrisDensity = 0;
                ditherWidth = 0;
                detailStrength = 0.45;
            }
            case DITHERED -> {
                endDepth = reach;
                filletRadius = reach * 0.6;
                grain = 0;
                lumpiness = reach * 0.25 * this.roughness;
                waveAmplitude = reach * 0.35 * this.roughness;
                harmonics = 3;
                debrisDensity = 0;
                ditherWidth = 0;
                detailStrength = 0.6 * this.roughness;
            }
            default -> throw new IllegalStateException("Unknown style " + style);
        }
    }

    @Override
    public boolean isBlend() {
        return blend;
    }

    @Override
    public int size() {
        return size;
    }

    /**
     * How deep the band is at a block corner of the given class. With one class every corner has
     * the plain depth; with more, the classes spread around it.
     */
    public double cornerDepth(int cornerClass, int classes) {
        if (blend) {
            return 0;
        }
        double factor = classes <= 1 ? 1.0 : 0.72 + 0.56 * cornerClass / (classes - 1);
        double depth = endDepth * factor;
        if (style == EdgeStyle.JAGGED) {
            double step = texelsPerStep();
            return Math.max(step, Math.round(depth / step) * step);
        }
        return depth;
    }

    // The plain 17-tile set

    /**
     * Covers {@code tile} of a plain overlay set: one {@link #EMPTY}, {@link #FROM_SIDE} or
     * {@link #FROM_END} per texel. The corner-only tiles come out empty.
     */
    @Override
    public byte[] cover(int tile) {
        int edgeBits = TileLayout.edges(tile);
        if (TileLayout.corner(tile) != 0) {
            return new byte[size * size];
        }
        Edge[] edges = new Edge[4];
        for (int direction = 0; direction < 4; direction++) {
            if ((edgeBits & (1 << direction)) != 0) {
                edges[direction] = blend
                    ? edge(direction, 0, 0, BLEND_SEED * 2 + direction % 2, BLEND_SEED, false)
                    : edge(direction, endDepth, endDepth, seed * 4 + direction, seed, false);
            }
        }
        double[] inner = noInnerCorners();
        if (!blend) {
            for (int corner = 0; corner < 4; corner++) {
                if (edges[corner] != null && edges[(corner + 1) % 4] != null) {
                    inner[corner] = endDepth;
                }
            }
        }
        return draw(new Piece(edges, inner, seed));
    }

    // Variety pieces

    /**
     * One edge's band, for a face whose corners at the start and end of that edge have the given
     * classes, using one of the edge's waves.
     */
    @Override
    public byte[] band(int direction, int startClass, int endClass, int wave, int classes) {
        Edge[] edges = new Edge[4];
        long profileSeed = variantSeed(direction, startClass, endClass, wave);
        double start = blend ? blendOffset(startClass, classes) : cornerDepth(startClass, classes);
        double end = blend ? blendOffset(endClass, classes) : cornerDepth(endClass, classes);
        edges[direction] = edge(direction, start, end, profileSeed, mix(profileSeed, 77), true);
        return draw(new Piece(edges, noInnerCorners(), mix(profileSeed, 78)));
    }

    /** The rounded fill where two bands meet inside a face, at a corner of the given class. */
    @Override
    public byte[] innerCorner(int corner, int cornerClass, int classes) {
        if (blend) {
            return new byte[size * size];
        }
        double[] inner = noInnerCorners();
        inner[corner] = cornerDepth(cornerClass, classes);
        return draw(new Piece(new Edge[4], inner, mix(seed, 500 + corner * 8L + cornerClass)));
    }

    /**
     * Blended seams share one wave between the two halves, with opposite signs, so the seed may
     * depend only on the axis and the corner classes. Full overlays also vary by source and edge.
     */
    private long variantSeed(int direction, int startClass, int endClass, int wave) {
        if (blend) {
            return mix(BLEND_SEED, 1000 + direction % 2, startClass, endClass, wave);
        }
        return mix(seed, direction, startClass, endClass, wave);
    }

    private static double[] noInnerCorners() {
        double[] inner = new double[4];
        Arrays.fill(inner, Double.NaN);
        return inner;
    }

    // Drawing a mask

    private byte[] draw(Piece piece) {
        byte[] mask = new byte[size * size];
        double[] fields = new double[size * size];
        byte[] kinds = new byte[size * size];
        // Filled per texel by coverTexel. Kept here rather than made per texel: at 128x and up,
        // two small arrays per texel were most of a build's allocation (gigabytes for a large pack).
        double[] distance = new double[4];
        int[] along = new int[4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                coverTexel(piece, x, y, fields, kinds, index, distance, along);
                if (fields[index] < NONE / 2 && isCovered(fields[index], x, y, piece.noiseSeed())) {
                    mask[index] = kinds[index];
                }
            }
        }
        boolean hasInnerOnly = Arrays.stream(piece.edges()).allMatch(edge -> edge == null);
        if (debrisDensity > 0 && !hasInnerOnly) {
            scatterDebris(mask, fields, kinds, piece.noiseSeed());
        }
        return mask;
    }

    /**
     * Works out a texel's field (negative inside the cover) and which edge it belongs to.
     * {@code distance} and {@code along} are scratch space, overwritten here: the texel's distance
     * from each edge (left, down, right, up) and its position along that edge.
     */
    private void coverTexel(Piece piece, int x, int y, double[] fields, byte[] kinds, int index, double[] distance,
                            int[] along) {
        distance[LEFT] = x + 0.5;
        distance[DOWN] = size - y - 0.5;
        distance[RIGHT] = size - x - 0.5;
        distance[UP] = y + 0.5;
        along[LEFT] = y;
        along[DOWN] = x;
        along[RIGHT] = y;
        along[UP] = x;

        double field = NONE;
        int nearest = -1;
        Edge[] edges = piece.edges();
        for (int direction = 0; direction < 4; direction++) {
            Edge edge = edges[direction];
            if (edge == null) {
                continue;
            }
            double reachHere = edge.depth()[along[direction]] - lumps(x, y, along[direction], edge)
                - detail(along[direction] + 0.5, edge.noiseSeed() + 31) * edgeFade(along[direction], edge.pinned());
            if (keepsSolidBand()) {
                reachHere = Math.max(minimumDepth(), reachHere);
            }
            double edgeField = distance[direction] - reachHere;
            edgeField = Math.min(edgeField, clumpField(edge, distance[direction], along[direction] + 0.5));
            field = Math.min(field, edgeField);
            if (nearest < 0 || distance[direction] < distance[nearest]) {
                nearest = direction;
            }
        }
        for (int corner = 0; corner < 4; corner++) {
            double depth = piece.innerDepths()[corner];
            if (!Double.isNaN(depth)) {
                field = Math.min(field, innerCorner(depth, distance[corner], distance[(corner + 1) % 4]));
                if (nearest < 0) {
                    nearest = distance[corner] < distance[(corner + 1) % 4] ? corner : (corner + 1) % 4;
                }
            }
        }
        fields[index] = field;
        kinds[index] = nearest == LEFT || nearest == RIGHT ? FROM_SIDE : FROM_END;
    }

    private boolean isCovered(double field, int x, int y, long noiseSeed) {
        if (blend) {
            // Soften the finger tips a little with the ordered dither.
            return field + (bayer(x, y) - 0.5) * 1.8 < 0;
        }
        return switch (style) {
            case DITHERED -> {
                double coverage = Math.clamp(0.5 - field / (reach * 1.4), 0.0, 1.0);
                yield coverage > bayer(x, y);
            }
            case ORGANIC -> field + (hash(x, y, 1, noiseSeed) - 0.5) * grain < 0 || isSpeck(field, x, y, noiseSeed);
            case CLEAN -> field < 0 || isRimDither(field, x, y);
            default -> field + (hash(x, y, 1, noiseSeed) - 0.5) * grain < 0;
        };
    }

    /**
     * Clean's falloff: a short band past the rim, filled densely next to it and thinning out, in an
     * ordered dither of hard pixels. The dither grain is a thirty-second of a block, so it stays a
     * pixel pattern at 16x and 32x and becomes small square grains at 64x and up.
     */
    private boolean isRimDither(double field, int x, int y) {
        if (field >= ditherWidth) {
            return false;
        }
        int grainSize = Math.max(1, size / 32);
        double coverage = 1 - field / ditherWidth;
        return bayer(x / grainSize, y / grainSize) < coverage * 0.85;
    }

    /** A few loose texels just past an organic edge, like stray blades. */
    private boolean isSpeck(double field, int x, int y, long noiseSeed) {
        return field > 0 && field < 1.8 && hash(x, y, 2, noiseSeed) < 0.12 * roughness;
    }

    /**
     * Dust and small debris past a Clean edge, thinning out with distance. The specks sit on a grid
     * one sixteenth of a block across, so a 16x pack gets single pixels and a 128x pack gets small
     * round clumps of the same size in the world. A speck is never cut by the tile's border.
     */
    private void scatterDebris(byte[] mask, double[] fields, byte[] kinds, long noiseSeed) {
        int cell = Math.max(1, size / 16);
        int cells = size / cell;
        double falloff = Math.max(0.6, endDepth * 0.5);
        double furthest = endDepth * 1.5 + cell;
        for (int cellY = 0; cellY < cells; cellY++) {
            for (int cellX = 0; cellX < cells; cellX++) {
                double centreX = cellX * cell + cell / 2.0;
                double centreY = cellY * cell + cell / 2.0;
                int centreIndex = (int) centreY * size + (int) centreX;
                double field = fields[centreIndex];
                if (field >= NONE / 2 || field > furthest) {
                    continue;
                }
                double chance = debrisDensity * Math.exp(-Math.max(field, 0) / falloff);
                if (hash(cellX, cellY, 11, noiseSeed) >= chance) {
                    continue;
                }
                double radius = cell == 1 ? 0.5 : cell * (0.45 + 0.3 * hash(cellX, cellY, 12, noiseSeed));
                if (centreX - radius < 0 || centreY - radius < 0 || centreX + radius > size || centreY + radius > size) {
                    continue;
                }
                stamp(mask, kinds, centreX, centreY, radius);
                // Some specks are two grains side by side, so the dust doesn't read as a single-pixel pattern.
                double pair = hash(cellX, cellY, 13, noiseSeed);
                if (pair < 0.4) {
                    double offset = cell * (pair < 0.2 ? 1 : -1);
                    boolean sideways = hash(cellX, cellY, 14, noiseSeed) < 0.5;
                    double pairX = centreX + (sideways ? offset : 0);
                    double pairY = centreY + (sideways ? 0 : offset);
                    if (pairX - radius >= 0 && pairY - radius >= 0 && pairX + radius <= size && pairY + radius <= size) {
                        stamp(mask, kinds, pairX, pairY, radius * 0.8);
                    }
                }
            }
        }
    }

    private void stamp(byte[] mask, byte[] kinds, double centreX, double centreY, double radius) {
        int fromX = Math.max(0, (int) Math.floor(centreX - radius));
        int toX = Math.min(size - 1, (int) Math.ceil(centreX + radius));
        int fromY = Math.max(0, (int) Math.floor(centreY - radius));
        int toY = Math.min(size - 1, (int) Math.ceil(centreY + radius));
        for (int y = fromY; y <= toY; y++) {
            for (int x = fromX; x <= toX; x++) {
                int index = y * size + x;
                if (mask[index] == EMPTY && Math.hypot(x + 0.5 - centreX, y + 0.5 - centreY) <= radius) {
                    mask[index] = kinds[index];
                }
            }
        }
    }

    /** Fills the inside corner where two covered edges meet, rounded for the softer styles. */
    private double innerCorner(double depth, double a, double b) {
        double radius = scaledRadius(filletRadius, depth);
        double centre = depth + radius;
        if (a < centre && b < centre) {
            return radius - Math.hypot(centre - a, centre - b);
        }
        return NONE;
    }

    /** Corner rounding grows and shrinks with the corner's depth, so deep and shallow corners keep their look. */
    private double scaledRadius(double radius, double depth) {
        return endDepth > 0 ? radius * depth / endDepth : radius;
    }

    /**
     * Smooth 2D noise that repeats every tile, faded out towards the ends of the edge so the
     * pinned corner depth still holds there.
     */
    private double lumps(int x, int y, int along, Edge edge) {
        if (lumpiness == 0) {
            return 0;
        }
        double fade = edge.pinned() ? endTaper(along) : Math.sqrt(Math.sin(Math.PI * (along + 0.5) / size));
        return lumpiness * fade * (valueNoise(x + 0.5, y + 0.5, 4, edge.noiseSeed()) * 2 - 1);
    }

    /**
     * Fine noise for textures of 32x and up, so a big texture's edge has irregularity at its own
     * texel scale instead of looking like a smooth, enlarged 16x curve. Each octave is half the size
     * of the last, starting at a sixteenth of a block and ending at a single texel; each is as
     * strong as its own cell size, so the result looks alike at every resolution, just finer. 8x
     * and 16x edges are left exactly as they were.
     *
     * <p>The noise runs along the edge only ({@code position} is texels along it), never across:
     * a depth that changes along the edge can't break off islands, so the edge stays one piece.
     */
    private double detail(double position, long noiseSeed) {
        if (detailStrength == 0 || size < 32) {
            return 0;
        }
        double total = 0;
        for (int cells = 16; size / cells >= 1; cells *= 2) {
            double cellTexels = (double) size / cells;
            total += detailStrength * cellTexels * (valueNoise(position, 0.5, cells, noiseSeed + cells) * 2 - 1);
        }
        return total;
    }

    /** How much of the fine noise an edge carries at a texel along it: none at the pinned ends. */
    private double edgeFade(int along, boolean pinned) {
        return pinned ? endTaper(along) : Math.sqrt(Math.sin(Math.PI * (along + 0.5) / size));
    }

    /** 0 at both ends of an edge, rising smoothly to 1 a fifth of the way in. */
    private double endTaper(int along) {
        double fromEnd = Math.min(along, size - 1 - along) / Math.max(1.0, size * 0.2);
        return smooth(Math.clamp(fromEnd, 0.0, 1.0));
    }

    /** Smooth noise on a grid of {@code cells} by {@code cells} that wraps around the tile. */
    private double valueNoise(double x, double y, int cells, long noiseSeed) {
        double cell = size / (double) cells;
        double gx = x / cell;
        double gy = y / cell;
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        double fx = smooth(gx - x0);
        double fy = smooth(gy - y0);
        double top = lerp(lattice(x0, y0, cells, noiseSeed), lattice(x0 + 1, y0, cells, noiseSeed), fx);
        double bottom = lerp(lattice(x0, y0 + 1, cells, noiseSeed), lattice(x0 + 1, y0 + 1, cells, noiseSeed), fx);
        return lerp(top, bottom, fy);
    }

    private double lattice(int x, int y, int cells, long noiseSeed) {
        return hash(Math.floorMod(x, cells), Math.floorMod(y, cells), 7, noiseSeed);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private double clumpField(Edge edge, double distance, double along) {
        double[][] list = edge.clumps();
        if (list == null) {
            return NONE;
        }
        double field = NONE;
        for (double[] clump : list) {
            field = Math.min(field, Math.hypot(distance - clump[0], along - clump[1]) - clump[2]);
        }
        return field;
    }

    // Edge profiles

    /**
     * Builds one edge, from {@code startDepth} at texel 0 along it to {@code finishDepth} at the far
     * end, with a wander drawn from {@code profileSeed}.
     */
    private Edge edge(int direction, double startDepth, double finishDepth, long profileSeed, long noiseSeed, boolean pinned) {
        if (blend) {
            return new Edge(blendProfile(direction, profileSeed, startDepth, finishDepth, pinned), null, noiseSeed, pinned);
        }
        double[] depth = switch (style) {
            case JAGGED -> toothProfile(startDepth, finishDepth, profileSeed);
            case CLEAN, ORGANIC, CLUMPS, DITHERED -> waveProfile(startDepth, finishDepth, profileSeed, pinned);
            case BRICKS -> throw new IllegalStateException("Bricks is drawn by BrickShape");
        };
        double[][] clumps = style == EdgeStyle.CLUMPS ? clumps(profileSeed + 101, depth) : null;
        return new Edge(depth, clumps, noiseSeed, pinned);
    }

    /** A sum of half-sine waves, which is zero at both ends of the edge, so the depth is pinned there. */
    private double[] waveProfile(double startDepth, double finishDepth, long profileSeed, boolean pinned) {
        SplittableRandom random = new SplittableRandom(profileSeed);
        double[] weights = new double[harmonics];
        double total = 0;
        for (int k = 0; k < harmonics; k++) {
            // The first harmonic is a single bulge per block, which makes the block grid show,
            // so it's kept faint.
            double strength = k == 0 ? 0.3 : 1.0 / (k + 0.5);
            weights[k] = (random.nextDouble() * 2 - 1) * strength;
            total += Math.abs(weights[k]);
        }
        double[] profile = new double[size];
        for (int t = 0; t < size; t++) {
            double wave = 0;
            for (int k = 0; k < harmonics; k++) {
                wave += weights[k] * Math.sin(Math.PI * (k + 1) * (t + 0.5) / size);
            }
            double normalised = total > 0 ? wave / total : 0;
            if (pinned) {
                normalised *= endTaper(t);
            }
            double base = lerp(startDepth, finishDepth, (t + 0.5) / size);
            profile[t] = Math.max(minimumDepth(), base + waveAmplitude * normalised);
        }
        return profile;
    }

    /**
     * A stepped fringe like the bottom of vanilla's grass side overlay: each texel column is a
     * whole number of texels deep, wandering one step at a time. The walk is bent back to the
     * pinned depth at both end texels, so neighbouring blocks meet without a step.
     */
    private double[] toothProfile(double startDepth, double finishDepth, long profileSeed) {
        double amplitude = Math.max(1.0, reach * (0.4 + 0.6 * roughness));
        SplittableRandom random = new SplittableRandom(profileSeed);
        double[] walk = new double[size];
        for (int t = 1; t < size; t++) {
            double step = random.nextDouble();
            walk[t] = walk[t - 1] + (step < 0.36 ? -1 : step < 0.64 ? 0 : 1);
        }
        double peak = 0;
        double[] bridged = new double[size];
        for (int t = 0; t < size; t++) {
            bridged[t] = walk[t] - walk[size - 1] * t / (size - 1.0);
            peak = Math.max(peak, Math.abs(bridged[t]));
        }
        double scale = peak > 0 ? amplitude / peak * (size / 16.0 >= 1 ? 1 : 0.8) : 0;
        double step = texelsPerStep();
        double[] profile = new double[size];
        for (int t = 0; t < size; t++) {
            double base = lerp(startDepth, finishDepth, t / (size - 1.0));
            profile[t] = Math.max(1, Math.round((base + bridged[t] * scale) / step) * step);
        }
        return profile;
    }

    /**
     * Round clumps growing out of the band: each one's centre sits close to the band's own depth
     * where it is, so it always overlaps the band and never floats. They're kept away from the tile
     * ends so none is cut off by a neighbour, and sized in blocks, so every resolution gets the
     * same clumps, each at its own pixel scale.
     */
    private double[][] clumps(long clumpSeed, double[] depth) {
        double scale = size / 16.0;
        int count = 5;
        SplittableRandom random = new SplittableRandom(clumpSeed);
        double[][] list = new double[count][];
        for (int i = 0; i < count; i++) {
            double radius = Math.max(0.75, (1.0 + random.nextDouble() * 1.2 * (0.4 + roughness)) * scale);
            double along = size * (0.14 + 0.72 * random.nextDouble());
            double bandDepth = depth[Math.clamp((int) along, 0, size - 1)];
            double inward = bandDepth + radius * (0.6 * random.nextDouble() - 0.2);
            list[i] = new double[] {inward, along, radius};
        }
        return list;
    }

    /**
     * Where a blended seam crosses a block corner of the given class: on the line itself with one
     * class, otherwise a little to one side or the other, so the seam doesn't pass through the same
     * point at every block. Positive means the left or lower block's texture reaches across.
     */
    private double blendOffset(int cornerClass, int classes) {
        if (classes <= 1) {
            return 0;
        }
        return reach * 0.45 * (2.0 * cornerClass / (classes - 1) - 1);
    }

    /**
     * Interlocking fingers for a blended seam. Opposite edges get mirror-image waves, so where one
     * block's texture reaches across the seam the other block's doesn't, and the two halves fit
     * together like a zip. The wave comes to zero at both corners, where the seam sits at the
     * corners' offsets.
     */
    private double[] blendProfile(int direction, long waveSeed, double startOffset, double endOffset, boolean pinned) {
        double amplitude = reach * (0.7 + 0.4 * roughness);
        SplittableRandom random = new SplittableRandom(waveSeed);
        int blendHarmonics = 3;
        double[] weights = new double[blendHarmonics];
        double total = 0;
        for (int k = 0; k < blendHarmonics; k++) {
            weights[k] = (random.nextDouble() * 2 - 1) / Math.sqrt(k + 1);
            total += Math.abs(weights[k]);
        }
        double[] wave = new double[size];
        double peak = 0;
        for (int t = 0; t < size; t++) {
            double value = 0;
            for (int k = 0; k < blendHarmonics; k++) {
                value += weights[k] * Math.sin(Math.PI * (k + 1) * (t + 0.5) / size);
            }
            wave[t] = total > 0 ? value / total : 0;
            peak = Math.max(peak, Math.abs(wave[t]));
        }
        // Left and right share one wave with opposite signs, and so do down and up. A square root
        // flattens the crests, so fingers come out broad and round-tipped rather than pointed.
        double sign = direction == LEFT || direction == DOWN ? 1 : -1;
        double[] profile = new double[size];
        for (int t = 0; t < size; t++) {
            double value = peak > 0 ? wave[t] / peak : 0;
            double finger = Math.signum(value) * Math.sqrt(Math.abs(value)) * amplitude;
            if (pinned) {
                finger *= endTaper(t);
            }
            double offset = lerp(startOffset, endOffset, (t + 0.5) / size);
            profile[t] = sign * (finger + offset);
        }
        return profile;
    }

    /** Clumps and Clean always keep a body along the edge; noise may never cut through it. */
    private boolean keepsSolidBand() {
        return style == EdgeStyle.CLUMPS || style == EdgeStyle.CLEAN;
    }

    /** How thin a band may get: a whole sixteenth of a block for the styles with a solid band. */
    private double minimumDepth() {
        return keepsSolidBand() ? texelsPerStep() : 0.6;
    }

    private double texelsPerStep() {
        return Math.max(1.0, size / 16.0);
    }

    private static double bayer(int x, int y) {
        return (BAYER_4[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
    }

    private static double hash(int x, int y, int channel, long noiseSeed) {
        long h = Hashing.mix64(noiseSeed * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L + y * 0x94D049BB133111EBL + channel);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Folds several values into one well-spread seed. */
    static long mix(long first, long... rest) {
        long h = first;
        for (long value : rest) {
            h = (h ^ value) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 32;
        }
        return h;
    }
}
