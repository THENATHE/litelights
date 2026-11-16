package com.thenathe.litelights.mixin.lightpost;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererLightPostMixin {
    /** The level's entities are about to be submitted: count the enchantment outline's markers afresh. */
    @Inject(method = "render", at = @At("HEAD"))
    private void litelights$beginOutlineMarkers(CallbackInfo callback) {
        LightPost.get().markers().beginLevel();
    }

    /**
     * The level's entity draws are prepared and its pass isn't open yet: the moment to replay the
     * enchantment outline's markers (worn armour, items in hand) into its mask.
     */
    @ModifyExpressionValue(
        method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private FeatureRenderDispatcher.PreparedFrame litelights$wornArmourOutline(FeatureRenderDispatcher.PreparedFrame frame) {
        LightPost.get().afterLevelFeaturesPrepared(frame);
        return frame;
    }
}
