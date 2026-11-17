package com.thenathe.litelights.render.feature.water.wake;

import java.util.Arrays;

/**
 * The water's wakes near the camera: a small height field over 64 x 64 blocks at four cells to a
 * block, stepped once a client tick by a damped 2D wave equation, with a foam amount beside it. Boats
 * and swimmers press the surface down as they move ({@link WakeSourceList}); the dents spread as
 * ripples, which cross, bounce off the shore and fade. Idea by Goby56 (Wakes); written from scratch
 * from the textbook wave equation (Hugo Elias' water, Tessendorf's interactive water surfaces).
 *
 * <p>The cells are anchored to the world and wrap like the column map: world cell (x, z) lives in
 * slot (x &amp; 255, z &amp; 255), so moving the window only clears the strips coming into it. The
 * window itself is not wrapped: waves meet a quiet edge there and are soaked up over its last
 * cells, never coming back on the far side.
 *
 * <p>{@link #texels()} is the field as the shaders read it (shaders/include/water/common.glsl,
 * {@code lite_wake}), one RGBA8 texel per cell, in slot order:
 * <ul>
 *   <li>R: the height now, G: the height a tick ago (the shader blends them by the partial tick),
 *       both 128 for flat water and {@link #HEIGHT_STEPS} steps per unit;</li>
 *   <li>B: the foam now, A: the foam a tick ago, 0 to 255.</li>
 * </ul>
 * Only cells that may have changed are worked on (the area with waves in it, grown by how far they
 * can travel in a tick), so open water without wakes costs next to nothing, and only 16 x 16 tiles
 * whose texels changed need uploading.
 *
 * <p>No Minecraft types, no randomness (the foam's lace is a hash of the cell and the tick), so the
 * same sources give the same texels on any machine; unit-tested. One step runs at a time, on a
 * worker; nothing else touches the field while it does.
 */
public final class WakeField {
    public static final int CELLS_PER_BLOCK = 4;
    public static final int SIZE = 256;
    /** Blocks across the window. */
    public static final int BLOCKS = SIZE / CELLS_PER_BLOCK;
    public static final int TILE = 16;
    public static final int TILES = SIZE / TILE;
    /** Byte steps per unit of height in the R and G bytes. Keep in step with LITE_WAKE_HEIGHT_STEPS. */
    public static final float HEIGHT_STEPS = 96.0f;
    /** The texel of flat water without foam (0xAABBGGRR). */
    public static final int FLAT_TEXEL = 0x00008080;

    private static final int WRAP = SIZE - 1;
    private static final int CELLS = SIZE * SIZE;

    /** Wave steps per tick: two keep fast boats' trails smooth. */
    static final int SUBSTEPS = 2;
    /** The wave speed squared, in cells per step: 0.3 cells a step, 3 blocks a second. */
    private static final float WAVE_SPEED_SQUARED = 0.09f;
    /** How much of its motion a cell keeps each step: ripples fade over a few seconds. */
    private static final float DAMPING = 0.9935f;
    /** A touch of smoothing, which keeps the finest ripples from turning into a checkerboard. */
    private static final float SMOOTHING = 0.012f;
    /** Cells along the window's edge that soak up the waves reaching it. */
    private static final int SPONGE = 16;
    private static final float SPONGE_DAMPING = 0.86f;
    /** How hard a hull pulls the water under it toward its own depth each step. */
    private static final float PRESS = 0.5f;
    /** A hull's push swells and eases once every this many blocks travelled, by this much. */
    private static final double PULSE_BLOCKS = 2.4;
    private static final double PULSE = 0.5;
    /** The rim of pushed-aside water around a hull: how high against the dent, and how far out (in radii). */
    private static final float RIM_HEIGHT = 0.55f;
    private static final float RIM_REACH = 1.7f;

    /** A hull's foam: the band along its sides (half its width, in radii), its churned trail's length and width. */
    private static final float FOAM_BAND = 0.24f;
    private static final float FOAM_TRAIL = 1.6f;
    private static final float FOAM_CHURN = 0.8f;
    /** Foam left each tick: it thins out over a second or two. */
    private static final float FOAM_KEEP = 0.972f;
    /** How much foam spreads to its neighbours each tick, so trails widen as they age. */
    private static final float FOAM_SPREAD = 0.035f;
    /** Crests higher than this whiten, more the higher they are (the rate was 0.6 before 0.9.8, 0.7 before 0.9.7). */
    private static final float CREST_FOAM_FROM = 0.1f;
    private static final float CREST_FOAM_RATE = 0.5f;

