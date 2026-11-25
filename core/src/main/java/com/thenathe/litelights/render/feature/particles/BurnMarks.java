package com.thenathe.litelights.render.feature.particles;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Burn marks: soot on the face a fire stands on, or failing that the wall it clings to. A mark only
 * ever lies on a full, sturdy face of a real block, exactly covering it: never on plants, snow
 * layers, carpets, leaves or any shape that doesn't fill the face, and never past its edges. It
 * darkens while the fire burns, fades slowly once the fire is gone, and goes at once when the block
 * under it changes so that the face no longer holds it.
 *
 * <p>Fires that burn forever (on netherrack, soul soil, magma) are lit on purpose and leave none.
 */
final class BurnMarks {
    static final int MAX_MARKS = 256;
    /** Ticks for a fresh mark to darken fully, and to fade once its fire is gone. */
    private static final float DARKEN_TICKS = 60.0f;
    private static final float FADE_TICKS = 240.0f;
    private static final int MARK_RGB = 0xFFFFFF;
    private static final float MARK_ALPHA = 0xD0 / 255.0f;
    private static final float DRAW_RANGE = 40.0f;
    private static final Direction[] WALLS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** Fire position to slot: each fire leaves at most one mark. */
    private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
    private final long[] fires = new long[MAX_MARKS];
    private final long[] supports = new long[MAX_MARKS];
    /** The marked face of the support block, in Direction's order. */
    private final byte[] faces = new byte[MAX_MARKS];
    private final float[] darkness = new float[MAX_MARKS];
    private final boolean[] burning = new boolean[MAX_MARKS];
    private final int[] light = new int[MAX_MARKS];
    private int count;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();

    BurnMarks() {
        slots.defaultReturnValue(-1);
    }

    void clear() {
        slots.clear();
        count = 0;
    }

    boolean isEmpty() {
        return count == 0;
    }

    int count() {
        return count;
    }

