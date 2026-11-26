package com.thenathe.litelights.render.feature.clouds;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.clouds.volume.CloudMap;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One of the clouds' column maps on the GPU (the near one or the far one): three textures a map
 * can be uploaded into, so the two of the change being shown stay put while the next build goes up
 * a slice a frame, and a fourth the two shown are blended into each frame
 * ({@code shaders/clouds/map_blend.fsh}), which the clouds and their shadows read. A map that is
 * already up (the far map, reused between builds) is never uploaded twice, and with nothing
 * changing the shown map is read as it is, with no blend. Render thread only.
 */
final class CloudMapTextures implements AutoCloseable {
    /** How many rows of the atlas go up in one frame: 1 MB. */
    private static final int ROWS_PER_FRAME = 256;
    private static final int PUSH_BYTES = 16;
    static final RenderPipeline BLEND = PostPipelines.fullScreen("cloud_map_blend", LiteLightsClient.id("clouds/map_blend"),
            PostPipelines.samplers("MapBefore", "MapAfter"), new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, PostPipelines.WRITE_RGBA),
            PUSH_BYTES)
        .build();

    private final String label;
    private final GpuTexture[] textures = new GpuTexture[3];
    private final GpuTextureView[] views = new GpuTextureView[3];
    private final @Nullable CloudMap[] held = new CloudMap[3];
    private @Nullable GpuTexture blended;
    private @Nullable GpuTextureView blendedView;
    private @Nullable ByteBuffer staging;

    // The upload in progress.
    private @Nullable CloudMap pending;
    private int pendingSlot = -1;
    private int uploadedRows;

    CloudMapTextures(String label) {
        this.label = label;
    }

    /**
     * Starts putting a map up, into a slot holding neither of the two being shown; nothing to do if
     * it is up already.
     */
    void begin(CloudMap map, @Nullable CloudMap shownBefore, @Nullable CloudMap shownAfter) {
        if (slotOf(map) >= 0) {
            pending = null;
            return;
        }
        int slot = 0;
        while (held[slot] != null && (held[slot] == shownBefore || held[slot] == shownAfter)) {
            slot++;
        }
        ensure(slot);
        held[slot] = null;
        pending = map;
        pendingSlot = slot;
        uploadedRows = 0;
    }

    /**
     * Uploads the next slice of the map being put up; true once it is all up (or nothing was).
     *
     * @param all the rest at once (the first build, or after a jump, when nothing is shown yet)
     */
    boolean uploadSome(boolean all) {
        CloudMap map = pending;
        if (map == null) {
            return true;
        }
        if (staging == null) {
            staging = MemoryUtil.memAlloc(CloudMap.ATLAS_SIZE * ROWS_PER_FRAME * 4);
        }
        byte[] texels = map.texels();
        do {
            int rows = Math.min(ROWS_PER_FRAME, CloudMap.ATLAS_SIZE - uploadedRows);
            int bytes = rows * CloudMap.ATLAS_SIZE * 4;
            staging.clear();
            staging.put(texels, uploadedRows * CloudMap.ATLAS_SIZE * 4, bytes).flip();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(textures[pendingSlot], staging, 0, 0, 0, uploadedRows,
                CloudMap.ATLAS_SIZE, rows);
            uploadedRows += rows;
        } while (all && uploadedRows < CloudMap.ATLAS_SIZE);
        if (uploadedRows < CloudMap.ATLAS_SIZE) {
            return false;
        }
        held[pendingSlot] = map;
        pending = null;
        return true;
    }

    /** Lets go of an upload that won't be shown (a jump made it stale). */
    void dropPending() {
        pending = null;
    }

    /**
     * The map to read this frame: the shown one as it is, or, while it is growing in from the one
     * before, the two blended by the change's progress. Null if the shown map isn't up.
     */
    @Nullable GpuTextureView shown(CloudMap before, CloudMap after, float progress) {
        int afterSlot = slotOf(after);
        if (afterSlot < 0) {
            return null;
        }
        int beforeSlot = slotOf(before);
        boolean blends = progress < 1.0f && before != after && beforeSlot >= 0 && before.texelBlocks() == after.texelBlocks();
        if (!blends) {
            return views[afterSlot];
        }
        if (blended == null) {
            var device = RenderSystem.getDevice();
            blended = device.createTexture("LiteLights " + label + " blended", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, CloudMap.ATLAS_SIZE, CloudMap.ATLAS_SIZE, 1, 1);
            blendedView = device.createTextureView(blended);
        }
        try (RenderPass pass = GuardedRenderPass.open(() -> "LiteLights " + label + " blend", blendedView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(BLEND));
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("MapBefore", views[beforeSlot], nearest);
            pass.setUniform("MapAfter", views[afterSlot], nearest);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putFloat(0, after.originX() - before.originX());
            push.putFloat(4, after.originZ() - before.originZ());
            push.putFloat(8, progress);
            push.putFloat(12, CloudMap.SIZE);
            pass.pushConstants(push);
            pass.draw(3, 1, 0, 0);
        }
        return blendedView;
    }

    private int slotOf(CloudMap map) {
        for (int slot = 0; slot < held.length; slot++) {
            if (held[slot] == map && textures[slot] != null) {
                return slot;
            }
        }
        return -1;
    }

    private void ensure(int slot) {
        if (textures[slot] == null) {
            var device = RenderSystem.getDevice();
            textures[slot] = device.createTexture("LiteLights " + label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, CloudMap.ATLAS_SIZE, CloudMap.ATLAS_SIZE, 1, 1);
            views[slot] = device.createTextureView(textures[slot]);
        }
    }

    /** Forgets what the textures hold (a new world: everything is uploaded afresh). */
    void forget() {
        for (int slot = 0; slot < held.length; slot++) {
            held[slot] = null;
        }
        pending = null;
    }

    @Override
    public void close() {
        for (int slot = 0; slot < textures.length; slot++) {
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
        if (blendedView != null) {
            blendedView.close();
            blendedView = null;
        }
        if (blended != null) {
            blended.close();
            blended = null;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        pending = null;
    }
}
