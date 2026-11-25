package com.thenathe.litelights.render.modules;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.api.RenderPasses;
import com.thenathe.litelights.api.ScreenTexture;
import com.thenathe.litelights.render.pipeline.GuardedRenderPass;
import com.thenathe.litelights.render.post.PostContext;
import com.thenathe.litelights.render.post.PostPipelines;
import com.thenathe.litelights.render.post.PostTexture;
import com.thenathe.litelights.render.post.PostTimings;
import com.thenathe.litelights.render.timing.AbTiming;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;

/** The post chain's helpers, for the modules' own passes. */
final class CoreRenderPasses implements RenderPasses {
    @Override
    public RenderPass open(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor) {
        return GuardedRenderPass.open(label, colorTexture, clearColor);
    }

    @Override
    public RenderPass open(Supplier<String> label, GpuTextureView colorTexture, GpuTextureView depthTexture) {
        return GuardedRenderPass.open(label, colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty());
    }

    @Override
    public RenderPipeline.Builder fullScreenPipeline(String name, Identifier fragmentShader, BindGroupLayout samplers, ColorTargetState target,
                                                     int pushConstantBytes) {
        return PostPipelines.fullScreen(name, fragmentShader, samplers, target, pushConstantBytes);
    }

    @Override
    public BindGroupLayout samplers(String... names) {
        return PostPipelines.samplers(names);
    }

    @Override
    public ColorTargetState halfFloatTarget() {
        return PostPipelines.HALF_FLOAT;
    }

    @Override
    public GpuSampler nearestSampler() {
        return PostContext.nearestSampler();
    }

    @Override
    public GpuSampler linearSampler() {
        return PostContext.linearSampler();
    }

    @Override
    public ScreenTexture screenTexture(String label, GpuFormat format, int divisor) {
        return new PostTexture(label, format, divisor);
    }

    @Override
    public void beginTiming(TimedSection section) {
        PostTimings.begin(sectionIndex(section));
    }

    @Override
    public void endTiming(TimedSection section) {
        PostTimings.end(sectionIndex(section));
    }

    @Override
    public void addCpuTiming(String metric, double millis) {
        AbTiming.addCpu(metric, millis);
    }

    @Override
    public boolean abFlipped(String switchName) {
        return AbTiming.flipped(switchName);
    }

    private static int sectionIndex(TimedSection section) {
        return switch (section) {
            case FAR_TERRAIN -> PostTimings.FAR_TERRAIN;
            case FAR_LAND -> PostTimings.FAR_LAND;
        };
    }
}
