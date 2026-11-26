package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.api.AlmanacVersions;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The data side of Snow on far terrain on LiteLights' own far land (FarLandSnow makes it; up to 1.0.7
 * it served Voxy's and Distant Horizons' pixels, whose support was dropped in 1.0.8).
 *
 * <p>Seasonal snow on far terrain (farsnow), with Almanac 1.0.0 or later on the client. Voxy and
 * Distant Horizons draw far land as it was when each chunk was last loaded, so a winter capture stays
 * white all summer and a summer one green all winter. Almanac's server streams a climate map, and its
 * client answers per column where snow lies now: its far snow line, and its bare line, above which
 * snow lies whatever the cover. This keeps both around the camera in a texture the far pass reads
 * (shaders/include/farterrain/far_snow.glsl), which paints the snow on or takes it off.
 *
 * <ul>
 *   <li><b>Two rings</b> ({@link FarSnowGrid}): 512 x 512 columns 16 blocks apart (8 km across,
 *   Almanac's default map radius) and 64 apart (32 km, its largest), one texture, both following the
 *   camera in whole steps. The season's grass and foliage colours, for captured snow that has to go
 *   and for tinting far grass and leaves to the season, are 256 x 256 columns 32 blocks apart each,
 *   in a second texture.</li>
 *   <li><b>Filled on a background thread</b> (Almanac's lookups are safe from any thread), only when
 *   Almanac says something changed ({@code FAR_CLIMATE_CHANGED}, only the area that changed) or a
 *   ring moves; never per frame. One fill at a time, and what changes meanwhile waits for the next.
 *   The fill also merges its lines into a copy of the rings and encodes the texels, so the render
 *   thread only swaps the copy in and uploads. A change of season while a fill runs makes that fill
 *   out of date: it may have read some of the new lines, so it is dropped, and every line is filled
 *   again. Colours are filled at most every quarter second.</li>
 *   <li><b>The cover</b>: between a column's bare line and its far snow line the season's snow lies
 *   on the share of columns its climate's cover says (Almanac's {@code snowCover}, the first layer of
 *   its depth), forming and melting alike. Nothing is timed or animated here; the cover shown only
 *   moves smoothly between the sixteenths of a layer Almanac sends ({@link #followCover}).</li>
 *   <li><b>Only the overworld</b> has far snow (Almanac's map is the overworld's). Anywhere else,
 *   and once the world is left, nothing of the level is kept ({@link #leaveLevel}).</li>
 * </ul>
 * Without Almanac, or beside one older than 1.0.0, nothing here is made, and the far pass draws its
 * old variant.
 */
final class FarSnow implements AutoCloseable {
    static final int INNER_SPACING = 16;
    static final int OUTER_SPACING = 64;
    static final int COLOUR_SIZE = 256;
    static final int COLOUR_SPACING = 32;
    private static final int INNER_STEP = 1024;
    private static final int OUTER_STEP = 4096;
    private static final int COLOUR_STEP = 1024;
    /**
     * The colours are filled at most this often. Joining a world, Almanac's map arrives in a couple
     * of hundred tiles over a few seconds, each of which changes the colours.
     */
    static final long COLOUR_FILL_INTERVAL_NANOS = 250_000_000L;
    private static final int UNIFORM_BYTES = 112;
    /** Layers of each climate's shares the far land reads: Almanac's default most layers is 3. */
    static final int SHARED_LAYERS = 4;
    private static final int RING_BYTES = FarSnowGrid.SIZE * FarSnowGrid.SIZE * 4;
    private static final int COLOUR_BYTES = COLOUR_SIZE * COLOUR_SIZE * 2 * 4;
    /** The colours texture's rows: grass, foliage, then the snow by place (FarSnowPlaces). */
    private static final int COLOUR_ROWS = COLOUR_SIZE * 2 + FarSnowPlaces.CELLS;

    /** Where finished fills go: the far pass's textures, or a test's record of them. */
    interface Uploads {
        /** A ring's texels for a rectangle, the ring's rows starting at {@code row} of the texture. */
        void lines(ByteBuffer texels, FarSnowGrid.Rect rect, int row);

        /** Every colour texel, grass rows then foliage rows. */
        void colours(ByteBuffer texels);

        /** Every place's layer shares, in the colours texture's rows from FarSnowPlaces.FIRST_ROW. */
        void places(ByteBuffer texels);
    }

    private final @Nullable FarSnowSource source;
    private final @Nullable String problem;
    private final Executor executor;
    private final Uploads uploads;
    private final LongSupplier clock;
    private final FarSnowGrid inner = new FarSnowGrid(INNER_SPACING, INNER_STEP);
    /** Each climate's layer shares, HOT, TEMPERATE, COLD, read each frame. */
    private final float[][] shares = new float[3][SHARED_LAYERS];
    private boolean leafTops;
    private boolean whiteGrass;
    private final FarSnowGrid outer = new FarSnowGrid(OUTER_SPACING, OUTER_STEP);
    private boolean coloursPlaced;
    private boolean coloursDirty = true;
    private boolean coloursKnown;
    private int colourOriginX;
    private int colourOriginZ;
    private long lastColourStart;

    private @Nullable ClientLevel level;
    private int floor;
    private @Nullable CompletableFuture<LinesJob> linesJob;
    private @Nullable CompletableFuture<ColourJob> colourJob;
    private @Nullable CompletableFuture<FarSnowPlaces.Job> placesJob;
    /** The season changed while the running lines fill ran: its result is dropped. */
    private boolean linesStale;
    /**
     * The texels a fill encodes into, kept for the next one. Null while a fill has them; a fill that
     * was let go keeps its own, and the next gets new ones. Managed by the garbage collector, so a
     * fill still running after the world is left never writes into freed memory.
     */
    private @Nullable ByteBuffer spareLinesStaging;
    private @Nullable ByteBuffer spareColourStaging;
    private @Nullable ByteBuffer sparePlacesStaging;
    /** The snow by place in the texture is for this level and to be read (FarSnowPlaces). */
    private boolean placesFilled;
    private int placesOriginX;
    private int placesOriginZ;
    private long lastPlacesStart;
    /** The places' shares the last fill knew, for the next (FarSnowPlaces.Remembered). */
    private FarSnowPlaces.@Nullable Remembered placesRemembered;
    /** How many places out from the camera's are asked about. */
    private int placesReach = FarSnowPlaces.MOST_REACH;
    private int cameraColumnX;
    private int cameraColumnZ;
    /** A cover this far along that is no longer part way has reached all of its ground. */
    static final double COVER_NEARLY_DONE = 0.75;
    /** Almanac's snow falls a layer per 4,096 ticks; the cover shown may move twice as fast. */
    static final double COVER_EASE_PER_TICK = 1.0 / 2048.0;
    static final double COVER_JUMP = 0.2;
    /** How long a melted-away cover holds before the lines count as caught up, in ticks. */
    static final double COVER_HOLD_TICKS = 100.0;
    /**
     * The cover far snow is heading for, and the cover shown, which moves to it smoothly since the
     * depths arrive in sixteenths of a layer. -1 before the first.
     */
    private double coverTarget = -1.0;
    private double coverShown = -1.0;
    private double coverShownAt;
    private double coverHeldSince = -1.0;
    private final double[] depths = new double[3];

    private @Nullable GpuTexture linesTexture;
    private @Nullable GpuTextureView linesView;
    private @Nullable GpuTexture colourTexture;
    private @Nullable GpuTextureView colourView;
    private @Nullable GpuBuffer uniforms;

    // For QA and the debug line.
    private int fills;
    private int staleFills;
    private int colourFills;
    private double lastFillMillis;
    private double lastColourFillMillis;
    private int placesFills;
    private int placesAsked;
    private int placesKnown;
    private int placesRecalled;
    private double lastPlacesFillMillis;
    private boolean anyKnown;
    /** The render thread's share of the last fill: taking it in and uploading it. */
    private double lastApplyMillis;
    private double lastColourApplyMillis;

    private FarSnow(@Nullable FarSnowSource source, @Nullable String problem) {
        this(source, problem, Util.backgroundExecutor(), null, System::nanoTime);
    }

    /** With fills run by {@code executor}, finished ones handed to {@code uploads}, and time from {@code clock} (tests). */
    FarSnow(FarSnowSource source, Executor executor, Uploads uploads, LongSupplier clock) {
        this(source, null, executor, uploads, clock);
    }

    private FarSnow(@Nullable FarSnowSource source, @Nullable String problem, Executor executor, @Nullable Uploads uploads, LongSupplier clock) {
        this.source = source;
        this.problem = problem;
        this.executor = executor;
        this.uploads = uploads != null ? uploads : new TextureUploads();
        this.clock = clock;
        lastColourStart = clock.getAsLong() - COLOUR_FILL_INTERVAL_NANOS;
        lastPlacesStart = clock.getAsLong() - FarSnowPlaces.INTERVAL_NANOS;
        if (source != null) {
            source.listen(this::changed);
        }
    }

    /** With Almanac 1.0.0 or later on the client, far snow from its lookups; otherwise none. */
    static FarSnow create() {
        if (!FabricLoader.getInstance().isModLoaded("almanac")) {
            return new FarSnow(null, "Needs Almanac on the client");
        }
        if (!LiteLightsApi.climate().almanacAtLeast(AlmanacVersions.MINIMUM)) {
            return new FarSnow(null, "Needs Almanac " + AlmanacVersions.MINIMUM + " or later");
        }
        try {
            return new FarSnow(AlmanacFarSnow.create(), null);
        } catch (LinkageError | RuntimeException changedApi) {
            LiteLightsMod.LOG.warn("Almanac's far-terrain lookups aren't as LiteLights knows them ({}); far terrain keeps the snow it was captured with",
                changedApi.toString());
            return new FarSnow(null, "Almanac's far-terrain lookups changed");
        }
    }

    boolean available() {
        return source != null;
    }

    /** Why far snow can't run, or null. */
    @Nullable String problem() {
        return problem;
    }

    /** The share of the season's snow that lies between the bare lines and the far snow lines, 0 to 1. */
    float cover() {
        return coverShown < 0.0 ? 1.0f : (float) coverShown;
    }

    /** For QA: columns with a known line in the inner ring (counted when asked). */
    int knownInner() {
        return inner.live().known();
    }

    // --- Almanac's changes (client thread) ---

    private void changed(ClientLevel changedLevel, FarSnowSource.Area area, boolean snowLines, boolean colourChange, FarSnowSource.Kind kind) {
        if (changedLevel != level) {
            return;
        }
        if (colourChange) {
            coloursDirty = true;
        }
        if (!snowLines) {
            return;
        }
        if (kind == FarSnowSource.Kind.SEASON) {
            // Nothing to animate: the lines are filled again, and the cover says how much snow they hold.
            inner.markAllDirty();
            outer.markAllDirty();
            // The running fill may have read some of the new lines and some of the old.
            if (linesJob != null) {
                linesStale = true;
            }
        } else {
            inner.markDirty(area);
            outer.markDirty(area);
        }
    }

    // --- Per frame (render thread) ---

    /**
     * Brings the lines up to date for this frame and follows the cover. Returns whether the far pass
     * should draw its snow variant: any line around the camera is known.
     */
    boolean prepare(ClientLevel currentLevel, double cameraX, double cameraZ, long gameTime, float partialTick) {
        if (source == null) {
            return false;
        }
        boolean draws = update(currentLevel, currentLevel.getMinY(), cameraX, cameraZ, gameTime, partialTick);
        writeUniforms();
        return draws;
    }

    /** {@link #prepare} but for the uniforms: what the tests drive. */
    boolean update(@Nullable ClientLevel currentLevel, int levelFloor, double cameraX, double cameraZ, long gameTime, float partialTick) {
        FarSnowSource from = source;
        if (from == null) {
            return false;
        }
        if (currentLevel != level) {
            forgetLevel();
            level = currentLevel;
        }
        floor = levelFloor;
        cameraColumnX = (int) Math.floor(cameraX);
        cameraColumnZ = (int) Math.floor(cameraZ);
        finishJobs();
        followCover(gameTime + partialTick);
        startJobs(currentLevel, from, cameraX, cameraZ);
        return inner.placed() && anyKnown;
    }

    /**
     * The player is no longer in the level far snow was filled for: another dimension, or the world
     * was left. Its lines and fills are let go, so nothing here keeps the level; coming back fills
     * everything again.
     */
    void leaveLevel() {
        if (level == null && linesJob == null && colourJob == null && placesJob == null) {
            return;
        }
        forgetLevel();
        level = null;
    }

    private void forgetLevel() {
        // Fills still running finish on their own, into columns and buffers of their own, and are never taken in.
        linesJob = null;
        colourJob = null;
        placesJob = null;
        placesFilled = false;
        placesRemembered = null;
        lastPlacesStart = clock.getAsLong() - FarSnowPlaces.INTERVAL_NANOS;
        linesStale = false;
        inner.clear();
        outer.clear();
        coloursPlaced = false;
        coloursDirty = true;
        coloursKnown = false;
        coverTarget = -1.0;
        coverShown = -1.0;
        coverHeldSince = -1.0;
        anyKnown = false;
    }

    private void startJobs(@Nullable ClientLevel currentLevel, FarSnowSource from, double cameraX, double cameraZ) {
        if (linesJob == null) {
            Part innerPart = plan(inner, cameraX, cameraZ);
            Part outerPart = plan(outer, cameraX, cameraZ);
            if (innerPart != null || outerPart != null) {
                LinesJob job = new LinesJob(currentLevel, from, floor, new RingFill(inner, innerPart), new RingFill(outer, outerPart),
                    takeLinesStaging());
                linesJob = CompletableFuture.supplyAsync(job::run, executor);
            }
        }
        if (colourJob == null && (coloursDirty || !coloursPlaced || coloursWantMove(cameraX, cameraZ))
            && clock.getAsLong() - lastColourStart >= COLOUR_FILL_INTERVAL_NANOS) {
            coloursDirty = false;
            lastColourStart = clock.getAsLong();
            ColourJob job = new ColourJob(currentLevel, from, FarSnowGrid.originFor(cameraX, COLOUR_STEP, COLOUR_SIZE * COLOUR_SPACING),
                FarSnowGrid.originFor(cameraZ, COLOUR_STEP, COLOUR_SIZE * COLOUR_SPACING), takeColourStaging());
            colourJob = CompletableFuture.supplyAsync(job::run, executor);
        }
        startPlaces(currentLevel, from, cameraX, cameraZ);
    }

    /**
     * Under Almanac's regional weather, every place's snow is filled again once a second round the
     * camera (FarSnowPlaces); without it, none is read, and far land takes the snow here everywhere.
     */
    private void startPlaces(@Nullable ClientLevel currentLevel, FarSnowSource from, double cameraX, double cameraZ) {
        if (!from.snowByPlace(currentLevel)) {
            placesFilled = false;
            return;
        }
        if (placesJob != null || clock.getAsLong() - lastPlacesStart < FarSnowPlaces.INTERVAL_NANOS) {
            return;
        }
        lastPlacesStart = clock.getAsLong();
        float[][] here = new float[3][SHARED_LAYERS];
        from.snowShares(currentLevel, here);
        ByteBuffer staging = sparePlacesStaging != null ? sparePlacesStaging : ByteBuffer.allocateDirect(FarSnowPlaces.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        sparePlacesStaging = null;
        FarSnowPlaces.Job job = new FarSnowPlaces.Job(currentLevel, from, FarSnowPlaces.originFor(cameraX), FarSnowPlaces.originFor(cameraZ),
            placesReach, here, placesRemembered, staging);
        placesJob = CompletableFuture.supplyAsync(job::run, executor);
    }

    private boolean coloursWantMove(double cameraX, double cameraZ) {
        double half = COLOUR_SIZE * COLOUR_SPACING / 2.0;
        return Math.abs(cameraX - colourOriginX - half) >= COLOUR_STEP || Math.abs(cameraZ - colourOriginZ - half) >= COLOUR_STEP;
    }

    /** What a ring needs filled this time: all of it at a new place if it moves, else its dirty texels. */
    private static @Nullable Part plan(FarSnowGrid grid, double cameraX, double cameraZ) {
        if (grid.wantsMove(cameraX, cameraZ)) {
            grid.takeDirty();
            return new Part(grid.wantedOriginX(cameraX), grid.wantedOriginZ(cameraZ), FarSnowGrid.Rect.ALL, true);
        }
        FarSnowGrid.Rect rect = grid.takeDirty();
        return rect == null ? null : new Part(grid.originX(), grid.originZ(), rect, false);
    }

    private ByteBuffer takeLinesStaging() {
        ByteBuffer staging = spareLinesStaging != null ? spareLinesStaging : ByteBuffer.allocateDirect(2 * RING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        spareLinesStaging = null;
        return staging;
    }

    private ByteBuffer takeColourStaging() {
        ByteBuffer staging = spareColourStaging != null ? spareColourStaging : ByteBuffer.allocateDirect(COLOUR_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        spareColourStaging = null;
        return staging;
    }

    /** Takes in finished fills. A fill that failed is passed on, so far snow reports it and stops. */
    private void finishJobs() {
        CompletableFuture<LinesJob> lines = linesJob;
        if (lines != null && lines.isDone()) {
            linesJob = null;
            boolean stale = linesStale;
            linesStale = false;
            if (lines.isCompletedExceptionally()) {
                throw new IllegalStateException("Filling the far snow lines failed", lines.exceptionNow());
            }
            LinesJob job = lines.resultNow();
            if (stale || job.level != level) {
                staleFills++;
                inner.giveBack(job.inner.into);
                outer.giveBack(job.outer.into);
            } else {
                apply(job);
            }
            spareLinesStaging = job.staging;
        }
        CompletableFuture<ColourJob> colour = colourJob;
        if (colour != null && colour.isDone()) {
            colourJob = null;
            if (colour.isCompletedExceptionally()) {
                throw new IllegalStateException("Filling the far grass colours failed", colour.exceptionNow());
            }
            ColourJob job = colour.resultNow();
            if (job.level == level) {
                applyColours(job);
            }
            spareColourStaging = job.staging;
        }
        CompletableFuture<FarSnowPlaces.Job> places = placesJob;
        if (places != null && places.isDone()) {
            placesJob = null;
            if (places.isCompletedExceptionally()) {
                throw new IllegalStateException("Filling the far snow by place failed", places.exceptionNow());
            }
            FarSnowPlaces.Job job = places.resultNow();
            if (job.level == level) {
                uploads.places(job.staging);
                placesOriginX = job.originX;
                placesOriginZ = job.originZ;
                placesFilled = true;
                placesFills++;
                placesAsked = job.asked;
                placesKnown = job.known;
                placesRecalled = job.recalled;
                placesRemembered = job.remembered;
                lastPlacesFillMillis = job.nanos / 1.0e6;
            }
            sparePlacesStaging = job.staging;
        }
    }

    private void apply(LinesJob job) {
        long started = System.nanoTime();
        fills++;
        lastFillMillis = job.nanos / 1.0e6;
        takeIn(inner, job.inner, 0);
        takeIn(outer, job.outer, FarSnowGrid.SIZE);
        anyKnown = job.anyKnown;
        lastApplyMillis = (System.nanoTime() - started) / 1.0e6;
    }

    private void takeIn(FarSnowGrid grid, @Nullable RingFill fill, int row) {
        if (fill == null) {
            return;
        }
        if (!fill.filled) {
            grid.giveBack(fill.into);
            return;
        }
        if (fill.upload != null) {
            uploads.lines(fill.texels, fill.upload, row);
        }
        grid.swapIn(fill.into, fill.resultOriginX(), fill.resultOriginZ());
    }

    private void applyColours(ColourJob job) {
        long started = System.nanoTime();
        colourOriginX = job.originX;
        colourOriginZ = job.originZ;
        coloursPlaced = true;
        coloursKnown = job.known > 0;
        colourFills++;
        lastColourFillMillis = job.nanos / 1.0e6;
        uploads.colours(job.staging);
        lastColourApplyMillis = (System.nanoTime() - started) / 1.0e6;
    }

    /**
     * Whether a colour cell and the cells after it in x, z and both have the same colours, so the
     * far pass can take the cell's colours as they are for every column between them (one biome).
     */
    static boolean sameAround(int[] grass, int[] foliage, int x, int z) {
        int at = z * COLOUR_SIZE + x;
        int nextX = at + Math.min(x + 1, COLOUR_SIZE - 1) - x;
        int nextZ = at + (Math.min(z + 1, COLOUR_SIZE - 1) - z) * COLOUR_SIZE;
        int nextBoth = nextZ + nextX - at;
        return sameColours(grass, foliage, at, nextX) && sameColours(grass, foliage, at, nextZ) && sameColours(grass, foliage, at, nextBoth);
    }

    private static boolean sameColours(int[] grass, int[] foliage, int a, int b) {
        return grass[a] == grass[b] && foliage[a] == foliage[b];
    }

    /** A colour (0xRRGGBB, or -1) as an RGBA8 texel in byte order, alpha 0 where it isn't known. */
    static int texel(int colour) {
        return colour < 0 ? 0 : 0xFF000000 | (colour & 0xFF) << 16 | (colour & 0xFF00) | (colour >> 16 & 0xFF);
    }

    /**
     * The share of the season's snow far land shows, from the climates' depths, moved to smoothly
     * since the depths arrive in sixteenths of a layer.
     */
    private void followCover(double now) {
        if (source == null || level == null) {
            return;
        }
        source.snowDepths(level, depths);
        double partWay = formingCover(depths);
        if (partWay >= 0.0) {
            coverHeldSince = -1.0;
        } else if (coverHeldSince < 0.0) {
            coverHeldSince = now;
        }
        coverTarget = bareCoverTarget(coverTarget, partWay, coverHeldSince < 0.0 ? 0.0 : now - coverHeldSince, linesJob != null);
        coverShown = coverShown < 0.0 ? coverTarget : easedCover(coverShown, coverTarget, now - coverShownAt);
        coverShownAt = now;
    }

    /**
     * The cover far snow heads for. A climate part way through its first layer gives it, forming or
     * melting. With none part way, every line holds all of its snow or none: a cover that had nearly
     * filled goes to 1 at once (the last sixteenth arrives as a whole layer); one that had nearly
     * melted away holds for a moment first, until the lines without that snow have arrived and been
     * filled in, so the last of it doesn't flash back white.
     */
    static double bareCoverTarget(double before, double partWay, double heldTicks, boolean filling) {
        if (partWay >= 0.0) {
            return partWay;
        }
        if (before < 0.0 || before >= COVER_NEARLY_DONE) {
            return 1.0;
        }
        return heldTicks >= COVER_HOLD_TICKS && !filling ? 1.0 : before;
    }

    /** Moves the cover shown towards its target, at most twice as fast as snow falls; a big gap is jumped. */
    static double easedCover(double shown, double target, double ticks) {
        double gap = target - shown;
        if (Math.abs(gap) > COVER_JUMP) {
            return target;
        }
        double most = Math.max(0.0, ticks) * COVER_EASE_PER_TICK;
        return shown + Math.clamp(gap, -most, most);
    }

    /**
     * The cover of the snow that is part way, 0 to 1, from each climate's depth in layers: the first
     * layer of a climate whose snow has begun and hasn't covered its ground yet (the least of them,
     * should two be part way). -1 when no climate is part way.
     */
    static double formingCover(double[] depths) {
        double cover = -1.0;
        for (double depth : depths) {
            if (depth > 0.0 && depth < 1.0 && (cover < 0.0 || depth < cover)) {
                cover = depth;
            }
        }
        return cover;
    }

    // --- The far pass's bindings ---

    private void ensureTextures() {
        if (linesTexture != null) {
            return;
        }
        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        linesTexture = device.createTexture("LiteLights far snow lines", usage, GpuFormat.RG16_UNORM, FarSnowGrid.SIZE, FarSnowGrid.SIZE * 2, 1, 1);
        linesView = device.createTextureView(linesTexture);
        colourTexture = device.createTexture("LiteLights far snow colours", usage, GpuFormat.RGBA8_UNORM, COLOUR_SIZE, COLOUR_ROWS, 1, 1);
        colourView = device.createTextureView(colourTexture);
        uniforms = device.createBuffer(() -> "LiteLights far snow", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
        // Nothing known until the first fill: every code 0.
        ByteBuffer empty = MemoryUtil.memCalloc(FarSnowGrid.SIZE * FarSnowGrid.SIZE * 2 * 4);
        try {
            var encoder = device.createCommandEncoder();
            encoder.writeToTexture(linesTexture, empty, 0, 0, 0, 0, FarSnowGrid.SIZE, FarSnowGrid.SIZE * 2);
            empty.limit(COLOUR_SIZE * COLOUR_ROWS * 4);
            encoder.writeToTexture(colourTexture, empty, 0, 0, 0, 0, COLOUR_SIZE, COLOUR_ROWS);
        } finally {
            MemoryUtil.memFree(empty);
        }
    }

    /** The far pass's textures. */
    private final class TextureUploads implements Uploads {
        @Override
        public void lines(ByteBuffer texels, FarSnowGrid.Rect rect, int row) {
            ensureTextures();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(linesTexture, texels, 0, 0, rect.minX(), row + rect.minZ(),
                rect.width(), rect.height());
        }

        @Override
        public void colours(ByteBuffer texels) {
            ensureTextures();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(colourTexture, texels, 0, 0, 0, 0, COLOUR_SIZE, COLOUR_SIZE * 2);
        }

        @Override
        public void places(ByteBuffer texels) {
            ensureTextures();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(colourTexture, texels, 0, 0, 0, FarSnowPlaces.FIRST_ROW,
                FarSnowPlaces.WIDTH, FarSnowPlaces.CELLS);
        }
    }

    /** How far snow looks on the far land: leaves white only on top, and grass in snow white. */
    void setLook(boolean leafTops, boolean whiteGrass) {
        this.leafTops = leafTops;
        this.whiteGrass = whiteGrass;
    }

    /** How far far land reaches from the camera, in blocks: the places within it are asked about. */
    void setReach(float blocks) {
        placesReach = FarSnowPlaces.reachFor(blocks);
    }

    /**
     * 0 when far land takes each place's snow, from the camera out (FarSnowPlaces), or -1 with none
     * filled. Up to 1.1.4 far land took the snow where the player stands out to the render distance
     * and eased to the places' over the next 512 blocks, so far land at a storm's snow turned bare as
     * it neared, before the near land with the snow came in.
     */
    float placesFrom() {
        return placesFilled ? 0.0f : -1.0f;
    }

    private void writeUniforms() {
        ensureTextures();
        if (source != null && level != null) {
            source.snowShares(level, shares);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, UNIFORM_BYTES)
                .putIVec4(inner.originX(), inner.originZ(), outer.originX(), outer.originZ())
                .putIVec4(colourOriginX, colourOriginZ, leafTops ? 1 : 0, whiteGrass ? 1 : 0)
                .putVec4(placesFrom(), floor, coloursKnown ? 1.0f : 0.0f, debugView() ? 1.0f : 0.0f);
            for (float[] layers : shares) {
                builder.putVec4(layers[0], layers[1], layers[2], layers[3]);
            }
            builder.putIVec4(placesOriginX, placesOriginZ, cameraColumnX, cameraColumnZ);
            ByteBuffer data = builder.get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniforms.slice(), data);
        }
    }

    /**
     * The debug view, from {@code -Dlitelights.debug=farsnow}: far pixels show what the lines say
     * (far_snow.glsl). Read every frame, so QA can switch it while the game runs.
     */
    private static boolean debugView() {
        return "farsnow".equals(System.getProperty("litelights.debug"));
    }

    /** Binds the snow variant's uniforms on the far pass. */
    void bind(RenderPass pass) {
        pass.setUniform("FarSnow", uniforms);
        pass.setUniform("FarSnowLines", linesView, LiteLightsApi.passes().nearestSampler());
        pass.setUniform("FarSnowColours", colourView, LiteLightsApi.passes().linearSampler());
    }

    /** For QA: what far snow knows and is doing. */
    String describe() {
        if (source == null) {
            return "absent (" + problem + ")";
        }
        return String.format(Locale.ROOT, "knownInner=%d, knownOuter=%d, fills=%d, staleFills=%d, lastFillMs=%.1f, lastApplyMs=%.2f, cover=%.3f, colours=%s, colourFills=%d, lastColourFillMs=%.1f, lastColourApplyMs=%.2f, byPlace=%s, placesFills=%d, placesAsked=%d, placesKnown=%d, placesRecalled=%d, placesReach=%d, lastPlacesFillMs=%.1f, holdsLevel=%s",
            inner.live().known(), outer.live().known(), fills, staleFills, lastFillMillis, lastApplyMillis, cover(), coloursKnown, colourFills,
            lastColourFillMillis, lastColourApplyMillis, placesFilled, placesFills, placesAsked, placesKnown, placesRecalled, placesReach, lastPlacesFillMillis, level != null);
    }

    /** The world was left: every line is forgotten and the textures go. */
    @Override
    public void close() {
        leaveLevel();
        if (linesView != null) {
            linesView.close();
            linesView = null;
        }
        if (linesTexture != null) {
            linesTexture.close();
            linesTexture = null;
        }
        if (colourView != null) {
            colourView.close();
            colourView = null;
        }
        if (colourTexture != null) {
            colourTexture.close();
            colourTexture = null;
        }
        if (uniforms != null) {
            uniforms.close();
            uniforms = null;
        }
    }

    // --- Background fills ---

    /** One ring's share of a fill: where the ring starts, and which of its texels to fill. */
    private record Part(int originX, int originZ, FarSnowGrid.Rect rect, boolean move) {
    }

    /**
     * One ring's part of a lines fill, worked out on the fill's thread: the ring's live columns are
     * only read, and the result is built in the ring's spare columns, with the texels to upload.
     */
    private static final class RingFill {
        final int spacing;
        final @Nullable Part part;
        final FarSnowGrid.Columns live;
        final int originX;
        final int originZ;
        /** Null when the fill doesn't change this ring. */
        final FarSnowGrid.@Nullable Columns into;
        int @Nullable [] lines;
        int @Nullable [] bare;
        byte @Nullable [] areas;
        /** Whether {@link #into} holds the ring's new columns. */
        boolean filled;
        FarSnowGrid.@Nullable Rect upload;
        @Nullable ByteBuffer texels;

        RingFill(FarSnowGrid grid, @Nullable Part part) {
            this.spacing = grid.spacing;
            this.part = part;
            this.live = grid.live();
            this.originX = grid.originX();
            this.originZ = grid.originZ();
            this.into = part != null ? grid.takeSpare() : null;
        }

        int resultOriginX() {
            return part != null && part.move() ? part.originX() : originX;
        }

        int resultOriginZ() {
            return part != null && part.move() ? part.originZ() : originZ;
        }

        void fill(FarSnowSource source, @Nullable ClientLevel level) {
            if (part == null) {
                return;
            }
            FarSnowGrid.Rect rect = part.rect();
            lines = new int[rect.width() * rect.height()];
            bare = new int[lines.length];
            int firstX = part.originX() + spacing / 2 + rect.minX() * spacing;
            int firstZ = part.originZ() + spacing / 2 + rect.minZ() * spacing;
            source.fillLines(level, firstX, firstZ, spacing, rect.width(), rect.height(), lines);
            source.fillBareLines(level, firstX, firstZ, spacing, rect.width(), rect.height(), bare);
            areas = new byte[lines.length];
            source.fillAreas(level, firstX, firstZ, spacing, rect.width(), rect.height(), areas);
        }

        /**
         * Builds the ring's new columns, the live ones with the new lines taken in (a ring that moved
         * is filled whole), and encodes what the far pass needs again.
         */
        void merge(int floor, ByteBuffer out) {
            if (into == null || part == null) {
                return;
            }
            if (!part.move()) {
                into.copyFrom(live);
            }
            into.set(part.rect(), lines, bare, areas);
            filled = true;
            upload = part.rect();
            into.encode(upload, floor, out);
            texels = out;
        }

        boolean hasKnown() {
            return filled ? into.hasKnown() : live.hasKnown();
        }
    }

    private static final class LinesJob {
        final @Nullable ClientLevel level;
        final FarSnowSource source;
        final int floor;
        final RingFill inner;
        final RingFill outer;
        final ByteBuffer staging;
        // The result.
        boolean anyKnown;
        long nanos;

        LinesJob(@Nullable ClientLevel level, FarSnowSource source, int floor, RingFill inner, RingFill outer, ByteBuffer staging) {
            this.level = level;
            this.source = source;
            this.floor = floor;
            this.inner = inner;
            this.outer = outer;
            this.staging = staging;
        }

        LinesJob run() {
            long started = System.nanoTime();
            inner.fill(source, level);
            outer.fill(source, level);
            inner.merge(floor, staging.slice(0, RING_BYTES).order(ByteOrder.LITTLE_ENDIAN));
            outer.merge(floor, staging.slice(RING_BYTES, RING_BYTES).order(ByteOrder.LITTLE_ENDIAN));
            anyKnown = inner.hasKnown() || outer.hasKnown();
            nanos = System.nanoTime() - started;
            return this;
        }
    }

    private static final class ColourJob {
        final @Nullable ClientLevel level;
        final FarSnowSource source;
        final int originX;
        final int originZ;
        final ByteBuffer staging;
        final int[] grass = new int[COLOUR_SIZE * COLOUR_SIZE];
        /** -1 where unknown, and on land hot all year, which isn't tinted. */
        final int[] foliage = new int[COLOUR_SIZE * COLOUR_SIZE];
        int known;
        long nanos;

        ColourJob(@Nullable ClientLevel level, FarSnowSource source, int originX, int originZ, ByteBuffer staging) {
            this.level = level;
            this.source = source;
            this.originX = originX;
            this.originZ = originZ;
            this.staging = staging;
        }

        ColourJob run() {
            long started = System.nanoTime();
            for (int z = 0; z < COLOUR_SIZE; z++) {
                for (int x = 0; x < COLOUR_SIZE; x++) {
                    int columnX = originX + x * COLOUR_SPACING + COLOUR_SPACING / 2;
                    int columnZ = originZ + z * COLOUR_SPACING + COLOUR_SPACING / 2;
                    int colour = source.grassColour(level, columnX, columnZ);
                    grass[z * COLOUR_SIZE + x] = colour;
                    foliage[z * COLOUR_SIZE + x] = colour < 0 || source.hot(level, columnX, columnZ) ? -1 : source.foliageColour(level, columnX, columnZ);
                    if (colour >= 0) {
                        known++;
                    }
                }
            }
            encode();
            nanos = System.nanoTime() - started;
            return this;
        }

        /** The texels: grass rows, then foliage rows, alpha 254 on a foliage cell the far pass must blend with its neighbours. */
        private void encode() {
            int index = 0;
            for (int colour : grass) {
                staging.putInt(index, texel(colour));
                index += 4;
            }
            for (int z = 0; z < COLOUR_SIZE; z++) {
                for (int x = 0; x < COLOUR_SIZE; x++) {
                    int texel = texel(foliage[z * COLOUR_SIZE + x]);
                    staging.putInt(index, texel == 0 || FarSnow.sameAround(grass, foliage, x, z) ? texel : texel & 0x00FFFFFF | 0xFE000000);
                    index += 4;
                }
            }
            staging.limit(index);
        }
    }
}
