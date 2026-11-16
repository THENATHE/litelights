package com.thenathe.litelights.render.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Binds LiteLights' uniforms on a render pass. Every uniform always has something bound, even
 * before the shadow maps or the material texture exist, because a pipeline that reads an unbound
 * uniform fails to draw: placeholders are a 1x1 depth texture that reads as fully lit and a 1x1
 * material texture that reads as the default material.
 */
public final class UniformBinder implements AutoCloseable {
    private final GpuTexture placeholderDepth;
    private final GpuTextureView placeholderDepthView;
    private final GpuTexture placeholderMaterials;
    private final GpuTextureView placeholderMaterialsView;

    private final GpuBuffer worldFrame;
    private final GpuBuffer neutralFrame;
    private final GpuBuffer handFrame;
    private boolean drawingHand;
    private @Nullable GpuTextureView sunShadow;
    private @Nullable GpuTextureView sunShadowPrevious;
    private @Nullable GpuTextureView mobShadow;
    private @Nullable GpuTextureView materials;

    public UniformBinder(GpuBuffer worldFrame, GpuBuffer neutralFrame, GpuBuffer handFrame) {
        this.worldFrame = worldFrame;
        this.neutralFrame = neutralFrame;
        this.handFrame = handFrame;
        var device = RenderSystem.getDevice();
        int depthUsage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;
        placeholderDepth = device.createTexture("LiteLights placeholder shadow map", depthUsage, GpuFormat.D32_FLOAT, 1, 1, 1, 1);
        placeholderDepthView = device.createTextureView(placeholderDepth);
        device.createCommandEncoder().clearDepthTexture(placeholderDepth, 1.0);

        int colorUsage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        placeholderMaterials = device.createTexture("LiteLights placeholder materials", colorUsage, GpuFormat.R8_UNORM, 1, 1, 1, 1);
        placeholderMaterialsView = device.createTextureView(placeholderMaterials);
        ByteBuffer zero = MemoryUtil.memCalloc(4);
        try {
            device.createCommandEncoder().writeToTexture(placeholderMaterials, zero, 0, 0, 0, 0, 1, 1);
        } finally {
            MemoryUtil.memFree(zero);
        }
    }

    public void setShadowMaps(@Nullable GpuTextureView sun, @Nullable GpuTextureView sunPrevious, @Nullable GpuTextureView mob) {
        sunShadow = sun;
        sunShadowPrevious = sunPrevious;
        mobShadow = mob;
    }

    public void setMaterials(@Nullable GpuTextureView view) {
        materials = view;
    }

    /** Set while the first-person hand's draws are prepared and drawn. */
    public void setDrawingHand(boolean drawingHand) {
        this.drawingHand = drawingHand;
    }

    /** Binds the world frame while the level is drawn, the hand frame for the hand, the neutral one otherwise. */
    public void bindDefaults(RenderPass pass) {
        bind(pass, RenderSystem.isRenderingLevel ? worldFrame : drawingHand ? handFrame : neutralFrame);
    }

    public void bind(RenderPass pass, GpuBuffer frame) {
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform(LiteBindings.FRAME, frame);
        pass.setUniform(LiteBindings.SUN_SHADOW, orPlaceholder(sunShadow), nearest);
        pass.setUniform(LiteBindings.SUN_SHADOW_PREVIOUS, orPlaceholder(sunShadowPrevious), nearest);
        pass.setUniform(LiteBindings.MOB_SHADOW, orPlaceholder(mobShadow), nearest);
        pass.setUniform(LiteBindings.MATERIALS, materials != null ? materials : placeholderMaterialsView, nearest);
    }

    /**
     * For passes that draw into a shadow map: no shadow map may be bound while it is a target. The
     * caster shaders don't read the frame block, so the neutral copy stands in for it.
     */
    public void bindForShadowCasters(RenderPass pass) {
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform(LiteBindings.FRAME, neutralFrame);
        pass.setUniform(LiteBindings.SUN_SHADOW, placeholderDepthView, nearest);
        pass.setUniform(LiteBindings.SUN_SHADOW_PREVIOUS, placeholderDepthView, nearest);
        pass.setUniform(LiteBindings.MOB_SHADOW, placeholderDepthView, nearest);
        pass.setUniform(LiteBindings.MATERIALS, materials != null ? materials : placeholderMaterialsView, nearest);
    }

    private GpuTextureView orPlaceholder(@Nullable GpuTextureView view) {
        return view != null && !view.isClosed() ? view : placeholderDepthView;
    }

    @Override
    public void close() {
        placeholderDepthView.close();
        placeholderDepth.close();
        placeholderMaterialsView.close();
        placeholderMaterials.close();
    }
}
