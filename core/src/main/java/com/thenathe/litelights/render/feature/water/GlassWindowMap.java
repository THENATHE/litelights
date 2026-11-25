package com.thenathe.litelights.render.feature.water;

import com.thenathe.litelights.LiteLightsClient;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps the {@link GlassWindowGrid} for the glass around the camera current, in its region of the
 * {@link FluidDataTexture}, while weather on glass is drawn.
 *
 * <p>Each chunk within {@link #CHUNK_RADIUS} of the camera's is scanned for glass once when it comes
 * into range or loads, and again when a glass block in it is placed, broken or changes shape (a pane
 * joining a new neighbour). Only sections whose palette holds glass are read block by block, so a
 * chunk without glass costs a palette check per section. The windows are worked out again from every
 * chunk's glass when the glass changes or the camera moves to another chunk or 16 blocks up or down,
 * at most once every {@link #FRAMES_BETWEEN_BUILDS} frames. The working out runs on the game's
 * background threads from a copy of the glass (a few milliseconds for thousands of glass blocks),
 * and the whole table is uploaded on the render thread once it is done.
 */
final class GlassWindowMap implements AutoCloseable {
    /** Chunks this far from the camera's chunk are mapped: at least 96 blocks, past where glass weather fades out (72). */
    private static final int CHUNK_RADIUS = 6;
    /** Glass this far above or below the camera is mapped. */
    private static final int VERTICAL_REACH = 80;
    private static final int SCANS_PER_FRAME = 4;
    private static final int FRAMES_BETWEEN_BUILDS = 10;
    private static final int REBUILD_HEIGHT = 16;

    private record ChunkGlass(long[] positions, byte[] kinds) {
        static final ChunkGlass NONE = new ChunkGlass(new long[0], new byte[0]);

        boolean sameAs(ChunkGlass other) {
            return Arrays.equals(positions, other.positions) && Arrays.equals(kinds, other.kinds);
        }
    }

    private final Long2ObjectOpenHashMap<ChunkGlass> chunks = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private GlassWindowGrid grid = new GlassWindowGrid();
    private @Nullable CompletableFuture<GlassWindowGrid> building;
    private final LongArrayList scanPositions = new LongArrayList();
    private final ByteArrayList scanKinds = new ByteArrayList();
    private final ByteBuffer uploadBuffer = MemoryUtil.memAlloc(GlassWindowGrid.WIDTH * GlassWindowGrid.ROWS * 4)
        .order(ByteOrder.LITTLE_ENDIAN);

    private @Nullable ClientLevel level;
    private int minY;
    private int centreChunkX;
    private int centreChunkZ;
    private boolean hasCentre;
    private int builtAtY;
    private boolean needsBuild = true;
    private boolean needsUpload;
    private int framesSinceBuild = FRAMES_BETWEEN_BUILDS;
    private volatile double lastBuildMillis;
    private boolean failureLogged;

    /** Once per frame while weather on glass is drawn, with no render pass open. */
    void update(ClientLevel currentLevel, double cameraX, double cameraY, double cameraZ, FluidDataTexture target) {
        if (currentLevel != level) {
            forget();
            level = currentLevel;
            minY = currentLevel.getMinY();
        }
        int cameraBlockY = (int) Math.floor(cameraY);
        int chunkX = (int) Math.floor(cameraX) >> 4;
        int chunkZ = (int) Math.floor(cameraZ) >> 4;
        if (!hasCentre || chunkX != centreChunkX || chunkZ != centreChunkZ) {
            moveCentre(chunkX, chunkZ);
        }
        scanSome(currentLevel);
        if (Math.abs(cameraBlockY - builtAtY) >= REBUILD_HEIGHT) {
            needsBuild = true;
        }
        framesSinceBuild++;
        if (building != null && building.isDone()) {
            takeBuilt();
        }
        if (needsBuild && building == null && framesSinceBuild >= FRAMES_BETWEEN_BUILDS) {
            startBuild((int) Math.floor(cameraX), cameraBlockY, (int) Math.floor(cameraZ));
        }
        if (needsUpload) {
            needsUpload = false;
            uploadBuffer.clear();
            uploadBuffer.asIntBuffer().put(grid.texels());
            target.writeGlassWindows(uploadBuffer);
        }
    }

    /** Weather on glass stopped or the world was left: forget the glass, so it is all scanned again next time. */
    void forget() {
        level = null;
        chunks.clear();
        dirty.clear();
        hasCentre = false;
        needsBuild = true;
        framesSinceBuild = FRAMES_BETWEEN_BUILDS;
        if (building != null) {
            // Its glass is from before: let it finish and drop it.
            building.cancel(false);
            building = null;
        }
    }

    /** The texture was made again: the table must be written again. */
    void requestUpload() {
        needsUpload = true;
    }

    /** A chunk arrived or left, or a glass block in it changed. */
    void markChunk(int chunkX, int chunkZ) {
        if (hasCentre && inRange(chunkX, chunkZ)) {
            dirty.add(ChunkPos.pack(chunkX, chunkZ));
        }
    }

    /** For QA: entries in the table and how long the last build took. */
    int entries() {
        return grid.entries();
    }

    double lastBuildMillis() {
        return lastBuildMillis;
    }

    private void moveCentre(int chunkX, int chunkZ) {
        centreChunkX = chunkX;
        centreChunkZ = chunkZ;
        hasCentre = true;
        chunks.keySet().removeIf(key -> !inRange(ChunkPos.getX(key), ChunkPos.getZ(key)));
        dirty.removeIf(key -> !inRange(ChunkPos.getX(key), ChunkPos.getZ(key)));
        for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                long key = ChunkPos.pack(chunkX + dx, chunkZ + dz);
                if (!chunks.containsKey(key)) {
                    dirty.add(key);
                }
            }
        }
        needsBuild = true;
    }

    private boolean inRange(int chunkX, int chunkZ) {
        return Math.abs(chunkX - centreChunkX) <= CHUNK_RADIUS && Math.abs(chunkZ - centreChunkZ) <= CHUNK_RADIUS;
    }

    private void scanSome(ClientLevel currentLevel) {
        for (int scanned = 0; scanned < SCANS_PER_FRAME && !dirty.isEmpty(); scanned++) {
            long nearest = nearestDirty();
            dirty.remove(nearest);
            int chunkX = ChunkPos.getX(nearest);
            int chunkZ = ChunkPos.getZ(nearest);
            LevelChunk chunk = currentLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            // A chunk that isn't here yet holds no glass until its load event marks it again.
            ChunkGlass found = chunk == null ? ChunkGlass.NONE : scan(chunk);
            ChunkGlass before = chunks.put(nearest, found);
            if (before == null ? found.positions.length > 0 : !before.sameAs(found)) {
                needsBuild = true;
            }
        }
    }

    private long nearestDirty() {
        long nearest = 0L;
        int best = Integer.MAX_VALUE;
        for (long key : dirty) {
            int dx = ChunkPos.getX(key) - centreChunkX;
            int dz = ChunkPos.getZ(key) - centreChunkZ;
            int distance = dx * dx + dz * dz;
            if (distance < best) {
                best = distance;
                nearest = key;
            }
        }
        return nearest;
    }

    private ChunkGlass scan(LevelChunk chunk) {
        scanPositions.clear();
        scanKinds.clear();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(WindowGlass::isGlass)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        int kind = WindowGlass.kind(state);
                        if (kind != 0) {
                            scanPositions.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                            scanKinds.add((byte) kind);
                        }
                    }
                }
            }
        }
        return scanPositions.isEmpty() ? ChunkGlass.NONE : new ChunkGlass(scanPositions.toLongArray(), scanKinds.toByteArray());
    }

    /** Copies the glass in reach and works out its windows on a background thread. */
    private void startBuild(int cameraX, int cameraY, int cameraZ) {
        GlassWindowGrid.Cells cells = new GlassWindowGrid.Cells();
        for (ChunkGlass glass : chunks.values()) {
            for (int i = 0; i < glass.positions.length; i++) {
                long position = glass.positions[i];
                int y = BlockPos.getY(position);
                if (Math.abs(y - cameraY) <= VERTICAL_REACH) {
                    cells.add(BlockPos.getX(position), y, BlockPos.getZ(position), glass.kinds[i]);
                }
            }
        }
        int floor = minY;
        building = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            GlassWindowGrid built = new GlassWindowGrid();
            built.build(cells, cameraX, cameraY, cameraZ, floor);
            lastBuildMillis = (System.nanoTime() - started) / 1.0e6;
            return built;
        }, Util.backgroundExecutor());
        builtAtY = cameraY;
        needsBuild = false;
        framesSinceBuild = 0;
    }

    private void takeBuilt() {
        CompletableFuture<GlassWindowGrid> done = building;
        building = null;
        try {
            grid = done.join();
            needsUpload = true;
        } catch (RuntimeException failure) {
            // The last table stays; the next change tries again.
            if (!failureLogged) {
                failureLogged = true;
                LiteLightsClient.LOG.warn("Couldn't work out the glass windows for weather on glass", failure);
            }
        }
    }

    @Override
    public void close() {
        MemoryUtil.memFree(uploadBuffer);
    }
}