    /** Smaller than this is flat water: the area with waves in it stops growing and ends. */
    private static final float QUIET_HEIGHT = 0.5f / HEIGHT_STEPS;
    private static final float QUIET_MOTION = 0.1f / HEIGHT_STEPS;
    private static final float QUIET_FOAM = 0.5f / 255.0f;

    private final float[] height = new float[CELLS];
    private final float[] motion = new float[CELLS];
    private final float[] foam = new float[CELLS];
    private final float[] lastHeight = new float[CELLS];
    private final float[] lastFoam = new float[CELLS];
    private final float[] scratch = new float[CELLS];
    private final int[] texels = new int[CELLS];
    /** Water in each block column of the window (window order), filled by the caller before a step. */
    private final byte[] water = new byte[BLOCKS * BLOCKS];
    private final float[] edgeKeep = new float[SIZE];

    /** Tiles (slot order) with a texel other than flat water now, and as last uploaded. */
    private final boolean[] tileLive = new boolean[TILES * TILES];
    private final boolean[] tileShown = new boolean[TILES * TILES];
    /** Tiles whose texels changed outside a step (cleared strips), to upload with the next one. */
    private final boolean[] tileCleared = new boolean[TILES * TILES];

    private final Box live = new Box();
    private final Box shown = new Box();
    private final Box work = new Box();

    private boolean hasOrigin;
    private int originBlockX;
    private int originBlockZ;
    private long tick;
    private long lastStepNanos;
    private int lastWorkCells;

    public WakeField() {
        Arrays.fill(texels, FLAT_TEXEL);
        for (int i = 0; i < SIZE; i++) {
            int toEdge = Math.min(i, SIZE - 1 - i);
            edgeKeep[i] = toEdge >= SPONGE ? 1.0f : SPONGE_DAMPING + (1.0f - SPONGE_DAMPING) * toEdge / SPONGE;
        }
    }

    // --- The window ---

    /**
     * Puts the window's north-west corner at this block column, clearing the cells that come into
     * it. The first call, or a jump further than the window, clears everything.
     */
    public void moveWindow(int newOriginBlockX, int newOriginBlockZ) {
        if (!hasOrigin) {
            hasOrigin = true;
            originBlockX = newOriginBlockX;
            originBlockZ = newOriginBlockZ;
            clear();
            return;
        }
        int dx = (newOriginBlockX - originBlockX) * CELLS_PER_BLOCK;
        int dz = (newOriginBlockZ - originBlockZ) * CELLS_PER_BLOCK;
        if (dx == 0 && dz == 0) {
            return;
        }
        originBlockX = newOriginBlockX;
        originBlockZ = newOriginBlockZ;
        if (Math.abs(dx) >= SIZE || Math.abs(dz) >= SIZE) {
            clear();
            return;
        }
        // Columns and rows that entered on the side the window moved toward hold another place's
        // cells from before: clear them.
        int fromX = dx > 0 ? SIZE - dx : 0;
        int toX = dx > 0 ? SIZE : -dx;
        for (int wz = 0; wz < SIZE; wz++) {
            for (int wx = fromX; wx < toX; wx++) {
                clearCell(slot(wx, wz));
            }
        }
        int fromZ = dz > 0 ? SIZE - dz : 0;
        int toZ = dz > 0 ? SIZE : -dz;
        for (int wz = fromZ; wz < toZ; wz++) {
            for (int wx = 0; wx < SIZE; wx++) {
                clearCell(slot(wx, wz));
            }
        }
        live.shift(-dx, -dz);
        shown.shift(-dx, -dz);
    }

    /** Flat water everywhere; every tile is uploaded with the next step. */
    public void clear() {
        Arrays.fill(height, 0.0f);
        Arrays.fill(motion, 0.0f);
        Arrays.fill(foam, 0.0f);
        Arrays.fill(lastHeight, 0.0f);
        Arrays.fill(lastFoam, 0.0f);
        Arrays.fill(texels, FLAT_TEXEL);
        Arrays.fill(tileLive, false);
        Arrays.fill(tileCleared, true);
        live.empty();
        shown.empty();
    }

