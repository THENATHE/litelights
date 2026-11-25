package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.thenathe.litelights.render.feature.skyfog.SkyFogFeature;
import com.thenathe.litelights.render.feature.skyfog.VanillaSkyPreview;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets LiteLights' sky take over parts of vanilla's: the sun, moon and stars whenever LiteLights
 * draws its own, and the sky disc and sunrise glow when the Realistic sky replaces them. While the
 * settings screen previews a time of day, vanilla's sky colours are those of the previewed time
 * (only the frame's render state changes; the world's time is never touched).
 */
@Mixin(SkyRenderer.class)
abstract class SkyRendererSkyFogMixin {
    @WrapWithCondition(method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSunMoonAndStars(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/vertex/PoseStack;FFFLnet/minecraft/world/level/MoonPhase;FF)V"))
    private boolean litelights$vanillaCelestials(SkyRenderer sky, RenderPass pass, PoseStack poseStack, float sunAngle, float moonAngle,
                                                 float starAngle, MoonPhase moonPhase, float rainBrightness, float starBrightness) {
        return !SkyFogFeature.get().drawsCelestials();
    }

    @WrapWithCondition(method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSkyDisc(Lcom/mojang/renderpearl/api/commands/RenderPass;Lorg/joml/Vector3fc;)V"))
    private boolean litelights$vanillaSkyDisc(SkyRenderer sky, RenderPass pass, Vector3fc skyColor) {
        return !SkyFogFeature.get().replacesSkyColours();
    }

    @WrapWithCondition(method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSunriseAndSunset(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/vertex/PoseStack;FLorg/joml/Vector4fc;)V"))
    private boolean litelights$vanillaSunrise(SkyRenderer sky, RenderPass pass, PoseStack poseStack, float sunAngle, Vector4fc color) {
        return !SkyFogFeature.get().replacesSkyColours();
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void litelights$previewTime(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo callback) {
        VanillaSkyPreview.apply(level, camera, state);
    }
}
