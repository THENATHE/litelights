package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.post.PostContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Seasonal snow on far terrain (farsnow), with Almanac on the client. Voxy and Distant Horizons draw
 * far land as it was when each chunk was last loaded, so a winter capture stays white all summer and
 * a summer one green all winter. Almanac's server streams a climate map, and its client answers per
 * column where snow lies now (its far snow line); this keeps those lines around the camera in a
 * texture the far pass reads (shaders/include/farterrain/far_snow.glsl), which paints the snow on or
 * takes it off.
 *
 * <ul>
 *   <li><b>Two rings</b> ({@link FarSnowGrid}): 512 x 512 lines 16 blocks apart (8 km across,
 *   Almanac's default map radius) and 64 apart (32 km, its largest), one texture, both following the
 *   camera in whole steps. The season's grass and foliage colours, for captured snow that has to go
 *   and for tinting far grass and leaves to the season, are 256 x 256 columns 32 blocks apart each,
 *   in a second texture.</li>
 *   <li><b>Filled on a background thread</b> (Almanac's lookups are safe from any thread), only when
 *   Almanac says something changed ({@code FAR_CLIMATE_CHANGED}, only the area that changed) or a
 *   ring moves; never per frame. One fill at a time, and what changes meanwhile waits for the next.</li>
 *   <li><b>Changes are shown in patches</b>: when what the season shows changes, the lines before the
 *   change are kept beside the new ones and the far pass switches each column once the change's
 *   progress passes the column's own threshold. The change lasts as long as Almanac's thaw in loaded
 *   chunks ({@code thawTicks}, or half an in-game day), counted in game ticks from the tick Almanac
 *   says it began (0.1.3; before, from when it arrives). Against an Almanac 0.1.2 server, whose near
 *   snow comes as soon as a layer lies, snow that forms from a snowfall follows the snow's depth
 *   instead, a layer for the whole change; from 0.1.3 near snow forms in patches over the thaw
 *   time, and far snow with it.</li>
 *   <li><b>Joining during a change</b> (0.1.3): the change is picked up at its progress, with the
 *   land as the far mod captured it standing in for the lines before it.</li>
 * </ul>
 * Without Almanac nothing here is made, and the far pass draws its old variant.
 */
final class FarSnow implements AutoCloseable {
    static final int INNER_SPACING = 16;
    static final int OUTER_SPACING = 64;
    static final int COLOUR_SIZE = 256;
    static final int COLOUR_SPACING = 32;
    private static final int INNER_STEP = 1024;
    private static final int OUTER_STEP = 4096;
    private static final int COLOUR_STEP = 1024;
    /** A change without Almanac's thaw time: half an in-game day. */
    static final long DEFAULT_CHANGE_TICKS = 12_000L;
    /**
     * Against an Almanac 0.1.2 server, snow forming from a snowfall follows the depth, but takes at
     * most this long: 0.1.2's loaded chunks lay their layer down in a minute or two once it rounds
     * to a whole one, and the snow near the player only grows from there, even when the snowfall
     * stops. From 0.1.3 forming is timed like the thaw, and neither this nor the rise below applies.
     */
    static final long FORMING_TICKS_AT_MOST = 12_000L;
    /**
     * How fast snow may form against a 0.1.2 server, in progress per tick: Almanac's line says
     * "snow" only once half a layer lies, so the first half comes over 50 ticks rather than at once.
     */
    static final double FORMING_RISE_PER_TICK = 0.01;
    private static final int UNIFORM_BYTES = 48;

    private final @Nullable FarSnowSource source;
    private final @Nullable String problem;
    private final FarSnowGrid inner = new FarSnowGrid(INNER_SPACING, INNER_STEP);
    private final FarSnowGrid outer = new FarSnowGrid(OUTER_SPACING, OUTER_STEP);
    private boolean coloursPlaced;
    private boolean coloursDirty = true;
    private boolean coloursKnown;
    private int colourOriginX;
    private int colourOriginZ;

    private @Nullable ClientLevel level;
    private int floor;
    private @Nullable CompletableFuture<LinesJob> linesJob;
    private @Nullable CompletableFuture<ColourJob> colourJob;
    /** The season changed: the next fill is the change's "after". */
    private boolean changeWaiting;
    /** The tick Almanac says the waiting change began, or -1 (before 0.1.3). */
    private long pendingChangeStart = -1;
    private boolean changeRunning;
    /** The change being shown was under way when the level was joined: it has no "before" lines. */
    private boolean resumed;
    /** Whether a change under way at joining has been looked for (Almanac's change tick has arrived). */
    private boolean resumeChecked;
    private long changeStart;
    private long changeTicks = DEFAULT_CHANGE_TICKS;
    /** Bits of Almanac's climates (HOT, TEMPERATE, COLD) whose snow appeared in this change, so their depth drives it. */
    private int formingClimates;
    private int pendingFormingClimates;
    private float progress = 1.0f;
    private final double[] depths = new double[3];
    private final int[] shownLayers = new int[3];
    private int shownSubSeason = -2;

    private @Nullable GpuTexture linesTexture;
    private @Nullable GpuTextureView linesView;
    private @Nullable GpuTexture colourTexture;
    private @Nullable GpuTextureView colourView;
    private @Nullable GpuBuffer uniforms;
    private @Nullable ByteBuffer uploadBuffer;

    // For QA and the debug line.
    private int fills;
    private double lastFillMillis;
    private double lastColourFillMillis;
    private int changes;
    private int resumes;
    private int lastChanging;
    private boolean anyKnown;
    /** The render thread's share of the last fill: taking it in and uploading it. */
    private double lastApplyMillis;

    private FarSnow(@Nullable FarSnowSource source, @Nullable String problem) {
        this.source = source;
        this.problem = problem;
        if (source != null) {
            source.listen(this::changed);
        }
    }

    /** With Almanac 0.1.2 or later on the client, far snow from its lookups; otherwise none. */
    static FarSnow create() {
        if (!FabricLoader.getInstance().isModLoaded("almanac")) {
            return new FarSnow(null, "Needs Almanac on the client");
        }
        try {
            return new FarSnow(AlmanacFarSnow.create(), null);
        } catch (LinkageError | RuntimeException olderAlmanac) {
            LiteLightsClient.LOG.warn("Almanac's far-terrain lookups aren't there ({}); far terrain keeps the snow it was captured with",
                olderAlmanac.toString());
            return new FarSnow(null, "Needs Almanac 0.1.2 or later");
        }
    }

    boolean available() {
        return source != null;
    }

    /** Why far snow can't run, or null. */
    @Nullable String problem() {
        return problem;
    }

    float progress() {
        return progress;
    }

    int changes() {
        return changes;
    }

    int resumes() {
        return resumes;
    }

    long changeStart() {
        return changeStart;
    }

    /** For QA: columns with a known line in the inner ring (counted when asked). */
    int knownInner() {
        return inner.known();
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
            noteSeasonChange(changedLevel);
            inner.markAllDirty();
            outer.markAllDirty();
        } else {
            inner.markDirty(area);
            outer.markDirty(area);
        }
    }

    /**
     * What the season shows changed. Against a 0.1.2 server, climates whose snow has just appeared
     * in the same sub-season (it has snowed there) let their depth drive the change; anything else,
     * and everything from 0.1.3 (whose server says when the change began), is timed.
     */
    private void noteSeasonChange(ClientLevel changedLevel) {
        FarSnowSource from = source;
        if (from == null) {
            return;
        }
        long began = from.changedTick(changedLevel);
        int subSeason = from.subSeason(changedLevel);
        from.snowDepths(changedLevel, depths);
        int forming = 0;
        for (int climate = 0; climate < 3; climate++) {
            int layers = wholeLayers(depths[climate]);
            if (followsDepth(began, subSeason, shownSubSeason, shownLayers[climate], layers)) {
                forming |= 1 << climate;
            }
            shownLayers[climate] = layers;
        }
        shownSubSeason = subSeason;
        pendingFormingClimates = forming;
        pendingChangeStart = began;
        changeWaiting = true;
        resumeChecked = true;
    }

    /**
     * Whether a climate's change follows its snow's depth: against a 0.1.2 server (no change tick),
     * when its first layer has just come in the same sub-season (it has snowed there).
     */
    static boolean followsDepth(long began, int subSeason, int shownSubSeason, int shownLayers, int layers) {
        return began < 0 && subSeason == shownSubSeason && shownLayers == 0 && layers > 0;
    }

    /** Layers on the ground for a depth, rounded half up as Almanac lays them; 0 when unknown. */
    static int wholeLayers(double depth) {
        return depth < 0.0 ? 0 : (int) Math.floor(depth + 0.5);
    }

    // --- Per frame (render thread) ---

    /**
     * Brings the lines up to date for this frame and works out the change's progress. Returns
     * whether the far pass should draw its snow variant: any line around the camera is known.
     */
    boolean prepare(ClientLevel currentLevel, double cameraX, double cameraZ, long gameTime, float partialTick) {
        FarSnowSource from = source;
        if (from == null) {
            return false;
        }
        if (currentLevel != level) {
            startLevel(currentLevel, from);
        }
        if (!resumeChecked) {
            resumeChange(currentLevel, from, gameTime);
        }
        finishJobs();
        startJobs(currentLevel, from, cameraX, cameraZ);
        updateProgress(gameTime + partialTick);
        writeUniforms();
        return inner.placed() && anyKnown;
    }

    private void startLevel(ClientLevel currentLevel, FarSnowSource from) {
        level = currentLevel;
        floor = currentLevel.getMinY();
        inner.clear();
        outer.clear();
        coloursPlaced = false;
        coloursDirty = true;
        coloursKnown = false;
        changeWaiting = false;
        changeRunning = false;
        resumed = false;
        resumeChecked = false;
        progress = 1.0f;
        anyKnown = false;
        shownSubSeason = from.subSeason(currentLevel);
        from.snowDepths(currentLevel, depths);
        for (int climate = 0; climate < 3; climate++) {
            shownLayers[climate] = wholeLayers(depths[climate]);
        }
    }

    /**
     * Picks up a change of what the season shows that was under way when the level was joined
     * (relogging mid-melt, say), at the progress every other player sees: Almanac 0.1.3 says when it
     * began. Waits for that tick to arrive; against an older Almanac it never does, and nothing is
     * resumed.
     */
    private void resumeChange(ClientLevel currentLevel, FarSnowSource from, long gameTime) {
        long began = from.changedTick(currentLevel);
        if (began < 0) {
            return;
        }
        resumeChecked = true;
        long thaw = from.thawTicks(currentLevel);
        long length = thaw > 0 ? thaw : DEFAULT_CHANGE_TICKS;
        if (!underWay(began, gameTime, length)) {
            return;
        }
        resumes++;
        resumed = true;
        changeRunning = true;
        changeStart = began;
        changeTicks = length;
        formingClimates = 0;
    }

    /** Whether a change that began at a tick is still being shown at another. */
    static boolean underWay(long began, long gameTime, long length) {
        long elapsed = gameTime - began;
        return elapsed >= 0 && elapsed < length;
    }

    private void startJobs(ClientLevel currentLevel, FarSnowSource from, double cameraX, double cameraZ) {
        if (linesJob == null) {
            boolean change = changeWaiting;
            LinesJob job = new LinesJob(currentLevel, from, change);
            if (change) {
                changeWaiting = false;
                job.formingClimates = pendingFormingClimates;
                job.changeStart = pendingChangeStart;
                job.changeTicks = from.thawTicks(currentLevel);
            }
            job.inner = plan(inner, cameraX, cameraZ);
            job.outer = plan(outer, cameraX, cameraZ);
            if (job.inner != null || job.outer != null || change) {
                linesJob = CompletableFuture.supplyAsync(job::run, Util.backgroundExecutor());
            }
        }
        if (colourJob == null && (coloursDirty || !coloursPlaced || coloursWantMove(cameraX, cameraZ))) {
            coloursDirty = false;
            ColourJob job = new ColourJob(currentLevel, from, FarSnowGrid.originFor(cameraX, COLOUR_STEP, COLOUR_SIZE * COLOUR_SPACING),
                FarSnowGrid.originFor(cameraZ, COLOUR_STEP, COLOUR_SIZE * COLOUR_SPACING));
            colourJob = CompletableFuture.supplyAsync(job::run, Util.backgroundExecutor());
        }
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

    /** Takes in finished fills. A fill that failed is passed on, so far snow reports it and stops. */
    private void finishJobs() {
        CompletableFuture<LinesJob> lines = linesJob;
        if (lines != null && lines.isDone()) {
            linesJob = null;
            if (lines.isCompletedExceptionally()) {
                throw new IllegalStateException("Filling the far snow lines failed", lines.exceptionNow());
            }
            LinesJob job = lines.resultNow();
            if (job.level == level) {
                apply(job);
            }
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
        }
    }

    private void apply(LinesJob job) {
        long started = System.nanoTime();
        fills++;
        lastFillMillis = job.nanos / 1.0e6;
        int changing = job.change ? changesIn(inner, job.inner, job.innerLines) + changesIn(outer, job.outer, job.outerLines) : 0;
        if (changing == 0) {
            // Only seen better, or a change of season that changes no line here (snow melting where
            // the season already shows none): a change being shown carries on.
            applyPart(inner, job.inner, job.innerLines, false, 0);
            applyPart(outer, job.outer, job.outerLines, false, FarSnowGrid.SIZE);
        } else {
            if (changeRunning) {
                // A new change cuts the last one short: its columns still to switch do so now.
                inner.settle();
                outer.settle();
            }
            resumed = false;
            applyPart(inner, job.inner, job.innerLines, true, 0);
            applyPart(outer, job.outer, job.outerLines, true, FarSnowGrid.SIZE);
            changes++;
            lastChanging = changing;
            changeRunning = true;
            changeStart = job.changeStart >= 0 ? job.changeStart : level == null ? 0L : level.getGameTime();
            changeTicks = job.changeTicks > 0 ? job.changeTicks : DEFAULT_CHANGE_TICKS;
            formingClimates = job.formingClimates;
            progress = 0.0f;
        }
        anyKnown = inner.hasKnown() || outer.hasKnown();
        lastApplyMillis = (System.nanoTime() - started) / 1.0e6;
    }

    /** How many of a ring's columns a change of season fills a new line into (a ring that moves animates nothing). */
    private static int changesIn(FarSnowGrid grid, @Nullable Part part, int @Nullable [] lines) {
        return part == null || lines == null || part.move ? 0 : grid.countChanges(part.rect, lines);
    }

    private void applyPart(FarSnowGrid grid, @Nullable Part part, int @Nullable [] lines, boolean change, int row) {
        if (lines != null) {
            if (part.move) {
                grid.moveTo(part.originX, part.originZ, lines, changeRunning);
            } else if (change) {
                grid.acceptChange(part.rect, lines);
            } else {
                grid.accept(part.rect, lines);
            }
        }
        if (change || lines != null) {
            upload(grid, change || part == null || part.move ? FarSnowGrid.Rect.ALL : part.rect, row);
        }
    }

    private void applyColours(ColourJob job) {
        colourOriginX = job.originX;
        colourOriginZ = job.originZ;
        coloursPlaced = true;
        coloursKnown = job.known > 0;
        lastColourFillMillis = job.nanos / 1.0e6;
        ensureTextures();
        ByteBuffer buffer = MemoryUtil.memAlloc(COLOUR_SIZE * COLOUR_SIZE * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (int colour : job.grass) {
                buffer.putInt(texel(colour));
            }
            for (int z = 0; z < COLOUR_SIZE; z++) {
                for (int x = 0; x < COLOUR_SIZE; x++) {
                    int texel = texel(job.foliage[z * COLOUR_SIZE + x]);
                    // Alpha 254 where the far pass must blend this cell with its neighbours.
                    buffer.putInt(texel == 0 || job.sameAround[z * COLOUR_SIZE + x] ? texel : texel & 0x00FFFFFF | 0xFE000000);
                }
            }
            buffer.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(colourTexture, buffer, 0, 0, 0, 0, COLOUR_SIZE, COLOUR_SIZE * 2);
        } finally {
            MemoryUtil.memFree(buffer);
        }
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

    private void upload(FarSnowGrid grid, FarSnowGrid.Rect rect, int row) {
        ensureTextures();
        ByteBuffer buffer = uploadBuffer();
        grid.encode(rect, floor, buffer);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(linesTexture, buffer, 0, 0, rect.minX(), row + rect.minZ(),
            rect.width(), rect.height());
    }

    /** One ring's worth of texels, kept while far snow runs. */
    private ByteBuffer uploadBuffer() {
        if (uploadBuffer == null) {
            uploadBuffer = MemoryUtil.memAlloc(FarSnowGrid.SIZE * FarSnowGrid.SIZE * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
        uploadBuffer.clear();
        return uploadBuffer;
    }

    private void updateProgress(double now) {
        if (!changeRunning) {
            return;
        }
        double elapsed = Math.max(0.0, now - changeStart);
        boolean followDepth = formingClimates != 0 && source != null && level != null;
        if (followDepth) {
            source.snowDepths(level, depths);
        }
        progress = (float) changeProgress(elapsed, changeTicks, followDepth, followDepth ? deepest(formingClimates) : 0.0);
        if (progress >= 1.0f) {
            changeRunning = false;
            resumed = false;
            inner.settle();
            outer.settle();
        }
    }

    private double deepest(int climates) {
        double deepest = 0.0;
        for (int climate = 0; climate < 3; climate++) {
            if ((climates & 1 << climate) != 0) {
                deepest = Math.max(deepest, depths[climate]);
            }
        }
        return deepest;
    }

    /**
     * How far through a change is, 0 to 1: the elapsed share of its time, or for snow that follows
     * its depth (against a 0.1.2 server) {@link #formingProgress}.
     */
    static double changeProgress(double elapsedTicks, long changeTicks, boolean followDepth, double depth) {
        double reached = followDepth ? formingProgress(elapsedTicks, depth, Math.min(changeTicks, FORMING_TICKS_AT_MOST)) : elapsedTicks / changeTicks;
        return Math.clamp(reached, 0.0, 1.0);
    }

    /**
     * Snow forming from a snowfall: as far along as the snow is deep (a layer for the whole change),
     * at least as far as a steady pace over {@code longest} ticks, and never rising faster than
     * {@link #FORMING_RISE_PER_TICK}.
     */
    static double formingProgress(double elapsedTicks, double depth, long longest) {
        double target = Math.max(depth, elapsedTicks / longest);
        return Math.min(target, elapsedTicks * FORMING_RISE_PER_TICK);
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
        colourTexture = device.createTexture("LiteLights far snow colours", usage, GpuFormat.RGBA8_UNORM, COLOUR_SIZE, COLOUR_SIZE * 2, 1, 1);
        colourView = device.createTextureView(colourTexture);
        uniforms = device.createBuffer(() -> "LiteLights far snow", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
        // Nothing known until the first fill: every code 0.
        ByteBuffer empty = MemoryUtil.memCalloc(FarSnowGrid.SIZE * FarSnowGrid.SIZE * 2 * 4);
        try {
            var encoder = device.createCommandEncoder();
            encoder.writeToTexture(linesTexture, empty, 0, 0, 0, 0, FarSnowGrid.SIZE, FarSnowGrid.SIZE * 2);
            empty.limit(COLOUR_SIZE * COLOUR_SIZE * 2 * 4);
            encoder.writeToTexture(colourTexture, empty, 0, 0, 0, 0, COLOUR_SIZE, COLOUR_SIZE * 2);
        } finally {
            MemoryUtil.memFree(empty);
        }
    }

    private void writeUniforms() {
        ensureTextures();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_BYTES)
                .putIVec4(inner.originX(), inner.originZ(), outer.originX(), outer.originZ())
                .putIVec4(colourOriginX, colourOriginZ, resumed && changeRunning ? 1 : 0, 0)
                .putVec4(progress, floor, coloursKnown ? 1.0f : 0.0f, debugView() ? 1.0f : 0.0f)
                .get();
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
        pass.setUniform("FarSnowLines", linesView, PostContext.nearestSampler());
        pass.setUniform("FarSnowColours", colourView, PostContext.linearSampler());
    }

    /** For QA: what far snow knows and is doing. */
    String describe() {
        if (source == null) {
            return "absent (" + problem + ")";
        }
        return String.format(Locale.ROOT, "knownInner=%d, knownOuter=%d, fills=%d, lastFillMs=%.1f, lastApplyMs=%.2f, changes=%d, changing=%d, progress=%.3f, running=%s, resumed=%s, resumes=%d, changeStart=%d, forming=%d, changeTicks=%d, colours=%s, lastColourFillMs=%.1f",
            inner.known(), outer.known(), fills, lastFillMillis, lastApplyMillis, changes, lastChanging, progress, changeRunning, resumed, resumes, changeStart,
            formingClimates, changeTicks, coloursKnown, lastColourFillMillis);
    }

    /** The world was left: every line is forgotten and the textures go. */
    @Override
    public void close() {
        level = null;
        inner.clear();
        outer.clear();
        coloursPlaced = false;
        changeRunning = false;
        changeWaiting = false;
        resumed = false;
        progress = 1.0f;
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
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
        }
    }

    // --- Background fills ---

    /** One ring's share of a fill: where the ring starts, and which of its texels to fill. */
    private record Part(int originX, int originZ, FarSnowGrid.Rect rect, boolean move) {
    }

    private static final class LinesJob {
        final ClientLevel level;
        final FarSnowSource source;
        final boolean change;
        @Nullable Part inner;
        @Nullable Part outer;
        int @Nullable [] innerLines;
        int @Nullable [] outerLines;
        int formingClimates;
        long changeStart = -1;
        long changeTicks = -1;
        long nanos;

        LinesJob(ClientLevel level, FarSnowSource source, boolean change) {
            this.level = level;
            this.source = source;
            this.change = change;
        }

        LinesJob run() {
            long started = System.nanoTime();
            innerLines = fill(inner, INNER_SPACING);
            outerLines = fill(outer, OUTER_SPACING);
            nanos = System.nanoTime() - started;
            return this;
        }

        private int @Nullable [] fill(@Nullable Part part, int spacing) {
            if (part == null) {
                return null;
            }
            FarSnowGrid.Rect rect = part.rect();
            int[] lines = new int[rect.width() * rect.height()];
            source.fillLines(level, part.originX() + spacing / 2 + rect.minX() * spacing, part.originZ() + spacing / 2 + rect.minZ() * spacing,
                spacing, rect.width(), rect.height(), lines);
            return lines;
        }
    }

    private static final class ColourJob {
        final ClientLevel level;
        final FarSnowSource source;
        final int originX;
        final int originZ;
        final int[] grass = new int[COLOUR_SIZE * COLOUR_SIZE];
        /** -1 where unknown, and on land hot all year, which isn't tinted. */
        final int[] foliage = new int[COLOUR_SIZE * COLOUR_SIZE];
        /** Cells whose neighbours after them have the same colours ({@link #sameAround}). */
        final boolean[] sameAround = new boolean[COLOUR_SIZE * COLOUR_SIZE];
        int known;
        long nanos;

        ColourJob(ClientLevel level, FarSnowSource source, int originX, int originZ) {
            this.level = level;
            this.source = source;
            this.originX = originX;
            this.originZ = originZ;
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
            for (int z = 0; z < COLOUR_SIZE; z++) {
                for (int x = 0; x < COLOUR_SIZE; x++) {
                    sameAround[z * COLOUR_SIZE + x] = FarSnow.sameAround(grass, foliage, x, z);
                }
            }
            nanos = System.nanoTime() - started;
            return this;
        }
    }
}
