package com.thenathe.litelights.render.feature.water;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps the {@link FluidColumnGrid} for the 16 x 16 chunks around the camera current, and its texels
 * in the corner of the {@link FluidDataTexture}.
 *
 * <p>Each chunk owns a fixed 16 x 16 slot of the wrapping grid (chunk x mod 16, chunk z mod 16), so
 * the camera moving a chunk only rescans the chunks that came into range. A chunk is scanned on
 * the render thread when it enters the window, loads, or has a block changed; a scan reads two
 * heightmaps and a few block states per column (tens of microseconds per chunk), and a few chunks
 * are scanned per frame, nearest first.
 *
 * <p>The scan also finds where falling water lands, for the waterfall foam and mist particles,
 * where rain stops in each column ({@link RainTopGrid}), for weather on glass, while foam drifts,
 * which way the water at each surface flows ({@link FluidFlowGrid}), and while the ocean waves are
 * on, which water is in an ocean biome and still ({@link OceanColumns}), from which a background
 * job works out the {@link ShoreField} whenever the columns have changed.
 */
final class FluidColumnMap implements AutoCloseable {
    private static final int CHUNKS = FluidColumnGrid.SIZE / 16;
    private static final int SLOT_COUNT = CHUNKS * CHUNKS;
    private static final int SCANS_PER_FRAME = 6;
    private static final long NO_CHUNK = Long.MIN_VALUE;
    /** Taller drops than this aren't followed down; the landing is simply not recorded. */
    private static final int LONGEST_FALL = 96;
    /** Leaves more than this far above the surface don't drop leaves on it. */
    private static final int CANOPY_REACH = 16;
    /** The shore field is worked out again at most this often while columns keep changing, in nanoseconds. */
    private static final long SHORE_FIELD_INTERVAL = 300_000_000L;
    /** Slot offsets from the camera's chunk, nearest first. */
    private static final int[][] OFFSETS_NEAREST_FIRST = offsetsNearestFirst();

    private final FluidColumnGrid grid = new FluidColumnGrid();
    private final RainTopGrid rainTops = new RainTopGrid();
    private final FluidFlowGrid flows = new FluidFlowGrid();
    private final OceanColumns oceanColumns = new OceanColumns();
    private final long[] slotChunk = new long[SLOT_COUNT];
    private final boolean[] needsScan = new boolean[SLOT_COUNT];
    private final boolean[] needsUpload = new boolean[SLOT_COUNT];
    private final int[] reflectiveColumns = new int[SLOT_COUNT];
    private final LongArrayList[] landingPositions = new LongArrayList[SLOT_COUNT];
    private final IntArrayList[] landingDrops = new IntArrayList[SLOT_COUNT];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos probeCursor = new BlockPos.MutableBlockPos();
    private final CoveredWater coveredWater = new CoveredWater();
    private @Nullable LevelChunk scanning;
    private final ByteBuffer uploadBuffer = MemoryUtil.memAlloc(16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);

    private @Nullable ClientLevel level;
    private int minY;
    private int originChunkX;
    private int originChunkZ;
    private boolean hasOrigin;
    private boolean complete;
    /** Whether scans record the surface flow (only while foam drifts: it reads a few more blocks per water column). */
    private boolean recordFlow;
    /** Whether scans record the ocean columns and the shore field is kept (only while Ocean waves is on). */
    private boolean recordWaves;
    private boolean shoreFieldStale;
    private boolean shoreFieldUploadWanted;
    private long shoreFieldStarted;
    private @Nullable CompletableFuture<ShoreFieldJob> shoreFieldJob;
    private @Nullable ShoreFieldJob shoreFieldReady;
    private @Nullable ShoreFieldJob spareShoreFieldJob;
    private @Nullable ByteBuffer shoreFieldUpload;

