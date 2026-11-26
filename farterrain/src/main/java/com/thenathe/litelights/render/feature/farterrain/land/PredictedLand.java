package com.thenathe.litelights.render.feature.farterrain.land;

import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.render.feature.farterrain.seed.Canopies;
import com.thenathe.litelights.render.feature.farterrain.seed.PredictionInputs;
import com.thenathe.litelights.render.feature.farterrain.seed.TerrainSampler;
import com.thenathe.litelights.render.feature.farterrain.seed.TileIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Seed generation's land for one world and dimension (docs/FAR-TERRAIN.md, "Seed generation"):
 * tiles predicted from the seed ({@link TerrainSampler}) on far terrain's background threads, kept
 * in a store of their own beside the visited one, under a folder named for what they were made
 * with (the seed and the generation's fingerprint), so a new seed or new mods start a new
 * prediction and never mix with an old one. {@link LandView} puts the two together for drawing.
 *
 * <p><b>What is predicted, in what order.</b> Wherever the selection will split a tile (closer
 * than {@code levelDistance << level}), its four children are predicted together, so a tile never
 * splits into a part predicted and three parts missing. The fill goes in three steps, in the
 * owner's order (DECISIONS, seedgen-speed-115), worked out afresh round the camera at every plan, so
 * they follow the player: first the land near the camera, out to {@link #NEAR_RING_BLOCKS} past the
 * render distance, to its own detail; then one coarse pass over all the rest, the whole far distance
 * at a coarse level and then as finely as Detail Low draws it; then the rest refined to the chosen
 * Detail. Within a step the coarse cover comes first, nearest first, and then the tile drawn most
 * coarsely for its distance. A tile whose every column the player has already seen isn't predicted.
 *
 * <p><b>Cost.</b> Tasks go on the mesh pool behind every mesh, at most {@link #allowed} at a time;
 * in single player that falls by half while the integrated server's mean tick is over
 * {@link #BUSY_TICK_MILLIS} and rises by one while it is under {@link #EASY_TICK_MILLIS}, so the
 * game's own server keeps its 20 ticks.
 *
 * <p><b>Accuracy.</b> As visited land arrives, its columns are compared with what was predicted
 * for them (the ground's top, trees left out on both sides); far off is counted, and if most of
 * it is (a wrong seed, other world-generation mods) the predictions are flagged as not matching.
 *
 * <p><b>Progress and the first fill.</b> Each plan also walks the whole fill round the camera
 * ({@link FillProgress}) for the page's status, and the speed is the tiles predicted over the last
 * ten seconds. A prediction that starts empty is a first fill: a marker file in its folder says so
 * until the first two steps are done ({@link #firstSetBuilt}), so far terrain can hold its land back
 * meanwhile (FarLandSettings.waitForDetail), across a restart too.
 */
final class PredictedLand implements LandView.Predictions, AutoCloseable {
    /** Prediction tasks queue behind every mesh. */
    static final double TASK_PRIORITY = 1.0e12;
    /** The game's tick, 20 a second: the budget the integrated server's mean tick is measured against. */
    private static final double TICK_MILLIS = 50.0;
    /**
     * The server is under pressure once its mean tick takes 60 % of the tick, and easy again under
     * 30 %: halving the work can bring it from one to the other, and every reading taken while
     * predicting stayed under the lower one (DECISIONS, tidy-up for 1.0.14).
     */
    static final double BUSY_TICK_MILLIS = 0.6 * TICK_MILLIS;
    static final double EASY_TICK_MILLIS = 0.3 * TICK_MILLIS;
    /** Half the 100 ticks the server's mean is taken over, so each step shows in the mean before the next. */
    private static final long PRESSURE_STEP_MILLIS = 2_500L;
    private static final long PLAN_MILLIS = 500L;
    private static final long FLUSH_MILLIS = 10_000L;
    private static final long HELD_BYTES = 24L << 20;
    /** The children of a parent are one task: this bit on the parent's key. */
    private static final long CHILDREN = 1L << 62;
    /** Visited tiles compared with a prediction made just for the comparison, in one session at most... */
    private static final int MAX_SAMPLED_COMPARISONS = 200;
    /** ...and with a prediction already stored, at most. */
    private static final int MAX_COMPARISONS = 4000;
    /** Columns compared before a verdict on whether predictions match (about twelve tiles of visited land). */
    private static final int VERDICT_COLUMNS = 12_000;
    /** In a prediction's folder while its first fill isn't built yet. */
    static final String FIRST_FILL_MARKER = "first-fill-pending";
    /** The fill's steps: the land near the camera to its own detail... */
    static final int STEP_NEAR = 0;
    /** ...one coarse pass over all the rest (Detail Low's levels)... */
    static final int STEP_COARSE = 1;
    /** ...and the rest refined to the chosen Detail. */
    static final int STEP_REFINE = 2;
    /**
     * How far past the render distance the first step reaches. There Detail Low draws land with
     * voxels two blocks wide, a few pixels from the ground; nearer it would show as blocky
     * (DECISIONS, seedgen-speed-115).
     */
    static final int NEAR_RING_BLOCKS = 256;
    /** The coarse pass's levels: as Detail Low draws land, a level coarser than Medium everywhere. */
    static final float COARSE_LEVEL_DISTANCE = FarLandSettings.Detail.LOW.levelDistance;
    /** The speed is the tiles predicted over this long. */
    private static final long SPEED_WINDOW_NANOS = 10_000_000_000L;

    private final FarStore store;
    private final FarStore visited;
    private final BlockPalette palette;
    private final BlockPalette sessionPalette;
    private final BlockLooks looks;
    private final PredictionInputs inputs;
    private final Canopies canopies;
    private final ThreadPoolExecutor pool;
    private final int minY;
    private final int worldHeight;
    private final int maxWorkers;
    private final ThreadLocal<TerrainSampler> samplers;
    private final PredictedIds ids = new PredictedIds();
    private final Object writeLock = new Object();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Long> fullySeen = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> toCompare = new ConcurrentLinkedQueue<>();
    private final Set<Long> compared = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sampledComparisons = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong tilesPredicted = new AtomicLong();
    private final AtomicLong sampleNanos = new AtomicLong();
    private final AtomicLong groundPredicted = new AtomicLong();
    private final long[] perLevel = new long[TileKey.MAX_LEVEL + 1];
    private final Accuracy accuracy = new Accuracy();
    private volatile boolean opened;
    private volatile boolean stopped;
    private volatile @Nullable String problem;
    private volatile int allowed;
    private long lastPlan;
    private long lastPressureStep;
    private long lastFlush = System.currentTimeMillis();
    private double planX = Double.NaN;
    private double planZ = Double.NaN;
    private int wantedLast;
    private double lastTickMillis = -1.0;
    private double worstTickMillis;
    private final long openedAt = System.nanoTime();
    private long coverAt;
    private volatile FillProgress.Reading progress = FillProgress.Reading.empty();
    /** Tiles predicted at each of the last plans, for the speed: [nanos, count] pairs, oldest first. */
    private final ArrayDeque<long[]> speedSamples = new ArrayDeque<>();
    private volatile double tilesPerSecond = -1.0;
    /** The share of the first two steps' tiles done over the same window, for the first fill's time left. */
    private final ArrayDeque<double[]> shareSamples = new ArrayDeque<>();
    private volatile double sharePerSecond = -1.0;
    private volatile boolean firstFillPending;
    private volatile long firstSetAt;
    /** When the ground past the render distance was first all drawn within 0, 1, 2 and 3 levels of its detail (QA). */
    private final long[] withinExcessAt = new long[4];
    /** When each step of the fill was first done (QA). */
    private final long[] stepsDoneAt = new long[3];

    PredictedLand(Path folder, FarStore visited, BlockPalette sessionPalette, BlockLooks looks, ClientLevel level, PredictionInputs inputs,
                  ThreadPoolExecutor pool, int minY, int worldHeight) {
        this.store = new FarStore(folder);
        this.visited = visited;
        this.palette = new BlockPalette(store.blocks(), store.biomes(), level);
        this.sessionPalette = sessionPalette;
        this.looks = looks;
        this.inputs = inputs;
        this.canopies = new Canopies(inputs.seed());
        this.pool = pool;
        this.minY = minY;
        this.worldHeight = worldHeight;
        this.maxWorkers = pool.getMaximumPoolSize();
        this.allowed = maxWorkers;
        this.samplers = ThreadLocal.withInitial(() -> new TerrainSampler(inputs, minY, worldHeight, ids, canopies));
        pool.execute(new Task(-1.0, () -> {
            try {
                store.open();
                firstFillPending = firstFill(store);
                opened = true;
            } catch (IOException | RuntimeException failure) {
                problem = "Couldn't open seed generation's store: " + failure.getMessage();
                LiteLightsMod.LOG.error("Couldn't open seed generation's store in {}", store.folder(), failure);
            }
        }));
    }

    Path folder() {
        return store.folder();
    }

    /**
     * Whether this prediction's first fill is still to be built: its marker is there (a first fill
     * cut short), or nothing is stored yet, in which case the marker is written now. A prediction
     * already holding land without a marker (an earlier session's, or 1.1.1's) is shown as it is.
     */
    private static boolean firstFill(FarStore store) throws IOException {
        Path marker = store.folder().resolve(FIRST_FILL_MARKER);
        if (Files.exists(marker)) {
            return true;
        }
        if (store.tileCount() > 0) {
            return false;
        }
        Files.createDirectories(store.folder());
        Files.writeString(marker, "The first fill from the seed isn't built yet: far terrain waits for it.\n");
        return true;
    }

    /** Where the first step ends, from the camera: {@link #NEAR_RING_BLOCKS} past the near terrain. */
    static double nearRing(double nearDistance) {
        return nearDistance + NEAR_RING_BLOCKS;
    }

    /**
     * The step a task belongs to, by its tile's nearest distance: a tile of the coarse cover
     * ({@code cover}) or the splitting of a tile at this level. Near the camera everything is the
     * first step; past it the cover and every split Detail Low makes too are the second.
     */
    static int stepOf(int level, double distance, boolean cover, double nearRing) {
        if (distance < nearRing) {
            return STEP_NEAR;
        }
        if (cover || distance < COARSE_LEVEL_DISTANCE * (1 << level)) {
            return STEP_COARSE;
        }
        return STEP_REFINE;
    }

    /**
     * The plan's order, lowest first: by step; within one, the cover nearest first, then the tile
     * drawn most coarsely for its distance ({@code splitAt}, the distance it splits at).
     */
    static double priority(int step, boolean cover, double distance, double farDistance, double splitAt) {
        return step * 4.0 + (cover ? distance / (farDistance * 4.0) : 1.0 + distance / splitAt);
    }

    /**
     * The first set of levels that Wait for detail waits for: the first two steps done, the
     * ground near the camera at its own detail and all the rest at least as fine as Detail Low draws
     * it. With no ground past the near terrain there is nothing to wait for.
     */
    static boolean firstSetBuilt(FillProgress.Reading reading) {
        return reading.areaNotDrawn() == 0.0 && reading.steps().firstTwoDone();
    }

    @Nullable String problem() {
        return problem;
    }

    // --- The view's side (any thread) ---

    @Override
    public boolean present(long key) {
        return opened && store.present(key);
    }

    @Override
    public @Nullable TileMeta meta(long key) {
        return opened ? store.meta(key) : null;
    }

    /** The predicted tile in the visited store's ids, so the mesher and the looks read it as they read visited land. */
    @Override
    public @Nullable TileVoxels read(long key) throws IOException {
        TileVoxels tile = opened ? store.read(key) : null;
        if (tile == null) {
            return null;
        }
        for (int i = 0; i < tile.states.length; i++) {
            int state = tile.states[i];
            if (state != 0) {
                tile.states[i] = ids.toSession(state);
            }
        }
        for (int i = 0; i < tile.biomes.length; i++) {
            tile.biomes[i] = ids.biomeToSession(tile.biomes[i]);
        }
        return tile;
    }

    /** A tile predicted, or one whose neighbour's edge changed with it; null when there is none. */
    @Nullable Long pollChanged() {
        return changed.poll();
    }

    /** A visited tile was written: compare it with the prediction for its ground, now and then. */
    void visitedChanged(long key) {
        if (TileKey.level(key) == 0 && compared.size() < MAX_COMPARISONS && compared.add(key)) {
            toCompare.add(key);
        }
    }

    boolean matches() {
        return accuracy.verdict() != Boolean.FALSE;
    }

    /** The comparison's readings, for the status and QA. */
    Map<String, Object> accuracy() {
        return accuracy.describe();
    }

    // --- Planning (client thread) ---

    /**
     * Once a frame: steps the back-off by the integrated server's tick, and every half a second
     * (or when the camera has moved 32 blocks) works out what to predict next and hands the most
     * urgent to the workers, as many as are allowed to run.
     */
    void plan(double cameraX, double cameraZ, float farDistance, float levelDistance, double nearDistance, double serverTickMillis, long now) {
        stepPressure(serverTickMillis, now);
        if (!opened || stopped) {
            return;
        }
        if (now - lastFlush > FLUSH_MILLIS) {
            lastFlush = now;
            pool.execute(new Task(TASK_PRIORITY, this::flush));
        }
        double moved = Double.isNaN(planX) ? Double.MAX_VALUE : Math.abs(cameraX - planX) + Math.abs(cameraZ - planZ);
        if (now - lastPlan < PLAN_MILLIS && moved < 32.0) {
            return;
        }
        lastPlan = now;
        planX = cameraX;
        planZ = cameraZ;
        measure(cameraX, cameraZ, farDistance, levelDistance, nearDistance);
        List<Wanted> wanted = new ArrayList<>();
        int top = topLevel(farDistance);
        int span = TileKey.span(top);
        int minX = (int) Math.floor((cameraX - farDistance) / span);
        int maxX = (int) Math.floor((cameraX + farDistance) / span);
        int minZ = (int) Math.floor((cameraZ - farDistance) / span);
        int maxZ = (int) Math.floor((cameraZ + farDistance) / span);
        boolean covered = true;
        double nearRing = nearRing(nearDistance);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                long key = TileKey.of(top, x, z);
                double distance = distance(key, cameraX, cameraZ);
                if (distance > farDistance) {
                    continue;
                }
                if (!done(key)) {
                    covered = false;
                    wanted.add(new Wanted(key, priority(stepOf(top, distance, true, nearRing), true, distance, farDistance, 0.0)));
                } else {
                    planChildren(key, cameraX, cameraZ, farDistance, levelDistance, nearRing, wanted);
                }
            }
        }
        if (covered && coverAt == 0) {
            coverAt = System.nanoTime();
        }
        wantedLast = wanted.size();
        wanted.sort((a, b) -> Double.compare(a.priority, b.priority));
        // Only as many handed over as may run, the rest waiting here where the next plan can
        // reorder them; twice as many while nothing holds the workers back, so each has its next
        // task queued through the half second between plans rather than waiting for the next one
        // (DECISIONS, seedgen-speed-115). The pool's size keeps how many run.
        int handOver = allowed >= maxWorkers ? allowed * 2 : allowed;
        for (Wanted next : wanted) {
            if (inFlight.size() >= handOver) {
                break;
            }
            if (inFlight.add(next.key)) {
                pool.execute(new Task(TASK_PRIORITY + next.priority, () -> predict(next.key)));
            }
        }
        if (!toCompare.isEmpty() && running.get() < allowed) {
            pool.execute(new Task(TASK_PRIORITY + 1.0e6, this::compareSome));
        }
    }

    /** The fill's progress, its speed, and whether the first set of levels is built yet. */
    private void measure(double cameraX, double cameraZ, float farDistance, float levelDistance, double nearDistance) {
        FillProgress.Reading reading = FillProgress.measure(this::done, cameraX, cameraZ, farDistance, levelDistance, nearDistance);
        progress = reading;
        long nanos = System.nanoTime();
        speedSamples.addLast(new long[] {nanos, tilesPredicted.get()});
        while (speedSamples.size() > 2 && nanos - speedSamples.peekFirst()[0] > SPEED_WINDOW_NANOS) {
            speedSamples.removeFirst();
        }
        long[] oldest = speedSamples.peekFirst();
        // Only once the window is mostly full: the first seconds' rate says little.
        tilesPerSecond = nanos - oldest[0] < SPEED_WINDOW_NANOS / 2 ? -1.0 : (tilesPredicted.get() - oldest[1]) / ((nanos - oldest[0]) / 1.0e9);
        double share = reading.steps().firstTwoShare();
        shareSamples.addLast(new double[] {nanos, share});
        while (shareSamples.size() > 2 && nanos - shareSamples.peekFirst()[0] > SPEED_WINDOW_NANOS) {
            shareSamples.removeFirst();
        }
        double[] first = shareSamples.peekFirst();
        sharePerSecond = nanos - first[0] < SPEED_WINDOW_NANOS / 2 ? -1.0 : (share - first[1]) / ((nanos - first[0]) / 1.0e9);
        for (int excess = 0; excess < withinExcessAt.length; excess++) {
            if (withinExcessAt[excess] == 0 && reading.area() > 0.0 && reading.areaNotDrawn() == 0.0 && reading.worstExcess() <= excess) {
                withinExcessAt[excess] = nanos;
            }
        }
        if (stepsDoneAt[STEP_NEAR] == 0 && reading.area() > 0.0 && reading.steps().nearWorstExcess() <= 0) {
            stepsDoneAt[STEP_NEAR] = nanos;
        }
        if (stepsDoneAt[STEP_COARSE] == 0 && reading.area() > 0.0 && firstSetBuilt(reading)) {
            stepsDoneAt[STEP_COARSE] = nanos;
        }
        if (stepsDoneAt[STEP_REFINE] == 0 && reading.wanted() > 0 && reading.complete()) {
            stepsDoneAt[STEP_REFINE] = nanos;
        }
        if (firstFillPending && firstSetBuilt(reading)) {
            firstFillPending = false;
            firstSetAt = nanos;
            Path marker = store.folder().resolve(FIRST_FILL_MARKER);
            pool.execute(new Task(-1.0, () -> {
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException failure) {
                    LiteLightsMod.LOG.warn("Couldn't remove seed generation's first fill marker in {}: {}", store.folder(), failure.toString());
                }
            }));
        }
    }

    private void planChildren(long key, double cameraX, double cameraZ, float farDistance, float levelDistance, double nearRing, List<Wanted> wanted) {
        int level = TileKey.level(key);
        if (level == 0) {
            return;
        }
        double distance = distance(key, cameraX, cameraZ);
        double splitAt = (double) levelDistance * (1 << level);
        if (distance >= splitAt) {
            return;
        }
        boolean allDone = true;
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            if (!done(TileKey.child(key, quadrant))) {
                allDone = false;
                break;
            }
        }
        if (!allDone) {
            wanted.add(new Wanted(key | CHILDREN, priority(stepOf(level, distance, false, nearRing), false, distance, farDistance, splitAt)));
            return;
        }
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            long child = TileKey.child(key, quadrant);
            if (distance(child, cameraX, cameraZ) <= farDistance) {
                planChildren(child, cameraX, cameraZ, farDistance, levelDistance, nearRing, wanted);
            }
        }
    }

    private boolean done(long key) {
        return store.meta(key) != null || fullySeen.contains(key);
    }

    /**
     * The level the whole far distance is first covered at: tiles about a quarter of the distance
     * across (level 5, 1,024 blocks, at 4 km), a few dozen tiles that take seconds.
     */
    static int topLevel(float farDistance) {
        int level = 0;
        while (level < TileKey.MAX_LEVEL && TileKey.span(level) * 4 < farDistance) {
            level++;
        }
        return Math.max(level, TerrainSampler.GRID_LEVEL);
    }

    private static double distance(long key, double cameraX, double cameraZ) {
        int span = TileKey.span(TileKey.level(key));
        double dx = axisDistance(cameraX, TileKey.originX(key), TileKey.originX(key) + span);
        double dz = axisDistance(cameraZ, TileKey.originZ(key), TileKey.originZ(key) + span);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double axisDistance(double camera, double low, double high) {
        return camera < low ? low - camera : camera > high ? camera - high : 0.0;
    }

    /** The integrated server's tick against the thresholds, a step every two and a half seconds at most. */
    private void stepPressure(double tickMillis, long now) {
        lastTickMillis = tickMillis;
        if (tickMillis < 0) {
            allowed = maxWorkers;
            return;
        }
        worstTickMillis = Math.max(worstTickMillis, tickMillis);
        if (now - lastPressureStep < PRESSURE_STEP_MILLIS) {
            return;
        }
        if (tickMillis > BUSY_TICK_MILLIS && allowed > 0) {
            allowed /= 2;
            lastPressureStep = now;
        } else if (tickMillis < EASY_TICK_MILLIS && allowed < maxWorkers) {
            allowed++;
            lastPressureStep = now;
        }
    }

    // --- The work (background threads) ---

    private record Wanted(long key, double priority) {
    }

    private final class Task extends FarTask {
        private final Runnable work;

        Task(double priority, Runnable work) {
            super(priority);
            this.work = work;
        }

        @Override
        public void run() {
            if (stopped) {
                return;
            }
            running.incrementAndGet();
            try {
                work.run();
            } catch (Throwable failure) {
                problem = "Seed generation failed: " + failure;
                LiteLightsMod.LOG.error("Seed generation failed", failure);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    /** Predicts a tile, or the four children of a parent, and writes them together. */
    private void predict(long work) {
        try {
            boolean children = (work & CHILDREN) != 0;
            long key = work & ~CHILDREN;
            List<Long> keys = new ArrayList<>(4);
            if (children) {
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    keys.add(TileKey.child(key, quadrant));
                }
            } else {
                keys.add(key);
            }
            Map<Long, TileVoxels> made = new LinkedHashMap<>();
            for (long tile : keys) {
                if (stopped || done(tile)) {
                    continue;
                }
                if (seenWhole(tile)) {
                    fullySeen.add(tile);
                    continue;
                }
                long started = System.nanoTime();
                made.put(tile, samplers.get().sample(tile));
                sampleNanos.addAndGet(System.nanoTime() - started);
            }
            if (made.isEmpty() || stopped) {
                return;
            }
            synchronized (writeLock) {
                for (Map.Entry<Long, TileVoxels> entry : made.entrySet()) {
                    long tile = entry.getKey();
                    store.write(tile, entry.getValue(), ids);
                    int level = TileKey.level(tile);
                    synchronized (perLevel) {
                        perLevel[level]++;
                    }
                    tilesPredicted.incrementAndGet();
                    groundPredicted.addAndGet((long) TileKey.span(level) * TileKey.span(level));
                    changed.add(tile);
                    changed.add(TileKey.neighbour(tile, 0, -1));
                    changed.add(TileKey.neighbour(tile, 0, 1));
                    changed.add(TileKey.neighbour(tile, -1, 0));
                    changed.add(TileKey.neighbour(tile, 1, 0));
                }
            }
        } finally {
            inFlight.remove(work);
        }
    }

    /** Whether the player has seen every column of a tile already, so predicting it is wasted. */
    private boolean seenWhole(long key) {
        if (visited.meta(key) == null) {
            return false;
        }
        try {
            TileVoxels seen = visited.read(key);
            if (seen == null) {
                return false;
            }
            for (long word : seen.known) {
                if (word != -1L) {
                    return false;
                }
            }
            return true;
        } catch (IOException unreadable) {
            return false;
        }
    }

    private void flush() {
        synchronized (writeLock) {
            try {
                store.flush();
                store.trim(HELD_BYTES);
            } catch (IOException | RuntimeException failure) {
                problem = "Couldn't write seed generation's land to disk: " + failure.getMessage();
                LiteLightsMod.LOG.error("Couldn't write seed generation's land to {}", store.folder(), failure);
            }
        }
    }

    /** Compares a few visited tiles with the prediction for their ground. */
    private void compareSome() {
        for (int i = 0; i < 4; i++) {
            Long key = toCompare.poll();
            if (key == null || stopped) {
                return;
            }
            try {
                TileVoxels seen = visited.read(key);
                if (seen == null || !seen.anyKnown()) {
                    continue;
                }
                TileVoxels guessed;
                if (store.meta(key) != null) {
                    guessed = read(key);
                } else if (sampledComparisons.incrementAndGet() <= MAX_SAMPLED_COMPARISONS) {
                    guessed = toSession(samplers.get().sample(key));
                } else {
                    continue;
                }
                accuracy.add(seen, guessed, sessionPalette, looks);
            } catch (IOException | RuntimeException failure) {
                LiteLightsMod.LOG.warn("Couldn't compare far terrain tile {} with its prediction: {}", TileKey.describe(key), failure.toString());
            }
        }
    }

    private TileVoxels toSession(TileVoxels tile) {
        for (int i = 0; i < tile.states.length; i++) {
            if (tile.states[i] != 0) {
                tile.states[i] = ids.toSession(tile.states[i]);
            }
        }
        for (int i = 0; i < tile.biomes.length; i++) {
            tile.biomes[i] = ids.biomeToSession(tile.biomes[i]);
        }
        return tile;
    }

    // --- Ids ---

    /**
     * The predicted store's ids. Its name tables are its own (it can be cleared or replaced
     * without touching the visited store's), so reads go through a table from its ids to the
     * visited store's, made once per id from the names.
     */
    private final class PredictedIds implements TileIds {
        private final AtomicReferenceArray<Integer> blocks = new AtomicReferenceArray<>(1 << 20);
        private final AtomicReferenceArray<Short> biomes = new AtomicReferenceArray<>(1 << 15);

        @Override
        public int idOf(BlockState state) {
            return palette.idOf(state);
        }

        @Override
        public short biomeIdOf(Holder<Biome> biome) {
            return palette.biomeIdOf(biome);
        }

        @Override
        public int kind(int state) {
            return state == 0 ? AIR : looks.kind(toSession(state));
        }

        int toSession(int id) {
            if (id <= 0 || id >= blocks.length()) {
                return 0;
            }
            Integer known = blocks.get(id);
            if (known == null) {
                String name = store.blocks().nameOf(id);
                known = name == null ? 0 : visited.blocks().idOf(name);
                blocks.set(id, known);
            }
            return known;
        }

        short biomeToSession(short id) {
            if (id < 0) {
                return 0;
            }
            Short known = biomes.get(id);
            if (known == null) {
                String name = store.biomes().nameOf(id);
                known = (short) (name == null ? 0 : Math.min(visited.biomes().idOf(name), Short.MAX_VALUE));
                biomes.set(id, known);
            }
            return known;
        }
    }

    /**
     * How close predictions came to the land as it was really generated: each visited column's
     * ground (its highest solid or water voxel, trees and their trunks left out) against the
     * prediction's for the same column, and whether its biome is the same.
     */
    static final class Accuracy {
        private long columns;
        private long sameBiome;
        private long withinOne;
        private long withinFour;
        private int worst;
        private long absoluteSum;

        synchronized void add(TileVoxels seen, TileVoxels guessed, BlockPalette palette, BlockKinds kinds) {
            for (int z = 0; z < TileVoxels.SIZE; z++) {
                for (int x = 0; x < TileVoxels.SIZE; x++) {
                    if (!seen.known(x, z)) {
                        continue;
                    }
                    int real = ground(seen, x, z, palette, kinds);
                    int predicted = ground(guessed, x, z, palette, kinds);
                    if (real < 0 || predicted < 0) {
                        continue;
                    }
                    int off = Math.abs(real - predicted);
                    columns++;
                    int column = TileVoxels.column(x, z);
                    if (seen.biomes[column] == guessed.biomes[column]) {
                        sameBiome++;
                    }
                    absoluteSum += off;
                    if (off <= 1) {
                        withinOne++;
                    }
                    if (off <= 4) {
                        withinFour++;
                    }
                    worst = Math.max(worst, off);
                }
            }
        }

        /** The ground's top row: trees, trunks and anything not a whole voxel left out. */
        static int ground(TileVoxels tile, int x, int z, BlockPalette palette, BlockKinds kinds) {
            for (int y = tile.height - 1; y >= 0; y--) {
                int state = tile.states[TileVoxels.index(x, y, z)];
                if (state == 0 || kinds.kind(state) == BlockKinds.AIR) {
                    continue;
                }
                BlockState block = palette.stateOf(state);
                if (block.is(BlockTags.LEAVES) || block.is(BlockTags.LOGS)) {
                    continue;
                }
                return y;
            }
            return -1;
        }

        /**
         * True when they match, false when under two in five columns have the predicted biome,
         * null before enough are compared. Biomes, not heights: the right seed gives every column
         * its biome but features the prediction leaves out (ice spikes, boulders) stand tall over
         * the ground, and a wrong seed's sea lies at the same height as the real one.
         */
        synchronized @Nullable Boolean verdict() {
            if (columns < VERDICT_COLUMNS) {
                return null;
            }
            return sameBiome * 5 >= columns * 2;
        }

        synchronized Map<String, Object> describe() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("columns", columns);
            state.put("sameBiome", columns == 0 ? 0.0 : sameBiome / (double) columns);
            state.put("sameBiomeColumns", sameBiome);
            state.put("withinOneColumns", withinOne);
            state.put("withinFourColumns", withinFour);
            state.put("withinOne", columns == 0 ? 0.0 : withinOne / (double) columns);
            state.put("withinFour", columns == 0 ? 0.0 : withinFour / (double) columns);
            state.put("meanOff", columns == 0 ? 0.0 : absoluteSum / (double) columns);
            state.put("worst", worst);
            return state;
        }
    }

    // --- Status ---

    long tiles() {
        return store.tileCount();
    }

    long diskBytes() {
        return store.diskBytes();
    }

    /** Square kilometres of ground predicted this session, at any level. */
    double squareKilometres() {
        return groundPredicted.get() / 1.0e6;
    }

    int working() {
        return running.get() + inFlight.size();
    }

    boolean opened() {
        return opened;
    }

    /** The last plan's walk of the fill round the camera. */
    FillProgress.Reading progress() {
        return progress;
    }

    /** Tiles predicted a second over the last ten seconds, or -1 while that isn't known yet. */
    double tilesPerSecond() {
        return tilesPerSecond;
    }

    /** How fast the share of ground at its first-set detail grows, a second; -1 while not known. */
    double firstSetSharePerSecond() {
        return sharePerSecond;
    }

    /** Whether this is a first fill whose first set of levels isn't built yet. */
    boolean firstFillPending() {
        return opened && firstFillPending;
    }

    /** Workers allowed now, of the most there may be (fewer while the integrated server is busy). */
    int allowedWorkers() {
        return allowed;
    }

    int maxWorkers() {
        return maxWorkers;
    }

    double serverTickMillis() {
        return lastTickMillis;
    }

    /** Prediction work is waiting while nothing of it runs: the workers are building meshes, which go first. */
    boolean waitingBehindMeshes() {
        return running.get() == 0 && !inFlight.isEmpty();
    }

    Map<String, Object> describe() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("folder", store.folder().toString());
        state.put("opened", opened);
        state.put("fromWorld", inputs.fromWorld());
        state.put("seaLevel", inputs.seaLevel());
        state.put("tiles", store.tileCount());
        synchronized (perLevel) {
            for (int level = 0; level <= TileKey.MAX_LEVEL; level++) {
                state.put("predictedL" + level, perLevel[level]);
            }
        }
        state.put("tilesPredicted", tilesPredicted.get());
        state.put("sampleMillis", sampleNanos.get() / 1_000_000L);
        state.put("groundKm2", squareKilometres());
        state.put("wanted", wantedLast);
        state.put("inFlight", inFlight.size());
        state.put("running", running.get());
        state.put("allowed", allowed);
        state.put("maxWorkers", maxWorkers);
        state.put("serverTickMillis", lastTickMillis);
        state.put("worstServerTickMillis", worstTickMillis);
        state.put("coverSeconds", coverAt == 0 ? -1.0 : (coverAt - openedAt) / 1.0e9);
        FillProgress.Reading reading = progress;
        state.put("fillWanted", reading.wanted());
        state.put("fillDone", reading.done());
        state.put("fillNextLevel", reading.nextLevel());
        state.put("fillWorstExcess", reading.worstExcess());
        state.put("fillShareAtDetail", reading.shareWithin(0));
        state.put("fillShareWithinOne", reading.shareWithin(1));
        state.put("fillShareNotDrawn", reading.area() <= 0.0 ? 0.0 : reading.areaNotDrawn() / reading.area());
        state.put("tilesPerSecond", tilesPerSecond);
        state.put("firstFillPending", firstFillPending);
        state.put("firstSetSeconds", firstSetAt == 0 ? -1.0 : (firstSetAt - openedAt) / 1.0e9);
        for (int excess = 0; excess < withinExcessAt.length; excess++) {
            state.put("withinExcess" + excess + "Seconds", withinExcessAt[excess] == 0 ? -1.0 : (withinExcessAt[excess] - openedAt) / 1.0e9);
        }
        FillProgress.Steps steps = reading.steps();
        state.put("fillNextStep", steps.nextStep() + 1);
        state.put("fillNearShareAtDetail", steps.nearArea() <= 0.0 ? 1.0 : steps.nearAreaAtDetail() / steps.nearArea());
        state.put("fillRestShareAtCoarse", steps.restArea() <= 0.0 ? 1.0 : steps.restAreaAtCoarse() / steps.restArea());
        state.put("fillFirstTwoShare", steps.firstTwoShare());
        for (int step = 0; step < stepsDoneAt.length; step++) {
            state.put("step" + (step + 1) + "DoneSeconds", stepsDoneAt[step] == 0 ? -1.0 : (stepsDoneAt[step] - openedAt) / 1.0e9);
        }
        state.put("fullySeen", fullySeen.size());
        state.put("diskBytes", store.diskBytes());
        state.put("heldBytes", store.heldBytes());
        state.put("accuracy", accuracy.describe());
        state.put("matches", accuracy.verdict());
        state.put("problem", problem);
        return state;
    }

    /** Stops planning; what is being predicted finishes or is dropped, the store is written to disk. Call once the pool has stopped. */
    @Override
    public void close() {
        stopped = true;
        synchronized (writeLock) {
            if (opened) {
                try {
                    store.flush();
                } catch (IOException | RuntimeException failure) {
                    LiteLightsMod.LOG.error("Couldn't write seed generation's land to {}", store.folder(), failure);
                }
            }
        }
    }

    /** Stops and deletes this prediction (Clear predicted land). */
    void delete() {
        stopped = true;
        synchronized (writeLock) {
            try {
                store.delete();
            } catch (IOException failure) {
                LiteLightsMod.LOG.error("Couldn't delete seed generation's land in {}", store.folder(), failure);
            }
        }
    }

    /** For a stop that keeps the session: no more tasks are started. */
    void stop() {
        stopped = true;
    }

    int minY() {
        return minY;
    }

    int worldHeight() {
        return worldHeight;
    }
}
