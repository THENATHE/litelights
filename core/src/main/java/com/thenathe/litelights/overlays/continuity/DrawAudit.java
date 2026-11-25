package com.thenathe.litelights.overlays.continuity;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the variety method drew on each face, for the rendered tests. Nothing is recorded unless a
 * test starts a recorder; until then drawing pays one volatile read per face.
 */
public final class DrawAudit {
    /** Told about every face a variety set is asked to draw on, from the chunk builder threads. */
    public interface Recorder {
        /**
         * @param set               the variety set's tile folder
         * @param pos               the block, as {@link net.minecraft.core.BlockPos#asLong()}
         * @param leftToBetterGrass the face was left to LambdaBetterGrass
         * @param pieces            the pieces drawn, in order, space-separated; empty when none
         */
        void face(String set, long pos, Direction face, BlockState state, boolean leftToBetterGrass, String pieces);
    }

    private static volatile Recorder recorder;

    private DrawAudit() {
    }

    public static void start(Recorder newRecorder) {
        recorder = newRecorder;
    }

    public static void stop() {
        recorder = null;
    }

    static Recorder recorder() {
        return recorder;
    }
}
