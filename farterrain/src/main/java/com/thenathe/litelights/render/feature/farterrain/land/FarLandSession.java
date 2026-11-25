package com.thenathe.litelights.render.feature.farterrain.land;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.thenathe.litelights.api.CameraView;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.NearTerrain;
import com.thenathe.litelights.api.WorldIdentity;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.thenathe.litelights.render.feature.farterrain.seed.PredictionInputs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.FrustumIntersection;
import org.jspecify.annotations.Nullable;

/**
 * Far terrain for one world and dimension while it is open: its store and the threads that fill it
 * (FarLandStorage) and mesh it, the meshes on the GPU, the seam with the near terrain and each
 * frame's draws. Made when far terrain is on and a level is open; closed (written to disk, threads
 * stopped, video memory freed) when either goes. Its methods run on the client thread.
 */
final class FarLandSession implements AutoCloseable {
    /** A chunk is copied this long after it arrived, so its light has come in too. */
    private static final long LOAD_SETTLE_MILLIS = 1500L;
    /** And this long after its last block change, so a building player's work is copied once. */
    private static final long CHANGE_SETTLE_MILLIS = 3000L;
    private static final int COPIES_PER_TICK = 48;
    private static final int MESH_REQUESTS_PER_FRAME = 24;
    private static final long UPLOAD_BYTES_PER_FRAME = 8L << 20;
    /** A mesh nobody wanted for this long gives its video memory back. */
    private static final long UNWANTED_MILLIS = 15_000L;
    /**
     * A tile that is drawn waits this long after its last change before it is meshed again, so land
     * filling in chunk by chunk is meshed once rather than once per chunk.
     */
    private static final long REMESH_SETTLE_MILLIS = 1500L;
    /**
     * A tile the near terrain covers whole waits this long after a change instead: chunks keep
     * arriving under it while the player stands there, and it is only drawn once they leave.
     */
    private static final long COVERED_SETTLE_MILLIS = 5000L;
    /** Added to the priority of a tile the near terrain covers, so every tile that shows goes first. */
    private static final double COVERED_LAST = 1.0e6;
    /**
     * Taken from the priority of a stand-in's children, so they go before everything else: it
     * splits only once all four are meshed, and until then the finer tiles meshed under it can't show.
     */
    private static final double STAND_IN_CHILDREN_FIRST = 1.0e7;
    /** A tile out of view waits as long as one this many times farther away in view. */
    private static final double OUT_OF_VIEW_FACTOR = 4.0;
    /** Lights are drawn while daylight (FrameAccess.daylight) is under this. */
    private static final float LIGHTS_DAYLIGHT = 0.98f;
    /**
     * When a tile takes over its ground from its parent or its children (or from nothing), it
     * dithers in over this long while what it replaces dithers out, so a level swap doesn't pop.
     */
    private static final long SWAP_FADE_MILLIS = 250L;
    /** How many sections down under water the seam looks for the bed the near terrain must have drawn. */
    private static final int BED_SECTIONS = 4;
    /** Chunks past the render distance the seam mask clears each frame, as far as the camera may move in one. */
    private static final int SEAM_RING = 3;
    private static final int FADE_IN = 1;
    private static final int FADE_OUT = 2;

    private record MeshResult(long key, long revision, int generation, @Nullable FarMesh mesh, int minY) {
    }

    /** A mesh wanted in the last walk, and how soon (lowest first). */
    private record Wanted(long key, double priority) {
    }

    private static final class MeshEntry {
        final long revision;
        final int generation;
        final FarGpu.@Nullable Slot slot;
        final int[] firstQuad;
        final int[] quadCount;
        final int lowestY;
        final int highestY;
        boolean stale;
        long wantedAt;

        MeshEntry(long revision, int generation, FarGpu.@Nullable Slot slot, int[] firstQuad, int[] quadCount, int lowestY, int highestY) {
            this.revision = revision;
            this.generation = generation;
            this.slot = slot;
            this.firstQuad = firstQuad;
            this.quadCount = quadCount;
            this.lowestY = lowestY;
            this.highestY = highestY;
        }
    }

    /** A tile chosen for its ground, and its distance when it was chosen (for drawing near to far). */
    private record Chosen(long key, MeshEntry entry, double distance) {
    }

