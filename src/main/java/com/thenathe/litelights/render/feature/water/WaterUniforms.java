package com.thenathe.litelights.render.feature.water;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import org.joml.Vector4f;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryStack;

/**
 * The LiteWater uniform block (shaders/include/water/uniforms.glsl). The renderer fills the public
 * fields each frame and {@link #upload()} writes them; the order in {@link #upload()} must match
 * the block.
 */
final class WaterUniforms implements AutoCloseable {
    static final int SIZE = new Std140SizeCalculator()
        .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
        .putIVec4().putIVec4().putIVec4().putIVec4()
        .putVec4()
        .putVec4().putVec4().putVec4()
        .putVec4()
        .putVec4().putVec4().putVec4().putVec4()
        .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
        .putVec4().putVec4().putVec4().putVec4()
        .putVec4()
        .putVec4()
        .putVec4().putVec4().putVec4()
        .putIVec4().putVec4()
        .get();

    final Vector4f skyColor = new Vector4f();
    final Vector4f horizonColor = new Vector4f();
    final Vector4f waterStillSprite = new Vector4f();
    final Vector4f waterFlowSprite = new Vector4f();
    final Vector4f lavaStillSprite = new Vector4f();
    final Vector4f lavaFlowSprite = new Vector4f();
    final Vector4f stripLayout = new Vector4f(1, 1, 1, 1);
    final Vector4f waterFrames = new Vector4f(0, 0, 1, 1);
    final Vector4f lavaFrames = new Vector4f(0, 0, 1, 1);
    final Vector4f params = new Vector4f();
    final Vector4f depth = new Vector4f();
    final Vector4i flags = new Vector4i();
    final Vector4i waterTileOrigin = new Vector4i();
    final Vector4i lavaTileOrigin = new Vector4i();
    final Vector4i anchor = new Vector4i();
    final Vector4f waterOverlaySprite = new Vector4f();
    final Vector4f[] iceSprites = {new Vector4f(), new Vector4f(), new Vector4f()};
    final Vector4f stripColumns = new Vector4f();
    final Vector4f[] alternateFluidSprites = {new Vector4f(2, 2, 2, 2), new Vector4f(2, 2, 2, 2), new Vector4f(2, 2, 2, 2), new Vector4f(2, 2, 2, 2)};
    /** Average colours: water still, flow, overlay, the other mod's still and flow, the large still and flow textures. */
    final Vector4f[] spriteMeans = {new Vector4f(0.7f), new Vector4f(0.7f), new Vector4f(0.7f), new Vector4f(0.7f), new Vector4f(0.7f),
        new Vector4f(0.7f), new Vector4f(0.7f)};
    /** Ocean waves: the swell's trains, the sea state and shore waves, the wind (see OceanSwell). */
    final Vector4f[] waveTrains = {new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f()};
    final Vector4f waveInfo = new Vector4f();
    final Vector4f waveWind = new Vector4f(1, 0, 0, 0);
    /** Snow, sand and red sand in the block atlas, for weather on glass; off the atlas (2) while unknown. */
    final Vector4f[] glassSprites = {new Vector4f(2, 2, 2, 2), new Vector4f(2, 2, 2, 2), new Vector4f(2, 2, 2, 2)};
    /** Wakes: xy the window's first cell less the camera block's (four cells a block), zw unused. */
    final Vector4i wakeWindow = new Vector4i();
    /** Wakes: x 1 to draw their foam, y the partial tick (the field is blended from the last tick to this one), zw unused. */
    final Vector4f wakeParams = new Vector4f();

    private final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "LiteLights water", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SIZE);

    GpuBuffer buffer() {
        return buffer;
    }

    void upload() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, SIZE)
                .putVec4(skyColor)
                .putVec4(horizonColor)
                .putVec4(waterStillSprite)
                .putVec4(waterFlowSprite)
                .putVec4(lavaStillSprite)
                .putVec4(lavaFlowSprite)
                .putVec4(stripLayout)
                .putVec4(waterFrames)
                .putVec4(lavaFrames)
                .putVec4(params)
                .putVec4(depth)
                .putIVec4(flags)
                .putIVec4(waterTileOrigin)
                .putIVec4(lavaTileOrigin)
                .putIVec4(anchor)
                .putVec4(waterOverlaySprite)
                .putVec4(iceSprites[0])
                .putVec4(iceSprites[1])
                .putVec4(iceSprites[2])
                .putVec4(stripColumns)
                .putVec4(alternateFluidSprites[0])
                .putVec4(alternateFluidSprites[1])
                .putVec4(alternateFluidSprites[2])
                .putVec4(alternateFluidSprites[3]);
            for (Vector4f mean : spriteMeans) {
                builder.putVec4(mean);
            }
            for (Vector4f train : waveTrains) {
                builder.putVec4(train);
            }
            builder.putVec4(waveInfo).putVec4(waveWind);
            for (Vector4f sprite : glassSprites) {
                builder.putVec4(sprite);
            }
            builder.putIVec4(wakeWindow).putVec4(wakeParams);
            ByteBuffer data = builder.get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
        }
    }

    @Override
    public void close() {
        buffer.close();
    }
}
