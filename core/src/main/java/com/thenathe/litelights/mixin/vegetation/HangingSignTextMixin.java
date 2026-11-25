package com.thenathe.litelights.mixin.vegetation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.thenathe.litelights.render.feature.vegetation.SwingingLanterns;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.HangingSignRenderState;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A hanging sign's board is a block model, which the terrain shaders swing with the lanterns; its
 * text is all the sign's block entity renderer draws, so it is turned here the same way, about the
 * same pivot, and the two move together.
 */
@Mixin(AbstractSignRenderer.class)
abstract class HangingSignTextMixin {
    @WrapMethod(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void litelights$swingText(SignRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera,
                                      Operation<Void> original) {
        if (!(state instanceof HangingSignRenderState)) {
            original.call(state, pose, collector, camera);
            return;
        }
        pose.pushPose();
        try {
            SwingingLanterns.get().turnSignText(state.blockPos, pose);
            original.call(state, pose, collector, camera);
        } finally {
            pose.popPose();
        }
    }
}