    private final WorldIdentity world;
    private final FarStore store;
    /** Visited land with seed generation's predictions where nothing was visited (LandView). */
    private final LandView land;
    private @Nullable PredictedLand predicted;
    private final BlockPalette palette;
    private final FarAppearances appearances = new FarAppearances();
    private final BlockLooks looks;
    private final FarLandStorage storage;
    private final Thread storageThread;
    private final ThreadPoolExecutor meshPool;
    private final boolean skyLight;
    private final int minY;
    private final int worldHeight;
    private final ConcurrentLinkedQueue<MeshResult> results = new ConcurrentLinkedQueue<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> copyDue = new HashMap<>();
    private final Long2ObjectOpenHashMap<MeshEntry> meshes = new Long2ObjectOpenHashMap<>();
    /** When each tile last changed (its own data or a neighbour's edge), for {@link #REMESH_SETTLE_MILLIS}. */
    private final Map<Long, Long> changedAt = new HashMap<>();
    private final FarGpu gpu = new FarGpu();
    private final SeamCoverage seam = new SeamCoverage();
    private final FarSelection selection = new FarSelection();
    private final FrustumIntersection frustum = new FrustumIntersection();
    /**
     * The tiles chosen for their ground, nearest first. Choosing walks the tile tree and asks for
     * meshes, so it runs only when the camera has moved a little, a mesh arrived or went, or a
     * quarter of a second has passed; each frame only tests the chosen against the view.
     */
    private final List<Chosen> chosen = new ArrayList<>();
    private boolean chosenDirty = true;
    private long chosenAt;
    private double chosenX;
    private double chosenY;
    private double chosenZ;
    private int drawnTiles;
    /** The tiles chosen last time and this time, and since when (for the swap fade). */
    private Long2LongOpenHashMap chosenBefore = new Long2LongOpenHashMap();
    private Long2LongOpenHashMap chosenNow = new Long2LongOpenHashMap();
    /** Tiles no longer chosen, drawn dithering out until their replacement has faded in. */
    private final Long2LongOpenHashMap fadingOut = new Long2LongOpenHashMap();
    private final FarDrawList openDraws = new FarDrawList();
    private final FarDrawList seamDraws = new FarDrawList();
    /** Plants' cards, drawn only on full-detail tiles within the Detail distance. */
    private final FarDrawList cardDraws = new FarDrawList();
    /** The water, drawn after everything opaque. */
    private final FarDrawList waterDraws = new FarDrawList();
    /** The lights' points, drawn after the water while it is dark. */
    private final FarDrawList lightDraws = new FarDrawList();
    private float cardDistance;
    private boolean cardsShown;
    private boolean waterShown;
    private boolean lightsShown;
    private final AtomicLong meshesBuilt = new AtomicLong();
    private final AtomicLong meshMillis = new AtomicLong();
    private final AtomicInteger generation = new AtomicInteger();
    private final long openedAt = System.nanoTime();
    private final long openedMillis = System.currentTimeMillis();
    private long firstDrawnAt;
    private long settledAt;
    private int seamCursor;
    private int seamCentreX = Integer.MIN_VALUE / 2;
    private int seamCentreZ = Integer.MIN_VALUE / 2;
    private int seamRadius = -1;
    private int meshRequests;
    /** The meshes the last walk wanted, sorted before they go to the workers: in view and nearest first. */
    private final List<Wanted> wanted = new ArrayList<>();
    private final LongOpenHashSet wantedKeys = new LongOpenHashSet();
    /** Every tile meshed since the session opened, for how often a tile is meshed. */
    private final LongOpenHashSet meshedTiles = new LongOpenHashSet();
    private int standIns;
    private long completeAt;
    private boolean memoryFull;
    private int atlasGeneration;
    private long lastEviction;
    private @Nullable String meshProblem;
    private long memoryFullAt;
    private boolean deleteOnClose;

