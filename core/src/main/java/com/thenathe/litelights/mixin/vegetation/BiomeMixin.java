package com.thenathe.litelights.mixin.vegetation;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.thenathe.litelights.render.feature.vegetation.NaturalFoliageColors;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Natural foliage colours: the biome's grass and foliage tints, as vanilla's and Sodium's chunk
 * builders ask for them, pass through {@link NaturalFoliageColors}.
 */
@Mixin(Biome.class)
abstract class BiomeMixin {
    @ModifyReturnValue(method = "getGrassColor", at = @At("RETURN"))
    private int litelights$naturalGrassColor(int color) {
        return NaturalFoliageColors.apply(color);
    }

    @ModifyReturnValue(method = "getFoliageColor", at = @At("RETURN"))
    private int litelights$naturalFoliageColor(int color) {
        return NaturalFoliageColors.apply(color);
    }
}