    /** Whether a block column of the window (0 to {@link #BLOCKS} - 1 each way) is open water the waves cross. */
    public void setWater(int blockInWindowX, int blockInWindowZ, boolean open) {
        water[blockInWindowX + blockInWindowZ * BLOCKS] = (byte) (open ? 1 : 0);
    }

    public void fillWater(boolean open) {
        Arrays.fill(water, (byte) (open ? 1 : 0));
    }

    public int originBlockX() {
        return originBlockX;
    }

    public int originBlockZ() {
        return originBlockZ;
    }

    public boolean hasOrigin() {
        return hasOrigin;
    }

    // --- One tick ---

    /** Moves the water on by a tick with these sources. */
    public void step(WakeSourceList sources) {
        long started = System.nanoTime();
        tick++;
        work.set(live);
        work.grow(SUBSTEPS + 2);
        work.add(shown);
        addSourceAreas(sources);
        work.clip();
        Arrays.fill(tileLive, false);
        live.empty();
        shown.empty();
        lastWorkCells = work.cells();
        if (lastWorkCells > 0) {
            remember();
            for (int substep = 0; substep < SUBSTEPS; substep++) {
                press(sources, substep);
                wave();
            }
            leaveFoam(sources);
            spreadFoam();
            pack();
        }
        lastStepNanos = System.nanoTime() - started;
    }

    /** The texels as the shaders read them, in slot order (index x + z * SIZE of the wrapped slot). */
    public int[] texels() {
        return texels;
    }

    /** Whether a tile (slot order, x + z * TILES) has to be uploaded after the last step. */
    public boolean tileChanged(int tile) {
        return tileLive[tile] || tileShown[tile] || tileCleared[tile];
    }

    /** The changed tiles were uploaded: the texture now holds the texels as they are. */
    public void markUploaded() {
        System.arraycopy(tileLive, 0, tileShown, 0, tileLive.length);
        Arrays.fill(tileCleared, false);
    }

    /** Every tile goes up with the next upload (the texture was made again). */
    public void requestFullUpload() {
        Arrays.fill(tileCleared, true);
    }

    /** No wave, no foam, and the texture holds only flat water: nothing for the shaders to read. */
    public boolean isFlat() {
        if (!live.isEmpty()) {
            return false;
        }
        for (int tile = 0; tile < tileShown.length; tile++) {
            if (tileShown[tile] || tileLive[tile]) {
                return false;
            }
        }
        return true;
    }

    public long lastStepNanos() {
        return lastStepNanos;
    }

    /** Cells worked on in the last step. */
    public int lastWorkCells() {
        return lastWorkCells;
    }

    /** The height of a world cell now (for tests and the prototype). */
    public float heightAt(int worldCellX, int worldCellZ) {
        return insideWindow(worldCellX, worldCellZ) ? height[(worldCellX & WRAP) + (worldCellZ & WRAP) * SIZE] : 0.0f;
    }

    public float foamAt(int worldCellX, int worldCellZ) {
        return insideWindow(worldCellX, worldCellZ) ? foam[(worldCellX & WRAP) + (worldCellZ & WRAP) * SIZE] : 0.0f;
    }

    // --- The step's parts ---

    private void addSourceAreas(WakeSourceList sources) {
        for (int source = 0; source < sources.size(); source++) {
            double reach = sources.radius(source) * 1.6 + sources.halfLength(source) + 1.0;
            double minX = Math.min(sources.fromX(source), sources.toX(source)) - reach;
            double maxX = Math.max(sources.fromX(source), sources.toX(source)) + reach;
            double minZ = Math.min(sources.fromZ(source), sources.toZ(source)) - reach;
            double maxZ = Math.max(sources.fromZ(source), sources.toZ(source)) + reach;
            work.add(cellX(minX), cellZ(minZ), cellX(maxX) + 1, cellZ(maxZ) + 1);
        }
    }

