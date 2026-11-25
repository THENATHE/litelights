package com.thenathe.litelights.mixin.lightpost;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.post.LitePostChain;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererLightPostMixin {
    /**
     * The hand's draws are prepared and its pass isn't open yet: the moment to draw the outline
     * around enchanted items in hand, underneath the hand itself. From here until the method ends
     * the hand's pass gets the hand frame, and bloom learns which depth the hand is drawn with.
     */
    @ModifyExpressionValue(
        method = "renderItemInHand",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private FeatureRenderDispatcher.PreparedFrame litelights$enchantmentOutline(FeatureRenderDispatcher.PreparedFrame frame,
                                                                               @Local(argsOnly = true) GpuTextureView depthTextureView) {
        LiteLightsRenderer.get().setDrawingHand(true);
        LightPost.get().beforeHand(frame, depthTextureView);
        return frame;
    }

    /** The hand's items are about to be submitted: count the enchantment outline's markers afresh. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void litelights$beginHand(CallbackInfo callback) {
        LightPost.get().markers().beginHand();
    }

    @Inject(method = "renderItemInHand", at = @At("TAIL"))
    private void litelights$handDrawn(CallbackInfo callback) {
        LiteLightsRenderer.get().setDrawingHand(false);
    }

    /**
     * Every way out, the early ones too (third person, a hidden HUD): what follows, the GUI's
     * player preview included, isn't the first-person hand.
     */
    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void litelights$endHand(CallbackInfo callback) {
        LightPost.get().markers().endHand();
    }

    /** The level, the hand and the screen overlays are drawn; the HUD comes next. */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void litelights$finalPostStage(CallbackInfo callback) {
        GameRenderer renderer = (GameRenderer) (Object) this;
        LitePostChain.runFinalStage(renderer.mainRenderTarget(), LiteLightsRenderer.get().frame());
    }
}
