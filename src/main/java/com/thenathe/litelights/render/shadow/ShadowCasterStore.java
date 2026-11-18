package com.thenathe.litelights.render.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.thenathe.litelights.LiteLightsClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * Owns every section's shadow caster mesh: which sections need building, the worker threads that
 * build them, and where they end up in GPU memory ({@link CasterBufferPool}).
 *
 * <p>Sections are marked dirty by chunk loads and by every block or light change the level
 * reports. Light changes rebuild the section too (they can't be told apart from block changes at
 * that point), but the rebuilt mesh hashes the same, so it is dropped without a shadow redraw:
 * only a changed hash is reported through {@link #nearestChangeWithin}.
 */
public final class ShadowCasterStore implements AutoCloseable {
    /**
     * Builds started per frame. Taking a section's snapshot copies up to seven block containers on
     * the client thread, so starting stops after this many sections or this much time, whichever
     * comes first.
     */
    private static final int MAX_BUILDS_PER_FRAME = 48;
    private static final long SNAPSHOT_BUDGET_NANOS = 500_000L;
    private static final int MAX_IN_FLIGHT = 192;
    private static final Direction[] DIRECTIONS = Direction.values();

    public record SectionCasters(long sectionKey, CasterBufferPool.Slot slot, int quadCount, long hash) {
        public GpuBuffer buffer() {
            return slot.buffer();
        }

        public int firstVertex() {
            return slot.firstVertex();
        }
    }

    /**
     * A worker's finished mesh and the generation its build started in. A level change between the
     * build's start and its collection makes it stale, and only the render thread decides that: a
     * check on the worker could pass just before the change and let the old level's mesh through.
     */
    private record Finished(CasterMesh mesh, long generation) {
    }

    private static final int NEAREST_LIMIT = MAX_BUILDS_PER_FRAME * 2;
    private final long[] nearestKeys = new long[NEAREST_LIMIT];
    private final double[] nearestDistances = new double[NEAREST_LIMIT];
    private final LongArrayList nearest = new LongArrayList(NEAREST_LIMIT);
    private final Long2ObjectMap<SectionCasters> built = new Long2ObjectOpenHashMap<>();
    /** Hashes of sections that were built but turned out empty, so an empty rebuild is no change either. */
    private final LongOpenHashSet builtEmpty = new LongOpenHashSet();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    private final LongArrayList changed = new LongArrayList();
    private final Queue<Finished> finished = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers;
    private final CasterArrayPool meshArrays = new CasterArrayPool();
    private final ThreadLocal<CasterMeshBuilder> builders = ThreadLocal.withInitial(() -> new CasterMeshBuilder(meshArrays));
    /**
     * The block containers copied during one {@link #update}, by section key. Sections started in
     * the same frame are mostly each other's neighbours (a chunk's whole column turns dirty at once),
     * so each section is copied once per frame and its copy shared by every snapshot that needs it.
     * Nothing changes the level during an update, and the copies are only ever read afterwards, so
     * sharing them between worker threads is safe. Cleared when the update ends.
     */
    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> frameCopies = new Long2ObjectOpenHashMap<>();
    private final CasterBufferPool buffers;
    private @Nullable ClientLevel level;
    /** Counts level changes and releases; render thread only, handed to each build as it starts. */
    private long generation;
    private int pendingInRange;
    /** Set by {@link #nearestDirty}: every dirty section within range not already being built. */
    private int dirtyInRange;
    /** Diagnostics: rebuilds dropped because their geometry hash didn't change (light-only rebuilds). */
    private long unchangedRebuilds;

    public ShadowCasterStore() {
        this(CasterBufferPool.onGpu());
    }

    ShadowCasterStore(CasterBufferPool buffers) {
        this.buffers = buffers;
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
        workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "LiteLights shadow casters");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /** Drops everything when the level changes (joining a world, changing dimension). */
    public void setLevel(@Nullable ClientLevel newLevel) {
        if (newLevel == level) {
            return;
        }
        clear();
        level = newLevel;
    }

    /**
     * Frees the GPU memory of every mesh (shadows have been switched off for a while) and marks those
     * sections dirty, so they are built again once shadows are back on. Sections known to be empty
     * hold no memory and stay known; builds still running are dropped and their sections marked
     * dirty too. Chunks keep arriving and leaving meanwhile, which keeps the dirty set current.
     */
    public void releaseMeshes() {
        dirty.addAll(built.keySet());
        dirty.addAll(inFlight);
        generation++;
        buffers.clear();
        built.clear();
        inFlight.clear();
        changed.clear();
        finished.clear();
    }

    /** Rebuild everything, for example after a resource reload changed the block models. */
    public void invalidateAll() {
        dirty.addAll(built.keySet());
        dirty.addAll(builtEmpty);
    }

    public void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        dirty.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    public void markBlockDirty(int blockX, int blockY, int blockZ) {
        markSectionDirty(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockY), SectionPos.blockToSectionCoord(blockZ));
    }

    /**
     * A chunk arrived: build all its sections, and rebuild the sections next to it, whose faces
     * toward this chunk were exposed until now.
     */
    public void chunkLoaded(ClientLevel chunkLevel, int chunkX, int chunkZ) {
        // After a dimension change the first chunks arrive before the renderer's next frame has
        // seen the new level; switching here keeps them instead of dropping the spawn area's casters.
        setLevel(chunkLevel);
        int minSection = chunkLevel.getMinSectionY();
        int maxSection = chunkLevel.getMaxSectionY();
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            markSectionDirty(chunkX, sectionY, chunkZ);
            markSectionDirty(chunkX - 1, sectionY, chunkZ);
            markSectionDirty(chunkX + 1, sectionY, chunkZ);
            markSectionDirty(chunkX, sectionY, chunkZ - 1);
            markSectionDirty(chunkX, sectionY, chunkZ + 1);
        }
    }

    public void chunkUnloaded(ClientLevel chunkLevel, int chunkX, int chunkZ) {
        if (chunkLevel != level) {
            return;
        }
        for (int sectionY = chunkLevel.getMinSectionY(); sectionY <= chunkLevel.getMaxSectionY(); sectionY++) {
            long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
            dirty.remove(key);
            builtEmpty.remove(key);
            SectionCasters removed = built.remove(key);
            if (removed != null) {
                buffers.release(removed.slot());
                changed.add(key);
            }
        }
    }

    /**
     * Once per frame on the render thread: uploads finished meshes and starts new builds, nearest
     * to the camera first, for dirty sections within range of the shadow map.
     */
    public void update(double cameraX, double cameraZ, double range) {
        buffers.endFrame();
        collectFinished();
        if (level == null || dirty.isEmpty()) {
            pendingInRange = inFlight.size();
            return;
        }
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        // Only the nearest few dirty sections can start this frame, so pick those instead of
        // sorting everything that is dirty (thousands while chunks stream in).
        LongArrayList candidates = nearestDirty(cameraX, cameraZ, (range + 16.0) * (range + 16.0));
        int inRange = dirtyInRange;
        int started = 0;
        int handled = 0;
        long deadline = System.nanoTime() + SNAPSHOT_BUDGET_NANOS;
        try {
            while (handled < candidates.size() && started < MAX_BUILDS_PER_FRAME && inFlight.size() < MAX_IN_FLIGHT
                && System.nanoTime() < deadline) {
                long key = candidates.getLong(handled++);
                dirty.remove(key);
                SectionSnapshot snapshot = snapshot(level, key);
                if (snapshot == null) {
                    accept(CasterMesh.empty(key));
                    continue;
                }
                inFlight.add(key);
                started++;
                long buildGeneration = generation;
                workers.execute(() -> build(snapshot, models, buildGeneration));
            }
        } finally {
            frameCopies.clear();
        }
        pendingInRange = inRange - handled + inFlight.size();
    }

    /**
     * Up to {@link #NEAREST_LIMIT} dirty sections within range, nearest first, skipping ones already
     * being built, and how many there are in all ({@link #dirtyInRange}): one pass over the dirty
     * set for both. Render thread only: the result reuses the same arrays every frame.
     */
    private LongArrayList nearestDirty(double cameraX, double cameraZ, double rangeSquared) {
        int limit = NEAREST_LIMIT;
        long[] keys = nearestKeys;
        double[] distances = nearestDistances;
        int count = 0;
        int inRange = 0;
        LongIterator iterator = dirty.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            double distance = distanceSquared(key, cameraX, cameraZ);
            if (distance > rangeSquared || inFlight.contains(key)) {
                continue;
            }
            inRange++;
            if (count == limit && distance >= distances[count - 1]) {
                continue;
            }
            int position = count == limit ? limit - 1 : count++;
            while (position > 0 && distances[position - 1] > distance) {
                keys[position] = keys[position - 1];
                distances[position] = distances[position - 1];
                position--;
            }
            keys[position] = key;
            distances[position] = distance;
        }
        nearest.clear();
        nearest.addElements(0, keys, 0, count);
        dirtyInRange = inRange;
        return nearest;
    }

    private static double distanceSquared(long key, double cameraX, double cameraZ) {
        double dx = SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8.0 - cameraX;
        double dz = SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8.0 - cameraZ;
        return dx * dx + dz * dz;
    }

    private void build(SectionSnapshot snapshot, BlockStateModelSet models, long buildGeneration) {
        CasterMesh mesh;
        try {
            mesh = builders.get().build(snapshot, models);
        } catch (Throwable error) {
            LiteLightsClient.LOG.warn("Couldn't build shadow casters for section {}", SectionPos.of(snapshot.sectionKey()), error);
            mesh = CasterMesh.empty(snapshot.sectionKey());
        }
        finishedBuild(mesh, buildGeneration);
    }

    /** A worker hands over a mesh built for {@code buildGeneration}; any thread. */
    void finishedBuild(CasterMesh mesh, long buildGeneration) {
        finished.add(new Finished(mesh, buildGeneration));
    }

    @SuppressWarnings("unchecked")
    private @Nullable SectionSnapshot snapshot(ClientLevel level, long key) {
        int sectionX = SectionPos.x(key);
        int sectionY = SectionPos.y(key);
        int sectionZ = SectionPos.z(key);
        PalettedContainer<BlockState> states = copyForThisFrame(level, sectionX, sectionY, sectionZ);
        if (states == null) {
            return null;
        }
        PalettedContainer<BlockState>[] neighbours = new PalettedContainer[DIRECTIONS.length];
        for (Direction direction : DIRECTIONS) {
            neighbours[direction.ordinal()] = copyForThisFrame(level,
                sectionX + direction.getStepX(), sectionY + direction.getStepY(), sectionZ + direction.getStepZ());
        }
        return new SectionSnapshot(key, states, neighbours);
    }

    /** The section's blocks, copied at most once per update (see {@link #frameCopies}). */
    private @Nullable PalettedContainer<BlockState> copyForThisFrame(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
        PalettedContainer<BlockState> copy = frameCopies.get(key);
        if (copy == null) {
            // Sections without blocks aren't kept: finding that out again costs no copy.
            copy = copySection(level, sectionX, sectionY, sectionZ);
            if (copy != null) {
                frameCopies.put(key, copy);
            }
        }
        return copy;
    }

    private static @Nullable PalettedContainer<BlockState> copySection(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        if (sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY()) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }
        LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
        if (section.hasOnlyAir()) {
            return null;
        }
        return section.getStates().copy();
    }

    /** Render thread: keeps the meshes built for the current level and drops the rest. */
    void collectFinished() {
        Finished entry;
        while ((entry = finished.poll()) != null) {
            if (entry.generation() != generation) {
                // Built for a level (or a release) that is gone. Its section may already be in
                // flight again for the new one, so it mustn't touch that build's entry either.
                meshArrays.give(entry.mesh().vertices());
                continue;
            }
            inFlight.remove(entry.mesh().sectionKey());
            accept(entry.mesh());
        }
    }

    /** Sections waiting to be built, for the tests. */
    int dirtyCount() {
        return dirty.size();
    }

    /** The current generation, for the tests (builds started now carry it). */
    long generation() {
        return generation;
    }

    /** Keeps a freshly built mesh, and reports it as changed only if its geometry differs. */
    private void accept(CasterMesh mesh) {
        long key = mesh.sectionKey();
        SectionCasters previous = built.get(key);
        if (mesh.isEmpty()) {
            if (previous != null) {
                built.remove(key);
                buffers.release(previous.slot());
                changed.add(key);
            }
            builtEmpty.add(key);
            return;
        }
        builtEmpty.remove(key);
        if (previous != null && previous.hash() == mesh.hash() && previous.quadCount() == mesh.quadCount()) {
            unchangedRebuilds++;
            meshArrays.give(mesh.vertices());
            return;
        }
        // The vertices are copied out during the store, so their array can go back to the workers.
        built.put(key, new SectionCasters(key, buffers.store(mesh.vertices(), mesh.byteCount()), mesh.quadCount(), mesh.hash()));
        meshArrays.give(mesh.vertices());
        if (previous != null) {
            buffers.release(previous.slot());
        }
        changed.add(key);
    }

    /**
     * The horizontal distance from (x, z) to the nearest section that changed geometry since the
     * last {@link #clearChanges}, among those within the given square; infinity if none did.
     * Changes outside the square can't affect a map that covers it.
     */
    public double nearestChangeWithin(double centerX, double centerZ, double radius, double fromX, double fromZ) {
        return nearestChangeWithin(changed, centerX, centerZ, radius, fromX, fromZ);
    }

    /** {@link #nearestChangeWithin} over a list of section keys; static for the tests. */
    static double nearestChangeWithin(LongArrayList changed, double centerX, double centerZ, double radius, double fromX, double fromZ) {
        double nearest = Double.POSITIVE_INFINITY;
        double nearestSquared = Double.POSITIVE_INFINITY;
        for (int i = 0; i < changed.size(); i++) {
            long key = changed.getLong(i);
            double x = SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8.0;
            double z = SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8.0;
            if (Math.abs(x - centerX) <= radius + 8.0 && Math.abs(z - centerZ) <= radius + 8.0) {
                double dx = x - fromX;
                double dz = z - fromZ;
                double squared = dx * dx + dz * dz;
                // Math.hypot is slow and this list holds thousands of sections while chunks stream
                // in, so a section clearly further than the nearest so far isn't measured. The
                // margin is far wider than the rounding of either calculation, so a skipped one
                // could never have been the nearest: the result is exactly what measuring all gives.
                if (squared <= nearestSquared * (1.0 + 1.0e-9)) {
                    double distance = Math.hypot(dx, dz);
                    if (distance < nearest) {
                        nearest = distance;
                        nearestSquared = squared;
                    }
                }
            }
        }
        return nearest;
    }

    public void clearChanges() {
        changed.clear();
    }

    public @Nullable SectionCasters get(long sectionKey) {
        return built.get(sectionKey);
    }

    public void forEachBuilt(Consumer<SectionCasters> consumer) {
        for (SectionCasters casters : built.values()) {
            consumer.accept(casters);
        }
    }

    /** GPU memory the caster meshes take: buffers, bytes reserved, bytes holding geometry. */
    public record Memory(int buffers, long reservedBytes, long usedBytes) {
    }

    public Memory memory() {
        return new Memory(buffers.pageCount(), buffers.reservedBytes(), buffers.usedBytes());
    }

    public long unchangedRebuilds() {
        return unchangedRebuilds;
    }

    public int builtCount() {
        return built.size();
    }

    /** Sections near enough to matter that are still waiting to be built, as of the last update. */
    public int pendingInRange() {
        return pendingInRange;
    }

    void clear() {
        generation++;
        buffers.clear();
        built.clear();
        builtEmpty.clear();
        dirty.clear();
        inFlight.clear();
        changed.clear();
        finished.clear();
    }

    @Override
    public void close() {
        clear();
        workers.shutdownNow();
    }
}