    private void remember() {
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int i = slot(wx, wz);
                lastHeight[i] = height[i];
                lastFoam[i] = foam[i];
            }
        }
    }

    /**
     * Hulls press the water toward their own shape at this step's point along their path; rings push
     * it down once, at the first step.
     */
    private void press(WakeSourceList sources, int substep) {
        double along = (substep + 1.0) / SUBSTEPS;
        for (int source = 0; source < sources.size(); source++) {
            if (sources.kind(source) == WakeSourceList.HULL) {
                double x = sources.fromX(source) + (sources.toX(source) - sources.fromX(source)) * along;
                double z = sources.fromZ(source) + (sources.toZ(source) - sources.fromZ(source)) * along;
                pressHull(sources, source, x, z);
            } else if (substep == 0) {
                pushRing(sources, source);
            }
        }
    }

    private void pressHull(WakeSourceList sources, int source, double x, double z) {
        float centreX = (float) ((x - originBlockX) * CELLS_PER_BLOCK);
        float centreZ = (float) ((z - originBlockZ) * CELLS_PER_BLOCK);
        float axisX = (float) sources.headingX(source);
        float axisZ = (float) sources.headingZ(source);
        float halfLength = (float) (sources.halfLength(source) * CELLS_PER_BLOCK);
        float radius = (float) (sources.radius(source) * CELLS_PER_BLOCK);
        float rimReach = radius * RIM_REACH;
        // The hull's push swells and eases with the distance travelled, so a moving hull sends out a
        // train of crests, as a real one does, rather than a single line.
        double travelled = (x * axisX + z * axisZ) * (Math.PI * 2.0 / PULSE_BLOCKS);
        float depth = (float) (sources.depth(source) * (1.0 + PULSE * Math.sin(travelled)));
        float lift = (float) (sources.bowLift(source) / SUBSTEPS);
        // The heap of water ahead of the bow.
        float bowX = centreX + axisX * (halfLength + radius * 0.55f);
        float bowZ = centreZ + axisZ * (halfLength + radius * 0.55f);
        float bowRadius = radius * 0.85f;
        int reach = (int) Math.ceil(halfLength + rimReach) + 1;
        int fromX = Math.max(0, (int) Math.floor(centreX) - reach);
        int toX = Math.min(SIZE, (int) Math.floor(centreX) + reach + 1);
        int fromZ = Math.max(0, (int) Math.floor(centreZ) - reach);
        int toZ = Math.min(SIZE, (int) Math.floor(centreZ) + reach + 1);
        for (int wz = fromZ; wz < toZ; wz++) {
            for (int wx = fromX; wx < toX; wx++) {
                if (!isWater(wx, wz)) {
                    continue;
                }
                float relX = wx + 0.5f - centreX;
                float relZ = wz + 0.5f - centreZ;
                float onAxis = relX * axisX + relZ * axisZ;
                float clamped = Math.clamp(onAxis, -halfLength, halfLength);
                float distance = length(relX - axisX * clamped, relZ - axisZ * clamped);
                int i = slot(wx, wz);
                if (distance < rimReach) {
                    // A dent under the hull, deeper toward the bow, and the water it pushed aside
                    // heaped in a rim around it.
                    float bowness = halfLength > 0.0f ? 0.5f + 0.5f * clamped / halfLength : 1.0f;
                    float rim = bump((distance - radius * 1.15f) / (radius * 0.55f));
                    float shape = bump(distance / radius) - RIM_HEIGHT * rim;
                    float target = -depth * shape * (0.65f + 0.35f * bowness);
                    float hold = PRESS * (1.0f - smoothstep(radius, rimReach, distance));
                    height[i] += (target - height[i]) * hold;
                }
                if (lift > 0.0f) {
                    float toBow = length(wx + 0.5f - bowX, wz + 0.5f - bowZ);
                    if (toBow < bowRadius) {
                        height[i] += lift * bump(toBow / bowRadius);
                    }
                }
            }
        }
    }

    private void pushRing(WakeSourceList sources, int source) {
        float centreX = cellCoordX(sources.toX(source));
        float centreZ = cellCoordZ(sources.toZ(source));
        float radius = (float) (sources.radius(source) * CELLS_PER_BLOCK);
        float depth = (float) sources.depth(source);
        forDisc(centreX, centreZ, radius, (i, distance) -> {
            height[i] -= depth * bump(distance / radius);
            motion[i] *= 0.5f;
        });
    }

    /** One step of the wave equation over the work area: speed from the neighbours' pull, then height. */
    private void wave() {
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            int row = ((originCellZ() + wz) & WRAP) * SIZE;
            int north = wz > 0 ? ((originCellZ() + wz - 1) & WRAP) * SIZE : -1;
            int south = wz < SIZE - 1 ? ((originCellZ() + wz + 1) & WRAP) * SIZE : -1;
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int column = (originCellX() + wx) & WRAP;
                int i = row + column;
                if (!isWater(wx, wz)) {
                    height[i] = 0.0f;
                    motion[i] = 0.0f;
                    scratch[i] = 0.0f;
                    continue;
                }
                float here = height[i];
                float west = wx > 0 ? height[row + ((column - 1) & WRAP)] : 0.0f;
                float east = wx < SIZE - 1 ? height[row + ((column + 1) & WRAP)] : 0.0f;
                float northHeight = north >= 0 ? height[north + column] : 0.0f;
                float southHeight = south >= 0 ? height[south + column] : 0.0f;
                float pull = west + east + northHeight + southHeight - 4.0f * here;
                scratch[i] = pull;
                motion[i] = (motion[i] + WAVE_SPEED_SQUARED * pull) * DAMPING * Math.min(edgeKeep[wx], edgeKeep[wz]);
            }
        }
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int i = slot(wx, wz);
                height[i] += motion[i] + SMOOTHING * scratch[i];
            }
        }
    }

    /**
     * Foam along a hull's sides and bow and in its trail, a ring of it where something went in, and
     * on the crests the wake throws up.
     */
    private void leaveFoam(WakeSourceList sources) {
        for (int source = 0; source < sources.size(); source++) {
            float amount = (float) sources.foam(source);
            if (amount <= 0.0f) {
                continue;
            }
            if (sources.kind(source) == WakeSourceList.HULL) {
                hullFoam(sources, source, amount);
            } else {
                float centreX = cellCoordX(sources.toX(source));
                float centreZ = cellCoordZ(sources.toZ(source));
                float radius = (float) (sources.radius(source) * CELLS_PER_BLOCK);
                forDisc(centreX, centreZ, radius * 1.3f, (i, distance) -> {
                    float band = 1.0f - Math.abs(distance / radius - 0.85f) / 0.45f;
                    if (band > 0.0f) {
                        foam[i] = Math.min(1.0f, foam[i] + amount * band);
                    }
                });
            }
        }
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int i = slot(wx, wz);
                float crest = height[i] - CREST_FOAM_FROM;
                if (crest > 0.0f) {
                    foam[i] = Math.min(1.0f, foam[i] + crest * CREST_FOAM_RATE);
                }
            }
        }
    }

    private void hullFoam(WakeSourceList sources, int source, float amount) {
        float centreX = cellCoordX(sources.toX(source));
        float centreZ = cellCoordZ(sources.toZ(source));
        float axisX = (float) sources.headingX(source);
        float axisZ = (float) sources.headingZ(source);
        float halfLength = (float) (sources.halfLength(source) * CELLS_PER_BLOCK);
        float radius = (float) (sources.radius(source) * CELLS_PER_BLOCK);
        // The foam is a fifth narrower and shorter than the hull's own dent and rim (waves097), so a
        // boat's wake reads as water first; the waves it makes are untouched.
        float trail = radius * FOAM_TRAIL;
        int reach = (int) Math.ceil(halfLength + trail + radius) + 1;
        int fromX = Math.max(0, (int) Math.floor(centreX) - reach);
        int toX = Math.min(SIZE, (int) Math.floor(centreX) + reach + 1);
        int fromZ = Math.max(0, (int) Math.floor(centreZ) - reach);
        int toZ = Math.min(SIZE, (int) Math.floor(centreZ) + reach + 1);
        for (int wz = fromZ; wz < toZ; wz++) {
            for (int wx = fromX; wx < toX; wx++) {
                if (!isWater(wx, wz)) {
                    continue;
                }
                float relX = wx + 0.5f - centreX;
                float relZ = wz + 0.5f - centreZ;
                float onAxis = relX * axisX + relZ * axisZ;
                float across = Math.abs(relX * axisZ - relZ * axisX);
                float added = 0.0f;
                if (onAxis >= -halfLength) {
                    // Sides and bow: a band just outside the hull, thickest at the bow.
                    float clamped = Math.min(onAxis, halfLength);
                    float offX = relX - axisX * clamped;
                    float offZ = relZ - axisZ * clamped;
                    float distance = (float) Math.sqrt(offX * offX + offZ * offZ);
                    float band = 1.0f - Math.abs(distance / radius - 1.02f) / FOAM_BAND;
                    float bowness = halfLength > 0.0f ? 0.5f + 0.5f * clamped / halfLength : 1.0f;
                    added = Math.max(0.0f, band) * (0.35f + 0.65f * bowness);
                } else if (onAxis > -halfLength - trail && across < radius * FOAM_CHURN) {
                    // The churned water right behind it.
                    float behind = (-halfLength - onAxis) / trail;
                    added = (1.0f - behind) * bump(across / (radius * FOAM_CHURN)) * 0.6f;
                }
                if (added > 0.0f) {
                    int i = slot(wx, wz);
                    float lace = 0.45f + 0.55f * hash(originCellX() + wx, originCellZ() + wz, tick);
                    foam[i] = Math.min(1.0f, foam[i] + amount * added * lace);
                }
            }
        }
    }

    /** Foam spreads a little and thins; on land there is none. */
    private void spreadFoam() {
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int i = slot(wx, wz);
                if (!isWater(wx, wz)) {
                    scratch[i] = 0.0f;
                    continue;
                }
                float around = foamInWindow(wx - 1, wz) + foamInWindow(wx + 1, wz) + foamInWindow(wx, wz - 1) + foamInWindow(wx, wz + 1);
                scratch[i] = (foam[i] * (1.0f - 4.0f * FOAM_SPREAD) + around * FOAM_SPREAD) * FOAM_KEEP;
            }
        }
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int i = slot(wx, wz);
                foam[i] = scratch[i];
            }
        }
    }

    /** Writes the work area's texels, snaps what has died down to flat water, and finds what still moves. */
    private void pack() {
        for (int wz = work.minZ; wz < work.maxZ; wz++) {
            for (int wx = work.minX; wx < work.maxX; wx++) {
                int i = slot(wx, wz);
                if (Math.abs(height[i]) < QUIET_HEIGHT && Math.abs(motion[i]) < QUIET_MOTION) {
                    height[i] = 0.0f;
                    motion[i] = 0.0f;
                }
                if (foam[i] < QUIET_FOAM) {
                    foam[i] = 0.0f;
                }
                if (height[i] != 0.0f || motion[i] != 0.0f || foam[i] != 0.0f) {
                    live.add(wx, wz, wx + 1, wz + 1);
                }
                int texel = texel(height[i], lastHeight[i], foam[i], lastFoam[i]);
                texels[i] = texel;
                if (texel != FLAT_TEXEL) {
                    shown.add(wx, wz, wx + 1, wz + 1);
                    tileLive[tileOf(i)] = true;
                }
            }
        }
    }

    static int texel(float heightNow, float heightBefore, float foamNow, float foamBefore) {
        int red = Math.clamp(Math.round(128.0f + heightNow * HEIGHT_STEPS), 0, 255);
        int green = Math.clamp(Math.round(128.0f + heightBefore * HEIGHT_STEPS), 0, 255);
        int blue = Math.clamp(Math.round(foamNow * 255.0f), 0, 255);
        int alpha = Math.clamp(Math.round(foamBefore * 255.0f), 0, 255);
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    // --- Helpers ---

    private interface CellVisitor {
        void visit(int slot, float distance);
    }

    private void forDisc(float centreX, float centreZ, float radius, CellVisitor visitor) {
        int fromX = Math.max(0, (int) Math.floor(centreX - radius));
        int toX = Math.min(SIZE, (int) Math.ceil(centreX + radius) + 1);
        int fromZ = Math.max(0, (int) Math.floor(centreZ - radius));
        int toZ = Math.min(SIZE, (int) Math.ceil(centreZ + radius) + 1);
        for (int wz = fromZ; wz < toZ; wz++) {
            for (int wx = fromX; wx < toX; wx++) {
                float dx = wx + 0.5f - centreX;
                float dz = wz + 0.5f - centreZ;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                if (distance < radius && isWater(wx, wz)) {
                    visitor.visit(slot(wx, wz), distance);
                }
            }
        }
    }

    private static float length(float x, float z) {
        return (float) Math.sqrt(x * x + z * z);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /** 1 at the centre, falling smoothly to 0 at the edge (t from 0 to 1). */
    private static float bump(float t) {
        float inside = 1.0f - t * t;
        return inside <= 0.0f ? 0.0f : inside * inside;
    }

    /** A steady value from 0 to 1 for a cell and a tick. */
    static float hash(int x, int z, long tick) {
        int h = x * 0x8DA6B343 ^ z * 0xD8163841 ^ (int) tick * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) / 16777216.0f;
    }

    private float foamInWindow(int wx, int wz) {
        if (wx < 0 || wz < 0 || wx >= SIZE || wz >= SIZE) {
            return 0.0f;
        }
        return foam[slot(wx, wz)];
    }

    private boolean isWater(int wx, int wz) {
        return water[(wx >> 2) + (wz >> 2) * BLOCKS] != 0;
    }

    private void clearCell(int i) {
        height[i] = 0.0f;
        motion[i] = 0.0f;
        foam[i] = 0.0f;
        lastHeight[i] = 0.0f;
        lastFoam[i] = 0.0f;
        if (texels[i] != FLAT_TEXEL) {
            texels[i] = FLAT_TEXEL;
            tileCleared[tileOf(i)] = true;
        }
    }

    private boolean insideWindow(int worldCellX, int worldCellZ) {
        int wx = worldCellX - originCellX();
        int wz = worldCellZ - originCellZ();
        return hasOrigin && wx >= 0 && wz >= 0 && wx < SIZE && wz < SIZE;
    }

    private int originCellX() {
        return originBlockX * CELLS_PER_BLOCK;
    }

    private int originCellZ() {
        return originBlockZ * CELLS_PER_BLOCK;
    }

    private int slot(int wx, int wz) {
        return ((originCellX() + wx) & WRAP) + ((originCellZ() + wz) & WRAP) * SIZE;
    }

    private static int tileOf(int slot) {
        return ((slot & WRAP) >> 4) + ((slot >> 8) >> 4) * TILES;
    }

    private float cellCoordX(double worldX) {
        return (float) ((worldX - originBlockX) * CELLS_PER_BLOCK);
    }

    private float cellCoordZ(double worldZ) {
        return (float) ((worldZ - originBlockZ) * CELLS_PER_BLOCK);
    }

    private int cellX(double worldX) {
        return (int) Math.floor(cellCoordX(worldX));
    }

    private int cellZ(double worldZ) {
        return (int) Math.floor(cellCoordZ(worldZ));
    }

    /** A rectangle of window cells, min inclusive, max exclusive; empty when min is not below max. */
    private static final class Box {
        int minX;
        int minZ;
        int maxX;
        int maxZ;

        Box() {
            empty();
        }

        void empty() {
            minX = SIZE;
            minZ = SIZE;
            maxX = 0;
            maxZ = 0;
        }

        boolean isEmpty() {
            return minX >= maxX || minZ >= maxZ;
        }

        void set(Box other) {
            minX = other.minX;
            minZ = other.minZ;
            maxX = other.maxX;
            maxZ = other.maxZ;
        }

        void add(Box other) {
            if (!other.isEmpty()) {
                add(other.minX, other.minZ, other.maxX, other.maxZ);
            }
        }

        void add(int fromX, int fromZ, int toX, int toZ) {
            if (fromX >= toX || fromZ >= toZ) {
                return;
            }
            if (isEmpty()) {
                minX = fromX;
                minZ = fromZ;
                maxX = toX;
                maxZ = toZ;
                return;
            }
            minX = Math.min(minX, fromX);
            minZ = Math.min(minZ, fromZ);
            maxX = Math.max(maxX, toX);
            maxZ = Math.max(maxZ, toZ);
        }

        void grow(int cells) {
            if (!isEmpty()) {
                minX -= cells;
                minZ -= cells;
                maxX += cells;
                maxZ += cells;
            }
        }

        void shift(int dx, int dz) {
            if (isEmpty()) {
                return;
            }
            minX += dx;
            maxX += dx;
            minZ += dz;
            maxZ += dz;
            clip();
        }

        void clip() {
            minX = Math.max(minX, 0);
            minZ = Math.max(minZ, 0);
            maxX = Math.min(maxX, SIZE);
            maxZ = Math.min(maxZ, SIZE);
            if (isEmpty()) {
                empty();
            }
        }

        int cells() {
            return isEmpty() ? 0 : (maxX - minX) * (maxZ - minZ);
        }
    }
}
