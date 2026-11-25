package com.thenathe.litelights.render.frame;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.thenathe.litelights.render.feature.vegetation.HangingSwing;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * The GPU side of the LiteFrame uniform block. There are three copies, so a pass can switch between
 * them by binding a different buffer instead of rewriting one mid-frame: the world copy for level
 * passes, the neutral copy (every feature off) for GUI and other passes that must look like
 * vanilla, and the hand copy, neutral but marked as the hand's pass. Shadow-caster passes get the
 * neutral copy too: no caster shader reads the block.
 */
public final class FrameUniforms implements AutoCloseable {
    public static final int SIZE = withIVec4s(new Std140SizeCalculator()
        .putMat4f().putMat4f().putMat4f().putMat4f().putMat4f().putMat4f()
        .putVec4().putVec4().putVec4().putVec4().putVec4()
        .putIVec4().putVec4().putVec4().putVec4()
        .putVec4().putVec4().putVec4().putVec4()
        .putVec4().putVec4().putVec4().putVec4()
        .putIVec4()
        .putVec4().putVec4().putVec4().putVec4()
        .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
        .putVec4().putVec4()
        .putVec4()
        .putVec4()
        .putVec4(),
        // The swinging columns' cells and the columns themselves.
        HangingSwing.CELL_ROWS / 4 + HangingSwing.MAX_COLUMNS)
        // The rocks ores are drawn on.
        .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
        .get();
    private static final int USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;

    private final GpuBuffer world;
    private final GpuBuffer neutral;
    private final GpuBuffer hand;

    public FrameUniforms() {
        var device = RenderSystem.getDevice();
        world = device.createBuffer(() -> "LiteLights frame (world)", USAGE, SIZE);
        neutral = device.createBuffer(() -> "LiteLights frame (neutral)", USAGE, SIZE);
        hand = device.createBuffer(() -> "LiteLights frame (hand)", USAGE, SIZE);
        write(neutral, new FrameState(), FrameFlags.PASS_NEUTRAL);
        write(hand, new FrameState(), FrameFlags.PASS_HAND);
    }

    public void update(FrameState state) {
        write(world, state, FrameFlags.PASS_WORLD);
    }

    public GpuBuffer world() {
        return world;
    }

    public GpuBuffer neutral() {
        return neutral;
    }

    public GpuBuffer hand() {
        return hand;
    }

    private static Std140SizeCalculator withIVec4s(Std140SizeCalculator size, int count) {
        for (int i = 0; i < count; i++) {
            size.putIVec4();
        }
        return size;
    }

    private static void write(GpuBuffer target, FrameState state, int passKind) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Only a world pass has features on; elsewhere the shaders fall back to vanilla's output.
            int features = passKind == FrameFlags.PASS_WORLD ? state.featureFlags : 0;
            int locks = passKind == FrameFlags.PASS_WORLD ? state.pixelLockFlags : 0;
            Std140Builder builder = Std140Builder.onStack(stack, SIZE)
                .putMat4f(state.sunShadowMatrix)
                .putMat4f(state.sunShadowMatrixPrevious)
                .putMat4f(state.mobShadowMatrix)
                .putMat4f(state.viewProjection)
                .putMat4f(state.viewProjectionPrevious)
                .putMat4f(state.inverseViewProjection)
                .putVec4(state.sunDirection)
                .putVec4(state.moonDirection)
                .putVec4(state.shadowLightDirection)
                .putVec4(state.sunColor)
                .putVec4(state.ambientColor)
                .putIVec4(state.cameraBlockX, state.cameraBlockY, state.cameraBlockZ, state.dimensionKind)
                .putVec4(state.cameraFraction.x, state.cameraFraction.y, state.cameraFraction.z, 0.0f)
                .putVec4(state.time)
                .putVec4(state.weather)
                .putVec4(state.lightning[0])
                .putVec4(state.lightning[1])
                .putVec4(state.lightning[2])
                .putVec4(state.lightning[3])
                .putVec4(state.shadowParams)
                .putVec4(state.shadowMapInfo)
                .putVec4(state.shadowFade)
                .putVec4(state.screen)
                .putIVec4(features, locks, passKind, state.materialGridShift)
                .putVec4(state.featureValuesA)
                .putVec4(state.featureValuesB)
                .putVec4(state.featureValuesC)
                .putVec4(state.featureValuesD)
                .putVec4(state.hazeColor)
                .putVec4(state.hazeShape)
                .putVec4(state.fogRange)
                .putVec4(state.sunGlow)
                .putVec4(state.stormFog)
                .putVec4(state.skyZenith)
                .putVec4(state.skyInfo)
                .putVec4(state.endTint)
                .putVec4(state.glassCold)
                .putVec4(state.glassInfo)
                .putVec4(state.puddles)
                .putVec4(state.snowCover)
                .putVec4(state.swing);
            int[] cells = state.swingCells;
            for (int at = 0; at < cells.length; at += 4) {
                builder.putIVec4(cells[at], cells[at + 1], cells[at + 2], cells[at + 3]);
            }
            int[] columns = state.swingColumns;
            for (int at = 0; at < columns.length; at += 4) {
                builder.putIVec4(columns[at], columns[at + 1], columns[at + 2], columns[at + 3]);
            }
            float[] rocks = state.oreRocks;
            for (int at = 0; at < rocks.length; at += 4) {
                builder.putVec4(rocks[at], rocks[at + 1], rocks[at + 2], rocks[at + 3]);
            }
            ByteBuffer data = builder.get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), data);
        }
    }

    @Override
    public void close() {
        world.close();
        neutral.close();
        hand.close();
    }
}