    /** QA only: each mark as "fire x y z -> face of x y z". */
    java.util.List<String> describe() {
        java.util.List<String> marks = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            marks.add(BlockPos.of(fires[i]).toShortString() + " -> " + Direction.from3DDataValue(faces[i]).getName() + " of "
                + BlockPos.of(supports[i]).toShortString());
        }
        return marks;
    }

    /** A plain fire is burning here: keeps its mark dark, or places one if a face can hold it. */
    void fireAt(ClientLevel level, long firePosition) {
        int slot = slots.get(firePosition);
        if (slot >= 0) {
            burning[slot] = true;
            light[slot] = LightCoordsUtil.getLightCoords(level, cursor.set(firePosition));
            return;
        }
        if (count == MAX_MARKS) {
            return;
        }
        cursor.set(firePosition);
        int face = findFace(level, cursor, support);
        if (face < 0) {
            return;
        }
        slot = count++;
        slots.put(firePosition, slot);
        fires[slot] = firePosition;
        supports[slot] = support.asLong();
        faces[slot] = (byte) face;
        darkness[slot] = 0.0f;
        burning[slot] = true;
        light[slot] = LightCoordsUtil.getLightCoords(level, cursor);
    }

    /** The fire here went out: its mark starts fading. */
    void fireGone(long firePosition) {
        int slot = slots.get(firePosition);
        if (slot >= 0) {
            burning[slot] = false;
        }
    }

    /** Every fire not in the given set has gone out (called when a scan of the area ends). */
    void keepOnly(Long2ByteMap fireTypes) {
        for (int i = 0; i < count; i++) {
            if (!fireTypes.containsKey(fires[i])) {
                burning[i] = false;
            }
        }
    }

    /**
     * A block changed. If it carried a mark whose face can no longer hold it (the block burned
     * away, or became something else), the mark goes at once. Marks lie on a neighbour of their
     * fire, so only the six fires around the block can own one.
     */
    void blockChanged(ClientLevel level, BlockPos position, BlockState newState) {
        if (count == 0) {
            return;
        }
        long changed = position.asLong();
        for (int face = 0; face < 6; face++) {
            long fire = BlockPos.offset(changed, EmberVeinReach.FACE_X[face], EmberVeinReach.FACE_Y[face], EmberVeinReach.FACE_Z[face]);
            int slot = slots.get(fire);
            if (slot >= 0 && supports[slot] == changed && !holdsMark(level, position, newState, Direction.from3DDataValue(faces[slot]))) {
                remove(slot);
            }
        }
    }

    /**
     * The face a fire at {@code fire} would mark: the top of the block under it, or else the side
     * of a wall it clings to. Writes the support block into {@code out}; returns -1 if none holds.
     */
    static int findFace(BlockGetter level, BlockPos fire, BlockPos.MutableBlockPos out) {
        out.setWithOffset(fire, Direction.DOWN);
        BlockState below = level.getBlockState(out);
        if (burnsForever(below)) {
            return -1;
        }
        if (holdsMark(level, out, below, Direction.UP)) {
            return Direction.UP.get3DDataValue();
        }
        for (Direction wall : WALLS) {
            out.setWithOffset(fire, wall);
            BlockState state = level.getBlockState(out);
            if (holdsMark(level, out, state, wall.getOpposite())) {
                return wall.getOpposite().get3DDataValue();
            }
        }
        return -1;
    }

    /** Whether a block's face can carry soot: a full sturdy face of a solid block that isn't leaves or snow. */
    static boolean holdsMark(BlockGetter level, BlockPos position, BlockState state, Direction face) {
        if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        return state.isFaceSturdy(level, position, face, SupportType.FULL);
    }

    /** Netherrack, soul soil, magma and the End's bedrock: fires on them are lit on purpose. */
    private static boolean burnsForever(BlockState below) {
        return below.is(BlockTags.INFINIBURN_OVERWORLD) || below.is(BlockTags.INFINIBURN_NETHER) || below.is(BlockTags.INFINIBURN_END)
            || below.is(BlockTags.SOUL_FIRE_BASE_BLOCKS);
    }

    void tick() {
        for (int i = 0; i < count; i++) {
            if (burning[i]) {
                darkness[i] = Math.min(1.0f, darkness[i] + 1.0f / DARKEN_TICKS);
            } else {
                darkness[i] -= 1.0f / FADE_TICKS;
                if (darkness[i] <= 0.0f) {
                    remove(i);
                    i--;
                }
            }
        }
    }

    private void remove(int slot) {
        slots.remove(fires[slot]);
        int last = --count;
        if (slot != last) {
            fires[slot] = fires[last];
            supports[slot] = supports[last];
            faces[slot] = faces[last];
            darkness[slot] = darkness[last];
            burning[slot] = burning[last];
            light[slot] = light[last];
            slots.put(fires[slot], slot);
        }
    }

    void draw(ParticleRenderer renderer, float[][] frames, double cameraX, double cameraY, double cameraZ) {
        for (int i = 0; i < count; i++) {
            long position = supports[i];
            int face = faces[i];
            float lift = 0.5f + ParticleRenderer.DECAL_LIFT;
            float x = (float) (BlockPos.getX(position) + 0.5 - cameraX) + EmberVeinReach.FACE_X[face] * lift;
            float y = (float) (BlockPos.getY(position) + 0.5 - cameraY) + EmberVeinReach.FACE_Y[face] * lift;
            float z = (float) (BlockPos.getZ(position) + 0.5 - cameraZ) + EmberVeinReach.FACE_Z[face] * lift;
            if (x * x + y * y + z * z > DRAW_RANGE * DRAW_RANGE) {
                continue;
            }
            int alpha = Math.round(darkness[i] * MARK_ALPHA * 255.0f);
            if (alpha < 4) {
                continue;
            }
            int look = (int) (EmberVeinReach.hash(fires[i]) * 8.0f);
            renderer.addFace(ParticleLayers.BURN_MARK, x, y, z, face, frames[look % frames.length], (look & 2) != 0, (look & 4) != 0,
                alpha << 24 | MARK_RGB, light[i]);
        }
    }
}
