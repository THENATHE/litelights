package com.thenathe.litelights.render.feature.clouds;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.clouds.field.CloudDrift;
import com.thenathe.litelights.clouds.mesh.CloudShadowMap;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The Dynamic clouds' shadows on the land (stage 4 of the clouds' plan): one full-screen draw that
 * darkens the main target where the clouds stand between the land and the sun
 * ({@code shaders/clouds/cloud_shadow.fsh}), from each generation's shadow map
 * (clouds.mesh.CloudShadowMap), not from the cubes, so it costs one pass whatever the sky holds and
 * adds no sampler to the terrain shaders. It falls on near and far land alike, since both are in
 * the target by then, and on entities drawn with the solid world.
 *
 * <p>Two textures hold the maps of the change being shown, its start and its end, and the shadow
 * blends between them as the cubes grow; a new build's map goes into the one its change no longer
 * needs. Render thread only.
 */
final class CloudShadows implements AutoCloseable {
    /** The land's colour times the shadow; alpha (bloom's mask) kept. */
    private static final BlendFunction MULTIPLY = new BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE);
    private static final int PUSH_BYTES = 48;
    static final RenderPipeline PIPELINE = PostPipelines.fullScreen("cloud_shadows", LiteLightsClient.id("clouds/cloud_shadow"),
            PostPipelines.samplers("SceneDepth", "ShadowBefore", "ShadowAfter"),
            new ColorTargetState(Optional.of(MULTIPLY), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGB), PUSH_BYTES)
        .build();
    /** The deepest a cloud's shadow darkens the land, against its colour in full sun. */
    static final float DEEPEST_SHADE = 0.42f;
    /** The height the clouds' shadow is cast from, in blocks over the floor: the low clouds' middle. */
    static final float LAYER_ABOVE_FLOOR = 80.0f;

    private final GpuTexture[] textures = new GpuTexture[2];
    private final GpuTextureView[] views = new GpuTextureView[2];
    private final @Nullable Generation[] held = new Generation[2];
    private @Nullable ByteBuffer staging;

    /**
     * Draws the shadows into the main target. Call with no render pass open, before the clouds.
     *
     * @param before   the shown change's start (the generation before it, or the same one)
     * @param after    its end, the shown generation
     * @param progress how far the change has grown in, 0..1
     */
    void draw(RenderTarget mainTarget, Generation before, Generation after, float progress, double cameraX, double cameraY, double cameraZ,
              double tick, CloudDrift wind, int reach) {
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        GpuTextureView colourView = mainTarget.getColorTextureView();
        if (depthView == null || colourView == null) {
            return;
        }
        int afterSlot = upload(after, -1);
        int beforeSlot = before == after ? afterSlot : upload(before, afterSlot);
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights cloud shadows", colourView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            RenderSystem.bindDefaultUniforms(pass);
            LiteLightsRenderer.get().bindWorldUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            pass.setUniform("SceneDepth", depthView, nearest);
            pass.setUniform("ShadowBefore", views[beforeSlot], linear);
            pass.setUniform("ShadowAfter", views[afterSlot], linear);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            place(push, 0, before, cameraX, cameraZ, tick, wind);
            place(push, 16, after, cameraX, cameraZ, tick, wind);
            push.putFloat(32, progress);
            push.putFloat(36, (float) (after.floor() + LAYER_ABOVE_FLOOR - cameraY));
            push.putFloat(40, DEEPEST_SHADE);
            push.putFloat(44, Math.min(reach, CloudShadowMap.SPAN_BLOCKS / 2));
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
    }

    /** A map's corner, camera-relative, with the wind's drift now, and one over its width. */
    private static void place(ByteBuffer push, int at, Generation generation, double cameraX, double cameraZ, double tick, CloudDrift wind) {
        push.putFloat(at, (float) (generation.shadowCornerX() + wind.driftX(tick) - cameraX));
        push.putFloat(at + 4, (float) (generation.shadowCornerZ() + wind.driftZ(tick) - cameraZ));
        push.putFloat(at + 8, 1.0f / CloudShadowMap.SPAN_BLOCKS);
        push.putFloat(at + 12, 0.0f);
    }

    /** The slot holding a generation's map, uploading it into the slot other than {@code keep} if none does yet. */
    private int upload(Generation generation, int keep) {
        for (int slot = 0; slot < 2; slot++) {
            if (held[slot] == generation && textures[slot] != null) {
                return slot;
            }
        }
        int slot = keep == 0 ? 1 : 0;
        if (textures[slot] == null) {
            var device = RenderSystem.getDevice();
            textures[slot] = device.createTexture("LiteLights cloud shadows", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R8_UNORM, CloudShadowMap.SIZE, CloudShadowMap.SIZE, 1, 1);
            views[slot] = device.createTextureView(textures[slot]);
        }
        if (staging == null) {
            staging = MemoryUtil.memAlloc(CloudShadowMap.SIZE * CloudShadowMap.SIZE);
        }
        staging.clear();
        staging.put(generation.shadow()).flip();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(textures[slot], staging, 0, 0, 0, 0, CloudShadowMap.SIZE, CloudShadowMap.SIZE);
        held[slot] = generation;
        return slot;
    }

    /** Forgets which maps the textures hold (a new world: the next draw uploads afresh). */
    void forget() {
        held[0] = null;
        held[1] = null;
    }

    @Override
    public void close() {
        for (int slot = 0; slot < 2; slot++) {
            if (views[slot] != null) {
                views[slot].close();
                views[slot] = null;
            }
            if (textures[slot] != null) {
                textures[slot].close();
                textures[slot] = null;
            }
            held[slot] = null;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
    }
}
