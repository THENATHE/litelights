package com.thenathe.litelights.render.feature.lightpost;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.thenathe.litelights.render.pipeline.FilteringRenderPass;
import org.jspecify.annotations.Nullable;

/**
 * Replays prepared draws (the level's or the hand's) keeping only the enchantment outline's
 * markers, which it draws for real: their colour and depth make the outline's mask. Everything else
 * is dropped.
 */
final class MarkerMaskPass extends FilteringRenderPass {
    private @Nullable CompiledRenderPipeline hidden;
    private @Nullable CompiledRenderPipeline mask;

    /** Starts filtering into a new pass. */
    @Override
    public void begin(RenderPass target) {
        super.begin(target);
        hidden = RenderSystem.getCompiledPipeline(OutlineMarkers.HIDDEN);
        mask = RenderSystem.getCompiledPipeline(OutlineMarkers.MASK);
    }

    @Override
    protected @Nullable CompiledRenderPipeline replacementFor(CompiledRenderPipeline pipeline) {
        return pipeline == hidden ? mask : null;
    }
}
