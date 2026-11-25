package com.thenathe.litelights.mixin.lightpost;

import com.mojang.blaze3d.vertex.PoseStack;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the enchantment outline's markers which item stack is being submitted, and in which
 * context (a hand, the ground, a frame), while its layers submit themselves.
 */
@Mixin(ItemStackRenderState.class)
abstract class ItemStackRenderStateMixin {
    @Shadow
    ItemDisplayContext displayContext;

    @Inject(method = "submit", at = @At("HEAD"))
    private void litelights$beginItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, int outlineColor,
                                      CallbackInfo callback) {
        LightPost.get().markers().beginItem(displayContext, poseStack);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void litelights$endItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, int outlineColor,
                                    CallbackInfo callback) {
        LightPost.get().markers().endItem();
    }
}
