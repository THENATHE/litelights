package com.thenathe.litelights.render.feature.water;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The water stream's uniforms (shaders/include/water/uniforms.glsl). Like LiteLights' own bind
 * group, the layout is added to every pipeline as it is built and the uniforms are bound on every
 * pass vanilla binds its defaults on (see mixin.water), so the terrain and block shaders can read
 * them without changing any pipeline. Shaders that don't read them pay nothing.
 *
 * <p>Few textures: OpenGL gives a draw twelve texture units, and Sodium's translucent terrain under
 * Improved Transparency already takes ten before water's. The terrain, block-model and Sodium
 * shaders read the atlas through their own sampler ({@code LITE_HOST_BLOCK_ATLAS}), so the
 * {@link #ATLAS} binding only costs a unit in a shader that doesn't sample the atlas itself.
 *
 * <p>Every uniform always has something bound: a 1 x 1 placeholder until the real texture exists.
 */
public final class WaterBindings implements AutoCloseable {
    public static final String WATER = "LiteWater";
    public static final String SCENE = "LiteScene";
    public static final String FLUID_DATA = "LiteFluidData";
    public static final String ATLAS = "LiteWaterAtlas";

    public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
        .withUniform(WATER, UniformType.UNIFORM_BUFFER)
        .withUniform(SCENE, UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform(FLUID_DATA, UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform(ATLAS, UniformType.COMBINED_IMAGE_SAMPLER)
        .build();

    private final GpuTexture placeholder;
    private final GpuTextureView placeholderView;
    private final GpuBuffer uniforms;

    private @Nullable GpuTextureView scene;
    private @Nullable GpuTextureView fluidData;
    private @Nullable GpuTextureView atlas;

    WaterBindings(GpuBuffer uniforms) {
        this.uniforms = uniforms;
        var device = RenderSystem.getDevice();
        placeholder = device.createTexture("LiteLights water placeholder", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        placeholderView = device.createTextureView(placeholder);
        ByteBuffer zero = MemoryUtil.memCalloc(4);
        try {
            device.createCommandEncoder().writeToTexture(placeholder, zero, 0, 0, 0, 0, 1, 1);
        } finally {
            MemoryUtil.memFree(zero);
        }
    }

    void setScene(@Nullable GpuTextureView sceneCopy) {
        scene = sceneCopy;
    }

    void setTextures(@Nullable GpuTextureView fluidDataView, @Nullable GpuTextureView blockAtlas) {
        fluidData = fluidDataView;
        atlas = blockAtlas;
    }

    void bind(RenderPass pass) {
        var samplers = RenderSystem.getSamplerCache();
        pass.setUniform(WATER, uniforms);
        pass.setUniform(SCENE, orPlaceholder(scene), samplers.getClampToEdge(FilterMode.NEAREST));
        pass.setUniform(FLUID_DATA, orPlaceholder(fluidData), samplers.getClampToEdge(FilterMode.NEAREST, true));
        pass.setUniform(ATLAS, orPlaceholder(atlas), samplers.getClampToEdge(FilterMode.NEAREST, true));
    }

    private GpuTextureView orPlaceholder(@Nullable GpuTextureView view) {
        return view != null && !view.isClosed() ? view : placeholderView;
    }

    @Override
    public void close() {
        placeholderView.close();
        placeholder.close();
    }
}
