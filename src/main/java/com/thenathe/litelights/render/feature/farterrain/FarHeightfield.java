package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The top surface of every loaded block column around the camera, for Advanced far shadows: one
 * R16 texel per column, holding how far the column's top surface is above the level's floor (0 =
 * nothing known, or nothing there).
 *
 * <p>The texture wraps: column (x, z) lives at (x mod 2048, z mod 2048), so the camera can move
 * without anything being copied, and 128 x 128 chunk slots cover far more than the largest render
 * distance (32 chunks either way). Each slot remembers which chunk it holds. A chunk is written when
 * it loads, when a block in it changes and when the texture is first made; it is cleared when it
 * unloads. Never rebuilt from scratch per frame: at most {@link #CHUNKS_PER_FRAME} chunks are
 * written a frame. 8 MiB of video memory while Advanced is chosen, none otherwise.
 *
 * <p>Heights come from the client chunks' own WORLD_SURFACE heightmaps, which the server sends and
 * the client keeps up to date as blocks change: the highest block that isn't air, leaves included.
 */
final class FarHeightfield implements AutoCloseable {
    static final int SIZE = 2048;
    private static final int SLOTS = SIZE / 16;
    private static final int CHUNKS_PER_FRAME = 32;
    private static final long EMPTY = Long.MIN_VALUE;

    private final long[] slotChunk = new long[SLOTS * SLOTS];
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private @Nullable ClientLevel level;
    private @Nullable ByteBuffer chunkTexels;
    private int minY;

    FarHeightfield() {
        Arrays.fill(slotChunk, EMPTY);
    }

    /**
     * Makes the texture if needed and writes up to a frame's share of pending chunks. When the
     * texture is new (or the level changed), every loaded chunk within reach is queued.
     */
    void update(ClientLevel currentLevel, int cameraChunkX, int cameraChunkZ, int renderDistance) {
        if (texture == null || currentLevel != level) {
            create(currentLevel);
            queueLoaded(currentLevel, cameraChunkX, cameraChunkZ, renderDistance);
        }
        int written = 0;
        while (!pending.isEmpty() && written < CHUNKS_PER_FRAME) {
            long key = pending.removeFirstLong();
            write(currentLevel, ChunkPos.getX(key), ChunkPos.getZ(key));
            written++;
        }
    }

    void chunkChanged(int chunkX, int chunkZ) {
        if (texture != null) {
            pending.add(ChunkPos.pack(chunkX, chunkZ));
        }
    }

    /** The level's floor: a texel's value is its column's top surface above this. */
    float minY() {
        return minY;
    }

    /** How far from the camera the texture holds the land, in blocks. */
    float reach() {
        return (SIZE / 2) - 16;
    }

    @Nullable GpuTextureView view() {
        return view;
    }

    private void create(ClientLevel currentLevel) {
        if (texture == null) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture("LiteLights far heightfield", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R16_UNORM, SIZE, SIZE, 1, 1);
            view = device.createTextureView(texture);
            chunkTexels = MemoryUtil.memAlloc(16 * 16 * 2).order(ByteOrder.LITTLE_ENDIAN);
        }
        level = currentLevel;
        minY = currentLevel.getMinY();
        Arrays.fill(slotChunk, EMPTY);
        pending.clear();
        clearTexture();
    }

    private void queueLoaded(ClientLevel currentLevel, int cameraChunkX, int cameraChunkZ, int renderDistance) {
        int radius = Math.min(renderDistance + 1, SLOTS / 2 - 1);
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring
                        && currentLevel.getChunkSource().getChunk(cameraChunkX + dx, cameraChunkZ + dz, ChunkStatus.FULL, false) != null) {
                        pending.add(ChunkPos.pack(cameraChunkX + dx, cameraChunkZ + dz));
                    }
                }
            }
        }
    }

    /** Writes a chunk's 16 x 16 heights into its slot, or clears the slot if the chunk isn't loaded. */
    private void write(ClientLevel currentLevel, int chunkX, int chunkZ) {
        ByteBuffer texels = chunkTexels;
        if (texture == null || texels == null) {
            return;
        }
        int slot = (chunkZ & (SLOTS - 1)) * SLOTS + (chunkX & (SLOTS - 1));
        long key = ChunkPos.pack(chunkX, chunkZ);
        LevelChunk chunk = currentLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            if (slotChunk[slot] != key) {
                return;
            }
            slotChunk[slot] = EMPTY;
            MemoryUtil.memSet(texels, 0);
        } else {
            slotChunk[slot] = key;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // The first free height above the column: its top surface. An empty column gives the floor, 0.
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    texels.putShort((z * 16 + x) * 2, encode(top, minY));
                }
            }
        }
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0,
            (chunkX & (SLOTS - 1)) * 16, (chunkZ & (SLOTS - 1)) * 16, 16, 16);
    }

    /** A column's texel: its top surface above the floor, 0 for an empty column, clamped to 16 bits. */
    static short encode(int topSurface, int floor) {
        return (short) Math.clamp(topSurface - floor, 0, 65535);
    }

    /** The texel a block column lives in, on one axis: the texture wraps every SIZE blocks. */
    static int texel(int blockCoordinate) {
        return blockCoordinate & (SIZE - 1);
    }

    private void clearTexture() {
        ByteBuffer row = MemoryUtil.memCalloc(SIZE * 16 * 2);
        try {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int y = 0; y < SIZE; y += 16) {
                encoder.writeToTexture(texture, row, 0, 0, 0, y, SIZE, 16);
            }
        } finally {
            MemoryUtil.memFree(row);
        }
    }

    @Override
    public void close() {
        if (texture == null) {
            // Nothing was made, or it is closed already: the slots and the queue only fill while the
            // texture exists. Far shadows call this every frame unless Advanced is chosen.
            return;
        }
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (chunkTexels != null) {
            MemoryUtil.memFree(chunkTexels);
            chunkTexels = null;
        }
        level = null;
        pending.clear();
        Arrays.fill(slotChunk, EMPTY);
    }
}
