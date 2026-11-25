package com.thenathe.litelights.mixin.farterrain;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's fog for the frame: while LiteLights' own far land is drawn, its render-distance fog
 * moves out to where the far land ends (FarTerrain.adjustVanillaFog). Without far land, untouched.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererFarLandMixin {
    @ModifyReturnValue(method = "setupFog", at = @At("RETURN"))
    private FogData litelights$farLandFog(FogData fog) {
        FarTerrain.get().adjustVanillaFog(fog, Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera());
        return fog;
    }
}