    FarLandSession(WorldIdentity world, ClientLevel level, int meshThreads) {
        this.world = world;
        this.store = new FarStore(world.dataFolder().resolve("far").resolve(folderName(world.dimension())));
        this.palette = new BlockPalette(store.blocks(), store.biomes(), level);
        this.looks = new BlockLooks(palette, appearances);
        this.land = new LandView(store, looks);
        this.skyLight = level.dimensionType().hasSkyLight();
        this.minY = level.getMinY();
        this.worldHeight = level.getHeight();
        this.storage = new FarLandStorage(store, palette, looks, minY, worldHeight);
        this.storageThread = new Thread(storage, "LiteLights far terrain store");
        storageThread.setDaemon(true);
        storageThread.setPriority(Thread.NORM_PRIORITY - 2);
        storageThread.start();
        AtomicInteger threadNumber = new AtomicInteger();
        this.meshPool = new ThreadPoolExecutor(meshThreads, meshThreads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "LiteLights far terrain mesher " + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
    }

    /** "minecraft:overworld" becomes "minecraft_overworld". */
    static String folderName(String dimension) {
        return dimension.replace(':', '_').replace('/', '_');
    }

    WorldIdentity world() {
        return world;
    }

    String dimension() {
        return world.dimension();
    }

    // --- Ingest (client thread) ---

    void chunkLoaded(int chunkX, int chunkZ) {
        copyDue.put(chunkKey(chunkX, chunkZ), System.currentTimeMillis() + LOAD_SETTLE_MILLIS);
    }

    void blockChanged(int chunkX, int chunkZ) {
        copyDue.put(chunkKey(chunkX, chunkZ), System.currentTimeMillis() + CHANGE_SETTLE_MILLIS);
    }

    /** A chunk going away with a copy still due is copied now, while it is there. */
    void chunkUnloading(ClientLevel level, LevelChunk chunk) {
        if (copyDue.remove(chunkKey(chunk.getPos().x(), chunk.getPos().z())) != null) {
            storage.submit(ChunkCopy.take(level, chunk));
        }
    }

    /** Every chunk the client holds now, for far terrain switched on in a world already open. */
    void noticeLoadedChunks(ClientLevel level, int centreX, int centreZ, int radius) {
        for (int z = centreZ - radius; z <= centreZ + radius; z++) {
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                    chunkLoaded(x, z);
                }
            }
        }
    }

    /** Copies the chunks whose wait is over, a few each tick. */
    void tick(ClientLevel level) {
        if (copyDue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int copied = 0;
        Iterator<Map.Entry<Long, Long>> due = copyDue.entrySet().iterator();
        while (due.hasNext() && copied < COPIES_PER_TICK) {
            Map.Entry<Long, Long> entry = due.next();
            if (entry.getValue() > now) {
                continue;
            }
            due.remove();
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk != null) {
                storage.submit(ChunkCopy.take(level, chunk));
                copied++;
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    // --- Each frame (render thread) ---

    /**
     * Takes in finished meshes, follows the near terrain's seam and picks this frame's draws.
     * Returns whether anything is drawn.
     */
    boolean frame(CameraView camera, NearTerrain near, FarLandSettings settings, ClientLevel level) {
        if (!storage.opened()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long changed;
        PredictedLand guessing = predicted;
        while ((changed = storage.pollChanged()) != null) {
            tileChanged(changed, now);
            if (guessing != null) {
                guessing.visitedChanged(changed);
            }
        }
        if (guessing != null) {
            while ((changed = guessing.pollChanged()) != null) {
                tileChanged(changed, now);
            }
        }
        takeResults(settings);
        updateSeam(camera, near, level, now);
        select(camera, settings, near, now);
        if (now - lastEviction > 1000L) {
            lastEviction = now;
            evictUnwanted(now - UNWANTED_MILLIS);
            changedAt.values().removeIf(at -> now - at > COVERED_SETTLE_MILLIS);
        }
        gpu.endFrame();
        boolean drawing = !openDraws.isEmpty() || !seamDraws.isEmpty() || !cardDraws.isEmpty() || !waterDraws.isEmpty() || !lightDraws.isEmpty();
        if (drawing && firstDrawnAt == 0) {
            firstDrawnAt = System.nanoTime();
        }
        if (settledAt == 0 && firstDrawnAt != 0 && inFlight.isEmpty() && meshRequests == 0 && storage.queued() == 0) {
            settledAt = System.nanoTime();
        }
        if (completeAt == 0 && firstDrawnAt != 0 && standIns == 0 && inFlight.isEmpty() && meshRequests == 0) {
            completeAt = System.nanoTime();
        }
        return drawing;
    }

    private void tileChanged(long key, long now) {
        changedAt.put(key, now);
        chosenDirty = true;
        MeshEntry entry = meshes.get(key);
        if (entry != null) {
            entry.stale = true;
        }
    }

    // --- Seed generation (client thread) ---

    /**
     * Starts predicting land from the seed beside the visited land, kept under {@code identity}
     * (the seed and the generation's fingerprint, hashed), and shows it where nothing was visited.
     * Any other prediction for this dimension, made with another seed or other mods, is deleted.
     */
    void startPredictions(PredictionInputs inputs, String identity, ClientLevel level) {
        stopPredictions();
        Path folder = predictionsFolder().resolve(identity);
        deleteOtherPredictions(identity);
        predicted = new PredictedLand(folder, store, palette, looks, level, inputs, meshPool, minY, worldHeight);
        land.predictions(predicted);
    }

    /** Stops predicting and stops showing predictions; what was predicted stays on disk. */
    void stopPredictions() {
        PredictedLand open = predicted;
        if (open == null) {
            return;
        }
        predicted = null;
        open.stop();
        land.predictions(null);
        // Every tile is meshed again from visited land alone.
        generation.incrementAndGet();
        chosenDirty = true;
        // Its tasks still queued return at once; it is written to disk when the session closes or from here.
        meshPool.execute(new FarTask(0.0) {
            @Override
            public void run() {
                open.close();
            }
        });
    }

    /** Stops predicting and deletes every prediction kept for this dimension (Clear predicted land). */
    void clearPredictions() {
        PredictedLand open = predicted;
        predicted = null;
        land.predictions(null);
        generation.incrementAndGet();
        chosenDirty = true;
        if (open != null) {
            open.stop();
        }
        meshPool.execute(new FarTask(0.0) {
            @Override
            public void run() {
                if (open != null) {
                    open.delete();
                }
                deleteOtherPredictions("");
            }
        });
    }

    /** Once a frame while predicting: the next work, paced by the integrated server's tick ({@code serverTickMillis}, or -1). */
    void planPredictions(CameraView camera, FarLandSettings settings, double serverTickMillis) {
        PredictedLand open = predicted;
        if (open != null) {
            open.plan(camera.x(), camera.z(), settings.distance.get().blocks, settings.detail.get().levelDistance, serverTickMillis,
                System.currentTimeMillis());
        }
    }

    @Nullable PredictedLand predictions() {
        return predicted;
    }

    private Path predictionsFolder() {
        return world.dataFolder().resolve("far-predicted").resolve(folderName(world.dimension()));
    }

    private void deleteOtherPredictions(String keep) {
        Path folder = predictionsFolder();
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> identities = Files.list(folder)) {
            for (Path other : identities.filter(path -> !path.getFileName().toString().equals(keep)).toList()) {
                new FarStore(other).delete();
            }
        } catch (IOException failure) {
            LiteLightsMod.LOG.warn("Couldn't delete old predicted far terrain in {}: {}", folder, failure.toString());
        }
    }

    private void takeResults(FarLandSettings settings) {
        long uploaded = 0;
        MeshResult result;
        while (uploaded < UPLOAD_BYTES_PER_FRAME && (result = results.poll()) != null) {
            inFlight.remove(result.key());
            if (result.generation() != generation.get() || result.mesh() == null) {
                continue;
            }
            FarMesh mesh = result.mesh();
            long key = result.key();
            int size = 1 << TileKey.level(key);
            int lowestY = result.minY() + mesh.lowestRow() * size;
            int highestY = result.minY() + (mesh.highestRow() + 1) * size;
            FarGpu.Slot slot = null;
            if (!mesh.isEmpty()) {
                slot = allocate(mesh.totalQuads(), settings.videoMemoryBytes());
                if (slot == null) {
                    memoryFull = true;
                    memoryFullAt = System.currentTimeMillis();
                    continue;
                }
                gpu.upload(slot, mesh.quads(), TileKey.originX(key), result.minY(), TileKey.originZ(key), TileKey.level(key));
                uploaded += (long) mesh.totalQuads() * FarQuad.BYTES;
            }
            meshedTiles.add(key);
            MeshEntry entry = new MeshEntry(result.revision(), result.generation(), slot, mesh.firstQuad(), mesh.quadCount(), lowestY, highestY);
            entry.wantedAt = System.currentTimeMillis();
            MeshEntry old = meshes.put(key, entry);
            chosenDirty = true;
            if (old != null && old.slot != null) {
                gpu.release(old.slot);
            }
        }
    }

    /** Room on the GPU, making it by letting go of the meshes wanted longest ago if the budget is reached. */
    private FarGpu.@Nullable Slot allocate(int quads, long budget) {
        FarGpu.Slot slot = gpu.allocate(quads, budget);
        if (slot != null) {
            return slot;
        }
        List<Map.Entry<Long, MeshEntry>> oldest = new ArrayList<>(meshes.long2ObjectEntrySet());
        oldest.sort((a, b) -> Long.compare(a.getValue().wantedAt, b.getValue().wantedAt));
        chosenDirty = true;
        long cutoff = System.currentTimeMillis() - 2000L;
        int released = 0;
        for (Map.Entry<Long, MeshEntry> entry : oldest) {
            if (entry.getValue().wantedAt > cutoff || released > 64) {
                break;
            }
            if (entry.getValue().slot != null) {
                gpu.release(entry.getValue().slot);
            }
            meshes.remove(entry.getKey());
            released++;
        }
        return released == 0 ? null : gpu.allocate(quads, budget);
    }

    private void evictUnwanted(long before) {
        Iterator<MeshEntry> entries = meshes.values().iterator();
        while (entries.hasNext()) {
            MeshEntry entry = entries.next();
            if (entry.wantedAt < before) {
                if (entry.slot != null) {
                    gpu.release(entry.slot);
                }
                entries.remove();
                chosenDirty = true;
            }
        }
    }

    /**
     * Which chunk columns the near terrain draws: each chunk within the render distance whose top
     * section the near renderer has faded in. A quarter of them a frame, the uncovered every frame.
     */
    private void updateSeam(CameraView camera, NearTerrain near, ClientLevel level, long now) {
        int radius = near.renderDistanceChunks();
        int centreX = (int) Math.floor(camera.x()) >> 4;
        int centreZ = (int) Math.floor(camera.z()) >> 4;
        boolean reset = Math.abs(centreX - seamCentreX) > SEAM_RING || Math.abs(centreZ - seamCentreZ) > SEAM_RING || radius != seamRadius;
        if (reset) {
            // A jump (a teleport, a new render distance): nothing the mask held is known to be right.
            seam.clear();
        } else {
            // Chunks the camera has moved away from: the near terrain no longer draws them. The mask
            // keeps a slot per chunk, so one left covered would hide far land when the camera comes
            // back near it from another side.
            for (int dz = -radius - SEAM_RING; dz <= radius + SEAM_RING; dz++) {
                for (int dx = -radius - SEAM_RING; dx <= radius + SEAM_RING; dx++) {
                    if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                        seam.update(centreX + dx, centreZ + dz, false, now);
                    }
                }
            }
        }
        seamCentreX = centreX;
        seamCentreZ = centreZ;
        seamRadius = radius;
        int side = radius * 2 + 1;
        int total = side * side;
        int step = Math.max(1, total / 4);
        for (int i = 0; i < total; i++) {
            int dx = i % side - radius;
            int dz = i / side - radius;
            int chunkX = centreX + dx;
            int chunkZ = centreZ + dz;
            boolean inTurn = i >= seamCursor && i < seamCursor + step;
            if (!inTurn && seam.fullyCovered(chunkX, chunkZ)) {
                continue;
            }
            seam.update(chunkX, chunkZ, nearCover(near, level, camera, chunkX, chunkZ, dx, dz, radius), now, reset);
        }
        seamCursor = seamCursor + step >= total ? 0 : seamCursor + step;
    }

    private SeamCoverage.Cover nearCover(NearTerrain near, ClientLevel level, CameraView camera, int chunkX, int chunkZ, int dx, int dz,
                                         int radius) {
        // The near renderer compiles a square of chunks but draws only those its render distance
        // rule takes in (SeamCoverage.nearDrawsChunk). Far land steps aside only inside one chunk
        // less than the render distance's circle, where every renderer draws every chunk it has, so
        // it never leaves a hole; far water by the renderer's own rule, as near water over far water
        // shows as a brighter band all round the render distance.
        if (!SeamCoverage.nearDrawsChunk(near.sodium(), camera.x(), camera.z(), chunkX, chunkZ, radius)) {
            return SeamCoverage.Cover.NONE;
        }
        boolean landInside = dx * dx + dz * dz <= (radius - 1) * (radius - 1);
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return SeamCoverage.Cover.NONE;
        }
        // The section holding the land's (or the water's) top, and under water the first section
        // down with anything but water in it: a lake's surface section can be drawn before the lake
        // bed's, and far land hidden there would show through the water. Far water only waits for the
        // surface: the bed under open sea may never be compiled (SeamCoverage).
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
        int sectionY = Math.floorDiv(Math.max(top - 1, level.getMinY()), 16);
        SeamCoverage.Cover cover = SeamCoverage.cover(new SeamCoverage.Column() {
            @Override
            public boolean drawn(int section) {
                return SeamCoverage.nearDrawsHeight(near.sodium(), camera.y(), section, radius) && near.sectionDrawn(chunkX, section, chunkZ);
            }

            @Override
            public boolean onlyWater(int section) {
                return FarLandSession.onlyWater(chunk.getSection(chunk.getSectionIndexFromSectionY(section)));
            }
        }, sectionY, level.getMinSectionY(), BED_SECTIONS);
        return cover == SeamCoverage.Cover.ALL && !landInside ? SeamCoverage.Cover.WATER : cover;
    }

    /** Whether a section holds nothing but water and air. */
    private static boolean onlyWater(LevelChunkSection section) {
        return !section.getStates().maybeHas(state -> !state.isAir() && !state.is(Blocks.WATER));
    }

    private final FarSelection.Tiles tiles = new FarSelection.Tiles() {
        @Override
        public boolean present(long key) {
            return land.present(key);
        }

        @Override
        public boolean stored(long key) {
            return land.meta(key) != null;
        }

        @Override
        public boolean ready(long key) {
            TileMeta meta = land.meta(key);
            if (meta == null) {
                return false;
            }
            return meta.empty() || meshes.containsKey(key);
        }

        @Override
        public int lowestY(long key) {
            MeshEntry entry = meshes.get(key);
            if (entry != null) {
                return entry.lowestY;
            }
            TileMeta meta = land.meta(key);
            return meta == null || meta.empty() ? minY : minY + (meta.lowestRow() << TileKey.level(key));
        }

        @Override
        public boolean coversWhole(long key) {
            return land.coversWhole(key);
        }

        @Override
        public int highestY(long key) {
            MeshEntry entry = meshes.get(key);
            if (entry != null) {
                return entry.highestY;
            }
            TileMeta meta = land.meta(key);
            return meta == null || meta.empty() ? minY + worldHeight : minY + ((meta.highestRow() + 1) << TileKey.level(key));
        }
    };

    private void select(CameraView camera, FarLandSettings settings, NearTerrain near, long now) {
        frustum.set(camera.viewProjection());
        double cameraX = camera.x();
        double cameraY = camera.y();
        double cameraZ = camera.z();
        double moved = (cameraX - chosenX) * (cameraX - chosenX) + (cameraY - chosenY) * (cameraY - chosenY)
            + (cameraZ - chosenZ) * (cameraZ - chosenZ);
        if (chosenDirty || moved > 4.0 || now - chosenAt > 250L) {
            choose(settings, cameraX, cameraY, cameraZ, now);
        }
        openDraws.clear();
        seamDraws.clear();
        cardDraws.clear();
        waterDraws.clear();
        lightDraws.clear();
        drawnTiles = 0;
        cardDistance = settings.detail.get().levelDistance;
        cardsShown = !LiteLightsApi.passes().abFlipped("farland.cards");
        waterShown = !LiteLightsApi.passes().abFlipped("farland.water");
        // Lights show in the dark only: at night, and always where there is no sky.
        lightsShown = settings.lights.on() && (!skyLight || LiteLightsApi.frame().daylight() < LIGHTS_DAYLIGHT)
            && !LiteLightsApi.passes().abFlipped("farland.lights");
        int radius = near.renderDistanceChunks();
        int cameraChunkX = (int) Math.floor(cameraX) >> 4;
        int cameraChunkZ = (int) Math.floor(cameraZ) >> 4;
        for (int i = 0, count = chosen.size(); i < count; i++) {
            Chosen tile = chosen.get(i);
            if (tile.entry().slot != null) {
                long since = chosenBefore.get(tile.key());
                draw(tile.key(), tile.entry(), now - since < SWAP_FADE_MILLIS, cameraX, cameraY, cameraZ, cameraChunkX, cameraChunkZ, radius);
            }
        }
        var fading = fadingOut.long2LongEntrySet().fastIterator();
        while (fading.hasNext()) {
            var out = fading.next();
            long key = out.getLongKey();
            MeshEntry entry = meshes.get(key);
            if (now - out.getLongValue() >= SWAP_FADE_MILLIS || entry == null || entry.slot == null) {
                fading.remove();
                continue;
            }
            draw(key, entry, true, cameraX, cameraY, cameraZ, cameraChunkX, cameraChunkZ, radius);
        }
    }

    /**
     * Walks the tile tree: picks the tile for every piece of ground, nearest first, asks for the
     * meshes wanted, and starts the swap fades of tiles taking over or giving up their ground.
     */
    private void choose(FarLandSettings settings, double cameraX, double cameraY, double cameraZ, long now) {
        chosen.clear();
        chosenNow.clear();
        wanted.clear();
        wantedKeys.clear();
        selection.select(tiles, (minX, minY, minZ, maxX, maxY, maxZ) -> true, cameraX, cameraY, cameraZ, settings.distance.get().blocks,
            settings.detail.get().levelDistance,
            (key, visible) -> {
                MeshEntry entry = meshes.get(key);
                if (entry == null || entry.slot == null) {
                    return;
                }
                long since = chosenBefore.containsKey(key) ? chosenBefore.get(key) : -1L;
                if (since < 0) {
                    since = now;
                    writeFade(key, entry, FADE_IN, now);
                }
                chosenNow.put(key, since);
                chosen.add(new Chosen(key, entry, selection.distance(key)));
            },
            (FarSelection.Wanted) (key, next) -> want(key, next, now, cameraX, cameraY, cameraZ));
        standIns = selection.standIns();
        submitWanted();
        chosen.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        var before = chosenBefore.long2LongEntrySet().fastIterator();
        while (before.hasNext()) {
            long key = before.next().getLongKey();
            if (chosenNow.containsKey(key) || fadingOut.containsKey(key)) {
                continue;
            }
            MeshEntry entry = meshes.get(key);
            if (entry != null && entry.slot != null) {
                fadingOut.put(key, now);
                writeFade(key, entry, FADE_OUT, now);
            }
        }
        // A tile chosen again stops fading out: it faded in afresh.
        var fading = fadingOut.long2LongEntrySet().fastIterator();
        while (fading.hasNext()) {
            if (chosenNow.containsKey(fading.next().getLongKey())) {
                fading.remove();
            }
        }
        Long2LongOpenHashMap swap = chosenBefore;
        chosenBefore = chosenNow;
        chosenNow = swap;
        chosenX = cameraX;
        chosenY = cameraY;
        chosenZ = cameraZ;
        chosenAt = now;
        chosenDirty = false;
    }

    /** Adds a tile's draws, if the view can see it: the face directions that can face the camera. */
    private void draw(long key, MeshEntry entry, boolean fadingTile, double cameraX, double cameraY, double cameraZ,
                      int cameraChunkX, int cameraChunkZ, int radius) {
        int level = TileKey.level(key);
        int span = TileKey.span(level);
        int originX = TileKey.originX(key);
        int originZ = TileKey.originZ(key);
        float minX = (float) (originX - cameraX);
        float minZ = (float) (originZ - cameraZ);
        if (!frustum.testAab(minX, (float) (entry.lowestY - cameraY), minZ, minX + span, (float) (entry.highestY - cameraY), minZ + span)) {
            return;
        }
        int chunkMinX = originX >> 4;
        int chunkMinZ = originZ >> 4;
        int chunkMaxX = (originX + span - 1) >> 4;
        int chunkMaxZ = (originZ + span - 1) >> 4;
        boolean nearSeam = chunkMaxX >= cameraChunkX - radius - 1 && chunkMinX <= cameraChunkX + radius + 1
            && chunkMaxZ >= cameraChunkZ - radius - 1 && chunkMinZ <= cameraChunkZ + radius + 1;
        if (nearSeam && level <= 2 && wholeTileCovered(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
            return;
        }
        int facing = FarSelection.facingDirections(cameraX, cameraY, cameraZ, originX, entry.lowestY, originZ,
            originX + span, entry.highestY, originZ + span);
        // Fading tiles go through the pipeline that can discard, as the seam's do.
        FarDrawList list = nearSeam || fadingTile ? seamDraws : openDraws;
        int page = entry.slot.page().index;
        int first = entry.slot.firstQuad();
        for (int face = 0; face < 6; face++) {
            if ((facing & 1 << face) != 0 && entry.quadCount[face] != 0) {
                list.add(page, (first + entry.firstQuad[face]) * 6, entry.quadCount[face] * 6);
            }
        }
        if (entry.quadCount[FarMesh.CARDS] != 0 && cardsShown && selection.distance(key) < cardDistance) {
            cardDraws.add(page, (first + entry.firstQuad[FarMesh.CARDS]) * 6, entry.quadCount[FarMesh.CARDS] * 6);
        }
        if (entry.quadCount[FarMesh.WATER] != 0 && waterShown) {
            waterDraws.add(page, (first + entry.firstQuad[FarMesh.WATER]) * 6, entry.quadCount[FarMesh.WATER] * 6);
        }
        if (entry.quadCount[FarMesh.LIGHTS] != 0 && lightsShown) {
            lightDraws.add(page, (first + entry.firstQuad[FarMesh.LIGHTS]) * 6, entry.quadCount[FarMesh.LIGHTS] * 6);
        }
        drawnTiles++;
    }

    private boolean wholeTileCovered(int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
            for (int x = chunkMinX; x <= chunkMaxX; x++) {
                if (!seam.fullyCovered(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Marks a tile's node as fading in or out from now (FarGpu.writeNode). */
    private void writeFade(long key, MeshEntry entry, int fade, long now) {
        int start = (int) ((now - openedMillis) / 10L) & 0x3FFFFFF;
        gpu.writeNode(entry.slot.node(), TileKey.originX(key), minY, TileKey.originZ(key), TileKey.level(key) | fade << 4 | start << 6);
    }


    /**
     * A tile the selection wants: kept, and meshed if its mesh is missing or out of date, once its
     * changes have settled. Only noted here; {@link #submitWanted} sends the soonest to the workers.
     */
    private void want(long key, boolean next, long now, double cameraX, double cameraY, double cameraZ) {
        MeshEntry entry = meshes.get(key);
        TileMeta meta = land.meta(key);
        if (entry != null) {
            entry.wantedAt = now;
        }
        if (meta == null || meta.empty() || !wantedKeys.add(key)) {
            return;
        }
        boolean current = entry != null && entry.revision >= meta.revision() && !entry.stale && entry.generation == generation.get();
        if (current || inFlight.contains(key)) {
            return;
        }
        boolean covered = coveredByNear(key, cameraX, cameraZ);
        if (now - changedAt.getOrDefault(key, 0L) < (covered ? COVERED_SETTLE_MILLIS : REMESH_SETTLE_MILLIS)) {
            // Its land is still coming in: meshed once the changes settle (still drawn as it was, if it was).
            return;
        }
        if (entry == null && now - memoryFullAt < 1000L) {
            // The video memory budget is full: no new tiles until something is let go.
            return;
        }
        double priority = next ? selection.distance(key) - STAND_IN_CHILDREN_FIRST
            : selection.distance(key) * (inView(key, cameraX, cameraY, cameraZ) ? 1.0 : OUT_OF_VIEW_FACTOR) + (covered ? COVERED_LAST : 0.0);
        wanted.add(new Wanted(key, priority));
    }

    /** Sends the soonest of the wanted meshes to the workers, as many as they take. */
    private void submitWanted() {
        meshRequests = wanted.size();
        if (wanted.isEmpty()) {
            return;
        }
        wanted.sort((a, b) -> Double.compare(a.priority(), b.priority()));
        int room = Math.min(MESH_REQUESTS_PER_FRAME, meshPool.getMaximumPoolSize() * 3 - inFlight.size());
        int meshGeneration = generation.get();
        for (int i = 0; i < room && i < wanted.size(); i++) {
            Wanted next = wanted.get(i);
            MeshEntry entry = meshes.get(next.key());
            if (entry != null) {
                entry.stale = false;
            }
            inFlight.add(next.key());
            meshPool.execute(new MeshTask(next.key(), next.priority(), meshGeneration));
        }
    }

    /** Whether the tile's box can be seen this frame. */
    private boolean inView(long key, double cameraX, double cameraY, double cameraZ) {
        int span = TileKey.span(TileKey.level(key));
        float minX = (float) (TileKey.originX(key) - cameraX);
        float minZ = (float) (TileKey.originZ(key) - cameraZ);
        return frustum.testAab(minX, (float) (tiles.lowestY(key) - cameraY), minZ, minX + span, (float) (tiles.highestY(key) - cameraY), minZ + span);
    }

    /** Whether the near terrain draws all of the tile's ground (small tiles only: a large one never is). */
    private boolean coveredByNear(long key, double cameraX, double cameraZ) {
        int level = TileKey.level(key);
        if (level > 2) {
            return false;
        }
        int span = TileKey.span(level);
        int originX = TileKey.originX(key);
        int originZ = TileKey.originZ(key);
        return wholeTileCovered(originX >> 4, originZ >> 4, (originX + span - 1) >> 4, (originZ + span - 1) >> 4);
    }

    /** A mesh to build, nearest first. */
    private final class MeshTask extends FarTask {
        private final long key;
        private final int meshGeneration;

        MeshTask(long key, double priority, int meshGeneration) {
            super(priority);
            this.key = key;
            this.meshGeneration = meshGeneration;
        }

        @Override
        public void run() {
            long started = System.nanoTime();
            try {
                TileMeta meta = land.meta(key);
                TileVoxels tile = meta == null ? null : land.read(key);
                if (tile == null) {
                    results.add(new MeshResult(key, 0, meshGeneration, null, minY));
                    return;
                }
                FarMesh mesh = MESHER.get().mesh(tile, outside(key), looks, skyLight);
                results.add(new MeshResult(key, meta.revision(), meshGeneration, mesh, tile.minY));
                meshesBuilt.incrementAndGet();
            } catch (Throwable failure) {
                meshProblem = failure.toString();
                LiteLightsMod.LOG.error("Couldn't mesh far terrain tile {}", TileKey.describe(key), failure);
                results.add(new MeshResult(key, 0, meshGeneration, null, minY));
            } finally {
                meshMillis.addAndGet((System.nanoTime() - started) / 1_000_000L);
            }
        }
    }

    private static final ThreadLocal<FarMesher> MESHER = ThreadLocal.withInitial(FarMesher::new);

    /** The tops of the columns across each edge, from the neighbours' metas. */
    private EdgeTops outside(long key) {
        int[][] tops = new int[4][];
        tops[EdgeTops.NORTH] = edge(TileKey.neighbour(key, 0, -1), EdgeTops.SOUTH);
        tops[EdgeTops.SOUTH] = edge(TileKey.neighbour(key, 0, 1), EdgeTops.NORTH);
        tops[EdgeTops.WEST] = edge(TileKey.neighbour(key, -1, 0), EdgeTops.EAST);
        tops[EdgeTops.EAST] = edge(TileKey.neighbour(key, 1, 0), EdgeTops.WEST);
        return new EdgeTops(tops);
    }

    private int[] edge(long neighbour, int side) {
        TileMeta meta = land.meta(neighbour);
        return meta == null ? EdgeTops.UNKNOWN.tops()[side] : meta.edges().tops()[side];
    }

    /** The block atlas was stitched again: sprites moved, models were made again. Everything is meshed again. */
    void atlasStitched() {
        atlasGeneration++;
        looks.reset();
        generation.incrementAndGet();
    }

    /** Draws this frame's far terrain into the main target (before translucent terrain). */
    void draw(RenderTarget mainTarget, FarLandPass pass, CameraView camera, NearTerrain near) {
        if (openDraws.isEmpty() && seamDraws.isEmpty() && cardDraws.isEmpty()) {
            return;
        }
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        GpuBuffer appearanceTable = gpu.appearances(appearances, atlas, atlasGeneration);
        pass.uploadSeam(seam);
        pass.draw(mainTarget, gpu, appearanceTable, openDraws, seamDraws, cardDraws, camera.drawViewProjection(),
            (System.currentTimeMillis() - openedMillis) / 10.0f,
            (int) Math.floor(camera.x()) >> 4, (int) Math.floor(camera.z()) >> 4, near.renderDistanceChunks());
    }

    /** Whether this frame has far water or lights to draw over the scene copy. */
    boolean drawsOverScene() {
        return !waterDraws.isEmpty() || !lightDraws.isEmpty();
    }

    /** Draws this frame's far water over the copied scene, then the lights (just before translucent terrain). */
    void drawOverScene(RenderTarget mainTarget, FarLandPass pass, CameraView camera, NearTerrain near, boolean plainWater) {
        if (!drawsOverScene()) {
            return;
        }
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        GpuBuffer appearanceTable = gpu.appearances(appearances, atlas, atlasGeneration);
        pass.uploadSeam(seam);
        pass.drawOverScene(mainTarget, gpu, appearanceTable, waterDraws, lightDraws, plainWater, camera.drawViewProjection(),
            (System.currentTimeMillis() - openedMillis) / 10.0f,
            (int) Math.floor(camera.x()) >> 4, (int) Math.floor(camera.z()) >> 4, near.renderDistanceChunks());
    }

    // --- Status ---

    String problem() {
        String problem = storage.problem();
        return problem != null ? problem : meshProblem;
    }

    long diskBytes() {
        return store.diskBytes();
    }

    long videoMemoryBytes() {
        return gpu.reservedBytes();
    }

    int tiles() {
        return store.tileCount();
    }

    /** Readings for QA and the status line. */
    Map<String, Object> describe() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("world", world.key());
        state.put("dimension", world.dimension());
        state.put("folder", store.folder().toString());
        state.put("opened", storage.opened());
        state.put("tiles", store.tileCount());
        for (int level = 0; level <= TileKey.MAX_LEVEL; level++) {
            state.put("tilesL" + level, store.tileCount(level));
        }
        state.put("chunksStored", storage.chunksStored());
        state.put("chunksUnchanged", storage.chunksUnchanged());
        state.put("coarseBuilt", storage.coarseBuilt());
        state.put("storeMillis", storage.storeMillis());
        state.put("storeQueue", storage.queued());
        state.put("copiesDue", copyDue.size());
        state.put("meshes", meshes.size());
        state.put("meshesBuilt", meshesBuilt.get());
        state.put("tilesMeshed", meshedTiles.size());
        state.put("meshesPerTile", meshedTiles.isEmpty() ? 0.0 : meshesBuilt.get() / (double) meshedTiles.size());
        state.put("meshesWanted", meshRequests);
        state.put("standIns", standIns);
        state.put("meshMillis", meshMillis.get());
        state.put("meshesInFlight", inFlight.size());
        state.put("chosenTiles", chosen.size());
        state.put("drawnTiles", drawnTiles);
        state.put("drawCalls", openDraws.draws() + seamDraws.draws() + cardDraws.draws() + waterDraws.draws());
        state.put("drawnQuads", openDraws.quads() + seamDraws.quads() + cardDraws.quads() + waterDraws.quads());
        state.put("seamDraws", seamDraws.draws());
        state.put("cardQuads", cardDraws.quads());
        state.put("waterQuads", waterDraws.quads());
        state.put("lightPoints", lightDraws.quads());
        state.putAll(seamCounts());
        state.put("quadBytes", gpu.usedBytes());
        state.put("videoMemoryBytes", gpu.reservedBytes());
        state.put("pages", gpu.pages().size());
        state.put("appearances", gpu.appearanceCount());
        state.put("memoryFull", memoryFull);
        state.put("diskBytes", store.diskBytes());
        state.put("heldBytes", store.heldBytes());
        state.put("firstDrawnSeconds", firstDrawnAt == 0 ? -1.0 : (firstDrawnAt - openedAt) / 1.0e9);
        state.put("settledSeconds", settledAt == 0 ? -1.0 : (settledAt - openedAt) / 1.0e9);
        state.put("completeSeconds", completeAt == 0 ? -1.0 : (completeAt - openedAt) / 1.0e9);
        state.put("problem", problem());
        state.put("showsPredictions", land.showsPredictions());
        PredictedLand guessing = predicted;
        if (guessing != null) {
            state.put("predicted", guessing.describe());
        }
        return state;
    }

    /**
     * The seam over the square of chunks round the camera it follows: how many chunks that is, and
     * in how many far land and far water are wholly gone.
     */
    private Map<String, Object> seamCounts() {
        int land = 0;
        int water = 0;
        int radius = Math.max(seamRadius, 0);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                land += seam.fullyCovered(seamCentreX + dx, seamCentreZ + dz) ? 1 : 0;
                water += seam.waterCovered(seamCentreX + dx, seamCentreZ + dz) ? 1 : 0;
            }
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("seamChunks", (radius * 2 + 1) * (radius * 2 + 1));
        counts.put("seamLandCovered", land);
        counts.put("seamWaterCovered", water);
        return counts;
    }

    /**
     * For QA: the seam over the square round the camera, a row of characters per chunk row from
     * north to south. '.' the chunk isn't loaded, ' ' the near renderer's rule leaves it out, 't'
     * its top section isn't drawn yet, 'w' far water is gone but far land stays, 'L' both are gone,
     * and in lower case the same while still fading.
     */
    List<String> seamPicture(NearTerrain near, ClientLevel level, CameraView camera) {
        List<String> rows = new ArrayList<>();
        int radius = near.renderDistanceChunks();
        int centreX = (int) Math.floor(camera.x()) >> 4;
        int centreZ = (int) Math.floor(camera.z()) >> 4;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centreX + dx;
                int chunkZ = centreZ + dz;
                char mark;
                if (level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    mark = '.';
                } else if (!SeamCoverage.nearDrawsChunk(near.sodium(), camera.x(), camera.z(), chunkX, chunkZ, radius)) {
                    mark = ' ';
                } else if (seam.fullyCovered(chunkX, chunkZ)) {
                    mark = 'L';
                } else if (seam.value(chunkX, chunkZ) > 0) {
                    mark = 'l';
                } else if (seam.waterCovered(chunkX, chunkZ)) {
                    mark = 'w';
                } else if (seam.waterValue(chunkX, chunkZ) > 0) {
                    mark = 'v';
                } else {
                    mark = 't';
                }
                row.append(dx == 0 && dz == 0 ? '@' : mark);
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /** Deletes what is stored for this dimension when the session closes (Clear far terrain). */
    void deleteOnClose() {
        deleteOnClose = true;
    }

    @Override
    public void close() {
        storage.stop();
        PredictedLand guessing = predicted;
        if (guessing != null) {
            guessing.stop();
        }
        meshPool.shutdownNow();
        try {
            storageThread.join(10_000L);
            meshPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (guessing != null) {
            guessing.close();
        }
        if (deleteOnClose) {
            try {
                store.delete();
            } catch (IOException failure) {
                LiteLightsMod.LOG.error("Couldn't delete far terrain in {}", store.folder(), failure);
            }
        }
        gpu.close();
        openDraws.close();
        seamDraws.close();
        cardDraws.close();
        waterDraws.close();
        lightDraws.close();
        meshes.clear();
        results.clear();
    }
}
