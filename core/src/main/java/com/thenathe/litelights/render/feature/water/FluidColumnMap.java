package com.thenathe.litelights.render.feature.water;

import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.feature.vegetation.LeafColours;
import com.thenathe.litelights.render.feature.weather.snow.FakeSnow;
import com.thenathe.litelights.render.feature.weather.snow.RealSnow;
import com.thenathe.litelights.render.feature.weather.snow.SnowHosts;
import com.thenathe.litelights.render.feature.weather.snow.SnowState;
import com.thenathe.litelights.render.feature.weather.snow.VisualSnow;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
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
 * job works out the {@link ShoreField} whenever the columns have changed. While Leaves under trees is
 * on it also notes the canopy's colour and the ground fallen leaves may lie on ({@link GroundLeavesGrid}),
 * and while Snowy leaves and ground is on, the canopies open to the sky, whether their biome snows and
 * the bare ground under them ({@link SnowCoverGrid}).
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
    /** Frames between workings-out of the snow's fanning while chunks change. */
    private static final int FAN_FRAMES = 10;
    /** How far below an overhang its ground may be, in blocks: eight since visual-snow-117 (six before), for higher roofs and balconies. */
    private static final int OVERHANG_REACH = 8;
    /** The shore field is worked out again at most this often while columns keep changing, in nanoseconds. */
    private static final long SHORE_FIELD_INTERVAL = 300_000_000L;
    /** Slot offsets from the camera's chunk, nearest first. */
    private static final int[][] OFFSETS_NEAREST_FIRST = offsetsNearestFirst();

    private final FluidColumnGrid grid = new FluidColumnGrid();
    private final RainTopGrid rainTops = new RainTopGrid();
    private final FluidFlowGrid flows = new FluidFlowGrid();
    private final OceanColumns oceanColumns = new OceanColumns();
    private final GroundLeavesGrid groundLeaves = new GroundLeavesGrid();
    private final GroundLeavesGrid.Tints groundTints = this::groundTint;
    private final SnowCoverGrid snowCover = new SnowCoverGrid();
    /** Whether each kind of leaves is a needle-leaved tree's, worked out once per block. */
    private final Reference2BooleanOpenHashMap<Block> conifers = new Reference2BooleanOpenHashMap<>();
    private final long[] slotChunk = new long[SLOT_COUNT];
    private final boolean[] needsScan = new boolean[SLOT_COUNT];
    private final boolean[] needsUpload = new boolean[SLOT_COUNT];
    private boolean shoreOnlyAgainstBlocks;
    private final int[] reflectiveColumns = new int[SLOT_COUNT];
    private final LongArrayList[] landingPositions = new LongArrayList[SLOT_COUNT];
    private final IntArrayList[] landingDrops = new IntArrayList[SLOT_COUNT];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos probeCursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos tintCursor = new BlockPos.MutableBlockPos();
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
    /** Whether scans note the canopy's colour and the ground leaves may lie on, and their texels are written (Leaves under trees). */
    private boolean recordGroundLeaves;
    /** Whether scans note where snow can gather on leaves and under trees, and their texels are written (Snowy leaves and ground). */
    private boolean recordSnowCover;
    /** Visual snow: plants stand in snow by its rule (Snow on blocks), and snow fans in under trees and overhangs. */
    private boolean visualPlants;
    private boolean fanTrees;
    private boolean fanOverhangs;
    private long visualSnowChanges = -1;
    private boolean fanStale;
    private int framesSinceFan;
    private int climateChanges = LocalClimate.changes();
    /** QA: chunks scanned and the nanoseconds the scans took, since the last reading. */
    private int scansDone;
    private long scanNanos;
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
        // Where it snows changes with the season: every canopy is noted again.
        if (climateChanges != LocalClimate.changes()) {
            climateChanges = LocalClimate.changes();
            if (recordSnowCover) {
                rescanEverything();
            }
        }
        // The visual snow's shares moved a step or its look changed: plants in snow are noted again.
        long snowChanges = VisualSnow.changes();
        if (snowChanges != visualSnowChanges) {
            visualSnowChanges = snowChanges;
            if (recordSnowCover && (visualPlants || fanTrees || fanOverhangs)) {
                rescanEverything();
            }
        }
        int scanned = scanSome(currentLevel);
        if (snowCover.fanning() && recordSnowCover) {
            fanStale |= scanned > 0;
            // The fanning looks across chunks, so it is worked out for the whole window at once, a few times a second at most.
            if (fanStale && ++framesSinceFan >= FAN_FRAMES) {
                framesSinceFan = 0;
                fanStale = false;
                snowCover.computeFan(originChunkX * 16, originChunkZ * 16, (x, z) -> markUpload(x >> 4, z >> 4));
            }
        }
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
     * Edge foam against blocks only, or against any ground near the waterline. The shore masks are
     * worked out as the texels are written, so a change writes every slot again; nothing is scanned.
     */
    void setShoreOnlyAgainstBlocks(boolean onlyAgainstBlocks) {
        if (onlyAgainstBlocks == shoreOnlyAgainstBlocks) {
            return;
        }
        shoreOnlyAgainstBlocks = onlyAgainstBlocks;
        grid.setShoreOnlyAgainstBlocks(onlyAgainstBlocks);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            needsUpload[slot] = slotChunk[slot] != NO_CHUNK;
        }
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

    /**
     * Starts or stops noting where fallen leaves lie under trees. Every chunk is scanned again, so
     * they appear everywhere at once; while off the scan reads nothing more for them.
     */
    void setRecordGroundLeaves(boolean record) {
        if (record != recordGroundLeaves) {
            recordGroundLeaves = record;
            rescanEverything();
        }
    }

    /**
     * Starts or stops noting where snow can gather on leaves and under trees. Every chunk is
     * scanned again, so the snow shows everywhere at once; while off the scan reads nothing more for it.
     */
    void setRecordSnowCover(boolean record) {
        if (record != recordSnowCover) {
            recordSnowCover = record;
            rescanEverything();
        }
    }

    /**
     * Visual snow's parts the snow cover region carries: plants standing in snow by the visual snow's
     * own rule, and snow fanning in under trees and under overhangs. A change scans every chunk again.
     */
    void setVisualSnow(boolean plants, boolean trees, boolean overhangs) {
        if (plants != visualPlants || trees != fanTrees || overhangs != fanOverhangs) {
            visualPlants = plants;
            fanTrees = trees;
            fanOverhangs = overhangs;
            snowCover.setFanning(trees || overhangs);
            fanStale = true;
            if (recordSnowCover) {
                rescanEverything();
            }
        }
    }

    /** The block textures changed (a resource reload): the canopy colours are read again. */
    void leafColoursChanged() {
        if (recordGroundLeaves) {
            rescanEverything();
        }
    }

    void rescanEverything() {
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

    /** QA only: a column's packed value and its shader texel, or zeros outside the map. */
    int[] columnForQa(int x, int z) {
        if (!contains(x, z)) {
            return new int[] {0, 0};
        }
        return new int[] {grid.get(x, z), grid.texel(x, z)};
    }

    /** QA only: a column's ground leaves texel, or 0 outside the map or while Leaves under trees is off. */
    int groundLeavesForQa(int x, int z) {
        if (!contains(x, z) || !recordGroundLeaves) {
            return 0;
        }
        return groundLeaves.texel(x, z, minY, grid, groundTints);
    }

    /** QA only: a column's snow cover texel, or 0 outside the map or while Snowy leaves and ground is off. */
    int snowCoverForQa(int x, int z) {
        if (!contains(x, z) || !recordSnowCover) {
            return 0;
        }
        return snowCover.texel(x, z, grid);
    }

    /** QA only: chunks scanned and their milliseconds since the last call. */
    double[] takeScanReadings() {
        double[] readings = {scansDone, scanNanos / 1.0e6};
        scansDone = 0;
        scanNanos = 0L;
        return readings;
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
                    groundLeaves.clearChunk(chunkX, chunkZ);
                    snowCover.clearChunk(chunkX, chunkZ);
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
                    groundLeaves.clearChunk(chunkX, chunkZ);
                    snowCover.clearChunk(chunkX, chunkZ);
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
        long started = System.nanoTime();
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
                int rainTop = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                rainTops.set(worldX, worldZ, RainTopGrid.pack(rainTop, minY));
                cursor.set(worldX, top, worldZ);
                BlockState state = chunk.getBlockState(cursor);
                if (recordSnowCover) {
                    noteSnowCover(chunk, worldX, worldZ, rainTop, top, state);
                }
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
                boolean canopy = false;
                int canopyColour = -1;
                if (surface > top && surface - top <= CANOPY_REACH) {
                    BlockState leaves = chunk.getBlockState(cursor.set(worldX, surface, worldZ));
                    canopy = leaves.is(BlockTags.LEAVES);
                    if (canopy && recordGroundLeaves && level != null) {
                        canopyColour = LeafColours.leaves(leaves, level, cursor);
                    }
                }
                grid.set(worldX, worldZ, FluidColumnGrid.column(surfaceY, minY, top + 1, lava, ice, canopy, falls));
                if (recordGroundLeaves) {
                    boolean holdsLeaves = fluid.isEmpty() && leavesCanLieOn(chunk, state, worldX, top, worldZ);
                    groundLeaves.set(worldX, worldZ, canopyColour, holdsLeaves ? top : Integer.MIN_VALUE, minY);
                }
                if ((!Double.isNaN(surfaceY) && !lava) || ice) {
                    reflective++;
                }
            }
        }
        scanning = chunk;
        reflective += coveredWater.flood(grid, minY, baseX, baseZ, this::waterSurface, this::coveredWaterCarriesOn);
        scanning = null;
        reflectiveColumns[slot] = reflective;
        scansDone++;
        scanNanos += System.nanoTime() - started;
    }

    /**
     * Whether fallen leaves may lie on a column's top block: grass, dirt, podzol, mycelium, moss or a
     * path, not snowed on, with no leaf litter, snow or water on it. Being the column's top block
     * (leaves aside) already means nothing but leaves hangs over it, so a floor under a roof never
     * qualifies.
     */
    private boolean leavesCanLieOn(LevelChunk chunk, BlockState ground, int x, int y, int z) {
        boolean soil = ground.is(BlockTags.GRASS_BLOCKS) || ground.is(BlockTags.DIRT) || ground.is(BlockTags.MOSS_BLOCKS)
            || ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.MOSS_CARPET) || ground.is(Blocks.PALE_MOSS_CARPET);
        if (!soil || ground.getValueOrElse(BlockStateProperties.SNOWY, false)) {
            return false;
        }
        BlockState above = chunk.getBlockState(probeCursor.set(x, y + 1, z));
        return !above.is(Blocks.LEAF_LITTER) && !above.is(BlockTags.SNOW) && above.getFluidState().isEmpty();
    }

    /**
     * Notes where snow can gather in a column: on its canopy if the highest block rain and snow stop
     * on is leaves (vanilla's snow layers don't stop them, so a canopy already carrying one still
     * counts), and on the ground under that canopy if it is bare ground with a full top, the kind
     * vanilla's own snow skips under trees. A log as the top block but for leaves is a trunk.
     */
    private void noteSnowCover(LevelChunk chunk, int x, int z, int rainTop, int groundY, BlockState ground) {
        int flags = ground.is(BlockTags.LOGS) ? SnowCoverGrid.TRUNK : 0;
        // With the visual snow on, a plant stands in snow by its own snow at its base and, as without
        // it, by real snow lying beside it: up to 1.1.4 only the first counted then, so a plant whose
        // own point the season's share hadn't reached stayed green between real snow layers.
        if (standsInSnow(chunk, x, groundY + 1, z) || (visualPlants && standsInVisualSnow(x, groundY + 1, z))) {
            flags |= SnowCoverGrid.PLANT_IN_SNOW;
        }
        snowCover.setFan(x, z, SnowFanField.BLOCKED, groundY);
        BlockState canopy = rainTop > groundY ? chunk.getBlockState(probeCursor.set(x, rainTop, z)) : ground;
        if (rainTop <= groundY || !canopy.is(BlockTags.LEAVES) || level == null) {
            if (snowCover.fanning() && level != null) {
                flags |= noteOpenOrOverhang(chunk, x, z, rainTop, ground);
                if ((flags & SnowCoverGrid.OVERHANG) != 0) {
                    return;
                }
            }
            snowCover.set(x, z, flags, 0, groundY);
            return;
        }
        flags |= SnowCoverGrid.CANOPY;
        // The biome where the snow lands on the canopy, as vanilla's snow asks it (above the top block).
        probeCursor.setY(rainTop + 1);
        Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(rainTop + 1), QuartPos.fromBlock(z));
        if (LocalClimate.fallAt(level, biome, probeCursor) == Biome.Precipitation.SNOW) {
            flags |= SnowCoverGrid.SNOWS;
        }
        if (isConifer(canopy.getBlock())) {
            flags |= SnowCoverGrid.CONIFER;
        }
        if (dustCanLieOn(chunk, ground, x, groundY, z)) {
            flags |= SnowCoverGrid.GROUND;
            if (fanTrees && (flags & SnowCoverGrid.SNOWS) != 0) {
                snowCover.setFan(x, z, SnowFanField.UNDER_TREE, groundY);
            }
        }
        snowCover.set(x, z, flags, rainTop, groundY);
    }

    /**
     * For the fanning, a column without a canopy: open with snow on its top (real snow, or the visual
     * snow's on a stair or a slab), where the fanning starts; or a roof or another overhang with bare
     * ground under it in a place where it snows, where it spreads (then the column's texel is written
     * here, and the overhang's flags are returned). Otherwise nothing.
     */
    private int noteOpenOrOverhang(LevelChunk chunk, int x, int z, int top, BlockState topState) {
        // An overhang first: a roof may carry snow of its own, but what the fanning needs is the ground under it.
        if (fanOverhangs && chunk.getBlockState(probeCursor.set(x, top - 1, z)).getCollisionShape(chunk, probeCursor).isEmpty()) {
            for (int y = top - 2; y >= top - OVERHANG_REACH && y > minY; y--) {
                BlockState floor = chunk.getBlockState(probeCursor.set(x, y, z));
                if (floor.getCollisionShape(chunk, probeCursor).isEmpty()) {
                    continue;
                }
                if (!dustCanLieOn(chunk, floor, x, y, z)) {
                    break;
                }
                probeCursor.set(x, y + 1, z);
                Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y + 1), QuartPos.fromBlock(z));
                if (LocalClimate.fallAt(level, biome, probeCursor) != Biome.Precipitation.SNOW) {
                    break;
                }
                int flags = SnowCoverGrid.OVERHANG | SnowCoverGrid.GROUND | SnowCoverGrid.SNOWS;
                snowCover.setFan(x, z, SnowFanField.UNDER_OVERHANG, y);
                snowCover.set(x, z, flags, 0, y);
                return flags;
            }
        }
        BlockState above = chunk.getBlockState(probeCursor.set(x, top + 1, z));
        if (above.getBlock() instanceof SnowLayerBlock || topState.is(Blocks.SNOW_BLOCK)
            || topState.getValueOrElse(BlockStateProperties.SNOWY, false) || visualSnowOnTop(x, top, z)) {
            snowCover.setFan(x, z, SnowFanField.SOURCE, top);
        }
        return 0;
    }

    /** Whether the visual snow lies on a column's top block (a stair, a slab, a wall). */
    private boolean visualSnowOnTop(int x, int y, int z) {
        SnowState snow = VisualSnow.state();
        if (!snow.blocks() || level == null) {
            return false;
        }
        BlockPos pos = probeCursor.set(x, y, z).immutable();
        if (!SnowHosts.cached(level.getBlockState(pos)).tops().isEmpty()) {
            return FakeSnow.onTop(snow, level, level.getBiome(pos), pos, false) > 0;
        }
        return false;
    }

    /**
     * With the visual snow's Snow on blocks on, a plant stands in snow where the visual snow (or
     * LambdaBetterGrass) draws snow in its space, so the grass the shader whitens is the grass the
     * snow is drawn around.
     */
    private boolean standsInVisualSnow(int x, int y, int z) {
        SnowState snow = VisualSnow.state();
        if (level == null) {
            return false;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState here = level.getBlockState(pos);
        if (!SnowHosts.cached(here).base()) {
            return false;
        }
        if (RealSnow.layersAt(level, pos, here, snow.betterSnow()) > 0) {
            return true;
        }
        return snow.blocks() && FakeSnow.atBase(snow, level, level.getBiome(pos), pos, here, false) > 0;
    }

    /**
     * Whether the block on a column's ground is something small that snow layers lie around (grass, a
     * fern, a flower): no snow itself, nothing to walk into, and snow on at least two of its four
     * sides, which is where LambdaBetterGrass draws its snow through such a block too. Neighbours in
     * chunks that aren't loaded count as bare.
     */
    private boolean standsInSnow(LevelChunk chunk, int x, int y, int z) {
        BlockState here = chunk.getBlockState(probeCursor.set(x, y, z));
        if (here.isAir() || here.is(BlockTags.SNOW) || !here.getFluidState().isEmpty()
            || !here.getCollisionShape(chunk, probeCursor).isEmpty()) {
            return false;
        }
        int snowy = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int sideX = x + side.getStepX();
            int sideZ = z + side.getStepZ();
            LevelChunk sideChunk = chunk;
            if (sideX >> 4 != chunk.getPos().x() || sideZ >> 4 != chunk.getPos().z()) {
                sideChunk = level == null ? null : level.getChunkSource().getChunk(sideX >> 4, sideZ >> 4, ChunkStatus.FULL, false);
            }
            if (sideChunk != null && sideChunk.getBlockState(probeCursor.set(sideX, y, sideZ)).is(BlockTags.SNOW)) {
                snowy++;
            }
        }
        return snowy >= 2;
    }

    /** Bare ground under a canopy: a full top face, no fluid, not a trunk, snow or ice, and no snow layer on it already. */
    private boolean dustCanLieOn(LevelChunk chunk, BlockState ground, int x, int y, int z) {
        if (!ground.getFluidState().isEmpty() || ground.is(BlockTags.LOGS) || ground.is(BlockTags.SNOW) || ground.is(BlockTags.ICE)) {
            return false;
        }
        if (!ground.isFaceSturdy(chunk, probeCursor.set(x, y, z), Direction.UP) && !doorstep(ground)) {
            return false;
        }
        return !chunk.getBlockState(probeCursor.set(x, y + 1, z)).is(BlockTags.SNOW);
    }

    /** A bottom slab or the lower half of a stair: a doorstep's top takes the dusting too (visual-snow-117). */
    private static boolean doorstep(BlockState ground) {
        if (ground.getBlock() instanceof SlabBlock) {
            return ground.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE) == SlabType.BOTTOM;
        }
        return ground.getBlock() instanceof StairBlock && ground.getValueOrElse(BlockStateProperties.HALF, Half.TOP) == Half.BOTTOM;
    }

    /** Spruce and, for other mods' trees, leaves named for a needle-leaved tree hold more snow. */
    private boolean isConifer(Block leaves) {
        if (conifers.containsKey(leaves)) {
            return conifers.getBoolean(leaves);
        }
        String name = BuiltInRegistries.BLOCK.getKey(leaves).getPath();
        boolean conifer = leaves == Blocks.SPRUCE_LEAVES || name.contains("spruce") || name.contains("pine") || name.contains("fir")
            || name.contains("larch") || name.contains("hemlock") || name.contains("cedar") || name.contains("redwood");
        conifers.put(leaves, conifer);
        return conifer;
    }

    /** For {@link GroundLeavesGrid}: the tint of the ground block at a column, read when its first texel with leaves is written. */
    private int groundTint(int x, int y, int z) {
        if (level == null) {
            return 0xFFFFFF;
        }
        tintCursor.set(x, y, z);
        return LeafColours.tint(level.getBlockState(tintCursor), level, tintCursor);
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
            if (recordGroundLeaves) {
                uploadBuffer.clear();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        uploadBuffer.putInt(groundLeaves.texel(baseX + x, baseZ + z, minY, grid, groundTints));
                    }
                }
                uploadBuffer.flip();
                target.writeGroundLeaves((slot % CHUNKS) * 16, (slot / CHUNKS) * 16, uploadBuffer);
            }
            if (recordSnowCover) {
                uploadBuffer.clear();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        uploadBuffer.putInt(snowCover.texel(baseX + x, baseZ + z, grid));
                    }
                }
                uploadBuffer.flip();
                target.writeSnowCover((slot % CHUNKS) * 16, (slot / CHUNKS) * 16, uploadBuffer);
            }
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
