package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.mixin.particles.FireBlockInvoker;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Ember veins: glowing cracks on flammable blocks joined to a fire ({@link EmberVeinReach} says
 * which). The search runs again only when fire appears or goes out nearby, or a block in or next to
 * the veins changes, and a burst of changes waits a moment so it costs one search. Between
 * searches the veins only ease on or off: they grow in as a fire takes hold and fade out over a few
 * seconds after it goes out. A vein whose block burns away or is broken goes at once: the fade is
 * only for a block that is still there. Drawn as emissive face overlays with a slow pulse.
 *
 * <p>Only full blocks get veins (an overlay on a stair or fence would float in air), but any
 * flammable block carries them on to the next. Faces against a full opaque block are left out, and
 * so are leaves against leaves, which a canopy would otherwise fill with hidden quads.
 */
final class EmberVeins {
    static final int MAX_VEINS = 1024;
    /** Ticks to grow to full glow, and to fade out once the fire is gone. */
    private static final float FADE_IN_TICKS = 30.0f;
    private static final float FADE_OUT_TICKS = 80.0f;
    /** A change waits this many ticks before the search runs, so a spreading fire's burst of changes costs one search. */
    private static final int SETTLE_TICKS = 4;
    /** And searches are at least this many ticks apart. */
    private static final int SEARCH_INTERVAL = 10;
    private static final float DRAW_RANGE = 48.0f;
    /** Hot orange; the sprite holds the yellow cores and the red edges. */
    private static final int VEIN_RGB = 0xFFA040;
    private static final long FACE_SALT = 0x9E3779B97F4A7C15L;

    private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
    private final long[] positions = new long[MAX_VEINS];
    private final float[] strength = new float[MAX_VEINS];
    private final boolean[] lit = new boolean[MAX_VEINS];
    /** Faces that show, one bit per face in Direction's order. */
    private final byte[] faces = new byte[MAX_VEINS];
    private final byte[] steps = new byte[MAX_VEINS];
    private final int[] light = new int[MAX_VEINS];
    /** Each face's sprite and mirroring, four bits a face, picked once when the vein is lit. */
    private final int[] looks = new int[MAX_VEINS];
    private int count;

    private final Long2IntOpenHashMap reached = new Long2IntOpenHashMap();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos cursorForChunk = new BlockPos.MutableBlockPos();
    private LevelChunk chunk;
    private int chunkPosX;
    private int chunkPosZ;
    private boolean dirty;
    private long dirtySince;
    private long lastSearch = Long.MIN_VALUE / 2;
    private int lastReached;
    private long lastSearchNanos;

    EmberVeins() {
        slots.defaultReturnValue(-1);
    }

    void clear() {
        slots.clear();
        count = 0;
        dirty = false;
    }

    boolean isEmpty() {
        return count == 0;
    }

    int count() {
        return count;
    }

    int lastReached() {
        return lastReached;
    }

    long lastSearchNanos() {
        return lastSearchNanos;
    }

    /** Fire appeared or went out nearby, or the veins' blocks changed: search again soon. */
    void markDirty(long gameTime) {
        if (!dirty) {
            dirty = true;
            dirtySince = gameTime;
        }
    }

    /**
     * A block changed. If it carried a vein and can't any more (it burnt away, turned to fire or was
     * broken), the vein goes now rather than fading over empty air.
     */
    void blockChanged(ClientLevel level, long position, BlockState newState) {
        int slot = slots.get(position);
        if (slot >= 0 && !carriesVein(level, position, newState)) {
            remove(slot);
        }
    }

    /** Only a full flammable block carries a vein (the same test {@link #light} makes). */
    private boolean carriesVein(ClientLevel level, long position, BlockState state) {
        cursor.set(position);
        return ((FireBlockInvoker) Blocks.FIRE).litelights$canBurn(state) && state.isCollisionShapeFullBlock(level, cursor);
    }

