package com.thenathe.litelights.api;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;

/**
 * Drawing passes of a module's own the way LiteLights' post passes are drawn (docs/ARCHITECTURE.md,
 * "The post chain" and "Feature states and errors"), and timing them.
 */
public interface RenderPasses {
    /**
     * Opens a guarded render pass on a colour target: a draw that throws leaves the frame intact.
     * Always in try-with-resources, with the failure caught outside it.
     */
    RenderPass open(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor);

    /**
     * The same on a colour and a depth target, clearing neither: for geometry drawn into the main
     * target among the level's own, depth-tested against it.
     */
    RenderPass open(Supplier<String> label, GpuTextureView colorTexture, GpuTextureView depthTexture);

    /**
     * Starts a full-screen pipeline (one triangle, {@code litelights:post/fullscreen} giving
     * {@code texCoord}) with this fragment shader. LiteLights' bind group is added when it is built.
     */
    RenderPipeline.Builder fullScreenPipeline(String name, Identifier fragmentShader, BindGroupLayout samplers, ColorTargetState target,
                                              int pushConstantBytes);

    /** A bind group of combined image samplers with these names. */
    BindGroupLayout samplers(String... names);

    /** An RGBA16F target that writes every channel. */
    ColorTargetState halfFloatTarget();

    GpuSampler nearestSampler();

    GpuSampler linearSampler();

    ScreenTexture screenTexture(String label, GpuFormat format, int divisor);

    /** GPU time per section, when LiteLights' timings are on (-Dlitelights.gpuTimings=true). */
    void beginTiming(TimedSection section);

    void endTiming(TimedSection section);

    /**
     * Adds a CPU time to the A/B timing tool's report under this metric, while a run is going
     * (docs/ARCHITECTURE.md, "Interleaved A/B timing"); does nothing otherwise.
     */
    void addCpuTiming(String metric, double millis);

    /** True only while an A/B timing run on this named switch is on its side B. */
    boolean abFlipped(String switchName);

    /** The timing sections modules own, beside the post slots. */
    enum TimedSection {
        /** The far shadow pass. */
        FAR_TERRAIN,
        /** The far land itself, drawn at the split before translucent terrain. */
        FAR_LAND
    }
}
