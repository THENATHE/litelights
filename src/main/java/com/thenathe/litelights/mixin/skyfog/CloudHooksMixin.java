package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.feature.skyfog.LiteClouds;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes the level renderer's cloud calls to LiteLights' cloud layers while they are in charge
 * (see LiteClouds). Every call still goes through the original when they aren't, so other mods'
 * hooks on vanilla's CloudRenderer keep working then.
 */
@Mixin(LevelRenderer.class)
abstract class CloudHooksMixin {
    @WrapOperation(method = "prepareTranslucents",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CloudRenderer;prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V"))
    private void litelights$prepareClouds(CloudRenderer clouds, int color, CloudStatus status, float bottomY, int range, Vec3 camera,
                                          long gameTime, float partialTicks, Operation<Void> original) {
        if (LiteClouds.get().activeThisFrame()) {
            LiteClouds.get().prepare(color, status, bottomY, range, camera, gameTime, partialTicks);
        } else {
            original.call(clouds, color, status, bottomY, range, camera, gameTime, partialTicks);
        }
    }

    @WrapOperation(method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CloudRenderer;render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void litelights$renderClouds(CloudRenderer clouds, CloudStatus status, RenderPass pass, Operation<Void> original) {
        if (LiteClouds.get().activeThisFrame()) {
            LiteClouds.get().render(status, pass);
        } else {
            original.call(clouds, status, pass);
        }
    }

    @WrapOperation(method = "executeOit",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CloudRenderer;renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;)V"))
    private void litelights$renderOitClouds(CloudRenderer clouds, CloudStatus status, OitStage stage, GpuTextureView depth,
                                            OitRenderPassProvider.Parameters parameters, Operation<Void> original) {
        if (LiteClouds.get().activeThisFrame()) {
            LiteClouds.get().renderOit(status, stage, depth, parameters);
        } else {
            original.call(clouds, status, stage, depth, parameters);
        }
    }
}
