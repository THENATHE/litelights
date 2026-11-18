package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.feature.particles.BiomeClimate;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Where the heat is around the camera, for the heat shimmer: a 128 x 128 map of block columns
 * (one cell a block, wrapping like water's column grid, so moving only rescans the chunks that come
 * into range) covering the 7 x 7 chunks around the camera, at least 48 blocks each way.
 *
 * <p>Each cell holds how hot its hottest source is (lava, fire, a lit campfire, a magma block, a lit
 * furnace or blast furnace) and the span of heights its exposed hot blocks take, and whether the
 * column is open desert or badlands ground, which is hot only around noon in clear weather (the pass
 * decides that). A hot block covered by a solid block gives off no shimmer; lava under lava does,
 * so a lava fall's whole height counts. Heights go to the texture over a reference height near the
 * camera, as half floats.
 *
 * <p>Chunks are read on the client tick, nearest first, a few a tick, from the camera's section
 * three down to two up: each section's palette says whether it can hold a hot block at all, so only
 * those are read in full, and at most {@link #FULL_READS_PER_TICK} of them a tick (a Nether lava sea
 * fills the map in about half a second). Block changes rescan their chunk at once, and every chunk
 * is read again every few seconds.
 */
final class HeatSources implements AutoCloseable {
    static final int SIZE = 128;
    private static final int WRAP = SIZE - 1;
    private static final int SLOTS = SIZE / 16;
    private static final int CHUNK_REACH = 3;
    private static final int SECTIONS_BELOW = 3;
    private static final int SECTIONS_ABOVE = 2;
    private static final int CHUNKS_PER_TICK = 4;
    private static final int FULL_READS_PER_TICK = 8;
    private static final long NO_CHUNK = Long.MIN_VALUE;
    private static final int NOT_HOT_GROUND = Integer.MIN_VALUE;
    /** A cell next to a hot one takes this share of its heat, so a single fire's plume is a few blocks across. */
    private static final float NEIGHBOUR_SHARE = 0.5f;

    private static final float LAVA = 1.0f;
    private static final float FIRE = 0.8f;
    private static final float CAMPFIRE = 0.6f;
    private static final float MAGMA = 0.35f;
    private static final float FURNACE = 0.3f;
    private static final Predicate<BlockState> MAY_BE_HOT = state -> heatOf(state) > 0.0f;
    private static final int[][] OFFSETS_NEAREST_FIRST = offsetsNearestFirst();

    private final float[] strength = new float[SIZE * SIZE];
    private final int[] bottom = new int[SIZE * SIZE];
    private final int[] top = new int[SIZE * SIZE];
    private final boolean[] hotGround = new boolean[SIZE * SIZE];
    private final int[] groundTop = new int[SIZE * SIZE];
    private final long[] slotChunk = new long[SLOTS * SLOTS];
    private final boolean[] needsScan = new boolean[SLOTS * SLOTS];
    // One chunk's columns while it is read.
    private final float[] columnStrength = new float[256];
    private final int[] columnBottom = new int[256];
    private final int[] columnTop = new int[256];
    private final boolean[] hotBiome = new boolean[16];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int climateChanges = LocalClimate.changes();

    private @Nullable ClientLevel level;
    private int centreChunkX;
    private int centreChunkZ;
    private int centreSectionY;
    private boolean hasCentre;
    private boolean overworldSky;
    private int rollingSlot;
    private boolean changed;

    // What the pass needs to know, from the last pack.
    private int referenceY;
    private boolean anyHeat;
    private boolean anyHotGround;
    private float lowestY;
    private float highestY;
    private int westX;
    private int northZ;
    private int eastX;
    private int southZ;

    private @Nullable ByteBuffer texels;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private boolean dirty;

    HeatSources() {
        Arrays.fill(slotChunk, NO_CHUNK);
    }