    FluidColumnMap() {
        Arrays.fill(slotChunk, NO_CHUNK);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            landingPositions[slot] = new LongArrayList();
            landingDrops[slot] = new IntArrayList();
        }
    }

    /** Once per frame, with no render pass open. */
    void update(ClientLevel currentLevel, double cameraX, double cameraZ, FluidDataTexture target) {
        if (currentLevel != level) {
            level = currentLevel;
            minY = currentLevel.getMinY();
            Arrays.fill(slotChunk, NO_CHUNK);
            hasOrigin = false;
        }
        int newOriginX = (int) Math.floor(cameraX / 16.0) - CHUNKS / 2;
        int newOriginZ = (int) Math.floor(cameraZ / 16.0) - CHUNKS / 2;
        if (!hasOrigin || newOriginX != originChunkX || newOriginZ != originChunkZ) {
            originChunkX = newOriginX;
            originChunkZ = newOriginZ;
            hasOrigin = true;
            grid.setWindow(originChunkX * 16, originChunkZ * 16);
            claimSlots();
            // Columns along the window's edge lost the neighbours beyond it: their shore and canopy
            // data are worked out again, so nothing stale from before the move remains there.
            markWindowEdgeForUpload();
        }
        int scanned = scanSome(currentLevel);
        uploadChanged(target);
        if (recordWaves) {
            if (scanned > 0) {
                shoreFieldStale = true;
            }
            updateShoreField(target);
        }
    }

    /** Forgets the level and every column, as after leaving the world. */
    void reset() {
        level = null;
        Arrays.fill(slotChunk, NO_CHUNK);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            landingPositions[slot].clear();
            landingDrops[slot].clear();
        }
        hasOrigin = false;
        complete = false;
        // A computation still running belongs to the old level: its result is dropped.
        shoreFieldJob = null;
        shoreFieldReady = null;
        shoreFieldStale = recordWaves;
    }

    /** The texture was made again: every slot's texels must be written again. */
    void requestFullUpload() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            needsUpload[slot] = slotChunk[slot] != NO_CHUNK;
        }
        shoreFieldUploadWanted = shoreFieldReady != null;
    }

    /**
     * Starts or stops recording the surface flow. Every chunk is scanned again, so the flow appears
     * (or is cleared) everywhere at once.
     */
    void setRecordFlow(boolean record) {
        if (record == recordFlow) {
            return;
        }
        recordFlow = record;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (slotChunk[slot] != NO_CHUNK) {
                needsScan[slot] = true;
                complete = false;
            }
        }
    }

    /**
     * Starts or stops recording what the ocean waves need. Turning it on scans every chunk again and
     * then works out the shore field; turning it off forgets the field.
     */
    void setRecordWaves(boolean record) {
        if (record == recordWaves) {
            return;
        }
        recordWaves = record;
        shoreFieldReady = null;
        shoreFieldStale = record;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (slotChunk[slot] != NO_CHUNK) {
                needsScan[slot] = true;
                complete = false;
            }
        }
    }

    /** How long the last shore field took to work out on its background thread, in milliseconds (for QA). */
    double shoreFieldMillis() {
        return shoreFieldReady == null ? Double.NaN : shoreFieldReady.nanos / 1.0e6;
    }

    /** Whether a shore field for the current columns is in the texture, so the waves may read it. */
    boolean hasShoreField() {
        return recordWaves && shoreFieldReady != null && !shoreFieldUploadWanted;
    }

    /**
     * A block changed. The flow at a column depends on its neighbours, so while it is recorded a
     * change on a chunk's edge also rescans the chunk across that edge.
     */
    void markBlock(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        markChunk(chunkX, chunkZ);
        if (!recordFlow) {
            return;
        }
        int inX = x & 15;
        int inZ = z & 15;
        if (inX == 0 || inX == 15) {
            markChunk(chunkX + (inX == 0 ? -1 : 1), chunkZ);
        }
        if (inZ == 0 || inZ == 15) {
            markChunk(chunkX, chunkZ + (inZ == 0 ? -1 : 1));
        }
    }

    /** A chunk arrived, left, or had a block changed. */
    void markChunk(int chunkX, int chunkZ) {
        int slot = slotOf(chunkX, chunkZ);
        if (slotChunk[slot] == chunkKey(chunkX, chunkZ)) {
            needsScan[slot] = true;
            complete = false;
        }
    }

    int originBlockX() {
        return originChunkX * 16;
    }

    int originBlockZ() {
        return originChunkZ * 16;
    }

    int minY() {
        return minY;
    }

    boolean hasOrigin() {
        return hasOrigin;
    }

    /** Whether any water or ice surface is in range (when not, the scene copy is skipped). */
    boolean hasReflectiveSurface() {
        for (int count : reflectiveColumns) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    /** Every chunk in the window that exists has been scanned (for the QA harness). */
    boolean isComplete() {
        return complete;
    }

    /** The surface height of the fluid in a column, or NaN, for the jump splash. */
    double surfaceY(int x, int z) {
        if (!contains(x, z)) {
            return Double.NaN;
        }
        int column = grid.get(x, z);
        return FluidColumnGrid.hasFluid(column) ? FluidColumnGrid.surfaceY(column, minY) : Double.NaN;
    }

    /**
     * The surface of open water in a column, or NaN: no fluid, lava, falling water or a column not
     * read yet. For the wakes, which only cross still and flowing water at its top.
     */
    double openWaterSurfaceY(int x, int z) {
        if (!contains(x, z)) {
            return Double.NaN;
        }
        int column = grid.get(x, z);
        boolean open = FluidColumnGrid.hasFluid(column) && (column & (FluidColumnGrid.LAVA | FluidColumnGrid.FALLING)) == 0
            && (column & FluidColumnGrid.KNOWN) != 0;
        return open ? FluidColumnGrid.surfaceY(column, minY) : Double.NaN;
    }

    interface LandingConsumer {
        void accept(int x, int y, int z, int drop);
    }

    /** Waterfall landings in chunks within {@code chunkRadius} of the camera's chunk. */
    void forEachLanding(int chunkRadius, LandingConsumer consumer) {
        int centreX = originChunkX + CHUNKS / 2;
        int centreZ = originChunkZ + CHUNKS / 2;
        for (int chunkZ = centreZ - chunkRadius; chunkZ <= centreZ + chunkRadius; chunkZ++) {
            for (int chunkX = centreX - chunkRadius; chunkX <= centreX + chunkRadius; chunkX++) {
                int slot = slotOf(chunkX, chunkZ);
                if (slotChunk[slot] != chunkKey(chunkX, chunkZ)) {
                    continue;
                }
                LongArrayList positions = landingPositions[slot];
                IntArrayList drops = landingDrops[slot];
                for (int i = 0; i < positions.size(); i++) {
                    long packed = positions.getLong(i);
                    consumer.accept(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed), drops.getInt(i));
                }
            }
        }
    }

    private boolean contains(int x, int z) {
        int inMapX = x - originChunkX * 16;
        int inMapZ = z - originChunkZ * 16;
        return hasOrigin && inMapX >= 0 && inMapZ >= 0 && inMapX < FluidColumnGrid.SIZE && inMapZ < FluidColumnGrid.SIZE;
    }

    /** Points every slot at the chunk it holds in the current window; slots whose chunk changed start empty. */
    private void claimSlots() {
        for (int slotZ = 0; slotZ < CHUNKS; slotZ++) {
            for (int slotX = 0; slotX < CHUNKS; slotX++) {
                int chunkX = originChunkX + Math.floorMod(slotX - originChunkX, CHUNKS);
                int chunkZ = originChunkZ + Math.floorMod(slotZ - originChunkZ, CHUNKS);
                int slot = slotX + slotZ * CHUNKS;
                long key = chunkKey(chunkX, chunkZ);
                if (slotChunk[slot] != key) {
                    slotChunk[slot] = key;
                    grid.clearChunk(chunkX, chunkZ);
                    rainTops.clearChunk(chunkX, chunkZ);
                    flows.clearChunk(chunkX, chunkZ);
                    oceanColumns.clearChunk(chunkX, chunkZ);
                    landingPositions[slot].clear();
                    landingDrops[slot].clear();
                    reflectiveColumns[slot] = 0;
                    needsScan[slot] = true;
                    needsUpload[slot] = true;
                    complete = false;
                }
            }
        }
    }

    /** Scans up to a few chunks that need it, nearest first; returns how many chunks' columns changed. */
    private int scanSome(ClientLevel currentLevel) {
        int scanned = 0;
        int changed = 0;
        boolean allDone = true;
        int centreX = originChunkX + CHUNKS / 2;
        int centreZ = originChunkZ + CHUNKS / 2;
        for (int[] offset : OFFSETS_NEAREST_FIRST) {
            int chunkX = centreX + offset[0];
            int chunkZ = centreZ + offset[1];
            int slot = slotOf(chunkX, chunkZ);
            if (!needsScan[slot]) {
                continue;
            }
            if (scanned == SCANS_PER_FRAME) {
                allDone = false;
                break;
            }
            LevelChunk chunk = currentLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            needsScan[slot] = false;
            if (chunk == null) {
                // Not here yet: stays empty until its load event marks it again.
                if (reflectiveColumns[slot] != 0 || !landingPositions[slot].isEmpty() || rainTops.get(chunkX * 16, chunkZ * 16) != 0) {
                    grid.clearChunk(chunkX, chunkZ);
                    rainTops.clearChunk(chunkX, chunkZ);
                    flows.clearChunk(chunkX, chunkZ);
                    oceanColumns.clearChunk(chunkX, chunkZ);
                    landingPositions[slot].clear();
                    landingDrops[slot].clear();
                    reflectiveColumns[slot] = 0;
                    markUploadAround(chunkX, chunkZ);
                    changed++;
                }
                continue;
            }
            scan(chunk, slot);
            markUploadAround(chunkX, chunkZ);
            scanned++;
            changed++;
        }
        complete = allDone;
        return changed;
    }

    private void markWindowEdgeForUpload() {
        for (int i = 0; i < CHUNKS; i++) {
            markUpload(originChunkX + i, originChunkZ);
            markUpload(originChunkX + i, originChunkZ + CHUNKS - 1);
            markUpload(originChunkX, originChunkZ + i);
            markUpload(originChunkX + CHUNKS - 1, originChunkZ + i);
        }
    }

    private void markUpload(int chunkX, int chunkZ) {
        int slot = slotOf(chunkX, chunkZ);
        if (slotChunk[slot] == chunkKey(chunkX, chunkZ)) {
            needsUpload[slot] = true;
        }
    }

    /** A chunk's columns feed its neighbours' shore and canopy data, so their texels change too. */
    private void markUploadAround(int chunkX, int chunkZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int slot = slotOf(chunkX + dx, chunkZ + dz);
                if (slotChunk[slot] == chunkKey(chunkX + dx, chunkZ + dz)) {
                    needsUpload[slot] = true;
                }
            }
        }
    }

    private void scan(LevelChunk chunk, int slot) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LongArrayList positions = landingPositions[slot];
        IntArrayList drops = landingDrops[slot];
        positions.clear();
        drops.clear();
        int reflective = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                rainTops.set(worldX, worldZ, RainTopGrid.pack(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), minY));
                cursor.set(worldX, top, worldZ);
                BlockState state = chunk.getBlockState(cursor);
                FluidState fluid = state.getFluidState();
                // Something thin floating on the fluid (a lily pad) hides it from the heightmap.
                if (fluid.isEmpty() && top > minY && !state.isCollisionShapeFullBlock(chunk, cursor)) {
                    cursor.setY(top - 1);
                    FluidState below = chunk.getBlockState(cursor).getFluidState();
                    if (!below.isEmpty()) {
                        fluid = below;
                    } else {
                        cursor.setY(top);
                    }
                }
                boolean ice = fluid.isEmpty() && state.is(BlockTags.ICE);
                double surfaceY = Double.NaN;
                boolean lava = false;
                boolean falls = false;
                int flow = 0;
                int ocean = 0;
                if (!fluid.isEmpty()) {
                    lava = fluid.is(FluidTags.LAVA);
                    surfaceY = cursor.getY() + fluid.getHeight(chunk, cursor);
                    if (!lava) {
                        if (recordFlow && level != null) {
                            // Read through the level, not the chunk: the flow looks at the neighbours,
                            // which on the chunk's edge lie in the next chunk.
                            Vec3 current = fluid.getFlow(level, cursor);
                            flow = FluidFlowGrid.pack(current.x, current.z);
                        }
                        if (recordWaves) {
                            ocean = oceanKind(chunk, fluid);
                        }
                        falls = recordFall(chunk, cursor.getX(), cursor.getY(), cursor.getZ(), positions, drops);
                    }
                }
                flows.set(worldX, worldZ, flow);
                oceanColumns.set(worldX, worldZ, ocean);
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                boolean canopy = surface > top && surface - top <= CANOPY_REACH
                    && chunk.getBlockState(cursor.set(worldX, surface, worldZ)).is(BlockTags.LEAVES);
                grid.set(worldX, worldZ, FluidColumnGrid.column(surfaceY, minY, top + 1, lava, ice, canopy, falls));
                if ((!Double.isNaN(surfaceY) && !lava) || ice) {
                    reflective++;
                }
            }
        }
        scanning = chunk;
        reflective += coveredWater.flood(grid, minY, baseX, baseZ, this::waterSurface, this::coveredWaterCarriesOn);
        scanning = null;
        reflectiveColumns[slot] = reflective;
    }

    /**
     * The ocean waves' bits for the water surface at the cursor: an ocean or beach biome there, and
     * a surface that doesn't flow (the game's own flow, so Streams Reflowing's currents count).
     */
    private int oceanKind(LevelChunk chunk, FluidState fluid) {
        int kind = OceanColumns.SCANNED;
        var biome = chunk.getNoiseBiome(QuartPos.fromBlock(cursor.getX()), QuartPos.fromBlock(cursor.getY()), QuartPos.fromBlock(cursor.getZ()));
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH)) {
            kind |= OceanColumns.OCEAN_BIOME;
        }
        if (level != null && fluid.isSource() && fluid.getFlow(level, cursor).lengthSqr() < 0.01) {
            kind |= OceanColumns.STILL;
        }
        return kind;
    }

    // --- The shore field ---

    /** One computation of the shore field: its inputs, copied on the render thread, and its result. */
    private static final class ShoreFieldJob {
        final int[] columns = new int[FluidColumnGrid.SIZE * FluidColumnGrid.SIZE];
        final byte[] kinds = new byte[FluidColumnGrid.SIZE * FluidColumnGrid.SIZE];
        final int[] texels = new int[FluidColumnGrid.SIZE * FluidColumnGrid.SIZE];
        final ShoreField field = new ShoreField();
        int minX;
        int minZ;
        long nanos;

        ShoreFieldJob run() {
            long started = System.nanoTime();
            field.compute(columns, kinds, texels);
            nanos = System.nanoTime() - started;
            return this;
        }
    }

    /**
     * Starts a computation when the columns changed and none is running (at most every 0.3 s), and
     * uploads a finished one. The job's arrays are reused: one is computed while the last result
     * stays in hand for uploading again if the texture is made again.
     */
    private void updateShoreField(FluidDataTexture target) {
        if (shoreFieldJob != null && shoreFieldJob.isDone()) {
            ShoreFieldJob finished = shoreFieldJob.join();
            shoreFieldJob = null;
            if (finished.minX == originBlockX() && finished.minZ == originBlockZ()) {
                spareShoreFieldJob = shoreFieldReady;
                shoreFieldReady = finished;
                shoreFieldUploadWanted = true;
            } else {
                spareShoreFieldJob = finished;
                shoreFieldStale = true;
            }
        }
        long now = System.nanoTime();
        if (shoreFieldStale && shoreFieldJob == null && hasOrigin && now - shoreFieldStarted >= SHORE_FIELD_INTERVAL) {
            ShoreFieldJob job = spareShoreFieldJob != null ? spareShoreFieldJob : new ShoreFieldJob();
            spareShoreFieldJob = null;
            job.minX = originBlockX();
            job.minZ = originBlockZ();
            grid.copyWindow(job.columns);
            oceanColumns.copyWindow(job.minX, job.minZ, job.kinds);
            shoreFieldStale = false;
            shoreFieldStarted = now;
            shoreFieldJob = CompletableFuture.supplyAsync(job::run, Util.backgroundExecutor());
        }
        if (shoreFieldUploadWanted && shoreFieldReady != null) {
            shoreFieldUploadWanted = false;
            uploadShoreField(shoreFieldReady, target);
        }
    }

    /** The field's texels are in window order; the texture wraps world columns like the column map. */
    private void uploadShoreField(ShoreFieldJob job, FluidDataTexture target) {
        int size = FluidColumnGrid.SIZE;
        if (shoreFieldUpload == null) {
            shoreFieldUpload = MemoryUtil.memAlloc(size * size * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer buffer = shoreFieldUpload;
        buffer.clear();
        for (int textureZ = 0; textureZ < size; textureZ++) {
            int windowZ = (textureZ - job.minZ) & (size - 1);
            for (int textureX = 0; textureX < size; textureX++) {
                int windowX = (textureX - job.minX) & (size - 1);
                buffer.putInt(job.texels[windowX + windowZ * size]);
            }
        }
        buffer.flip();
        target.writeShoreField(buffer);
    }

    /** For {@link CoveredWater}: the water surface in a block, read from the chunk being scanned or a loaded neighbour. */
    private double waterSurface(int x, int y, int z) {
        LevelChunk chunk = scanning;
        if (chunk == null || level == null) {
            return Double.NaN;
        }
        if (x >> 4 != chunk.getPos().x() || z >> 4 != chunk.getPos().z()) {
            chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            if (chunk == null) {
                return Double.NaN;
            }
        }
        FluidState fluid = chunk.getFluidState(probeCursor.set(x, y, z));
        if (!fluid.is(FluidTags.WATER) || chunk.getFluidState(probeCursor.set(x, y + 1, z)).is(FluidTags.WATER)) {
            return Double.NaN;
        }
        return y + fluid.getHeight(chunk, probeCursor.set(x, y, z));
    }

    /** Covered water carries on into a neighbouring chunk: scan that one again, seeded by this one's water. */
    private void coveredWaterCarriesOn(int x, int z) {
        markChunk(x >> 4, z >> 4);
    }

    /**
     * Follows falling water down from just below the column's top fluid block. When it falls at
     * least two blocks, records where it lands (the first block below the fall) and how far it fell.
     */
    private boolean recordFall(LevelChunk chunk, int x, int topY, int z, LongArrayList positions, IntArrayList drops) {
        int y = topY - 1;
        int lowest = Math.max(minY, topY - LONGEST_FALL);
        while (y >= lowest && isFallingWater(chunk.getBlockState(cursor.set(x, y, z)).getFluidState())) {
            y--;
        }
        int drop = topY - y - 1;
        cursor.set(x, topY, z);
        if (drop < 2 || y < lowest) {
            return false;
        }
        positions.add(BlockPos.asLong(x, y, z));
        drops.add(drop);
        return true;
    }

    private static boolean isFallingWater(FluidState fluid) {
        return fluid.is(FluidTags.WATER) && fluid.getValueOrElse(FlowingFluid.FALLING, false);
    }

    private void uploadChanged(FluidDataTexture target) {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (!needsUpload[slot] || slotChunk[slot] == NO_CHUNK) {
                continue;
            }
            needsUpload[slot] = false;
            long key = slotChunk[slot];
            int baseX = chunkX(key) * 16;
            int baseZ = chunkZ(key) * 16;
            uploadBuffer.clear();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    uploadBuffer.putInt(grid.texel(baseX + x, baseZ + z));
                }
            }
            uploadBuffer.flip();
            target.writeColumns((slot % CHUNKS) * 16, (slot / CHUNKS) * 16, uploadBuffer);
            uploadBuffer.clear();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    uploadBuffer.putInt(rainTops.texel(baseX + x, baseZ + z) | flows.texelBits(baseX + x, baseZ + z));
                }
            }
            uploadBuffer.flip();
            target.writeRainTops((slot % CHUNKS) * 16, (slot / CHUNKS) * 16, uploadBuffer);
        }
    }

    private static int slotOf(int chunkX, int chunkZ) {
        return Math.floorMod(chunkX, CHUNKS) + Math.floorMod(chunkZ, CHUNKS) * CHUNKS;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    private static int[][] offsetsNearestFirst() {
        int[][] offsets = new int[SLOT_COUNT][];
        int index = 0;
        for (int dz = -CHUNKS / 2; dz < CHUNKS / 2; dz++) {
            for (int dx = -CHUNKS / 2; dx < CHUNKS / 2; dx++) {
                offsets[index++] = new int[] {dx, dz};
            }
        }
        Arrays.sort(offsets, (a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        return offsets;
    }

    @Override
    public void close() {
        MemoryUtil.memFree(uploadBuffer);
        if (shoreFieldUpload != null) {
            MemoryUtil.memFree(shoreFieldUpload);
        }
    }
}
