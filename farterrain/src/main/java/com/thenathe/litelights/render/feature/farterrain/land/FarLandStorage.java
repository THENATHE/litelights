package com.thenathe.litelights.render.feature.farterrain.land;

import com.thenathe.litelights.api.LiteLightsMod;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The store worker: one thread that turns chunk copies into level-0 tiles, builds the coarse
 * levels from them a moment later, and writes the store to disk every few seconds and when it
 * stops. Every change to the store goes through it, so tiles are never written from two places.
 *
 * <p>A coarse tile is built {@link #COARSE_DELAY_MILLIS} after the last change under it, so a
 * player flying over new land makes each coarse tile once rather than once per chunk; building
 * stops at the first level that comes out unchanged.
 */
final class FarLandStorage implements Runnable {
    static final long COARSE_DELAY_MILLIS = 2000L;
    private static final long FLUSH_MILLIS = 10_000L;
    /** Tile data read back from disk kept in memory, beyond what is written. */
    private static final long HELD_BYTES = 48L << 20;
    /** Decoded level-0 tiles kept for the chunks still to come into them (a tile takes four chunks). */
    private static final int RECENT_TILES = 16;

    private final FarStore store;
    private final BlockPalette palette;
    private final BlockLooks looks;
    private final int minY;
    private final int worldHeight;
    private final LinkedBlockingQueue<ChunkCopy> copies = new LinkedBlockingQueue<>();
    private final Map<Long, Long> coarseDue = new ConcurrentHashMap<>();
    /** Tiles written, and tiles whose neighbours changed their edges (their border faces are out of date). */
    private final ConcurrentLinkedQueue<Long> changedTiles = new ConcurrentLinkedQueue<>();
    private final Map<Long, TileVoxels> recent = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TileVoxels> eldest) {
            return size() > RECENT_TILES;
        }
    };
    private final ChunkCopy.Before before = new ChunkCopy.Before();
    private final AtomicLong chunksStored = new AtomicLong();
    /** Chunks seen again as they were: their tiles weren't written, built or meshed again. */
    private final AtomicLong chunksUnchanged = new AtomicLong();
    private final AtomicLong coarseBuilt = new AtomicLong();
    private volatile boolean running = true;
    private volatile boolean opened;
    private volatile String problem;
    private volatile long storeMillis;

    FarLandStorage(FarStore store, BlockPalette palette, BlockLooks looks, int minY, int worldHeight) {
        this.store = store;
        this.palette = palette;
        this.looks = looks;
        this.minY = minY;
        this.worldHeight = worldHeight;
    }

    boolean opened() {
        return opened;
    }

    String problem() {
        return problem;
    }

    void submit(ChunkCopy copy) {
        if (running) {
            copies.add(copy);
        }
    }

    int queued() {
        return copies.size() + coarseDue.size();
    }

    long chunksStored() {
        return chunksStored.get();
    }

    long chunksUnchanged() {
        return chunksUnchanged.get();
    }

    long coarseBuilt() {
        return coarseBuilt.get();
    }

    /** Time spent storing and building, in milliseconds, since the world was opened. */
    long storeMillis() {
        return storeMillis;
    }

    /** A tile written, or one whose neighbour's edge changed; null when there is none. */
    Long pollChanged() {
        return changedTiles.poll();
    }

    /** Asks the thread to finish what it holds, write to disk and stop. */
    void stop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            store.open();
            opened = true;
        } catch (IOException | RuntimeException failure) {
            problem = "Couldn't open far terrain's store: " + failure.getMessage();
            LiteLightsMod.LOG.error("Couldn't open far terrain's store in {}", store.folder(), failure);
            return;
        }
        long lastFlush = System.currentTimeMillis();
        List<ChunkCopy> batch = new ArrayList<>();
        while (running) {
            try {
                ChunkCopy first = copies.poll(100, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    copies.drainTo(batch, 255);
                    storeChunks(batch);
                    batch.clear();
                }
                buildDueCoarse(System.currentTimeMillis(), 40);
                if (System.currentTimeMillis() - lastFlush > FLUSH_MILLIS) {
                    flush();
                    lastFlush = System.currentTimeMillis();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException failure) {
                LiteLightsMod.LOG.error("Far terrain's store worker failed on a batch; carrying on", failure);
            }
        }
        // Whatever is still queued goes to disk with the rest.
        copies.drainTo(batch);
        try {
            storeChunks(batch);
        } catch (RuntimeException failure) {
            LiteLightsMod.LOG.error("Far terrain couldn't store its last chunks", failure);
        }
        flush();
    }

    private void flush() {
        try {
            store.flush();
            store.trim(HELD_BYTES);
        } catch (IOException | RuntimeException failure) {
            problem = "Couldn't write far terrain to disk: " + failure.getMessage();
            LiteLightsMod.LOG.error("Couldn't write far terrain to {}", store.folder(), failure);
        }
    }

    /** Puts the chunks into their level-0 tiles, each tile read and written once per batch. */
    private void storeChunks(List<ChunkCopy> batch) {
        if (batch.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        Map<Long, List<ChunkCopy>> byTile = new HashMap<>();
        for (ChunkCopy copy : batch) {
            byTile.computeIfAbsent(TileKey.ofChunk(copy.chunkX, copy.chunkZ), key -> new ArrayList<>()).add(copy);
        }
        for (Map.Entry<Long, List<ChunkCopy>> entry : byTile.entrySet()) {
            long key = entry.getKey();
            TileVoxels tile = readOrNew(key, 0);
            boolean changed = store.meta(key) == null;
            for (ChunkCopy copy : entry.getValue()) {
                changed |= copy.writeInto(tile, palette, looks, before);
            }
            if (changed) {
                written(key, store.meta(key), store.write(key, tile, looks));
            } else {
                chunksUnchanged.addAndGet(entry.getValue().size());
            }
            recent.put(key, tile);
            chunksStored.addAndGet(entry.getValue().size());
        }
        storeMillis += (System.nanoTime() - started) / 1_000_000L;
    }

    private TileVoxels readOrNew(long key, int level) {
        int height = TileVoxels.heightFor(worldHeight, level);
        TileVoxels kept = recent.get(key);
        if (kept != null) {
            return kept;
        }
        try {
            TileVoxels stored = store.read(key);
            if (stored != null && stored.minY == minY && stored.height == height && stored.level == level) {
                return stored;
            }
        } catch (IOException damaged) {
            LiteLightsMod.LOG.warn("Far terrain tile {} couldn't be read and is made again: {}", TileKey.describe(key), damaged.getMessage());
        }
        return new TileVoxels(level, minY, height);
    }

    private void written(long key, TileMeta before, TileMeta after) {
        if (TileKey.level(key) < TileKey.MAX_LEVEL) {
            coarseDue.put(TileKey.parent(key), System.currentTimeMillis() + COARSE_DELAY_MILLIS);
        }
        changedTiles.add(key);
        if (before == null || !before.edges().equals(after.edges())) {
            changedTiles.add(TileKey.neighbour(key, 0, -1));
            changedTiles.add(TileKey.neighbour(key, 0, 1));
            changedTiles.add(TileKey.neighbour(key, -1, 0));
            changedTiles.add(TileKey.neighbour(key, 1, 0));
        }
    }

    /** Builds the coarse tiles whose wait is over, lowest level first, for up to {@code budgetMillis}. */
    private void buildDueCoarse(long now, long budgetMillis) {
        if (coarseDue.isEmpty()) {
            return;
        }
        List<Long> due = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : coarseDue.entrySet()) {
            if (entry.getValue() <= now) {
                due.add(entry.getKey());
            }
        }
        due.sort((a, b) -> Integer.compare(TileKey.level(a), TileKey.level(b)));
        long started = System.nanoTime();
        for (long key : due) {
            if ((System.nanoTime() - started) / 1_000_000L > budgetMillis || !running) {
                break;
            }
            coarseDue.remove(key);
            buildCoarse(key);
        }
        storeMillis += (System.nanoTime() - started) / 1_000_000L;
    }

    private void buildCoarse(long key) {
        TileVoxels[] children = new TileVoxels[4];
        boolean any = false;
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            long child = TileKey.child(key, quadrant);
            if (store.meta(child) == null) {
                continue;
            }
            try {
                TileVoxels kept = recent.get(child);
                children[quadrant] = kept != null ? kept : store.read(child);
                any |= children[quadrant] != null;
            } catch (IOException damaged) {
                LiteLightsMod.LOG.warn("Far terrain tile {} couldn't be read: {}", TileKey.describe(child), damaged.getMessage());
            }
        }
        if (!any) {
            return;
        }
        int level = TileKey.level(key);
        TileVoxels built = LevelBuilder.build(children, level, minY, worldHeight, looks);
        TileMeta before = store.meta(key);
        if (before != null) {
            try {
                TileVoxels kept = recent.get(key);
                TileVoxels stored = kept != null ? kept : store.read(key);
                if (stored != null && stored.sameAs(built)) {
                    return;
                }
            } catch (IOException damaged) {
                // Written again below.
            }
        }
        written(key, before, store.write(key, built, looks));
        recent.put(key, built);
        coarseBuilt.incrementAndGet();
    }
}
