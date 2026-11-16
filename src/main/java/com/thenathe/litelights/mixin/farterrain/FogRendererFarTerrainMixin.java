package com.thenathe.litelights.mixin.farterrain;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's fog for the frame, after every mod has had its say (Voxy pushes the render-distance
 * fog out here). While LiteLights' haze covers Voxy's far terrain, the environmental fog Voxy would
 * lay over it as well is pushed out too (FarTerrain.adjustVanillaFog).
 */
@Mixin(FogRenderer.class)
abstract class FogRendererFarTerrainMixin {
    @ModifyReturnValue(method = "setupFog", at = @At("RETURN"))
    private FogData litelights$farTerrainFog(FogData fog) {
        FarTerrain.get().adjustVanillaFog(fog, Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera());
        return fog;
    }
}
