package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.thenathe.litelights.render.feature.skyfog.LiteClouds;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The cloud style the frame is drawn with: LiteLights' own Clouds setting while LiteLights draws
 * the clouds. Only the frame's copy of the options changes; vanilla's saved option is untouched.
 */
@Mixin(GameRenderer.class)
abstract class CloudOptionsMixin {
    @ModifyExpressionValue(method = "extractOptions",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCloudStatus()Lnet/minecraft/client/CloudStatus;"))
    private CloudStatus litelights$cloudStatus(CloudStatus vanilla) {
        return LiteClouds.get().statusFor(vanilla);
    }
}