    /** QA only: veins whose block can no longer carry one, which should never outlast a tick. */
    int countStranded(ClientLevel level) {
        int stranded = 0;
        for (int i = 0; i < count; i++) {
            if (!carriesVein(level, positions[i], stateAt(level, positions[i]))) {
                stranded++;
            }
        }
        chunk = null;
        return stranded;
    }

    /** Whether a changed block is a vein or next to one (a face may now show, or the chain broke). */
    boolean touches(long position) {
        if (count == 0) {
            return false;
        }
        if (slots.containsKey(position)) {
            return true;
        }
        for (int face = 0; face < 6; face++) {
            long next = BlockPos.offset(position, EmberVeinReach.FACE_X[face], EmberVeinReach.FACE_Y[face], EmberVeinReach.FACE_Z[face]);
            if (slots.containsKey(next)) {
                return true;
            }
        }
        return false;
    }

    boolean wantsSearch(long gameTime) {
        return dirty && gameTime - dirtySince >= SETTLE_TICKS && gameTime - lastSearch >= SEARCH_INTERVAL;
    }

    /** Works out which blocks glow from the plain fires given, and turns the rest off to fade out. */
    void search(ClientLevel level, LongIterable fires, long gameTime) {
        long started = System.nanoTime();
        dirty = false;
        lastSearch = gameTime;
        FireBlockInvoker fire = (FireBlockInvoker) Blocks.FIRE;
        chunk = null;
        lastReached = EmberVeinReach.search(fires, position -> fire.litelights$canBurn(stateAt(level, position)), reached, queue);
        for (int i = 0; i < count; i++) {
            lit[i] = false;
        }
        // Nearest steps first, so a full list keeps the veins closest to the fire.
        for (int step = 1; step <= EmberVeinReach.MAX_STEPS; step++) {
            for (ObjectIterator<Long2IntMap.Entry> iterator = reached.long2IntEntrySet().fastIterator(); iterator.hasNext(); ) {
                Long2IntMap.Entry entry = iterator.next();
                if (entry.getIntValue() == step && EmberVeinReach.shows(entry.getLongKey(), step)) {
                    light(level, entry.getLongKey(), step);
                }
            }
        }
        // Don't hold on to a chunk past the search: it may be unloaded before the next one.
        chunk = null;
        lastSearchNanos = System.nanoTime() - started;
    }

    /** Turns one reached block's vein on, if the block is a full one with a face showing. */
    private void light(ClientLevel level, long position, int step) {
        BlockState state = stateAt(level, position);
        cursor.set(position);
        if (!state.isCollisionShapeFullBlock(level, cursor)) {
            return;
        }
        int faceMask = showingFaces(level, state);
        if (faceMask == 0) {
            return;
        }
        int slot = slots.get(position);
        if (slot < 0) {
            if (count == MAX_VEINS) {
                return;
            }
            slot = count++;
            slots.put(position, slot);
            positions[slot] = position;
            strength[slot] = 0.0f;
            looks[slot] = looksFor(position);
        }
        lit[slot] = true;
        faces[slot] = (byte) faceMask;
        steps[slot] = (byte) step;
        // Only for the fog in front of the glow (the shader reads the sky light from it).
        light[slot] = LightCoordsUtil.getLightCoords(level, cursor);
    }

    /** Each face picks its own sprite and mirroring, so neighbouring faces don't repeat. */
    private static int looksFor(long position) {
        int packed = 0;
        for (int face = 0; face < 6; face++) {
            packed |= (int) (EmberVeinReach.hash(position + (face + 1) * FACE_SALT) * 16.0f) << face * 4;
        }
        return packed;
    }

    /**
     * A block's state, through the last chunk looked at: the search walks neighbours, which are
     * nearly always in the same chunk, and the level's own lookup finds the chunk every time.
     */
    private BlockState stateAt(ClientLevel level, long position) {
        int chunkX = SectionPos.blockToSectionCoord(BlockPos.getX(position));
        int chunkZ = SectionPos.blockToSectionCoord(BlockPos.getZ(position));
        if (chunk == null || chunkX != chunkPosX || chunkZ != chunkPosZ) {
            chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
            chunkPosX = chunkX;
            chunkPosZ = chunkZ;
        }
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(cursorForChunk.set(position));
    }

