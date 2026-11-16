package com.thenathe.litelights.render.feature.water;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostPipelines;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A copy of the opaque scene, taken after the solid world is drawn and before translucent terrain:
 * its colour, and its view depth in blocks in alpha (shaders/water/scene_copy.fsh). Water and ice
 * read it for screen-space reflections, for how much water lies in front of the bottom, and to see
 * what is behind them. One full-screen pass into a half-float texture; nothing is drawn twice.
 *
 * <p>The same pass keeps the opaque scene's own alpha in a second, one-channel texture. That alpha
 * is light-post's emissive mask, which translucent surfaces overwrite; with it, bloom can still
 * find lava under water and scale it by how much of it shows through.
 *
 * <p>The copy is "valid" from the moment it is taken until the level is finished; passes bound in
 * that window get the copy, others get a 1 x 1 placeholder, which is how the shaders tell the solid
 * pass (no copy yet) from the translucent one. Bloom runs after the level, so it asks whether the
 * copy was taken this frame instead.
 */
final class SceneCopy implements AutoCloseable {
    static final RenderPipeline PIPELINE = PostPipelines.fullScreen("water_scene_copy", LiteLightsClient.id("water/scene_copy"),
            PostPipelines.samplers("InColor", "InDepth"), PostPipelines.HALF_FLOAT, 0)
        .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, PostPipelines.WRITE_RGBA))
        .build();

    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private @Nullable GpuTexture opaqueAlphaTexture;
    private @Nullable GpuTextureView opaqueAlphaView;
    private boolean valid;
    private boolean takenThisFrame;

    /** Copies the main target. Call with no render pass open. */
    void take(RenderTarget mainTarget) {
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }
        int width = colorView.getWidth(0);
        int height = colorView.getHeight(0);
        var device = RenderSystem.getDevice();
        if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height) {
            closeTexture();
            texture = device.createTexture("LiteLights scene copy", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            view = device.createTextureView(texture);
            opaqueAlphaTexture = device.createTexture("LiteLights opaque alpha", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.R8_UNORM, width, height, 1, 1);
            opaqueAlphaView = device.createTextureView(opaqueAlphaTexture);
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "LiteLights scene copy")
            .withColorAttachment(view)
            .withColorAttachment(opaqueAlphaView)
            .build();
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = GuardedRenderPass.open(descriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("InColor", colorView, nearest);
            pass.setUniform("InDepth", depthView, nearest);
            pass.draw(3, 1, 0, 0);
        }
        valid = true;
        takenThisFrame = true;
    }

    /** A new frame: nothing has been copied yet. */
    void beginFrame() {
        valid = false;
        takenThisFrame = false;
    }

    /** The level is finished: later passes (the hand, the HUD) get the placeholder again. */
    void invalidate() {
        valid = false;
    }

    @Nullable GpuTextureView view() {
        return valid ? view : null;
    }

    /** The copied colour, if a copy was taken this frame (also after the level is finished). */
    @Nullable GpuTextureView colorThisFrame() {
        return takenThisFrame ? view : null;
    }

    /** The opaque scene's alpha (1 where it had no depth), if a copy was taken this frame. */
    @Nullable GpuTextureView opaqueAlphaThisFrame() {
        return takenThisFrame ? opaqueAlphaView : null;
    }

    private void closeTexture() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (opaqueAlphaView != null) {
            opaqueAlphaView.close();
            opaqueAlphaView = null;
        }
        if (opaqueAlphaTexture != null) {
            opaqueAlphaTexture.close();
            opaqueAlphaTexture = null;
        }
        valid = false;
        takenThisFrame = false;
    }

    @Override
    public void close() {
        closeTexture();
    }
}
