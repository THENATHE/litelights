package com.thenathe.litelights.mixin.particles;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The world's hashed seed, which the server sends to every client. Night sky events and the wind
 * are worked out from it, so they are the same for every player on a world.
 */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("biomeZoomSeed")
    long litelights$biomeZoomSeed();
}
