package com.thenathe.litelights.mixin.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the mob shadow pass replay chosen phases of the frame's prepared entity draws. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface PreparedFrameAccessor {
    @Accessor("context")
    @Nullable FeatureFrameContext litelights$context();

    @Accessor("submitNodeStorage")
    @Nullable SubmitNodeStorage litelights$submitNodeStorage();

    @Invoker("executePhase")
    void litelights$executePhase(FeatureRenderPhase<?> phase, FeatureFrameContext context, @Nullable OitStage stage, RenderPass renderPass);
}
