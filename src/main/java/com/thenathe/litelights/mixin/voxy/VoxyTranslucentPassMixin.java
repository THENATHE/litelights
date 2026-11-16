package com.thenathe.litelights.mixin.voxy;

import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the start and end of Voxy's translucent pass, so far water can be told from the ground by
 * what that pass drew (render.feature.farterrain.FarWaterCapture). Voxy has no API for this. The
 * handlers only copy the colour Voxy is drawing into; Voxy's own code runs unchanged.
 *
 * <p>Optional by design: this config applies only when Voxy is installed (VoxyMixinPlugin), and
 * nothing here is required, so a Voxy whose pipeline changed simply loses the capture and far water
 * goes back to finding the sea by its height.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
abstract class VoxyTranslucentPassMixin {
    private static final String RENDER_TRANSLUCENT =
        "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;renderTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;)V";

    @Inject(method = "runPipeline", at = @At(value = "INVOKE", target = RENDER_TRANSLUCENT), require = 0, remap = false)
    private void litelights$beforeTranslucents(CallbackInfo info) {
        FarTerrain.get().voxyBeforeTranslucents();
    }

    @Inject(method = "runPipeline", at = @At(value = "INVOKE", target = RENDER_TRANSLUCENT, shift = At.Shift.AFTER), require = 0, remap = false)
    private void litelights$afterTranslucents(CallbackInfo info) {
        FarTerrain.get().voxyAfterTranslucents();
    }
}