    /** Faces of the block at the cursor that can be seen: not against a full opaque block, nor leaves against leaves. */
    private int showingFaces(ClientLevel level, BlockState state) {
        boolean leaves = state.is(BlockTags.LEAVES);
        int mask = 0;
        for (int face = 0; face < 6; face++) {
            neighbour.setWithOffset(cursor, EmberVeinReach.FACE_X[face], EmberVeinReach.FACE_Y[face], EmberVeinReach.FACE_Z[face]);
            BlockState next = stateAt(level, neighbour.asLong());
            if (next.isSolidRender() || leaves && next.is(BlockTags.LEAVES)) {
                continue;
            }
            mask |= 1 << face;
        }
        return mask;
    }

    /**
     * Eases every vein toward on or off; removes the ones that have faded out. A fading vein whose
     * block is gone is removed at once, in case its block went without a block update reaching
     * {@link #blockChanged} (a chunk resent from the server, say).
     */
    void tick(ClientLevel level) {
        for (int i = 0; i < count; i++) {
            if (lit[i]) {
                strength[i] = Math.min(1.0f, strength[i] + 1.0f / FADE_IN_TICKS);
                continue;
            }
            strength[i] -= 1.0f / FADE_OUT_TICKS;
            if (strength[i] <= 0.0f || !carriesVein(level, positions[i], stateAt(level, positions[i]))) {
                remove(i);
                i--;
            }
        }
        chunk = null;
    }

    private void remove(int slot) {
        slots.remove(positions[slot]);
        int last = --count;
        if (slot != last) {
            positions[slot] = positions[last];
            strength[slot] = strength[last];
            lit[slot] = lit[last];
            faces[slot] = faces[last];
            steps[slot] = steps[last];
            light[slot] = light[last];
            looks[slot] = looks[last];
            slots.put(positions[slot], slot);
        }
    }

    /**
     * Adds every showing face to this frame's batch. {@code seconds} drives the pulse: each block
     * breathes slowly on its own phase and rate, from its position's hash.
     */
    void draw(ParticleRenderer renderer, float[][] frames, double cameraX, double cameraY, double cameraZ, float seconds) {
        for (int i = 0; i < count; i++) {
            long position = positions[i];
            float x = (float) (BlockPos.getX(position) + 0.5 - cameraX);
            float y = (float) (BlockPos.getY(position) + 0.5 - cameraY);
            float z = (float) (BlockPos.getZ(position) + 0.5 - cameraZ);
            if (x * x + y * y + z * z > DRAW_RANGE * DRAW_RANGE) {
                continue;
            }
            float hash = EmberVeinReach.hash(position);
            float rate = 1.3f + hash * 0.7f;
            float pulse = 0.78f + 0.22f * (float) Math.sin(seconds * rate + hash * 40.0f);
            // Veins further along the chain burn a little dimmer.
            float glow = strength[i] * pulse * (1.0f - 0.12f * (steps[i] - 1));
            int alpha = Math.clamp(Math.round(glow * 255.0f), 0, 255);
            if (alpha < 4) {
                continue;
            }
            int color = alpha << 24 | VEIN_RGB;
            float lift = 0.5f + ParticleRenderer.DECAL_LIFT;
            for (int face = 0; face < 6; face++) {
                if ((faces[i] & 1 << face) == 0) {
                    continue;
                }
                int look = looks[i] >>> face * 4 & 15;
                renderer.addFace(ParticleLayers.EMBER_VEIN, x + EmberVeinReach.FACE_X[face] * lift, y + EmberVeinReach.FACE_Y[face] * lift,
                    z + EmberVeinReach.FACE_Z[face] * lift, face, frames[look % frames.length], (look & 4) != 0, (look & 8) != 0,
                    color, light[i]);
            }
        }
    }
}