    /** How hot a block is, 0 for one that gives off no heat. */
    static float heatOf(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.LAVA_CAULDRON)) {
            return LAVA;
        }
        if (state.is(BlockTags.FIRE)) {
            return FIRE;
        }
        if (state.is(BlockTags.CAMPFIRES)) {
            return state.getValueOrElse(CampfireBlock.LIT, false) ? CAMPFIRE : 0.0f;
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return MAGMA;
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)) {
            return state.getValueOrElse(AbstractFurnaceBlock.LIT, false) ? FURNACE : 0.0f;
        }
        return 0.0f;
    }

    /** Client tick: follows the camera and reads what is due. {@code overworldSky}: the dimension has a sun (desert noon). */
    void tick(ClientLevel currentLevel, double cameraX, double cameraY, double cameraZ, boolean hasSky) {
        if (currentLevel != level) {
            forget();
            level = currentLevel;
        }
        overworldSky = hasSky;
        int chunkX = SectionPos.blockToSectionCoord(cameraX);
        int chunkZ = SectionPos.blockToSectionCoord(cameraZ);
        int sectionY = SectionPos.blockToSectionCoord(cameraY);
        if (!hasCentre || chunkX != centreChunkX || chunkZ != centreChunkZ) {
            centreChunkX = chunkX;
            centreChunkZ = chunkZ;
            claimWindow();
        }
        // Every chunk is read again on a new section height, and when the season changes which desert is hot.
        if (!hasCentre || sectionY != centreSectionY || climateChanges != LocalClimate.changes()) {
            centreSectionY = sectionY;
            climateChanges = LocalClimate.changes();
            Arrays.fill(needsScan, true);
            changed = true;
        }
        hasCentre = true;
        if (slotChunk[rollingSlot] != NO_CHUNK) {
            needsScan[rollingSlot] = true;
        }
        rollingSlot = (rollingSlot + 1) % slotChunk.length;
        scanDue(currentLevel);
        if (changed) {
            pack();
            changed = false;
        }
    }

    /**
     * A block changed: if heat came or went, or a hot block below was covered or uncovered, its
     * chunk is read again on the next tick.
     */
    void blockChanged(BlockPos position, BlockState oldState, BlockState newState) {
        if (level == null) {
            return;
        }
        boolean heatChanged = heatOf(oldState) > 0.0f || heatOf(newState) > 0.0f;
        if (!heatChanged && (oldState.isSolidRender() == newState.isSolidRender() || heatOf(level.getBlockState(position.below())) <= 0.0f)) {
            return;
        }
        int chunkX = SectionPos.blockToSectionCoord(position.getX());
        int chunkZ = SectionPos.blockToSectionCoord(position.getZ());
        int slot = slotOf(chunkX, chunkZ);
        if (slotChunk[slot] == chunkKey(chunkX, chunkZ)) {
            needsScan[slot] = true;
        }
    }

    /** Forgets everything, as when leaving a world. */
    void forget() {
        Arrays.fill(slotChunk, NO_CHUNK);
        Arrays.fill(needsScan, false);
        Arrays.fill(strength, 0.0f);
        Arrays.fill(hotGround, false);
        hasCentre = false;
        level = null;
        anyHeat = false;
        anyHotGround = false;
        changed = false;
    }

    boolean anyHeat() {
        return anyHeat;
    }

    boolean anyHotGround() {
        return anyHotGround;
    }

    int referenceY() {
        return referenceY;
    }

    /** The lowest and highest heights the heat's sources and hot ground take (world y). */
    float lowestY() {
        return lowestY;
    }

    float highestY() {
        return highestY;
    }

    /** Where the hot cells lie: the west, north, east and south edges (world block coordinates, the last two exclusive). */
    int westX() {
        return westX;
    }

    int northZ() {
        return northZ;
    }

    int eastX() {
        return eastX;
    }

    int southZ() {
        return southZ;
    }

    /** The texture, its texels uploaded if they changed. Render thread only. */
    GpuTextureView view() {
        if (texture == null || texture.isClosed()) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture("LiteLights heat shimmer sources", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA16_FLOAT, SIZE, SIZE, 1, 1);
            view = device.createTextureView(texture);
            dirty = true;
        }
        if (texels == null) {
            texels = MemoryUtil.memCalloc(SIZE * SIZE * 8).order(ByteOrder.LITTLE_ENDIAN);
            dirty = true;
        }
        if (dirty) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, 0, 0, SIZE, SIZE);
            dirty = false;
        }
        return view;
    }

    /** Gives each chunk of the window around the camera its slot; slots no chunk of the window holds are emptied. */
    private void claimWindow() {
        boolean[] claimed = new boolean[slotChunk.length];
        for (int dz = -CHUNK_REACH; dz <= CHUNK_REACH; dz++) {
            for (int dx = -CHUNK_REACH; dx <= CHUNK_REACH; dx++) {
                int chunkX = centreChunkX + dx;
                int chunkZ = centreChunkZ + dz;
                int slot = slotOf(chunkX, chunkZ);
                claimed[slot] = true;
                long key = chunkKey(chunkX, chunkZ);
                if (slotChunk[slot] != key) {
                    clearSlot(slot);
                    slotChunk[slot] = key;
                    needsScan[slot] = true;
                }
            }
        }
        for (int slot = 0; slot < slotChunk.length; slot++) {
            if (!claimed[slot] && slotChunk[slot] != NO_CHUNK) {
                clearSlot(slot);
                slotChunk[slot] = NO_CHUNK;
                needsScan[slot] = false;
            }
        }
        changed = true;
    }

    private void clearSlot(int slot) {
        int baseX = (slot % SLOTS) * 16;
        int baseZ = (slot / SLOTS) * 16;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int cell = baseX + x + (baseZ + z) * SIZE;
                strength[cell] = 0.0f;
                hotGround[cell] = false;
            }
        }
    }

    /** Reads due chunks nearest first, within this tick's budget. Chunks not loaded yet wait. */
    private void scanDue(ClientLevel currentLevel) {
        int chunks = 0;
        int fullReads = 0;
        for (int[] offset : OFFSETS_NEAREST_FIRST) {
            if (chunks >= CHUNKS_PER_TICK || fullReads >= FULL_READS_PER_TICK) {
                return;
            }
            int chunkX = centreChunkX + offset[0];
            int chunkZ = centreChunkZ + offset[1];
            int slot = slotOf(chunkX, chunkZ);
            if (!needsScan[slot] || !currentLevel.hasChunk(chunkX, chunkZ)) {
                continue;
            }
            needsScan[slot] = false;
            fullReads += scan(currentLevel.getChunk(chunkX, chunkZ), slot);
            chunks++;
        }
    }

    /** Reads one chunk's heat into its slot. Returns how many sections were read in full. */
    private int scan(LevelChunk chunk, int slot) {
        Arrays.fill(columnStrength, 0.0f);
        Arrays.fill(columnBottom, Integer.MAX_VALUE);
        Arrays.fill(columnTop, Integer.MIN_VALUE);
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        int fullReads = 0;
        for (int sectionY = centreSectionY - SECTIONS_BELOW; sectionY <= centreSectionY + SECTIONS_ABOVE; sectionY++) {
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sections.length) {
                continue;
            }
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(MAY_BE_HOT)) {
                continue;
            }
            fullReads++;
            readSection(chunk, section, baseX, sectionY << 4, baseZ);
        }

        if (overworldSky && level != null) {
            readHotBiomes(level, chunk);
        }
        int slotX = (slot % SLOTS) * 16;
        int slotZ = (slot / SLOTS) * 16;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = x + z * 16;
                int cell = slotX + x + (slotZ + z) * SIZE;
                float heat = columnStrength[column];
                int groundY = overworldSky ? hotGroundTop(chunk, x, z) : NOT_HOT_GROUND;
                boolean ground = groundY != NOT_HOT_GROUND;
                if (heat != strength[cell] || heat > 0.0f && (columnBottom[column] != bottom[cell] || columnTop[column] != top[cell])
                    || ground != hotGround[cell] || ground && groundTop[cell] != groundY) {
                    changed = true;
                }
                strength[cell] = heat;
                bottom[cell] = columnBottom[column];
                top[cell] = columnTop[column];
                hotGround[cell] = ground;
                groundTop[cell] = groundY;
            }
        }
        return fullReads;
    }

    private void readSection(LevelChunk chunk, LevelChunkSection section, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    float heat = heatOf(section.getBlockState(x, y, z));
                    if (heat <= 0.0f) {
                        continue;
                    }
                    BlockState above = y < 15 ? section.getBlockState(x, y + 1, z)
                        : chunk.getBlockState(cursor.set(baseX + x, baseY + 16, baseZ + z));
                    if (above.isSolidRender() && heatOf(above) <= 0.0f) {
                        continue;
                    }
                    int column = x + z * 16;
                    int worldY = baseY + y;
                    columnStrength[column] = Math.max(columnStrength[column], heat);
                    columnBottom[column] = Math.min(columnBottom[column], worldY);
                    columnTop[column] = Math.max(columnTop[column], worldY + 1);
                }
            }
        }
    }

    /**
     * Which 4 x 4 column groups of a chunk are desert or badlands in its hot, dry season (always,
     * without a seasons mod), by the biome at their surface (the biome grid's own size).
     */
    private void readHotBiomes(ClientLevel currentLevel, LevelChunk chunk) {
        for (int quart = 0; quart < 16; quart++) {
            int x = chunk.getPos().getMinBlockX() + (quart & 3) * 4 + 2;
            int z = chunk.getPos().getMinBlockZ() + (quart >> 2) * 4 + 2;
            int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z));
            BiomeClimate climate = BiomeClimate.of(biome);
            hotBiome[quart] = (climate == BiomeClimate.DESERT || climate == BiomeClimate.BADLANDS)
                && LocalClimate.dryHeat(currentLevel, biome, cursor.set(x, surface, z));
        }
    }

    /**
     * Where the hot air starts over open desert or badlands ground (one above the top block), or
     * {@link #NOT_HOT_GROUND} for any other column, or one with water or lava on top.
     */
    private int hotGroundTop(LevelChunk chunk, int x, int z) {
        if (!hotBiome[(x >> 2) + (z >> 2) * 4]) {
            return NOT_HOT_GROUND;
        }
        int topBlock = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        cursor.set(chunk.getPos().getMinBlockX() + x, topBlock, chunk.getPos().getMinBlockZ() + z);
        return chunk.getBlockState(cursor).getFluidState().isEmpty() ? topBlock + 1 : NOT_HOT_GROUND;
    }

    /**
     * Works out the texels: each cell's heat with a share of its hottest neighbour's, and heights
     * for every cell (a cold cell takes its hot neighbour's), so the shader's linear filtering
     * blends heat between cells without dragging heights toward nothing.
     */
    private void pack() {
        referenceY = centreSectionY << 4;
        ByteBuffer buffer = texels;
        if (buffer == null) {
            buffer = MemoryUtil.memCalloc(SIZE * SIZE * 8).order(ByteOrder.LITTLE_ENDIAN);
            texels = buffer;
        }
        int windowX = (centreChunkX - CHUNK_REACH) << 4;
        int windowZ = (centreChunkZ - CHUNK_REACH) << 4;
        boolean heat = false;
        boolean ground = false;
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        int west = Integer.MAX_VALUE;
        int north = Integer.MAX_VALUE;
        int east = Integer.MIN_VALUE;
        int south = Integer.MIN_VALUE;
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                int cell = column + row * SIZE;
                float own = strength[cell];
                float spread = 0.0f;
                int from = cell;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int other = (column + dx & WRAP) + (row + dz & WRAP) * SIZE;
                        if (strength[other] > spread) {
                            spread = strength[other];
                            if (own <= 0.0f) {
                                from = other;
                            }
                        }
                    }
                }
                float value = Math.max(own, NEIGHBOUR_SHARE * spread);
                boolean hotHere = hotGround[cell];
                float low;
                float high;
                if (strength[from] > 0.0f) {
                    low = bottom[from];
                    high = top[from];
                } else if (hotHere) {
                    low = groundTop[cell];
                    high = groundTop[cell];
                } else {
                    low = referenceY;
                    high = referenceY;
                }
                buffer.putShort(cell * 8, Float.floatToFloat16(value));
                buffer.putShort(cell * 8 + 2, Float.floatToFloat16(low - referenceY));
                buffer.putShort(cell * 8 + 4, Float.floatToFloat16(high - referenceY));
                buffer.putShort(cell * 8 + 6, Float.floatToFloat16(hotHere ? 1.0f : 0.0f));
                if (value > 0.0f || hotHere) {
                    heat |= value > 0.0f;
                    ground |= hotHere;
                    lowest = Math.min(lowest, low);
                    highest = Math.max(highest, high);
                    // The world column this cell holds now: the one inside the window.
                    int worldX = windowX + (column - windowX & WRAP);
                    int worldZ = windowZ + (row - windowZ & WRAP);
                    west = Math.min(west, worldX);
                    north = Math.min(north, worldZ);
                    east = Math.max(east, worldX + 1);
                    south = Math.max(south, worldZ + 1);
                }
            }
        }
        anyHeat = heat;
        anyHotGround = ground;
        lowestY = lowest;
        highestY = highest;
        westX = west;
        northZ = north;
        eastX = east;
        southZ = south;
        dirty = true;
    }

    private static int slotOf(int chunkX, int chunkZ) {
        return (chunkX & SLOTS - 1) + (chunkZ & SLOTS - 1) * SLOTS;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    private static int[][] offsetsNearestFirst() {
        int span = CHUNK_REACH * 2 + 1;
        int[][] offsets = new int[span * span][];
        int i = 0;
        for (int dz = -CHUNK_REACH; dz <= CHUNK_REACH; dz++) {
            for (int dx = -CHUNK_REACH; dx <= CHUNK_REACH; dx++) {
                offsets[i++] = new int[] {dx, dz};
            }
        }
        Arrays.sort(offsets, (a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        return offsets;
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (texels != null) {
            MemoryUtil.memFree(texels);
            texels = null;
        }
    }
}
