package com.thenathe.litelights.render.feature.weather.snow;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Without Almanac, the share of snowable open ground that is white, measured from the loaded land:
 * of the columns where vanilla's snow could lie now (the highest block rain stops on can carry a snow
 * layer, nothing stands on it, and the biome there is cold enough), how many carry one. A few chunks
 * are counted again each tick, sweeping out from the camera's, so the share follows the snow as it
 * falls and as the player moves; each chunk's count is kept until it is counted again or falls out of
 * reach. Client thread only.
 */
final class SnowShareMeter {
    /** Chunks counted a tick. */
    private static final int CHUNKS_A_TICK = 4;
    /** Fewer snowable columns than this in reach measure no snow at all. */
    private static final int FEWEST_COLUMNS = 64;

    private final Long2IntMap counts = new Long2IntOpenHashMap();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int sweep;
    private long snowable;
    private long white;

    void update(ClientLevel level, SnowClimate climate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameRenderer.mainCamera() == null) {
            return;
        }
        ChunkPos centre = ChunkPos.containing(BlockPos.containing(minecraft.gameRenderer.mainCamera().position()));
        int reach = minecraft.options.getEffectiveRenderDistance();
        int side = reach * 2 + 1;
        for (int i = 0; i < CHUNKS_A_TICK; i++) {
            int index = sweep++ % (side * side);
            int chunkX = centre.x() - reach + index % side;
            int chunkZ = centre.z() - reach + index / side;
            LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
            long key = ChunkPos.pack(chunkX, chunkZ);
            if (chunk == null) {
                forget(key);
            } else {
                count(level, climate, chunk, key);
            }
        }
        if (sweep % (side * side) == 0) {
            dropOutOfReach(centre, reach);
        }
    }

    double share() {
        return snowable < FEWEST_COLUMNS ? 0.0 : (double) white / snowable;
    }

    void clear() {
        counts.clear();
        snowable = 0;
        white = 0;
        sweep = 0;
    }

    private void count(ClientLevel level, SnowClimate climate, LevelChunk chunk, long key) {
        int chunkSnowable = 0;
        int chunkWhite = 0;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                cursor.set(baseX + x, top + 1, baseZ + z);
                BlockState above = chunk.getBlockState(cursor);
                boolean snow = above.getBlock() instanceof SnowLayerBlock;
                if (!snow && !above.isAir()) {
                    continue;
                }
                if (!snow && !Blocks.SNOW.defaultBlockState().canSurvive(level, cursor)) {
                    continue;
                }
                if (!climate.coldEnough(level, level.getBiome(cursor), cursor)) {
                    continue;
                }
                chunkSnowable++;
                if (snow) {
                    chunkWhite++;
                }
            }
        }
        forget(key);
        counts.put(key, chunkWhite << 16 | chunkSnowable);
        snowable += chunkSnowable;
        white += chunkWhite;
    }

    private void forget(long key) {
        if (counts.containsKey(key)) {
            int old = counts.remove(key);
            snowable -= old & 0xFFFF;
            white -= old >>> 16;
        }
    }

    private void dropOutOfReach(ChunkPos centre, int reach) {
        ObjectIterator<Long2IntMap.Entry> entries = counts.long2IntEntrySet().iterator();
        while (entries.hasNext()) {
            Long2IntMap.Entry entry = entries.next();
            long key = entry.getLongKey();
            if (Math.abs(ChunkPos.getX(key) - centre.x()) > reach || Math.abs(ChunkPos.getZ(key) - centre.z()) > reach) {
                snowable -= entry.getIntValue() & 0xFFFF;
                white -= entry.getIntValue() >>> 16;
                entries.remove();
            }
        }
    }
}
